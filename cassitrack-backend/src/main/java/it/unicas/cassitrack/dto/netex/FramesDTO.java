package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/**
 * Wrapper NeTEx per i frame eterogenei dentro CompositeFrame.
 * Ordine: ResourceFrame (risorse) → SiteFrame (fermate) → ServiceFrame (rete) → TimetableFrame (corse).
 */
@Data
public class FramesDTO {

    @JacksonXmlProperty(localName = "ResourceFrame")
    private ResourceFrameDTO resourceFrame;

    @JacksonXmlProperty(localName = "SiteFrame")
    private SiteFrameDTO siteFrame;

    @JacksonXmlProperty(localName = "ServiceFrame")
    private ServiceFrameDTO serviceFrame;

    /**
     * Prima del TimetableFrame, perché le ServiceJourney che stanno lì
     * referenziano i DayType definiti qui: un documento si legge dall'alto, e
     * un riferimento che precede la sua definizione costringe il consumatore a
     * due passate.
     */
    @JacksonXmlProperty(localName = "ServiceCalendarFrame")
    private ServiceCalendarFrameDTO serviceCalendarFrame;

    @JacksonXmlProperty(localName = "TimetableFrame")
    private TimetableFrameDTO timetableFrame;
}
