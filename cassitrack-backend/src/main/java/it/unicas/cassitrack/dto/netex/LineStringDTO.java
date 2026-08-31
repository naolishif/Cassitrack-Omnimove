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

    /**
     * Obbligatorio in GML 3.2: ogni geometria deve essere identificabile.
     * Mancava, e da solo bastava a far fallire una validazione GML.
     */
    @JacksonXmlProperty(isAttribute = true, namespace = "http://www.opengis.net/gml/3.2", localName = "id")
    private String gmlId;

    /**
     * Forma URN invece di "EPSG:4326".
     *
     * Non è pedanteria: nella forma corta l'ordine degli assi è ambiguo e
     * diversi strumenti la leggono lon-lat, ribaltando ogni coordinata
     * dall'altra parte del mondo. L'URN fissa l'ordine ufficiale di EPSG:4326,
     * che è lat-lon — cioè esattamente come posList è scritta qui.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "srsName")
    private String srsName = "urn:ogc:def:crs:EPSG::4326";

    /** "lat lon lat lon …", in path order. */
    @JacksonXmlProperty(namespace = "http://www.opengis.net/gml/3.2", localName = "posList")
    private String posList;
}
