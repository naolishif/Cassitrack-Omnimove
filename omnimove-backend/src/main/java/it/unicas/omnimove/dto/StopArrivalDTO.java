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

    /**
     * Il mezzo ha gia' iniziato la corsa.
     *
     * Falso quando CassiTrack gli ha assegnato la corsa in anticipo e sta
     * ancora aspettando al capolinea: il veicolo e' noto, ma l'orario di
     * arrivo viene dalla tabella e non dalla sua posizione.
     */
    @JsonProperty("in_transit")        private boolean inTransit;

    /** Partenza della corsa dal capolinea, per il caso "non ancora partito". */
    @JsonProperty("scheduled_departure") private Instant scheduledDeparture;
}
