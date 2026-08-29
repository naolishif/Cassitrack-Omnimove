package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

import java.util.List;

/**
 * Quando circolano le corse.
 *
 * PERCHÉ SERVIVA
 * Le ServiceJourney dicevano dove passa un mezzo e a che ora, ma non in quali
 * giorni. Un consumatore riceveva "la corsa delle 06:25" senza sapere se vale
 * il lunedì, la domenica o solo il 3 marzo. In NeTEx quel legame passa da un
 * DayType, e senza il documento è incompleto anche quando ogni singolo
 * elemento è formalmente valido.
 *
 * PERCHÉ UN SOLO DayType
 * Il database non ha un calendario: la tabella trips non ha giorni di servizio
 * e gli orari sono secondi dalla mezzanotte, uno solo, ripetuto ogni giorno.
 * Emettere più DayType significherebbe affermare distinzioni che i dati non
 * fanno. Uno solo, "tutti i giorni", è la traduzione fedele di ciò che il
 * sistema sa davvero — e il giorno in cui il database avrà i giorni di
 * servizio, questo è il punto in cui andranno.
 */
@Data
public class ServiceCalendarFrameDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id = "CASSITRACK:ServiceCalendarFrame:1";

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1";

    @JacksonXmlElementWrapper(localName = "dayTypes")
    @JacksonXmlProperty(localName = "DayType")
    private List<DayTypeDTO> dayTypes;
}
