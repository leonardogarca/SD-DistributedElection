/**
 * Representa a apuração regional de votos em uma eleição distribuída.
 * 
 * Cada instância mantém:
 * - O nome da região.
 * - Um mapa de votos por candidato.
 * - Um mapa das BuData (urnas) que contribuíram para a apuração.
 * 
 * Métodos principais:
 * - mergeBu(BuData bu): Adiciona os votos de uma urna à apuração.
 * - mergeTally(RegionalTally other): Mescla outra apuração regional nesta, agregando todas as urnas e votos.
 * - sumVotesFromBus(): Calcula a soma dos votos de todas as urnas registradas.
 */
package common;

import java.util.HashMap;
import java.util.Map;

public class RegionalTally {
    public String region; // Nome da região
    public Map<String, Integer> votes; // Votos por candidato
    public Map<String, BuData> urnBus; // Urnas que contribuíram para a apuração

    /**
     * Cria uma nova apuração regional para a região especificada.
     * @param region Nome da região
     */
    public RegionalTally(String region) {
        this.region = region;
        this.votes = new HashMap<>();
        this.urnBus = new HashMap<>();
    }

    /**
     * Cria uma instância de RegionalTally a partir de uma string JSON.
     * @param json String JSON representando um RegionalTally
     * @return Nova instância de RegionalTally
     */
    public static RegionalTally fromJson(String json) {
        return new com.google.gson.Gson().fromJson(json, RegionalTally.class);
    }

    /**
     * Adiciona os votos de uma BuData (urna) à apuração.
     * @param bu Dados da urna a serem agregados
     */
    public void mergeBu(BuData bu) {
        if (bu == null || bu.votes == null) return;
        bu.votes.forEach((candidate, voteCount) ->
            votes.merge(candidate, voteCount, Integer::sum));
        urnBus.put(bu.urnId, bu);
    }

    /**
     * Mescla outra apuração regional nesta, agregando todas as urnas e votos.
     * @param other Outra apuração regional
     */
    public void mergeTally(RegionalTally other) {
        if (other == null || other.urnBus == null) return;
        for (BuData bu : other.urnBus.values()) {
            this.mergeBu(bu);
        }
    }

    /**
     * Calcula a soma dos votos de todas as urnas registradas.
     * @return Mapa de votos totais por candidato
     */
    public Map<String, Integer> sumVotesFromBus() {
        Map<String, Integer> sumVotes = new HashMap<>();
        for (BuData bu : urnBus.values()) {
            if (bu.votes != null) {
                bu.votes.forEach((candidate, votes) ->
                    sumVotes.merge(candidate, votes, Integer::sum));
            }
        }
        return sumVotes;
    }    
}