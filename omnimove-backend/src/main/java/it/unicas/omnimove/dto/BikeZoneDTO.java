package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * An operating/parking zone of the bike-sharing service.
 * Either a polygon (list of [lat, lon] points) or a circle
 * (center + radius_m) — the frontend renders whichever is present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BikeZoneDTO {
    @JsonProperty("zone_id")   private String zoneId;
    private String title;
    @JsonProperty("zone_type") private String zoneType;
    private String color;
    private List<double[]> polygon;
    private double[] center;
    @JsonProperty("radius_m")  private Integer radiusM;
}
