package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * NeTEx Vehicle — rappresenta un singolo veicolo fisico.
 * I campi operativi non presenti nello standard (targa, disponibilità)
 * sono raccolti in <Extensions>.
 * <p>
 * NB: in NeTEx l'elemento Extensions è definito nella EntityStructure di base,
 * quindi deve comparire PRIMA del contenuto specifico (es. PrivateCode).
 */
@Data
@JsonPropertyOrder({ "extensions", "privateCode" })
public class VehicleDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1";

    /** Campi non standard NeTEx raccolti in Extensions (in testa, come da schema) */
    @JacksonXmlProperty(localName = "Extensions")
    private VehicleExtensionsDTO extensions;

    /** ID del veicolo nel sistema MQTT (es. BUS-101) */
    @JacksonXmlProperty(localName = "PrivateCode")
    private String privateCode;
}
