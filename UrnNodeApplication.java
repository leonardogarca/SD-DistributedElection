import common.*;
import com.google.gson.Gson;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class UrnNodeApplication {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java UrnNodeApplication <config-file>");
            System.exit(1);
        }

        UrnConfig config = loadConfig(args[0]);
        UrnNode node = new UrnNode(config);
        node.start();
    }

    private static UrnConfig loadConfig(String configFile) throws Exception {
        String json = new String(Files.readAllBytes(Paths.get(configFile)));
        return new Gson().fromJson(json, UrnConfig.class);
    }
}

class UrnNode {
    private final UrnConfig config;
    private final String zkAddress = "127.0.0.1:2181";
    private final SyncPrimitive.Leader leader;
    private final SyncPrimitive.Lock lock;
    private final SyncPrimitive.Queue queue;
    private final SyncPrimitive.Barrier barrier;
    private final SyncPrimitive.DataStore dataStore;
    private final BuData localBus;
    private final Gson gson = new Gson();

    public UrnNode(UrnConfig config) throws Exception {
        this.config = config;
        this.leader = new SyncPrimitive.Leader(zkAddress, "/election/" + config.region, "/leader", config.id);
        this.lock = new SyncPrimitive.Lock(zkAddress, "/tallies/total");
        this.queue = new SyncPrimitive.Queue(zkAddress, "/queues/" + config.region);
        this.barrier = new SyncPrimitive.Barrier(zkAddress, "/urns/" + config.region, config.groupSize);
        this.dataStore = new SyncPrimitive.DataStore(zkAddress);
        this.localBus = BuReader.readLocalBU(config);
    }

    public void start() throws Exception {
        // Manda BU local para o Zookeeper
        submitLocalBu();

        // Espera que todas as urnas do grupo enviem seus votos para continuar
        waitForAllUrns();
        
        // Como o processo de lider é bloquante, rodamos em uma thread separada
        // (O SyncPrimitive do laboratório é assim, não reclama comigo)
        new Thread(this::tryBecomeLeader).start();
        // Todos os nós, líderes ou não, entram em modo seguidor para auditoria
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

    private void waitForAllUrns() {
        try {
            System.out.println(config.urnId + " waiting for all urns to submit...");
            barrier.enter();
            System.out.println("All urns have submitted their BUs");
        } catch (Exception e) {
            System.err.println("Error waiting for barrier: " + e.getMessage());
        }
    }

    private void tryBecomeLeader() {
        try {
            leader.elect();
            System.out.println(config.urnId + " is now LEADER of region " + config.region);
            LeaderProcessing();
        } catch (Exception e) {
            System.err.println("Leadership election failed: " + e.getMessage());
        }
    }

    private void LeaderProcessing() throws Exception {
        RegionalTally tally = consolidateVotes();
        storeTally(tally);
        System.out.println("Regional tally completed and stored");
    }

    private RegionalTally consolidateVotes() throws Exception {
        Map<String, Integer> consolidatedVotes = new HashMap<>();
        Map<String, BuData> urnBus = new HashMap<>();
        byte[] data;

        while ((data = queue.consumeBytes()) != null) {
            BuData bu = gson.fromJson(new String(data), BuData.class);
            urnBus.put(bu.urnId, bu);
            bu.votes.forEach((candidate, votes) ->
                consolidatedVotes.merge(candidate, votes, Integer::sum));
        }

        RegionalTally tally = new RegionalTally(config.region);
        tally.votes = consolidatedVotes;
        tally.urnBus = urnBus;
        return tally;
    }

    private void storeTally(RegionalTally tally) throws Exception {
        String nodePath = "/tallies/" + config.region;
        byte[] tallyData = gson.toJson(tally).getBytes();
        dataStore.store(nodePath, tallyData);
        
        // Also write to local file
        String fileName = "regional_tally_" + config.region + ".json";
        Files.write(Paths.get(fileName), tallyData);
    }

    private void startFollowerMode() {
        System.out.println(config.urnId + " running as FOLLOWER in region " + config.region);
        
        new Thread(() -> {
            while (true) {
                try {
                    auditTally();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("Audit error: " + e.getMessage());
                }
            }
        }).start();
    }

    private void auditTally() throws Exception {
        String nodePath = "/tallies/" + config.region + "/result";
        byte[] tallyData = dataStore.retrieve(nodePath);

        if (tallyData == null) {
            return; // No tally available yet
        }

        RegionalTally tally = gson.fromJson(new String(tallyData), RegionalTally.class);

        // Check this urn's BU matches the one in the tally
        BuData myBu = tally.urnBus.get(config.urnId);
        boolean myBuMatches = myBu != null && myBu.votes.equals(localBus.votes);

        // Check sum of all BUs matches the total
        Map<String, Integer> sumVotes = new HashMap<>();
        for (BuData bu : tally.urnBus.values()) {
            bu.votes.forEach((candidate, votes) ->
                sumVotes.merge(candidate, votes, Integer::sum));
        }
        boolean sumMatches = sumVotes.equals(tally.votes);

        boolean auditPassed = myBuMatches && sumMatches;
        System.out.println(config.urnId + " audit: " + (auditPassed ? "PASSED" : "FAILED"));
    }
}