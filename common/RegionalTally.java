package common;

import java.util.HashMap;
import java.util.Map;

public class RegionalTally {
    public String region;
    public Map<String, Integer> votes; // total sum
    public Map<String, BuData> urnBus; // urnId -> BuData

    public RegionalTally(String region) {
        this.region = region;
        this.votes = new HashMap<>();
        this.urnBus = new HashMap<>();
    }
}