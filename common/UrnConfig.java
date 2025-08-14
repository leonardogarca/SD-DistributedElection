/**
 * Representa a configuração de uma urna eletrônica em uma eleição distribuída.
 * 
 * Cada instância mantém:
 * - O identificador da urna.
 * - O nome da região.
 * - O tamanho do grupo de urnas.
 * - O identificador único da urna no grupo.
 * - Um mapa de votos por candidato.
 */
package common;

import java.util.Map;

public class UrnConfig {
    public String urnId;                
    public String region;               
    public int groupSize;              
    public int id;                      
    public Map<String, Integer> votes;  
}
