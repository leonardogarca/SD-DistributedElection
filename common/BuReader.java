package common;

import java.security.KeyPair;
import java.util.*;

public class BuReader {
    public static List<BuData> readLocalBUs(String region, String urnId) {
        List<BuData> buList = new ArrayList<>();

        try {
            // Simulate one BU per urn
            KeyPair keyPair = CryptoUtils.generateKeyPair();
            BuData bu = new BuData();
            bu.region = region;
            bu.sectionId = urnId;
            bu.votes = new HashMap<>();
            bu.votes.put("Candidate A", new Random().nextInt(100) + 50);
            bu.votes.put("Candidate B", new Random().nextInt(100) + 50);
            bu.publicKey = keyPair.getPublic();
            bu.signature = CryptoUtils.sign(bu, keyPair.getPrivate());

            buList.add(bu);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return buList;
    }
}
