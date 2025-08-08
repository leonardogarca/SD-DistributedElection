package com.electoral.tally.urn;

import com.electoral.tally.core.BuData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.electoral.tally.core.SyncPrimitive;

import org.apache.zookeeper.KeeperException;

import java.util.HashMap;
import java.util.Map;

public class RegionalTallyProcessor {
    private final String regionId;
    private final String peerId;
    private final String zkAddress;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public RegionalTallyProcessor(String regionId, String peerId, String zkAddress) {
        this.regionId = regionId;
        this.peerId = peerId;
        this.zkAddress = zkAddress;
    }

    public void start() throws Exception {
        SyncPrimitive.Leader leader = new SyncPrimitive.Leader(zkAddress, "/election/" + regionId, "/leader/" + regionId, Integer.parseInt(peerId));
        boolean isLeader = leader.elect();

        if (!isLeader) {
            System.out.println("Node " + peerId + " is not leader for region " + regionId);
            return;
        }

        System.out.println("Node " + peerId + " is the LEADER for region " + regionId);

        SyncPrimitive.Queue queue = new SyncPrimitive.Queue(zkAddress, "/tasks/" + regionId);
        SyncPrimitive.Lock lock = new SyncPrimitive.Lock(zkAddress, "/lock/" + regionId, 1000);

        Map<String, Integer> tally = new HashMap<>();

        while (true) {
            byte[] taskData = queue.consumeBytes();
            BuData buData = objectMapper.readValue(taskData, BuData.class);
            System.out.println("Processing BU from urn: " + buData.urnId());

            if (lock.lock()) {
                try {
                    buData.votes().forEach((candidate, votes) ->
                        tally.merge(candidate, votes, Integer::sum));

                    System.out.println("Updated Tally: " + tally);
                } finally {
                    // Lock is ephemeral and will be released on process exit
                }
            }
        }
    }
}