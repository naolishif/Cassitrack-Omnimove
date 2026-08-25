package it.unicas.omnimove.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * NeTEx {@code <Presentation>} — how CassiTrack wants a line drawn.
 *
 * Absent from feeds published by older CassiTrack builds, in which case the
 * traveller map keeps generating a colour of its own.
 *
 * Values are 6-digit hex without a leading '#', matching GTFS route_color.
 */
@Data
public class PresentationDTO {

    @JacksonXmlProperty(localName = "Colour")
    private String colour;

    @JacksonXmlProperty(localName = "TextColour")
    private String textColour;
}
