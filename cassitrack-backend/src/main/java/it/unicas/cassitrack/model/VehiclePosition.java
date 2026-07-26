package it.unicas.cassitrack.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.time.Instant;

/**
 * Represents the CURRENT live position of a vehicle, stored in Redis.
 * The historical tracking will be handled separately by InfluxDB.
 */
@RedisHash("vehicle_positions") // Dice a Spring che questo oggetto va in Redis
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehiclePosition {

    @Id // 🔑 Il vehicleId diventa la CHIAVE del record (es. vehicle_positions:MAGNI-001)
    private String vehicleId;

    private Integer busId; // 🚌

    private Integer numeroPosti;
    private Boolean wheelchairAccessible;

    private Instant timestamp;
    private Double lat;
    private Double lon;
    private Double speedKmh;
    private Double headingDeg;
    private Integer bleDeviceCount;
    private Double batteryVoltage;
    private String firmwareVersion;
    private Integer passengers;
    private Integer capacity;
    private Integer delayMinutes;

    // ── A quale fermata si riferisce il ritardo ──────────────────
    // Non coincide sempre con lastStopRegistered*: quando il gate degli 80 m
    // scarta un passaggio, l'ancora avanza ma il ritardo resta indietro.
    private String  delayStopId;
    private String  delayStopName;
    private Integer delayStopSequence;
    private Instant delayMeasuredAt;

    /** Last stop the bus was physically detected at — derived server-side from GPS */
    private String  lastStopRegistered;      // display name
    private String  lastStopRegisteredId;    // stops.id

    /** Posizione nella sequenza della corsa (scheduled_stops.stop_sequence) dell'ultimo arrivo registrato.
     *  È QUESTA, non lo stopId, a dire dove siamo lungo l'anello. */
    private Integer lastStopSequence;

    // ── Stato della macchina "passaggio al minimo" ──────────────
    /** Fermata verso cui il bus si sta avvicinando. */
    private Integer approachStopSequence;
    /** Distanza minima osservata finora verso quella fermata. */
    private Double  approachMinDistanceMetres;
    /** Istante del fix in cui quella distanza minima è stata osservata. */
    private Instant approachMinTimestamp;

    /** The stop it is heading to — derived server-side from the trip sequence */
    private String  nextStopId;
    private String  tripId;
    private String  routeId;
    private String  routeName;        // resolved from routes.short_name
    private String  nextStop;         // display name, computed server-side

    // Server-side processing fields
    private String matchedRouteId;
    private ScheduleStatus scheduleStatus;
    private Instant receivedAt;

    //Pending: no more alarm table (SHOULD do later on)
    public enum ScheduleStatus {
        ON_TIME,
        SLIGHTLY_LATE,
        SIGNIFICANTLY_LATE,
        EARLY,
        UNKNOWN
    }
}