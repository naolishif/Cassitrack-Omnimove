package it.unicas.omnimove.service;

import it.unicas.omnimove.client.CassitrackClient;
import it.unicas.omnimove.dto.StopArrivalDTO;
import it.unicas.omnimove.dto.VehicleDTO;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enriches stop arrivals with a REAL-TIME delay, recomputed from each bus's
 * live GPS position to the requested stop, using Google Maps live traffic.
 *
 * Two rules that shape everything here:
 *
 *  1. Only buses that have ALREADY DEPARTED get a recomputed delay - i.e.
 *     those with a vehicleId (they are on the road, tracked by GPS). A
 *     scheduled entry with no vehicleId has no position and no meaning for
 *     traffic, so it is returned untouched (departure time only).
 *
 *  2. The delta is computed PER BUS, from that bus's own coordinates to the
 *     stop - not one fixed route-start-to-stop delta applied to everyone,
 *     which is what the previous version did.
 *
 * When the google.stop_eta flag is OFF, this service is not called at all:
 * JourneyController falls back to CassiTrack's retrospective delay.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrafficAwareETAService {

    private final CassitrackClient  cassitrackClient;
    private final GoogleMapsService googleMapsService;
    private final StopRepository    stopRepository;

    /**
     * The stop arrival, plus the real-time recomputation when available.
     *
     * @param realTime      true if delayMinutes came from a live GPS+traffic calc
     * @param delayMinutes  positive = late, negative = early, 0 = on time; null if not computed
     */
    public record LiveEta(
            StopArrivalDTO arrival,
            boolean        realTime,
            Integer        delayMinutes,
            Instant        adjustedArrival
    ) {}

    /**
     * Recompute delays for the buses arriving at a stop.
     *
     * @param stopId   the stop
     * @param arrivals the merged list already assembled by JourneyController
     *                 (live buses + scheduled entries)
     */
    public List<LiveEta> enrich(String stopId, List<StopArrivalDTO> arrivals) {
        Stop dest = stopRepository.findById(stopId).orElse(null);
        if (dest == null || dest.getLat() == null || dest.getLon() == null) {
            log.warn("enrich: unknown or incomplete stop {}", stopId);
            return arrivals.stream()
                    .map(a -> new LiveEta(a, false, null, a.getEstimatedArrival()))
                    .toList();
        }

        // Current GPS position of every live vehicle, keyed by vehicleId.
        Map<String, VehicleDTO> live = cassitrackClient.getActiveVehicles().stream()
                .filter(v -> v.getVehicleId() != null
                        && v.getLat() != null && v.getLon() != null)
                .collect(Collectors.toMap(VehicleDTO::getVehicleId, Function.identity(),
                        (a, b) -> a));

        return arrivals.stream()
                .map(a -> enrichOne(a, dest, live))
                .toList();
    }

    private LiveEta enrichOne(StopArrivalDTO a, Stop dest, Map<String, VehicleDTO> live) {

        // Rule 1: not yet departed -> departure time only, no delay.
        if (a.getVehicleId() == null || !live.containsKey(a.getVehicleId())) {
            return new LiveEta(a, false, null, a.getEstimatedArrival());
        }

        VehicleDTO bus = live.get(a.getVehicleId());

        // Rule 2: this bus's own position -> the stop, with live traffic.
        Optional<GoogleMapsService.TrafficResult> g = googleMapsService.getTravelTime(
                bus.getLat(), bus.getLon(),
                dest.getLat(), dest.getLon(),
                "driving");

        if (g.isEmpty()) {
            // Google unavailable for this bus: leave it as CassiTrack had it.
            return new LiveEta(a, false, a.getDelayMinutes(), a.getEstimatedArrival());
        }

        long trafficSec = g.get().durationInTrafficSeconds();
        Instant adjusted = Instant.now().plusSeconds(trafficSec);

        // Delay = predicted real arrival vs the scheduled arrival at this stop.
        Integer delayMinutes = null;
        if (a.getScheduledArrival() != null) {
            long deltaSec = adjusted.getEpochSecond() - a.getScheduledArrival().getEpochSecond();
            delayMinutes = Math.round(deltaSec / 60.0f);
        }

        return new LiveEta(a, true, delayMinutes, adjusted);
    }
}
