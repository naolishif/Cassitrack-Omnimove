package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;
import java.util.List;

// NB: Extensions è definito nella EntityStructure di base NeTEx, quindi va in testa
//     (prima di LineRef); calls resta in coda.
@Data
// dayTypes PRIMA di lineRef: entrambi vengono da Journey_VersionStructure e
// lo schema li vuole in quest'ordine. Il validatore lo ha detto accettando
// LineRef e poi rifiutando dayTypes, cioe' "qui quell'elemento e' gia'
// passato". Elencarli esplicitamente invece di affidarsi all'ordine dei
// campi rende il vincolo visibile a chi rimaneggera' la classe.
@JsonPropertyOrder({ "extensions", "dayTypes", "lineRef", "calls" })
public class ServiceJourneyDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1";

    /**
     * Associazione veicolo–corsa. Non prevista nel core NeTEx (è runtime),
     * esposta come Extensions per trasferire l'info statica di configurazione.
     */
    @JacksonXmlProperty(localName = "Extensions")
    private ServiceJourneyExtensionsDTO extensions;

    @JacksonXmlProperty(localName = "LineRef")
    private RefDTO lineRef;

    /**
     * In quali giorni circola questa corsa.
     *
     * Senza, il documento diceva a che ora passa il mezzo ma non se quella
     * corsa vale il lunedì o la domenica: un orario privo di validità. Punta
     * all'unico DayType del ServiceCalendarFrame, perché è quanto il database
     * afferma — un orario solo, ripetuto ogni giorno.
     */
    @JacksonXmlElementWrapper(localName = "dayTypes")
    @JacksonXmlProperty(localName = "DayTypeRef")
    private List<RefDTO> dayTypes;

    @JacksonXmlElementWrapper(localName = "calls")
    @JacksonXmlProperty(localName = "Call")
    private List<CallDTO> calls;
}
