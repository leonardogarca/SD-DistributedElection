package common;


import java.util.Map;

public class BuData {
    public String region;
    public String sectionId;
    public Map<String, Integer> votes;


    public String toMessage() {
        return region + sectionId + votes.toString();
    }
}