package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data @Builder
public class JourneyOption {
    private String mode;
    @JsonProperty("mode_label") private String modeLabel;
    @JsonProperty("duration_minutes") private Integer durationMinutes;
    @JsonProperty("distance_metres") private Double distanceMetres;
    @JsonProperty("cost_euros") private Double costEuros;
    @JsonProperty("green_index") private Integer greenIndex;
    @JsonProperty("co2_grams") private Double co2Grams;
    @JsonProperty("eta_minutes") private Integer etaMinutes;
    private String summary;
    @JsonProperty("weather_warning") private String weatherWarning;
    @JsonProperty("weather_suggestion") private String weatherSuggestion;
    private List<JourneyLeg> legs;
    @JsonProperty("delay_minutes")   private Integer delayMinutes;
    @JsonProperty("delay_status")    private String  delayStatus;    // ON_TIME, SLIGHTLY_LATE
    @JsonProperty("delay_real_time") private Boolean delayRealTime;
    @JsonProperty("delay_at_stop")   private String  delayAtStop;
    @JsonProperty("delay_label")     private String  delayLabel;     // frase pronta per la scheda

    /**
     * Slack at the interchange, in minutes. Null when the option has no
     * transfer — nothing to miss — which the Custom ranking reads as the
     * safest case rather than as a missing value.
     */
    @JsonProperty("transfer_wait_minutes") private Integer transferWaitMinutes;

    /**
     * How much can go wrong before this itinerary breaks, 0..1, on an ABSOLUTE
     * scale — not relative to the other options of the search. It has to be
     * absolute: the planner returns at most one bus option, so a value
     * normalised across the set would be the same for every option and could
     * never change an order. See JourneyPlannerService.reliabilityOf.
     */
    @JsonProperty("reliability_score") private Double reliabilityScore;

    /**
     * Where the traveller boards and which line they board, so a journey that
     * has been started can be watched for delays. Null on options with no bus
     * leg — there is no timetable to slip.
     */
    @JsonProperty("boarding_stop_id") private String boardingStopId;
    @JsonProperty("bus_route_id")     private String busRouteId;

    /**
     * The runs this itinerary actually rides, and where they are watched.
     *
     * The trip id is what makes the delay the traveller's own: arrivals at a
     * stop carry it, so the watcher can pick out the very bus being ridden
     * instead of the next one of the same line — which is what happens the
     * moment they board.
     *
     * The first run is watched at the interchange when there is one and at the
     * alighting stop otherwise: in both cases the point ahead of the bus, not
     * the one behind it.
     */
    @JsonProperty("boarding_trip_id") private String boardingTripId;
    @JsonProperty("alight_stop_id")   private String alightStopId;
    @JsonProperty("transfer_stop_id") private String transferStopId;
    @JsonProperty("transfer_trip_id") private String transferTripId;

    // Shared-vehicle (Elerent) grounding — set only on BIKE/SCOOTER options
    @JsonProperty("bike_id")          private String  bikeId;
    @JsonProperty("bike_plate")       private String  bikePlate;
    @JsonProperty("bike_battery_pct") private Integer bikeBatteryPct;
    @JsonProperty("bike_walk_metres") private Integer bikeWalkMetres;
    @JsonProperty("bike_warning")     private String  bikeWarning;
}
