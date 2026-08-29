package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.Data;
import java.time.Instant;

@Data
@JacksonXmlRootElement(localName = "PublicationDelivery")
public class PublicationDeliveryDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "xmlns")
    private String xmlns = "http://www.netex.org.uk/netex";

    /**
     * Dichiarato perché la geometria dentro Extensions usa il prefisso gml:.
     * Senza questa riga quel prefisso non è legato a nulla e il documento non
     * è nemmeno XML ben formato, prima ancora di essere NeTEx valido.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "xmlns:gml")
    private String xmlnsGml = "http://www.opengis.net/gml/3.2";

    @JacksonXmlProperty(isAttribute = true, localName = "xmlns:xsi")
    private String xmlnsXsi = "http://www.w3.org/2001/XMLSchema-instance";

    /**
     * Dice contro cosa validare. Senza, un consumatore che voglia verificare
     * il documento deve indovinare lo schema.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "xsi:schemaLocation")
    private String schemaLocation =
            "http://www.netex.org.uk/netex "
            + "http://www.netex.org.uk/schemas/1.0/xsd/NeTEx_publication.xsd";

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1.0";

    @JacksonXmlProperty(localName = "PublicationTimestamp")
    private String publicationTimestamp = Instant.now().toString();

    @JacksonXmlProperty(localName = "ParticipantRef")
    private String participantRef = "CASSITRACK";

    @JacksonXmlProperty(localName = "dataObjects")
    private DataObjects dataObjects;
}
