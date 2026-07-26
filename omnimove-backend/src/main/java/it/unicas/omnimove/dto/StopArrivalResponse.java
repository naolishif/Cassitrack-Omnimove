package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * What the stop popup actually renders. Wraps the raw CassiTrack arrival
 * and adds the fields the frontend needs to decide what to show:
 *
 *   real_time = true  -> delay_minutes was recomputed live from GPS+traffic
 *                        (Google ON, bus already departed)
 *   real_time = false -> delay_minutes, if present, is CassiTrack's
 *                        retrospective value; delay_stop_name says where it
 *                        was measured. Used for the C1 notice.
 *
 * A not-yet-departed bus has departed = false and no delay at all:
 * the popup shows only its departure time.
 */
@Data
@Builder
public class StopArrivalResponse {

    @JsonProperty("vehicle_id")        private String  vehicleId;
    @JsonProperty("route_id")          private String  routeId;
    @JsonProperty("route_name")        private String  routeName;
    @JsonProperty("route_short_name")  private String  routeShortName;

    @JsonProperty("estimated_arrival") private Instant estimatedArrival;
    @JsonProperty("scheduled_arrival") private Instant scheduledArrival;
    @JsonProperty("schedule_status")   private String  scheduleStatus;
    @JsonProperty("crowding_level")    private String  crowdingLevel;

    /** True if this bus is on the road (has a live GPS position). */
    @JsonProperty("departed")          private boolean departed;

    /** True if delay_minutes was recomputed from live traffic. */
    @JsonProperty("real_time")         private boolean realTime;

    /** Positive = late, negative = early, 0 = on time. Null = unknown / not departed. */
    @JsonProperty("delay_minutes")     private Integer delayMinutes;

    /** For the retrospective notice: the stop where CassiTrack measured the delay. */
    @JsonProperty("delay_stop_name")   private String  delayStopName;
}
