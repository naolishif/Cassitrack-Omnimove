package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * One currently running trip, as the fleet manager's Active Trips view needs it.
 *
 * Combines two sources that answer different questions:
 *   • the TIMETABLE (trips + scheduled_stops) — what is supposed to be running
 *   • the LIVE FEED (VehicleStateCache)       — what the bus is actually doing
 *
 * Keeping both visible is the point of the view. A scheduled trip whose bus is
 * reporting nothing is not an empty row to be filtered out; it is the row an
 * operator most needs to see.
 */
@Data
@Builder
public class ActiveTripDTO {

    @JsonProperty("trip_id")
    private String tripId;

    @JsonProperty("route_id")
    private String routeId;

    /** "1 — Anello Folcara / Ausonia" — short and long name combined for display. */
    @JsonProperty("route_name")
    private String routeName;

    // ── Assigned vehicle (from the timetable, not from telemetry) ───
    @JsonProperty("bus_id")
    private Integer busId;

    @JsonProperty("plate")
    private String plate;

    /** Antenna id the bus transmits under, e.g. "BUS1". Null if none fitted. */
    @JsonProperty("vehicle_id")
    private String vehicleId;

    // ── Schedule ────────────────────────────────────────────────────
    /** Scheduled departure, "HH:mm". */
    @JsonProperty("start_time")
    private String startTime;

    /** Scheduled arrival at the last stop, "HH:mm". */
    @JsonProperty("end_time")
    private String endTime;

    /**
     * When the bus was actually observed reaching the last stop, "HH:mm".
     *
     * Null until that happens, and also null when it happened but was never
     * witnessed — the bus passed further than 80 m from the stop, or InfluxDB
     * was unreachable. Absence means "we did not see it", not "it did not
     * finish", and the view should not imply otherwise.
     */
    @JsonProperty("actual_end_time")
    private String actualEndTime;

    // ── Progress, measured in stops actually reached ────────────────
    /** How many of this trip's stops the bus has been recorded at. */
    @JsonProperty("stops_done")
    private int stopsDone;

    @JsonProperty("stops_total")
    private int stopsTotal;

    /**
     * True when the progress count comes from arrivals we actually witnessed.
     *
     * When a bus first joins a trip it is anchored by the clock — "by now it
     * should have passed stop 4" — and no arrival has been observed yet. That
     * inferred count looks identical to a measured one, which is misleading, so
     * the view marks it.
     */
    @JsonProperty("progress_observed")
    private boolean progressObserved;

    /** 0–100, derived from the two above; convenience for the progress bar. */
    @JsonProperty("progress_pct")
    private int progressPct;

    @JsonProperty("last_stop_name")
    private String lastStopName;

    @JsonProperty("next_stop_name")
    private String nextStopName;

    // ── Live status ─────────────────────────────────────────────────
    /**
     * Where the trip is in its life: NOT_STARTED, ACTIVE or FINISHED.
     *
     * Deliberately separate from {@link #status}. This one is always known —
     * it follows from the clock and from whether we saw the bus reach the last
     * stop — whereas punctuality can be unmeasured. Conflating them is what
     * made a finished trip and a running one look alike.
     *
     * A trip is FINISHED either because it was observed reaching its final
     * stop (possibly early) or because its scheduled window has elapsed.
     */
    /**
     * Vehicle health while the trip runs, independent of {@link #phase}:
     *   OK        — reporting and moving
     *   STALLED   — reporting but hasn't moved for 10 minutes
     *   NO_SIGNAL — nothing received for 5 minutes
     *
     * Kept apart from the phase because a trip can be OVERDUE *because* the bus
     * is STALLED: showing both says what is happening and why.
     */
    @JsonProperty("health")
    private String health;

    @JsonProperty("phase")
    private String phase;

    /**
     * HOW the trip is running: ON_TIME / SLIGHTLY_LATE / SIGNIFICANTLY_LATE /
     * EARLY / UNKNOWN, plus two the live feed does not produce:
     *   COMPLETED — the bus was observed at the final stop
     *   NO_SIGNAL — the assigned bus is not reporting at all
     *
     * Meaningless before departure; null when phase is NOT_STARTED.
     */
    @JsonProperty("status")
    private String status;

    @JsonProperty("delay_minutes")
    private Integer delayMinutes;

    /** False when the assigned bus has sent no recent telemetry. */
    @JsonProperty("live")
    private boolean live;
}
