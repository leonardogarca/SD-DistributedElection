/**
 * Representa os dados de um Boletim de Urna (BU).
 * 
 * Cada instância mantém:
 * - O nome da região.
 * - O identificador da urna.
 * - Um mapa de votos por candidato.
 * 
 * Métodos principais:
 * - toMessage(): Retorna uma representação textual dos dados da urna.
 * - votesEqual(BuData other): Compara os votos desta urna com outra.
 */
package common;

import java.util.Map;
import java.util.Objects;

public class BuData {
    public String region; // Região da urna
    public String urnId;  // Identificador da urna
    public Map<String, Integer> votes; // Votos por candidato

    /**
     * Construtor que inicializa BuData a partir de uma string JSON.
     * @param json String JSON representando um BuData
     */
    public BuData(String json) {
        BuData bu = new com.google.gson.Gson().fromJson(json, BuData.class);
        this.region = bu.region;
        this.urnId = bu.urnId;
        this.votes = bu.votes;
    }
    
    /**
     * Construtor padrão sem argumentos.
     */
    public BuData() {
        // Inicializa os campos com valores padrão (null ou vazio)
        this.region = null;
        this.urnId = null;
        this.votes = null;
    }

    /**
     * Retorna uma representação textual dos dados da urna.
     * @return String representando a urna e seus votos
     */
    public String toMessage() {
        return region + urnId + votes.toString();
    }

    /**
     * Compara os votos deste Boletim com o de outro
     * @param other Outra BuData para comparação
     * @return true se os votos forem iguais, false caso contrário
     */
    public boolean votesEqual(BuData other) {
        return other != null && Objects.equals(this.votes, other.votes);
    }

}