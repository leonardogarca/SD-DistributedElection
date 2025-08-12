import common.*;
import com.google.gson.Gson;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;


// Ponto de entrada
public class UrnNodeApplication {
    
    // Main padrão que certifica se o arquivo de configuração foi passado como argumento
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java UrnNodeApplication <config-file>");
            System.exit(1);
        }
        
        // Lê o arquivo de configuração e inicializa a UrnNode
        String json = new String(Files.readAllBytes(Paths.get(args[0])));
        UrnConfig config = new Gson().fromJson(json, UrnConfig.class);
        UrnNode node = new UrnNode(config);
        node.start();
    }
}

class UrnNode {
    private final UrnConfig config;
    private final String zkAddress = "127.0.0.1:2181";
    private final SyncPrimitive.Leader leader;
    private final SyncPrimitive.Lock lock;
    private final SyncPrimitive.Queue queue;
    private final SyncPrimitive.Barrier barrier;
    private final SyncPrimitive.Barrier auditBarrier;
    private final SyncPrimitive.DataStore dataStore;
    private final BuData localBus;
    private final Gson gson = new Gson();
    
    // Flag para caso a urna seja lider e seguidor ao mesmo tempo, assim ela não sai antes da thread de líder terminar
    private volatile boolean canExit = true;

    // Construtor que inicializa os primitivos de sincronização e lê o BU da Urna
    public UrnNode(UrnConfig config) throws Exception {
        this.config = config;
        this.leader = new SyncPrimitive.Leader(zkAddress, "/leaders/" + config.region, "/leader", config.id);
        this.lock = new SyncPrimitive.Lock(zkAddress, "/tallies/total");
        this.queue = new SyncPrimitive.Queue(zkAddress, "/queues/" + config.region);
        this.barrier = new SyncPrimitive.Barrier(zkAddress, "/urns/" + config.region, config.groupSize);
        this.auditBarrier = new SyncPrimitive.Barrier(zkAddress, "/audited/" + config.region, config.groupSize+1); // +1 porque o líder também entra na barreira de auditoria
        this.dataStore = new SyncPrimitive.DataStore(zkAddress);
        this.localBus = BuReader.readLocalBU(config);
    }

    public void start() throws Exception {
        // Manda BU local para o Zookeeper usando a queue
        submitLocalBu();

        // Entra na barreira para esperar que todas as urnas do grupo enviem seus votos para continuar
        enterBarrier(barrier, "submission");
        
        // Como o processo de lider é bloquante, rodamos em uma thread separada
        // (O SyncPrimitive do laboratório é assim, não reclama comigo)
        new Thread(this::tryBecomeLeader).start();
        
        // Todos os nós, mesmo sendo lider, entram em modo seguidor para auditoria
        startFollowerMode();
    }

    private void submitLocalBu() {
        System.out.println(config.urnId + " submitting BU...");
        try {
            queue.produce(gson.toJson(localBus).getBytes());
        } catch (Exception e) {
            System.err.println("Error submitting BU: " + e.getMessage());
        }
    }

    private void tryBecomeLeader() {
        try {
            // A thread da urna que não conseguir ser líder vai ficar presa aqui
            leader.elect();
            // A partir daqui, só a urna líder roda o codigo restante
            System.out.println(config.urnId + " is now LEADER of region " + config.region);
            canExit = false;
            LeaderProcessing();
        } catch (Exception e) {
            System.err.println("Leadership election failed: " + e.getMessage());
        }
    }

    private void LeaderProcessing() throws Exception {
        // Consolidar votos de todas as BUs na fila
        Map<String, Integer> consolidatedVotes = new HashMap<>();
        Map<String, BuData> urnBus = new HashMap<>();
        byte[] data;

        while ((data = queue.consumeBytes()) != null) {
            BuData bu = gson.fromJson(new String(data), BuData.class);
            urnBus.put(bu.urnId, bu);
            bu.votes.forEach((candidate, votes) ->
                consolidatedVotes.merge(candidate, votes, Integer::sum));
            System.out.println("Consolidated votes from " + bu.urnId);
        }

        RegionalTally tally = new RegionalTally(config.region);
        tally.votes = consolidatedVotes;
        tally.urnBus = urnBus;

        // Salva a apuração regional no Zookeeper
        String nodePath = "/tallies/" + config.region;
        byte[] tallyData = gson.toJson(tally).getBytes();
        dataStore.store(nodePath, tallyData);
        System.out.println("Regional tally completed and stored");

        
        // Entra na barreira de auditoria, para esperar os seguidores fazerem a auditoria
        enterBarrier(auditBarrier, "audit as leader");
        System.out.println(config.region + " passed audit barrier, updating total tally...");

        // Passada a auditoria, atualiza a apuração total
        // Usa lock para garantir exclusividade na atualização
        lock.lock();
        System.out.println(config.urnId + " acquired lock for total tally update");
        Thread.sleep(2000); // Tempo apena para demonstrar o bloqueio
        try {
            String totalTallyPath = "/tallies/total";
            byte[] totalData = dataStore.retrieve(totalTallyPath);
            Map<String, Integer> totalVotes = new HashMap<>();
            Map<String, BuData> totalUrnBus = new HashMap<>();

            if (totalData != null) {
                RegionalTally totalTally = gson.fromJson(new String(totalData), RegionalTally.class);
                if (totalTally != null) {
                    if (totalTally.votes != null) {
                        totalVotes.putAll(totalTally.votes);
                    }
                    if (totalTally.urnBus != null) {
                        totalUrnBus.putAll(totalTally.urnBus);
                    }
                }
            }

            // Realiza a soma das apurações
            for (Map.Entry<String, Integer> entry : tally.votes.entrySet()) {
                totalVotes.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
            // Adiciona as BUs da apuração regional
            for (Map.Entry<String, BuData> entry : tally.urnBus.entrySet()) {
                totalUrnBus.put(entry.getKey(), entry.getValue());
            }

            RegionalTally newTotalTally = new RegionalTally("total");
            newTotalTally.votes = totalVotes;
            newTotalTally.urnBus = totalUrnBus;

            byte[] newTotalData = gson.toJson(newTotalTally).getBytes();
            dataStore.store(totalTallyPath, newTotalData);

            System.out.println("Total tally updated and stored");
        } finally {
            lock.unlock();
            canExit = true; // Permite que a thread de seguidor saia
            System.exit(0); // Finaliza a aplicação
        }
    }

    private void startFollowerMode() {
        System.out.println(config.urnId + " running as FOLLOWER in region " + config.region);
        
        // Tenta pegar a apuração do grupo no Zookeeper
        // Se não estiver disponível, espera e tenta novamente
        String nodePath = "/tallies/" + config.region;
        byte[] tallyData = null;

        while (tallyData == null) {
            try {
                tallyData = dataStore.retrieve(nodePath);
                if (tallyData == null) {
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                System.err.println("Error retrieving tally: " + e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        RegionalTally tally = gson.fromJson(new String(tallyData), RegionalTally.class);

        // Executar auditoria da apuração
        boolean auditPassed = auditTally(tally, localBus, config.urnId);
        System.out.println(config.urnId + " audit: " + (auditPassed ? "PASSED" : "FAILED"));

        // Se passou na auditoria, entra na barreira de auditoria
        if (auditPassed) {
            enterBarrier(auditBarrier, "audit");
        } 
        // Se não passou, cria um znode de alarme
        else {
            String alarmPath = "/alarms/" + config.urnId;
            String alarmMsg = config.urnId + " audit failed in region " + config.region;
            try {
                dataStore.store(alarmPath, alarmMsg.getBytes());
                System.out.println("Alarm created at " + alarmPath);
            } catch (Exception e) {
                System.err.println("Error creating alarm: " + e.getMessage());
            }
        }
        
        
        while(true) {
            // Confere se não há uma thread de líder rodando
            // Se não houver, pode sair
            if (canExit) {
                System.exit(0);
            }
        }
    }

    private boolean auditTally(RegionalTally tally, BuData localBus, String urnId) {
        // Confere se a BU local bate com a BU armazenada na apuração do lider
        BuData myBu = tally.urnBus.get(urnId);
        boolean myBuMatches = myBu != null && myBu.votes.equals(localBus.votes);

        // Confere se a soma dos votos na tally bate com a soma dos votos nas BUs
        Map<String, Integer> sumVotes = new HashMap<>();
        for (BuData bu : tally.urnBus.values()) {
            bu.votes.forEach((candidate, votes) ->
                sumVotes.merge(candidate, votes, Integer::sum));
        }
        boolean sumMatches = sumVotes.equals(tally.votes);

        return myBuMatches && sumMatches;
    }

    // Helper para entrar em barreiras
    private void enterBarrier(SyncPrimitive.Barrier barrier, String action) {
        try {
            System.out.println(config.urnId + " entering " + action + " barrier...");
            barrier.enter();
            System.out.println(action + " barrier passed!");
        } catch (Exception e) {
            System.err.println("Error entering " + action + " barrier: " + e.getMessage());
        }
    }
}