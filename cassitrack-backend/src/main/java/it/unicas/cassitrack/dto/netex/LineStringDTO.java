package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * The road geometry of a line, carried on the NeTEx feed so OmniMove receives
 * it along with routes and stops instead of needing its own copy step.
 *
 * Modelled on GML's LineString, which NeTEx itself uses for link geometry: the
 * whole polyline is one whitespace-separated list of "lat lon lat lon …". Kept
 * as a single string on purpose — a route runs to a few hundred vertices, and
 * one element per point would bloat the document enormously for no gain.
 *
 * Optional: lines with no shape simply omit the element, and importers that
 * predate it ignore it.
 */
@Data
public class LineStringDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "srsName")
    private String srsName = "EPSG:4326";

    /** "lat lon lat lon …", in path order. */
    @JacksonXmlProperty(localName = "posList")
    private String posList;
}
