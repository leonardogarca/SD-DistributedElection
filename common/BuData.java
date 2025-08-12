package common;


import java.util.Map;

public class BuData {
    public String region;
    public String urnId;
    public Map<String, Integer> votes;


    public String toMessage() {
        return region + urnId + votes.toString();
    }
}