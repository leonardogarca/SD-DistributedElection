package common;

import java.util.*;
// Classe para abstrair a leitura do BU local
public class BuReader {
    public static BuData readLocalBU(UrnConfig config) {
        BuData bu = new BuData();
        bu.region = config.region;
        bu.urnId = config.urnId;
        bu.votes = config.votes != null ? new HashMap<>(config.votes) : new HashMap<>();
        return bu;
    }
}
