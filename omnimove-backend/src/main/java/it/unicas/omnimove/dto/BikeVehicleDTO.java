package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A shared bike/scooter position as shown on the traveller map.
 * Source: Elerent fleet on the ATOM Mobility (RideAtom) platform,
 * or the mock provider while no App-Public-Key is available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BikeVehicleDTO {
    @JsonProperty("bike_id")      private String bikeId;
    @JsonProperty("plate")        private String plate;
    private Double lat;
    private Double lon;
    @JsonProperty("battery_pct")  private Integer batteryPct;
    @JsonProperty("vehicle_type") private String vehicleType;   // BIKE | SCOOTER
    @JsonProperty("is_available") private Boolean isAvailable;
    @JsonProperty("last_updated") private Instant lastUpdated;
}
