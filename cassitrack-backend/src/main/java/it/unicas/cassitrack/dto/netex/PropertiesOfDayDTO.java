package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/** Le proprietà di un DayType: quali giorni della settimana copre. */
@Data
public class PropertiesOfDayDTO {

    @JacksonXmlProperty(localName = "PropertyOfDay")
    private PropertyOfDayDTO propertyOfDay;

    public PropertiesOfDayDTO() {}
    public PropertiesOfDayDTO(PropertyOfDayDTO p) { this.propertyOfDay = p; }
}
