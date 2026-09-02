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
import java.time.LocalDate;
import java.time.LocalTime;
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
    private final RoutePatternService     routePatternService;

    private static final ZoneId ITALY_TZ =
            ZoneId.of("Europe/Rome");

    /**
     * Quanto in la' guardiamo. Deve coprire l'anticipo con cui una corsa viene
     * assegnata a un mezzo, piu' il tempo di percorrenza fino alla fermata
     * chiesta: altrimenti il veicolo risulta assegnato ma il suo arrivo viene
     * scartato proprio perche' distante, e alla fermata resta l'orario di
     * tabella senza che si capisca il perche'.
     */
    private static final long MAX_ETA_SECONDS =
            TripResolutionService.PRE_TRIP_LEAD_SECONDS + 30 * 60;


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
            SeqEta seqEta = computeSequenceEta(bus, targetStopId);
            if (seqEta == null) return null;
            if (seqEta.etaSeconds() > MAX_ETA_SECONDS) return null;

            Instant estimatedArrival = Instant.now().plusSeconds(seqEta.etaSeconds());
            // L'orario di tabella a QUESTA fermata: senza, chi legge non ha un
            // termine di paragone e non puo' calcolare alcun ritardo.
            Instant scheduledArrival = LocalDate.now(ITALY_TZ).atStartOfDay(ITALY_TZ)
                    .plusSeconds(seqEta.scheduledSecondsAtStop()).toInstant();
            Instant scheduledDeparture = bus.getTripStartSeconds() == null ? null
                    : LocalDate.now(ITALY_TZ).atStartOfDay(ITALY_TZ)
                            .plusSeconds(bus.getTripStartSeconds()).toInstant();

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
                    .scheduledArrival(scheduledArrival)
                    .scheduledDeparture(scheduledDeparture)
                    .inTransit(seqEta.inTransit())
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

    /**
     * Esito del calcolo: quanto manca, l'orario di tabella a quella fermata e
     * se il mezzo e' gia' in viaggio o attende ancora al capolinea.
     */
    private record SeqEta(long etaSeconds, int scheduledSecondsAtStop, boolean inTransit) {}

    /** ETA in secondi sommando i tratti dal DB, o null se non calcolabile. */
    private SeqEta computeSequenceEta(VehiclePosition bus, String targetStopId) {
        // NOTA: non usiamo bus.getMatchedRouteId() per recuperare la sequenza.
        // Quel campo dovrebbe contenere la linea DEDOTTA dal GPS (route matching),
        // ma al momento non e' implementato: nessuno lo valorizza davvero e finisce
        // sempre a "UNKNOWN_ROUTE". Ci appoggiamo quindi a tripId/routeId, che il
        // simulatore pubblica leggendoli dal DB e sono affidabili.
        List<ScheduledStop> seq;
        String routeId;
        if (bus.getTripId() != null) {
            seq = scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(bus.getTripId());
            // Da V27 la fermata di una riga di orario arriva dal pattern della
            // linea, quindi serve sapere di quale linea si tratta. La corsa la
            // conosce; se il caricamento pigro non l'ha portata, ci si appoggia
            // a quella pubblicata dal bus.
            routeId = (!seq.isEmpty() && seq.get(0).getTrip() != null
                                      && seq.get(0).getTrip().getRoute() != null)
                    ? seq.get(0).getTrip().getRoute().getId()
                    : bus.getRouteId();
        } else {
            routeId = bus.getRouteId() != null ? bus.getRouteId() : bus.getMatchedRouteId();
            if (routeId == null) return null;
            seq = scheduledStopRepository.findRepresentativeSequence(routeId);
        }
        if (seq.isEmpty() || routeId == null) return null;

        // Corsa assegnata ma non ancora iniziata: il mezzo attende al capolinea
        // e non ha agganciato nessuna fermata, quindi un'ancora nella sequenza
        // non esiste. L'attesa e' allora quella di tabella — quanto manca al
        // passaggio previsto qui — e il veicolo resta associato all'arrivo, che
        // e' l'informazione utile: "il tuo autobus e' quello, parte fra poco".
        Integer tripStart = bus.getTripStartSeconds();
        int nowSeconds = LocalTime.now(ITALY_TZ).toSecondOfDay();
        if (tripStart != null && nowSeconds < tripStart) {
            int idx = indexOfStop(seq, routeId, targetStopId, 0);
            if (idx < 0) return null;
            int scheduled = seq.get(idx).getArrivalSeconds();
            long eta = scheduled - nowSeconds;
            return eta > 0 ? new SeqEta(eta, scheduled, false) : null;
        }

        // L'ancora è la posizione nella sequenza, non l'ID della fermata.
        // ScheduleAdherenceService la mantiene: è la stessa che produce il ritardo.
        Integer anchorSeq = bus.getLastStopSequence();
        if (anchorSeq == null) return null;

        int anchorIdx = -1;
        for (int i = 0; i < seq.size(); i++) {
            if (seq.get(i).getStopSequence().equals(anchorSeq)) { anchorIdx = i; break; }
        }
        if (anchorIdx < 0) return null;

        if (targetStopId.equals(
                routePatternService.stopIdAt(routeId, seq.get(anchorIdx).getStopSequence()))) {
            return new SeqEta(0L, seq.get(anchorIdx).getArrivalSeconds(), true);
        }

        // Find the next occurrence of targetStopId after the anchor.
        int targetIdx = indexOfStop(seq, routeId, targetStopId, anchorIdx + 1);
        if (targetIdx < 0) return null;

        long eta = (long) seq.get(targetIdx).getArrivalSeconds()
                - seq.get(anchorIdx).getArrivalSeconds();
        return eta > 0
                ? new SeqEta(eta, seq.get(targetIdx).getArrivalSeconds(), true)
                : null;
    }



    private int indexOfStop(List<ScheduledStop> seq, String routeId, String stopId, int from) {
        if (stopId == null) return -1;
        for (int i = Math.max(0, from); i < seq.size(); i++) {
            if (stopId.equals(routePatternService.stopIdAt(routeId, seq.get(i).getStopSequence()))) {
                return i;
            }
        }
        return -1;
    }

}