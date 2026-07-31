package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class JourneyLeg {
    private String mode;
    private String from;
    private String to;
    @JsonProperty("duration_minutes") private Integer durationMinutes;
    @JsonProperty("distance_metres") private Double distanceMetres;
    @JsonProperty("stop_coords")  private List<double[]> stopCoords;
    private String instruction;
    @JsonProperty("route_id") private String routeId;
}
