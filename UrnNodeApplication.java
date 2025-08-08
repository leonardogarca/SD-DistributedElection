import common.*;
import com.google.gson.Gson;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class UrnNodeApplication {
    public static void main(String[] args) throws Exception {
        // Load configuration
        if (args.length < 1) {
            System.err.println("Usage: java UrnNodeApplication <config-file>");
            System.exit(1);
        }
        UrnConfig config = new Gson().fromJson(
            new String(Files.readAllBytes(Paths.get(args[0]))),
            UrnConfig.class
        );

        // Initialize SyncPrimitive components
        String zkAddress = "127.0.0.1:2181";
        String region = config.region;
        String urnId = config.urnId;

        SyncPrimitive.Leader leader = new SyncPrimitive.Leader(zkAddress, "/election/" + region, "/leader", config.id);
        SyncPrimitive.Lock lock = new SyncPrimitive.Lock(zkAddress, "/tallies/" + region);
        SyncPrimitive.Queue queue = new SyncPrimitive.Queue(zkAddress, "/queues/" + region);

        RegionalTallyProcessor processor = new RegionalTallyProcessor(region, urnId, lock, queue);

        // Participate in election
        new Thread(() -> {
            try {
                leader.elect();
                processor.isLeader();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // Start follower behavior in parallel
        processor.notLeader();
    }
}

class RegionalTallyProcessor {
    private final String region;
    private final String urnId;
    private final SyncPrimitive.Lock lock;
    private final SyncPrimitive.Queue queue;
    private final List<BuData> localBus;
    private RegionalTally currentTally;

    public RegionalTallyProcessor(String region, String urnId, SyncPrimitive.Lock lock, SyncPrimitive.Queue queue) {
        this.region = region;
        this.urnId = urnId;
        this.lock = lock;
        this.queue = queue;
        this.localBus = BuReader.readLocalBUs(region, urnId);
        this.currentTally = new RegionalTally(region);

        // Submit local BU
        for (BuData bu : localBus) {
            byte[] buJson = new Gson().toJson(bu).getBytes();
            try {
                queue.produce(buJson);
            } catch (org.apache.zookeeper.KeeperException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void isLeader() throws Exception {
        System.out.println(urnId + " is the LEADER of region " + region);

        if (!lock.lock()) return;
        try {
            Map<String, Integer> consolidatedVotes = new HashMap<>();

            byte[] data;
            while ((data = queue.consumeBytes()) != null) {
                BuData bu = new Gson().fromJson(new String(data), BuData.class);

                for (Map.Entry<String, Integer> entry : bu.votes.entrySet()) {
                    consolidatedVotes.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            }

            currentTally.votes = consolidatedVotes;
            String path = "regional_tally_" + region + ".json";
            Files.write(Paths.get(path), new Gson().toJson(currentTally).getBytes());
            System.out.println("Tally written to " + path);
        } finally {
            lock.unlock();
        }
    }

    public void notLeader() throws Exception {
        System.out.println(urnId + " is a FOLLOWER in region " + region);

        new Thread(() -> {
            while (true) {
                try {
                    String path = "regional_tally_" + region + ".json";
                    if (!Files.exists(Paths.get(path))) {
                        Thread.sleep(1000);
                        continue;
                    }

                    String tallyJson = new String(Files.readAllBytes(Paths.get(path)));
                    RegionalTally latestTally = new Gson().fromJson(tallyJson, RegionalTally.class);
                    
                    Map<String, Integer> expected = new HashMap<>();
                    for (BuData bu : localBus) {
                        for (Map.Entry<String, Integer> entry : bu.votes.entrySet()) {
                            expected.merge(entry.getKey(), entry.getValue(), Integer::sum);
                        }
                    }

                    boolean match = expected.equals(latestTally.votes);
                    System.out.println(match ? "Audit successful." : "AUDIT FAILED!");

                    Thread.sleep(5000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}

class UrnConfig {
    String urnId;
    String region;
    int id;
}
