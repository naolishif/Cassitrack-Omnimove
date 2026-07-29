package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.Instant;

@Data
public class StopArrivalDTO {
    @JsonProperty("vehicle_id")        private String  vehicleId;
    @JsonProperty("trip_id")           private String  tripId;
    @JsonProperty("route_id")          private String  routeId;
    @JsonProperty("route_name")        private String  routeName;
    @JsonProperty("route_short_name")  private String  routeShortName;
    @JsonProperty("estimated_arrival") private Instant estimatedArrival;
    @JsonProperty("scheduled_arrival") private Instant scheduledArrival;
    @JsonProperty("schedule_status")   private String  scheduleStatus;
    @JsonProperty("delay_minutes")     private Integer delayMinutes;
    @JsonProperty("crowding_level")    private String  crowdingLevel;

    /** Stop where CassiTrack measured the retrospective delay (for the C1 notice). */
    @JsonProperty("delay_stop_name")   private String  delayStopName;
}
