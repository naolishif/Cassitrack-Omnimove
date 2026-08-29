package it.unicas.cassitrack.dto.netex;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Data;

/** Dati operativi non standard del ServiceJourney */
@Data
public class ServiceJourneyExtensionsDTO {

    /**
     * Il mezzo fisico assegnato alla corsa.
     *
     * Va emesso come {@code <VehicleRef ref="..."/>}, non come elemento con
     * testo dentro: in NeTEx VehicleRef è una struttura di riferimento e
     * l'attributo {@code ref} è obbligatorio.
     *
     * Il validatore lo segnala anche stando dentro Extensions, e la ragione
     * merita di essere ricordata: Extensions è {@code xsd:any} con
     * {@code processContents="lax"}, cioè "non pretendo di conoscere quello
     * che c'è qui dentro, ma se lo conosco lo controllo". VehicleRef è un
     * elemento NeTEx a tutti gli effetti, quindi viene validato.
     */
    @JacksonXmlProperty(localName = "VehicleRef")
    private RefDTO vehicleRef;

    public ServiceJourneyExtensionsDTO() {}
    public ServiceJourneyExtensionsDTO(String vehicleRef) {
        this.vehicleRef = new RefDTO(vehicleRef);
    }
}
