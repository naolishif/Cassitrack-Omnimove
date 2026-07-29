package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Predicted arrival at a specific stop.
 * Returned by GET /api/v1/stops/{stopId}/arrivals
 *
 * Example response:
 * [
 *   {
 *     "vehicle_id": "MAGNI-001",
 *     "route_id": "LINEA-16",
 *     "route_name": "Linea 16 - Campus Folcara",
 *     "scheduled_arrival": "2026-04-04T08:45:00Z",
 *     "estimated_arrival": "2026-04-04T08:48:00Z",
 *     "delay_minutes": 3,
 *     "schedule_status": "SLIGHTLY_LATE"
 *   }
 * ]
 */
@Data
@Builder
public class StopArrivalDTO {

    @JsonProperty("vehicle_id")
    private String vehicleId;

    @JsonProperty("trip_id")
    private String tripId;

    @JsonProperty("route_id")
    private String routeId;

    @JsonProperty("route_name")
    private String routeName;

    @JsonProperty("route_short_name")
    private String routeShortName;

    @JsonProperty("crowding_level")
    private String crowdingLevel;

    /** The arrival time according to the static GTFS schedule */
    @JsonProperty("scheduled_arrival")
    private Instant scheduledArrival;

    /** The arrival time predicted from real-time position */
    @JsonProperty("estimated_arrival")
    private Instant estimatedArrival;

    /** Minutes late (positive = late, negative = early) */
    @JsonProperty("delay_minutes")
    private Integer delayMinutes;

    @JsonProperty("schedule_status")
    private String scheduleStatus;

    /** Stop where the delay was measured, for OmniMove's retrospective notice. */
    @JsonProperty("delay_stop_name")
    private String delayStopName;
}
