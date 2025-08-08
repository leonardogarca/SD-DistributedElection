package common;

import java.util.HashMap;
import java.util.Map;

public class RegionalTally {
    public String region;
    public Map<String, Integer> votes;

    public RegionalTally(String region) {
        this.region = region;
        this.votes = new HashMap<>();
    }
}