package it.unicas.cassitrack.service;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.model.VehiclePosition.ScheduleStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;


@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduleAdherenceService {

    private final VehicleStateCache vehicleStateCache;
    private final RouteMatchingService routeMatchingService;

    // Injects the InfluxDB writer to keep historical records of delays and crowding
    private final WriteApiBlocking influxWriteApi;

    private static final int SLIGHTLY_LATE_MINUTES = 3;
    private static final int SIGNIFICANTLY_LATE_MINUTES = 10;
    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");

    /**
     * Il minimo dell'avvicinamento deve cadere sotto questa soglia perché il
     * passaggio conti come arrivo. Un bus che sfila a 200 m ha preso una deviazione.
     */
    private static final double APPROACH_GATE_METRES = 80.0;

    /**
     * La distanza deve crescere di ALMENO tanto perché sia un vero allontanamento
     * e non rumore GPS. Finestra utile: [17,0 · 19,7] m — sotto i 17,0 il rumore
     * (±6 m su lat e lon, quindi 2·6·√2) genera falsi arrivi; sopra i 19,7 si perde
     * il passo di interpolazione più corto della rete (XXS→GIA).
     */
    private static final double RECESSION_MARGIN_METRES = 18.0;

    /** Unica fonte di verità: da minuti di ritardo a stato di puntualità. */
    public static ScheduleStatus statusFromDelay(Integer delayMinutes) {
        if (delayMinutes == null)                       return ScheduleStatus.UNKNOWN;
        if (delayMinutes < -1)                          return ScheduleStatus.EARLY;
        if (delayMinutes <= SLIGHTLY_LATE_MINUTES)      return ScheduleStatus.ON_TIME;
        if (delayMinutes <= SIGNIFICANTLY_LATE_MINUTES) return ScheduleStatus.SLIGHTLY_LATE;
        return ScheduleStatus.SIGNIFICANTLY_LATE;
    }

    @Scheduled(fixedRate = 30000)
    public void checkAdherence() {
        Collection<VehiclePosition> activeBuses = vehicleStateCache.getActive();
        for (VehiclePosition pos : activeBuses) {
            processBusAdherence(pos);
            vehicleStateCache.update(pos.getVehicleId(), pos);
        }
    }

    public void processBusAdherence(VehiclePosition pos) {
        try {
            if (pos.getLat() == null || pos.getLon() == null) {
                pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
                return;
            }
            if (pos.getTripId() == null) {
                pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
                return;
            }

            // L'entità arriva "nuda" dal MqttMessageHandler: lo stato dell'avvicinamento
            // e l'ultimo ritardo vivono in Redis. Riportali su questa istanza.
            carryOverState(pos);

            int nowSeconds = secondsOfDay(pos.getTimestamp());

            // ── Aggancio iniziale ────────────────────────────────
            if (pos.getLastStopSequence() == null) {
                Integer seq = routeMatchingService.bootstrapSequence(pos.getTripId(), nowSeconds);
                if (seq == null) { pos.setScheduleStatus(ScheduleStatus.UNKNOWN); return; }

                pos.setLastStopSequence(seq);
                var anchor = routeMatchingService.stopAtSequence(pos.getTripId(), seq);
                if (anchor != null) pos.setLastStopRegisteredId(anchor.stopId());
                resetApproach(pos);

                log.info("Bus {} agganciato alla corsa {} da seq {} ({})",
                        pos.getVehicleId(), pos.getTripId(), seq,
                        anchor != null ? anchor.stopId() : "?");
                return;   // nessun ritardo: quell'arrivo non l'abbiamo osservato
            }

            // ── Il candidato è SEMPRE la fermata successiva. Mai una precedente. ──
            var candidate = routeMatchingService.stopAtSequence(
                    pos.getTripId(), pos.getLastStopSequence() + 1);
            if (candidate == null) return;   // capolinea: TripResolutionService riaggancerà

            Double d = routeMatchingService.distanceToStop(
                    candidate.stopId(), pos.getLat(), pos.getLon());
            if (d == null) return;

            // Nuovo candidato → inizializza il minimo
            if (!Integer.valueOf(candidate.stopSequence()).equals(pos.getApproachStopSequence())
                    || pos.getApproachMinDistanceMetres() == null) {
                pos.setApproachStopSequence(candidate.stopSequence());
                pos.setApproachMinDistanceMetres(d);
                pos.setApproachMinTimestamp(pos.getTimestamp());
                return;
            }

            // Ancora in avvicinamento → il minimo scende
            if (d < pos.getApproachMinDistanceMetres()) {
                pos.setApproachMinDistanceMetres(d);
                pos.setApproachMinTimestamp(pos.getTimestamp());
                return;
            }

            // La distanza cresce, ma non abbastanza da escludere il rumore
            if (d < pos.getApproachMinDistanceMetres() + RECESSION_MARGIN_METRES) return;

            // ── Allontanamento confermato: il minimo ERA l'arrivo ──
            double  minDist = pos.getApproachMinDistanceMetres();
            Instant minAt   = pos.getApproachMinTimestamp();

            pos.setLastStopSequence(candidate.stopSequence());
            pos.setLastStopRegisteredId(candidate.stopId());
            resetApproach(pos);

            if (minDist > APPROACH_GATE_METRES) {
                log.warn("Bus {} passato a {} m da {} — troppo lontano, arrivo non registrato",
                        pos.getVehicleId(), Math.round(minDist), candidate.stopId());
                return;   // l'ancora avanza, il ritardo resta quello di prima
            }

            int arrivedAt    = secondsOfDay(minAt);
            int delaySeconds = arrivedAt - candidate.arrivalSeconds();
            int delayMinutes = Math.round(delaySeconds / 60.0f);   // arrotonda, non tronca

            pos.setDelayMinutes(delayMinutes);
            pos.setScheduleStatus(statusFromDelay(delayMinutes));
            pos.setDelayStopId(candidate.stopId());
            pos.setDelayStopName(routeMatchingService.stopName(candidate.stopId()));
            pos.setDelayStopSequence(candidate.stopSequence());
            pos.setDelayMeasuredAt(minAt);

            writeArrivalEvent(pos, candidate, delayMinutes, minAt);
            logArrival(pos, candidate, arrivedAt, delaySeconds, delayMinutes, minDist);

        } catch (Exception e) {
            log.warn("Adherence non calcolabile per {}: {}", pos.getVehicleId(), e.getMessage());
            pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
        }
    }

    /** Lo stato vive in Redis. Se la corsa è cambiata, riparte da zero. */
    private void carryOverState(VehiclePosition pos) {
        vehicleStateCache.get(pos.getVehicleId()).ifPresent(prev -> {
            if (!java.util.Objects.equals(prev.getTripId(), pos.getTripId())) return;
            pos.setLastStopSequence(prev.getLastStopSequence());
            pos.setLastStopRegisteredId(prev.getLastStopRegisteredId());
            pos.setApproachStopSequence(prev.getApproachStopSequence());
            pos.setApproachMinDistanceMetres(prev.getApproachMinDistanceMetres());
            pos.setApproachMinTimestamp(prev.getApproachMinTimestamp());
            pos.setDelayMinutes(prev.getDelayMinutes());
            pos.setScheduleStatus(prev.getScheduleStatus());
            pos.setDelayStopId(prev.getDelayStopId());
            pos.setDelayStopName(prev.getDelayStopName());
            pos.setDelayStopSequence(prev.getDelayStopSequence());
            pos.setDelayMeasuredAt(prev.getDelayMeasuredAt());
        });
        if (pos.getScheduleStatus() == null) pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
    }

    /**
     * Un punto per arrivo su InfluxDB. Non uno per ping GPS: quello è il campo
     * "delay" di vehicle_position, scritto dal MqttMessageHandler.
     *
     * L'istante è quello del fix in cui il bus era più vicino alla fermata,
     * non quello in cui il server se n'è accorto: l'allontanamento viene
     * confermato uno o due campioni dopo.
     */
    private void writeArrivalEvent(VehiclePosition pos,
                                   RouteMatchingService.StopOnTrip stop,
                                   int delayMinutes,
                                   Instant arrivedAt) {

        String routeId = pos.getRouteId() != null ? pos.getRouteId() : "UNKNOWN_ROUTE";

        Integer pax = CrowdingService.effectivePassengers(
                pos.getPassengers(), pos.getBleDeviceCount());

        Point arrivalEvent = Point.measurement("stop_arrival")
                .addTag("vehicle_id", pos.getVehicleId())
                .addTag("stop_id",    stop.stopId())
                .addTag("route_id",   routeId)
                .addField("bus_id",               pos.getBusId() != null ? pos.getBusId() : 0)
                .addField("stop_sequence",        stop.stopSequence())
                .addField("delay_minutes",        delayMinutes)
                .addField("estimated_passengers", pax != null ? pax : 0)
                .time(arrivedAt != null ? arrivedAt : Instant.now(), WritePrecision.S);

        influxWriteApi.writePoint(arrivalEvent);
    }

    private void resetApproach(VehiclePosition pos) {
        pos.setApproachStopSequence(null);
        pos.setApproachMinDistanceMetres(null);
        pos.setApproachMinTimestamp(null);
    }

    /** Secondi dalla mezzanotte dell'ISTANTE DEL FIX, non dell'orologio del server. */
    private int secondsOfDay(Instant fix) {
        Instant t = (fix != null) ? fix : Instant.now();
        return t.atZone(ITALY_TZ).toLocalTime().toSecondOfDay();
    }

    private void logArrival(VehiclePosition pos, RouteMatchingService.StopOnTrip stop,
                            int arrivedAt, int delaySeconds, int delayMinutes, double minDist) {
        String segno = delaySeconds >= 0 ? "+" : "-";
        log.info("{} · {} (seq {}) · previsto {} · reale {} · {}{} s ({} min) · {} · min {} m",
                pos.getVehicleId(),
                routeMatchingService.stopName(stop.stopId()),
                stop.stopSequence(),
                LocalTime.ofSecondOfDay(stop.arrivalSeconds()),
                LocalTime.ofSecondOfDay(arrivedAt),
                segno, Math.abs(delaySeconds), delayMinutes,
                pos.getScheduleStatus(),
                Math.round(minDist));
    }

}