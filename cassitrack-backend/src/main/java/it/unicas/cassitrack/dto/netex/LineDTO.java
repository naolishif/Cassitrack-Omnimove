package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

@Data
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
     * Road geometry of the line, or null when it has none. Optional by design:
     * consumers that predate it ignore the element, and lines without a shape
     * keep the stop-to-stop rendering.
     */
    @JacksonXmlProperty(localName = "LineString")
    private LineStringDTO lineString;
}
