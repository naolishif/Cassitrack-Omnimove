package it.unicas.omnimove.dto.netex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * Il blocco &lt;Extensions&gt; di una Line.
 *
 * CassiTrack ci pubblica la geometria stradale. Prima stava come figlio
 * diretto di &lt;Line&gt;, dove NeTEx non la prevede: una Line non ha un
 * LineString, la geometria appartiene ai RouteLink. Extensions e' il punto
 * che lo standard riserva al contenuto non previsto.
 *
 * localName senza prefisso: sul filo l'elemento e' &lt;gml:LineString&gt;, ma
 * il parser separa il prefisso dal nome e qui arriva il solo nome locale.
 * Scrivere "gml:LineString" non troverebbe nulla.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LineExtensionsDTO {

    @JacksonXmlProperty(localName = "LineString")
    private LineStringDTO lineString;
}
