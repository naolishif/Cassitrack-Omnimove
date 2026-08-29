package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * I giorni della settimana coperti.
 *
 * Elencati per esteso e non con "Everyday": quest'ultimo è ammesso dallo
 * schema ma diversi importatori non lo gestiscono, e l'elenco esplicito dice
 * la stessa cosa senza dipendere da come il consumatore interpreta un valore
 * di comodo.
 */
@Data
public class PropertyOfDayDTO {

    @JacksonXmlProperty(localName = "DaysOfWeek")
    private String daysOfWeek =
            "Monday Tuesday Wednesday Thursday Friday Saturday Sunday";
}
