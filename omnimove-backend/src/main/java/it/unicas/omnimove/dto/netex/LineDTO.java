package it.unicas.omnimove.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

@Data
public class LineDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version;

    @JacksonXmlProperty(localName = "Name")
    private String name;

    @JacksonXmlProperty(localName = "ShortName")
    private String shortName;

    @JacksonXmlProperty(localName = "TransportMode")
    private String transportMode;

    /**
     * Line and label colour, or null on feeds that predate it. Imported into
     * routes.color / routes.text_color.
     */
    @JacksonXmlProperty(localName = "Presentation")
    private PresentationDTO presentation;

    /**
     * Road geometry of the line, or null when the feed carries none (older
     * CassiTrack builds). Imported into route_shapes; absence simply leaves the
     * existing shape untouched.
     */
    /**
     * Posizione STORICA della geometria: figlio diretto di Line.
     *
     * Non conforme a NeTEx, ma e' dove le versioni di CassiTrack fino ad
     * agosto 2026 la pubblicavano. Si continua a leggerla per non dipendere
     * dall'ordine con cui i due sistemi vengono aggiornati: un lettore
     * tollerante accetta il vecchio e il nuovo, e nessuno dei due deployment
     * deve aspettare l'altro.
     */
    @Deprecated
    @JacksonXmlProperty(localName = "LineString")
    private LineStringDTO lineString;

    /** Posizione corrente: Extensions, come prevede lo standard. */
    @JacksonXmlProperty(localName = "Extensions")
    private LineExtensionsDTO extensions;

    /**
     * La geometria, da dove si trova. Preferisce la posizione conforme e
     * ricade su quella storica: il chiamante non deve sapere quale delle due
     * versioni di CassiTrack ha prodotto il documento.
     */
    public LineStringDTO resolveLineString() {
        if (extensions != null && extensions.getLineString() != null) {
            return extensions.getLineString();
        }
        return lineString;
    }
}
