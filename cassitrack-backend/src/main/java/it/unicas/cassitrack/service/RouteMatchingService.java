package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Matches a bus GPS position to:
 *   1. The nearest bus stop
 *   2. The scheduled arrival time at that stop
 *
 * This is how we know a bus is "at" a stop
 * even though GPS coordinates are never exact.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RouteMatchingService {

    private final ScheduledStopRepository scheduledStopRepo;
    private final StopRepository stopRepository;

    /** A stop identified by both its id and its human-readable name. */
    public record NamedStop(String id, String name) {}

    /** Una riga di scheduled_stops, appiattita. */
    public record StopOnTrip(String stopId, int stopSequence, int arrivalSeconds) {}

    public List<ScheduledStop> tripSequence(String tripId) {
        return tripId == null ? List.of()
                : scheduledStopRepo.findByTripIdOrderByStopSequenceAsc(tripId);
    }

    /** La fermata in posizione {@code stopSequence}, o null oltre il capolinea. */
    public StopOnTrip stopAtSequence(String tripId, int stopSequence) {
        for (ScheduledStop ss : tripSequence(tripId)) {
            if (ss.getStopSequence() == stopSequence) {
                return new StopOnTrip(ss.getStopId(), ss.getStopSequence(), ss.getArrivalSeconds());
            }
        }
        return null;
    }

    /** Distanza dal fix a una fermata, o null se la fermata è sconosciuta. */
    public Double distanceToStop(String stopId, double lat, double lon) {
        Stop s = stopRepository.findById(stopId).orElse(null);
        if (s == null || s.getLat() == null || s.getLon() == null) return null;
        return haversineMetres(lat, lon, s.getLat(), s.getLon());
    }

    /**
     * Aggancio iniziale: quando non sappiamo ancora dove sia il bus lungo la corsa
     * (avvio del servizio, cambio corsa), si sceglie l'occorrenza il cui orario di
     * tabella è più vicino all'ora corrente. Su un anello questo è l'unico criterio
     * che distingue il quinto passaggio dal quattordicesimo.
     */
    public Integer bootstrapSequence(String tripId, int nowSecondsOfDay) {
        ScheduledStop best = null;
        for (ScheduledStop ss : tripSequence(tripId)) {   // già ordinata per stop_sequence
            if (ss.getArrivalSeconds() <= nowSecondsOfDay) best = ss;
            else break;
        }
        // Prima della partenza: aggancia al capolinea, il candidato sarà la seconda fermata.
        return best != null ? best.getStopSequence()
                : (tripSequence(tripId).isEmpty() ? null : 1);
    }
    /** La fermata immediatamente successiva nella sequenza. Null al capolinea. */
    public NamedStop nextStopAfterSequence(String tripId, Integer stopSequence) {
        if (stopSequence == null) return null;
        StopOnTrip next = stopAtSequence(tripId, stopSequence + 1);
        return next != null ? namedStop(next.stopId()) : null;
    }

    /**
     * Resolve a stop id to its display name.
     * Returns the id itself if the stop is unknown, never null-propagates.
     */
    public String stopName(String stopId) {
        if (stopId == null) return null;
        return stopRepository.findById(stopId).map(Stop::getName).orElse(stopId);
    }

    /** Wrap a stop id together with its resolved name. */
    public NamedStop namedStop(String stopId) {
        if (stopId == null) return null;
        return new NamedStop(stopId, stopName(stopId));
    }

    /**
     * Calculate the distance in metres between two GPS coordinates.
     * Haversine formula — the same used in navigation systems.
     */
    public double haversineMetres(
            double lat1, double lon1,
            double lat2, double lon2) {

        final double R = 6371000; // Earth radius in metres
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
