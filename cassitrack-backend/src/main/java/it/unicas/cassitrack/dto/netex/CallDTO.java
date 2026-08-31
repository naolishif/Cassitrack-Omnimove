package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CallDTO {

    /**
     * Obbligatorio: in NeTEx una Call è un DataManagedObject e ogni
     * DataManagedObject deve essere identificabile.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1";

    @JacksonXmlProperty(isAttribute = true, localName = "order")
    private Integer order;

    @JacksonXmlProperty(localName = "ScheduledStopPointRef")
    private RefDTO scheduledStopPointRef;

    /**
     * Orario di partenza (prima fermata) o arrivo (ultima fermata).
     * Fermate intermedie hanno entrambi (stesso orario, dato che il DB ha un solo campo).
     * Regola NeTEx: prima fermata → solo Departure; ultima → solo Arrival; mezzo → entrambi.
     */
    @JacksonXmlProperty(localName = "Arrival")
    private ArrivalDTO arrival;

    @JacksonXmlProperty(localName = "Departure")
    private DepartureDTO departure;
}
