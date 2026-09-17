package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * An emergency a journey option passes through, as the traveller screen
 * draws it: a circle on the map and a line of text on the card.
 */
@Data @Builder
public class HazardDTO {
    private Long id;
    @JsonProperty("event_type") private String eventType;
    private String severity;
    private String title;
    private Double latitude;
    private Double longitude;
    @JsonProperty("radius_m") private Integer radiusMetres;
}
