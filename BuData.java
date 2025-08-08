package com.electoral.tally.core;

import java.util.Map;

public record BuData(String urnId, String regionId, Map<String, Integer> votes) {
}