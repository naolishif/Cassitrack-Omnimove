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
    @JsonProperty("stop_coords")      private List<double[]> stopCoords;
    @JsonProperty("stop_names")       private List<String>   stopNames;
    /** Only the actual bus stop positions (subset of stop_coords), used for dot markers. */
    @JsonProperty("bus_stop_coords")  private List<double[]> busStopCoords;
    private String instruction;
    @JsonProperty("route_id") private String routeId;
    /**
     * WAIT legs only: true when the wait is a change of bus rather than the initial wait.
     * The client used to infer this by looking for "Change at" inside the instruction,
     * which quietly stopped working the moment that sentence was translated.
     */
    @JsonProperty("transfer") private Boolean transfer;
    /** WAIT legs only: label of the line to board after the change, e.g. "01 → Casilina Nord". */
    @JsonProperty("transfer_line") private String transferLine;
}
