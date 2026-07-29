package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.StopArrivalDTO;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Predicts when buses will arrive at a specific stop.
 *
 * How it works:
 *   1. Look at every active bus
 *   2. For each bus, compute how far it is
 *      from the requested stop
 *   3. Estimate travel time based on current speed
 *      and distance
 *   4. Combine with schedule data for accuracy
 *   5. Return a sorted list of predicted arrivals
 *
 * This is what powers the "arrives in 4 minutes"
 * display on the passenger app.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ETAService {

    private final VehicleStateCache       vehicleStateCache;
    private final ScheduledStopRepository scheduledStopRepository;
    private final RouteRepository         routeRepository;

    private static final ZoneId ITALY_TZ =
            ZoneId.of("Europe/Rome");


    /**
     * Get predicted arrivals at a specific stop.
     * Called by StopController for the API endpoint:
     *   GET /api/v1/stops/{stopId}/arrivals
     */
    public List<StopArrivalDTO> getArrivalsAtStop(String stopId) {
        // Load route data once to avoid N DB hits inside the loop
        Map<String, Route> routeMap = routeRepository.findAll().stream()
                .collect(Collectors.toMap(Route::getId, r -> r, (a, b) -> a));

        List<StopArrivalDTO> arrivals = new ArrayList<>();
        for (VehiclePosition bus : vehicleStateCache.getActive()) {
            StopArrivalDTO arrival = computeArrival(bus, stopId, routeMap);
            if (arrival != null) arrivals.add(arrival);
        }
        arrivals.sort(Comparator.comparing(StopArrivalDTO::getEstimatedArrival));
        return arrivals;
    }

    /**
     * Compute when one specific bus will reach
     * a specific stop.
     */
    private StopArrivalDTO computeArrival(VehiclePosition bus, String targetStopId,
                                          Map<String, Route> routeMap) {
        try {
            Long seqEta = computeSequenceEta(bus, targetStopId);
            if (seqEta == null) return null;
            if (seqEta > 1800) return null;

            Instant estimatedArrival = Instant.now().plusSeconds(seqEta);

            String routeId = bus.getRouteId() != null
                    ? bus.getRouteId() : bus.getMatchedRouteId();
            Route route = routeId != null ? routeMap.get(routeId) : null;

            String routeName = route != null && route.getLongName() != null
                    ? route.getLongName()
                    : (bus.getRouteName() != null ? bus.getRouteName() : routeId);
            String routeShortName = route != null ? route.getShortName() : null;

            // Ritardo e stato vengono da ScheduleAdherenceService — fonte unica di verità.
            // Non ricalcoliamo nulla qui: usiamo quello che è già stato calcolato
            // all'ultimo arrivo reale del bus a una fermata.
            int delayMinutes = bus.getDelayMinutes() != null ? bus.getDelayMinutes() : 0;
            String scheduleStatus = bus.getScheduleStatus() != null
                    ? bus.getScheduleStatus().name()
                    : VehiclePosition.ScheduleStatus.UNKNOWN.name();

            return StopArrivalDTO.builder()
                    .vehicleId(bus.getVehicleId())
                    .tripId(bus.getTripId())
                    .routeId(routeId)
                    .routeName(routeName)
                    .routeShortName(routeShortName)
                    .crowdingLevel(CrowdingService.levelFromRatio(
                            CrowdingService.effectivePassengers(
                                    bus.getPassengers(), bus.getBleDeviceCount()),
                            bus.getCapacity()))
                    .estimatedArrival(estimatedArrival)
                    .delayMinutes(delayMinutes)
                    .scheduleStatus(scheduleStatus)
                    .delayStopName(bus.getDelayStopName())
                    .build();

        } catch (Exception e) {
            log.warn("ETA computation failed for bus {} to stop {}: {}",
                    bus.getVehicleId(), targetStopId, e.getMessage());
            return null;
        }
    }

    /** ETA in secondi sommando i tratti dal DB, o null se non calcolabile. */
    private Long computeSequenceEta(VehiclePosition bus, String targetStopId) {
        // NOTA: non usiamo bus.getMatchedRouteId() per recuperare la sequenza.
        // Quel campo dovrebbe contenere la linea DEDOTTA dal GPS (route matching),
        // ma al momento non e' implementato: nessuno lo valorizza davvero e finisce
        // sempre a "UNKNOWN_ROUTE". Ci appoggiamo quindi a tripId/routeId, che il
        // simulatore pubblica leggendoli dal DB e sono affidabili.
        List<ScheduledStop> seq;
        if (bus.getTripId() != null) {
            seq = scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(bus.getTripId());
        } else {
            String routeId = bus.getRouteId() != null ? bus.getRouteId() : bus.getMatchedRouteId();
            if (routeId == null) return null;
            seq = scheduledStopRepository.findRepresentativeSequence(routeId);
        }
        if (seq.isEmpty()) return null;

        // L'ancora è la posizione nella sequenza, non l'ID della fermata.
        // ScheduleAdherenceService la mantiene: è la stessa che produce il ritardo.
        Integer anchorSeq = bus.getLastStopSequence();
        if (anchorSeq == null) return null;

        int anchorIdx = -1;
        for (int i = 0; i < seq.size(); i++) {
            if (seq.get(i).getStopSequence().equals(anchorSeq)) { anchorIdx = i; break; }
        }
        if (anchorIdx < 0) return null;

        if (targetStopId.equals(seq.get(anchorIdx).getStopId())) return 0L;

        // Find the next occurrence of targetStopId after the anchor.
        int targetIdx = indexOfStop(seq, targetStopId, anchorIdx + 1);
        if (targetIdx < 0) return null;

        long eta = (long) seq.get(targetIdx).getArrivalSeconds()
                - seq.get(anchorIdx).getArrivalSeconds();
        return eta > 0 ? eta : null;
    }



    private int indexOfStop(List<ScheduledStop> seq, String stopId, int from) {
        if (stopId == null) return -1;
        for (int i = Math.max(0, from); i < seq.size(); i++) {
            if (seq.get(i).getStopId().equals(stopId)) return i;
        }
        return -1;
    }

}