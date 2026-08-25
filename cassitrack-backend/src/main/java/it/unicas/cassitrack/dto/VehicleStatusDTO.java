package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import it.unicas.cassitrack.model.VehiclePosition.ScheduleStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * The data returned by GET /api/v1/vehicles and GET /api/v1/vehicles/{id}.
 *
 * This is what OMNIMOVE and the fleet dashboard will receive.
 * It combines the latest GPS position with derived information
 * (schedule status, ETA, crowding estimate).
 */
@Data
@Builder
public class VehicleStatusDTO {

    /** Bus identifier (e.g. "BUS-101") */
    @JsonProperty("vehicle_id")
    private String vehicleId;

    /**
     * Whether this bus should be drawn on the fleet map — the persisted
     * buses.map_visible flag, toggled from Data Management. Read fresh on every
     * request so the toggle applies immediately; null is never sent, the
     * service resolves it to TRUE.
     */
    @JsonProperty("map_visible")
    private Boolean mapVisible;

    /** this id is used to link the MQTT message to the relative bus in the postgres DB */
    private Integer busId;

    private Integer numeroPosti;    // 🚌
    private Boolean wheelchairAccessible;

    /** Current latitude */
    private Double lat;

    /** Current longitude */
    private Double lon;

    /** Current speed in km/h */
    @JsonProperty("speed_kmh")
    private Double speedKmh;

    /** Heading in degrees (0=N, 90=E, 180=S, 270=W) */
    @JsonProperty("heading_deg")
    private Double headingDeg;

    /**
     * Trip this bus is currently running (e.g. "LINEA-16-T03").
     * Derived server-side from bus_id + current time.
     *
     * BUG FIX: this field never existed, but cassitrack-fleetmanager.js has always
     * read `v.trip_id`. The check `!v.trip_id ? 'NO_TRIP' : ...` was therefore
     * always true and every bus rendered as NO_TRIP.
     */

    /** Route this bus is currently operating (e.g. "LINEA-16") */
    @JsonProperty("route_id")
    private String routeId;

    /** Human-readable route name */
    @JsonProperty("route_name")
    private String routeName;

    /** ID of the trip (scheduled run) this bus is currently operating; null if not on a trip */
    @JsonProperty("trip_id")
    private String tripId;

    /** Schedule adherence status */
    @JsonProperty("schedule_status")
    private ScheduleStatus scheduleStatus;

    /**
     * Minutes late (positive = late, negative = early, 0 = on time).
     * Null if status is UNKNOWN.
     */
    @JsonProperty("delay_minutes")
    private Integer delayMinutes;

    /** Fermata a cui il ritardo è stato misurato. Null se nessun arrivo è ancora stato osservato. */
    @JsonProperty("delay_stop_name")
    private String delayStopName;

    @JsonProperty("delay_stop_sequence")
    private Integer delayStopSequence;

    /** Istante del fix GPS in cui il bus era alla fermata. */
    @JsonProperty("delay_measured_at")
    private Instant delayMeasuredAt;

    /**
     * The last stop this bus was physically detected at.
     *
     * BUG FIX: these two used to be published as next_stop_id / next_stop_name
     * while actually holding lastStopRegistered*. The frontend silently
     * compensated by labelling next_stop_name as "LAST STOP" in the popup.
     * They now say what they mean.
     */
    @JsonProperty("last_stop_id")
    private String lastStopId;

    @JsonProperty("last_stop_name")
    private String lastStopName;

    /** ID of the next stop this bus will reach */
    @JsonProperty("next_stop_id")
    private String nextStopId;

    /** Name of the next stop */
    @JsonProperty("next_stop_name")
    private String nextStopName;

    /** Estimated seconds until the bus reaches the next stop */
    @JsonProperty("eta_seconds")
    private Integer etaSeconds;

    /**
     * Estimated number of passengers on board.
     * Direct count when the unit provides one, otherwise derived from the
     * BLE device count using the calibration model.
     */
    @JsonProperty("estimated_passengers")
    private Integer estimatedPassengers;

    /**
     * Crowding level: LOW, MEDIUM, HIGH, VERY_HIGH.
     * Null if passenger estimate is not available.
     */
    @JsonProperty("crowding_level")
    private String crowdingLevel;

    /** Riempimento reale in percentuale. Null se passeggeri o capienza sconosciuti. */
    @JsonProperty("occupancy_pct")
    private Integer occupancyPct;

    /** When this position was recorded on the bus */
    @JsonProperty("timestamp")
    private Instant timestamp;

    /** When the server last received data from this bus */
    @JsonProperty("last_seen")
    private Instant lastSeen;

    /** Whether this bus is currently considered active */
    @JsonProperty("is_active")
    private Boolean isActive;

}
