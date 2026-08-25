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
    @JacksonXmlProperty(localName = "LineString")
    private LineStringDTO lineString;
}
