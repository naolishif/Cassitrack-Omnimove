package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * Un tipo di giorno di servizio.
 *
 * Qui ce n'è uno solo — "tutti i giorni" — perché è quanto il database
 * afferma: un orario unico, ripetuto. Le proprietà stanno in
 * {@link PropertiesOfDayDTO}: DaysOfWeek elencato per esteso invece del
 * valore di comodo "Everyday", che non tutti gli importatori riconoscono.
 */
@Data
public class DayTypeDTO {

    @JacksonXmlProperty(isAttribute = true, localName = "id")
    private String id;

    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version = "1";

    @JacksonXmlProperty(localName = "Name")
    private String name;

    @JacksonXmlProperty(localName = "properties")
    private PropertiesOfDayDTO properties;

    public DayTypeDTO() {}

    public DayTypeDTO(String id, String name, PropertiesOfDayDTO properties) {
        this.id = id;
        this.name = name;
        this.properties = properties;
    }
}
