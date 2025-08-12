package common;

import java.util.HashMap;
import java.util.Map;

public class RegionalTally {
    public String region;
    public Map<String, Integer> votes; // votos por candidato
    public Map<String, BuData> urnBus; // BUs que contribuíram para a apuração

    public RegionalTally(String region) {
        this.region = region;
        this.votes = new HashMap<>();
        this.urnBus = new HashMap<>();
    }
}