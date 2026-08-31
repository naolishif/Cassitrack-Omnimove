package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@Data
/**
 * Extensions PRIMO, poi gli elementi propri di Line.
 *
 * Extensions viene da DataManagedObject, il tipo base, e in un'estensione XSD
 * la sequenza della base precede sempre quella del tipo derivato. Lo prova il
 * confronto con ServiceJourney, che dichiarava Extensions per primo e non dava
 * errore mentre Line, che lo dichiarava per ultimo, sì.
 */
@JsonPropertyOrder({ "extensions", "name", "shortName", "transportMode", "presentation" })
public class LineDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1";

    @JacksonXmlProperty(localName = "Name")
    private String name;

    @JacksonXmlProperty(localName = "ShortName")
    private String shortName;

    /** Modalità di trasporto — valore standard NeTEx: bus, tram, metro, ferry… */
    @JacksonXmlProperty(localName = "TransportMode")
    private String transportMode = "bus";

    /**
     * Line and label colour. Null when the route has no colour set, in which
     * case consumers fall back to whatever they generate themselves.
     */
    @JacksonXmlProperty(localName = "Presentation")
    private PresentationDTO presentation;

    /**
     * Dati fuori standard di questa linea — oggi la sola geometria stradale.
     *
     * Prima la geometria era un figlio diretto di Line, dove NeTEx non la
     * prevede. Vedi {@link LineExtensionsDTO} per il perché di Extensions.
     * Resta opzionale: una linea senza tracciato omette l'elemento.
     */
    @JacksonXmlProperty(localName = "Extensions")
    private LineExtensionsDTO extensions;
}
