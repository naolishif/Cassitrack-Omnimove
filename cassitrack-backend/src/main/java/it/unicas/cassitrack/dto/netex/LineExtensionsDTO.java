package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Contenitore per i dati di una Line che NeTEx non prevede.
 *
 * PERCHÉ ESISTE
 * La geometria stradale stava come figlio diretto di {@code <Line>}, e lì non
 * può stare: in NeTEx una Line non ha un figlio LineString. La geometria
 * appartiene ai RouteLink / ServiceLink attraverso una LinkSequenceProjection,
 * un impianto che richiede di modellare i collegamenti fra fermate — dati che
 * questo sistema non ha e che inventare sarebbe peggio che ometterli.
 *
 * {@code <Extensions>} è il punto che lo standard prevede proprio per questo:
 * contenuto arbitrario, dichiarato tale. Un validatore lo attraversa senza
 * lamentarsi, e un consumatore che non lo conosce lo ignora — a differenza di
 * un elemento fuori posto, che lo fa fallire.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineExtensionsDTO {

    /**
     * Il tracciato stradale della linea, in GML.
     *
     * Qualificato nel namespace GML perché il contenuto di Extensions è fuori
     * dallo spazio dei nomi NeTEx: senza, un consumatore non avrebbe modo di
     * sapere che è una geometria GML e non un elemento inventato da noi.
     *
     * Si usa l'attributo {@code namespace} e non un prefisso scritto dentro
     * {@code localName}: i due punti in un nome locale sono comportamento non
     * definito per lo scrittore XML, mentre così il prefisso lo genera lui e
     * l'XML è valido per costruzione. In lettura è indifferente — Jackson
     * confronta i nomi locali ignorando il prefisso.
     */
    @JacksonXmlProperty(namespace = "http://www.opengis.net/gml/3.2", localName = "LineString")
    private LineStringDTO lineString;

    public LineExtensionsDTO(LineStringDTO lineString) {
        this.lineString = lineString;
    }
}
