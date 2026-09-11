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

    @Id // 🔑 Il vehicleId diventa la CHIAVE del record (es. vehicle_positions:BUS-101)
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

    /**
     * Quante volte di fila il mezzo e' ARRETRATO lungo la sequenza della corsa
     * che gli e' stata assegnata.
     *
     * Un passo indietro isolato non vuol dire niente: il GPS oscilla, e a
     * cavallo di una fermata la posizione piu' vicina puo' alternarsi fra due
     * indici. Tre di fila, con un movimento vero fra un fix e l'altro, non sono
     * piu' rumore: quella corsa il mezzo la sta percorrendo al contrario, cioe'
     * non e' la sua.
     *
     * Azzerato da un solo passo in avanti e a ogni cambio di corsa: e' un
     * sospetto che si accumula, non una condanna che resta.
     */
    private Integer wrongWayStrikes;

    // ── Stato della macchina "passaggio al minimo" ──────────────
    /** Fermata verso cui il bus si sta avvicinando. */
    private Integer approachStopSequence;
    /** Distanza minima osservata finora verso quella fermata. */
    private Double  approachMinDistanceMetres;
    /** Istante del fix in cui quella distanza minima è stata osservata. */
    private Instant approachMinTimestamp;

    // ── Fix precedente ──────────────────────────────────────────
    /**
     * L'ultimo fix ricevuto prima di questo.
     *
     * Serve a ricostruire il TRATTO percorso fra due invii. Un OBU che
     * trasmette una volta al minuto salta le fermate che attraversa fra un
     * invio e l'altro: senza il punto di partenza del tratto non c'è modo di
     * accorgersi che ci è passato sopra.
     */
    private Double  prevFixLat;
    private Double  prevFixLon;
    private Instant prevFixAt;

    /** The stop it is heading to — derived server-side from the trip sequence */
    private String  nextStopId;

    /**
     * Orario di TABELLA alla prossima fermata, in secondi dalla mezzanotte.
     *
     * Si conserva l'orario previsto, non i secondi mancanti: un ETA calcolato
     * qui invecchierebbe fino al messaggio successivo, e su un mezzo che
     * trasmette una volta al minuto significa mostrare "3 min" per un minuto
     * intero. L'orario di tabella invece non invecchia, e chi legge lo
     * trasforma in un'attesa con l'ora del momento in cui la chiede.
     */
    private Integer nextStopArrivalSeconds;
    private String  tripId;

    /**
     * Partenza della corsa assegnata, in secondi dalla mezzanotte.
     *
     * Da quando la corsa viene assegnata gia' prima della partenza (vedi
     * TripResolutionService.PRE_TRIP_LEAD_SECONDS) non basta piu' sapere QUALE
     * corsa fa il mezzo: serve sapere se l'ha gia' iniziata. Chi legge questo
     * campo evita una query per scoprirlo.
     */
    private Integer tripStartSeconds;
    private String  routeId;
    private String  routeName;        // resolved from routes.short_name
    private String  nextStop;         // display name, computed server-side

    // Server-side processing fields
    private String matchedRouteId;
    private ScheduleStatus scheduleStatus;
    private Instant receivedAt;

    /**
     * Since when this bus has not really moved.
     *
     * Reset every time it travels more than a few metres, so "now minus this"
     * is how long it has been standing still. Kept here, where positions
     * arrive, because a single reading cannot tell a stopped bus from a moving
     * one — only the comparison with the previous one can.
     *
     * A stationary bus is not by itself a problem (stops, lights, traffic); it
     * becomes one when it lasts, and only while a trip is running.
     */
    private Instant stationarySince;

    //Pending: no more alarm table (SHOULD do later on)
    public enum ScheduleStatus {
        ON_TIME,
        SLIGHTLY_LATE,
        SIGNIFICANTLY_LATE,
        EARLY,
        UNKNOWN
    }
}