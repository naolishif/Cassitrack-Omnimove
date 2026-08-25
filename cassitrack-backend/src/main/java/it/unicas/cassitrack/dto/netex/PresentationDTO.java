package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NeTEx {@code <Presentation>} — how a line should be drawn.
 *
 * The colour has always lived in CassiTrack's routes table but was never
 * published, so OmniMove had to invent its own from a hash of the line's short
 * name. With 18 lines and a 10-colour palette that guaranteed collisions, and
 * two lines could share a colour on the map while CassiTrack showed them apart.
 *
 * Both values are 6-digit hex WITHOUT a leading '#', as in the database and in
 * GTFS route_color / route_text_color. Omitted entirely when the line has no
 * colour set, so consumers can tell "no colour" from "black".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PresentationDTO {

    @JacksonXmlProperty(localName = "Colour")
    private String colour;

    @JacksonXmlProperty(localName = "TextColour")
    private String textColour;
}
