import common.*;
import com.google.gson.Gson;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * UrnNodeApplication
 * 
 * Ponto de entrada para um nó de urna eletrônica em uma eleição distribuída.
 * Cada instância representa uma urna que participa do processo de apuração regional,
 * auditoria e consolidação dos resultados totais usando Apache ZooKeeper para coordenação.
 */
public class UrnNodeApplication {

    /**
     * Main padrão que certifica se o arquivo de configuração foi passado como argumento.
     */
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
    private volatile boolean canExit = true;

    /**
     * Construtor que inicializa os primitivos de sincronização e lê o BU da Urna.
     */
    public UrnNode(UrnConfig config) throws Exception {
        this.config = config;
        this.leader = new SyncPrimitive.Leader(zkAddress, "/leaders/" + config.region, "/leader", config.id);
        this.lock = new SyncPrimitive.Lock(zkAddress, "/tallies/total");
        this.queue = new SyncPrimitive.Queue(zkAddress, "/queues/" + config.region);
        this.barrier = new SyncPrimitive.Barrier(zkAddress, "/urns/" + config.region, config.groupSize);
        this.auditBarrier = new SyncPrimitive.Barrier(zkAddress, "/audited/" + config.region, config.groupSize + 1); // +1 para o líder
        this.dataStore = new SyncPrimitive.DataStore(zkAddress);
        this.localBus = BuReader.readLocalBU(config);
    }

    /**
     * Inicia o nó da urna, enviando o BU, sincronizando com o grupo,
     * participando da eleição de líder e realizando auditoria.
     */
    public void start() throws Exception {
        submitLocalBu();
        enterBarrier(barrier, "submission");

        // Processo de liderança em thread separada
        new Thread(this::tryBecomeLeader).start();

        // Todos os nós auditam a apuração regional
        startFollowerMode();
    }

    /**
     * Envia o BU local para o Zookeeper usando a fila.
     */
    private void submitLocalBu() {
        System.out.println(config.urnId + " submitting BU...");
        try {
            queue.produce(gson.toJson(localBus).getBytes());
        } catch (Exception e) {
            System.err.println("Error submitting BU: " + e.getMessage());
        }
    }

    /**
     * Tenta tornar-se líder regional em uma thread separada.
     */
    private void tryBecomeLeader() {
        try {
            leader.elect();
            System.out.println(config.urnId + " is now LEADER of region " + config.region);
            canExit = false;
            LeaderProcessing();
        } catch (Exception e) {
            System.err.println("Leadership election failed: " + e.getMessage());
        }
    }

    /**
     * Consolida os votos regionais, publica apuração, aguarda auditoria
     * e atualiza o resultado total com exclusividade.
     */
    private void LeaderProcessing() throws Exception {
        RegionalTally tally = new RegionalTally(config.region);

        // Consome todos os BUs da fila e agrega na apuração regional
        byte[] data;
        while ((data = queue.consumeBytes()) != null) {
            BuData bu = new BuData(new String(data));
            tally.mergeBu(bu);
            System.out.println("Consolidated votes from " + bu.urnId);
        }

        // Salva a apuração regional no Zookeeper
        String nodePath = "/tallies/" + config.region;
        byte[] tallyData = gson.toJson(tally).getBytes();
        dataStore.store(nodePath, tallyData);
        System.out.println("Regional tally completed and stored");

        // Aguarda auditoria dos seguidores
        enterBarrier(auditBarrier, "audit as leader");
        System.out.println(config.region + " passed audit barrier, updating total tally...");

        // Atualiza apuração total com exclusividade
        lock.lock();
        System.out.println(config.urnId + " acquired lock for total tally update");
        Thread.sleep(2000); // Apenas para demonstrar o bloqueio

        try {
            String totalTallyPath = "/tallies/total";
            byte[] totalData = dataStore.retrieve(totalTallyPath);

            RegionalTally totalTally = (totalData != null)
                ? new RegionalTally(new String(totalData))
                : new RegionalTally("total");

            totalTally.mergeTally(tally);

            byte[] newTotalData = gson.toJson(totalTally).getBytes();
            dataStore.store(totalTallyPath, newTotalData);

            System.out.println("Total tally updated and stored");
        } finally {
            lock.unlock();
            canExit = true;
            System.exit(0);
        }
    }

    /**
     * Executa o modo seguidor, auditando a apuração regional e criando alarmes em caso de falha.
     */
    private void startFollowerMode() {
        System.out.println(config.urnId + " running as FOLLOWER in region " + config.region);

        String nodePath = "/tallies/" + config.region;
        byte[] tallyData = null;

        // Aguarda até que a apuração regional esteja disponível
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

        RegionalTally tally = new RegionalTally(new String(tallyData));

        // Auditoria da apuração
        boolean auditPassed = auditTally(tally, localBus, config.urnId);
        System.out.println(config.urnId + " audit: " + (auditPassed ? "PASSED" : "FAILED"));

        if (auditPassed) {
            enterBarrier(auditBarrier, "audit");
        } else {
            String alarmPath = "/alarms/" + config.urnId;
            String alarmMsg = config.urnId + " audit failed in region " + config.region;
            try {
                dataStore.store(alarmPath, alarmMsg.getBytes());
                System.out.println("Alarm created at " + alarmPath);
            } catch (Exception e) {
                System.err.println("Error creating alarm: " + e.getMessage());
            }
        }

        // Aguarda até que a thread de líder termine antes de sair
        while (true) {
            if (canExit) {
                System.exit(0);
            }
        }
    }

    /**
     * Realiza a auditoria da apuração regional.
     */
    private boolean auditTally(RegionalTally tally, BuData localBus, String urnId) {
        BuData myBu = tally.urnBus.get(urnId);
        boolean myBuMatches = localBus.votesEqual(myBu);
        boolean sumMatches = tally.sumVotesFromBus().equals(tally.votes);
        return myBuMatches && sumMatches;
    }

    /**
     * Helper para entrar em barreiras de sincronização.
     */
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