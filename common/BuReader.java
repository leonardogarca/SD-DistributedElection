package common;

import java.util.*;

public class BuReader {
    public static BuData readLocalBU(UrnConfig config) {
        BuData bu = new BuData();
        bu.region = config.region;
        bu.urnId = config.urnId;
        bu.votes = config.votes != null ? new HashMap<>(config.votes) : new HashMap<>();
        return bu;
    }
}
