package com.electoral.tally.urn;

import com.electoral.tally.core.BuData;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

public class BuReader {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static byte[] readBuDataAsBytes(String urnId, String regionId) throws IOException {
        Map<String, Integer> votes = Map.of(
            "CandidateA", (int) (Math.random() * 100),
            "CandidateB", (int) (Math.random() * 150),
            "CandidateC", (int) (Math.random() * 50)
        );
        BuData buData = new BuData(urnId, regionId, votes);
        return objectMapper.writeValueAsBytes(buData);
    }
}