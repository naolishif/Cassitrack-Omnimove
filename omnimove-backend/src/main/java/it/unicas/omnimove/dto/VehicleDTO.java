package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.Instant;
@Data
public class VehicleDTO {
    @JsonProperty("vehicle_id") private String vehicleId;
    private Double lat;
    private Double lon;
    @JsonProperty("speed_kmh") private Double speedKmh;
    @JsonProperty("schedule_status") private String scheduleStatus;
    @JsonProperty("crowding_level") private String crowdingLevel;
    @JsonProperty("estimated_passengers") private Integer estimatedPassengers;
    // Fields needed for live bus map markers
    @JsonProperty("route_id")        private String routeId;
    @JsonProperty("route_name")      private String routeName;
    @JsonProperty("delay_minutes")   private Integer delayMinutes;
    @JsonProperty("next_stop_name")  private String nextStopName;
    @JsonProperty("eta_seconds")     private Integer etaSeconds;
    @JsonProperty("last_seen")       private Instant lastSeen;
    @JsonProperty("is_active")       private Boolean isActive;
}
