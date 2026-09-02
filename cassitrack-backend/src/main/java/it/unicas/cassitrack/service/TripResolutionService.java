package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers the question the bus can no longer answer for us:
 * "which trip is this vehicle running right now?"
 *
 * How it works:
 *   1. The MQTT payload gives us a vehicle_id → buses.current_vehicle_id → bus_id
 *   2. Postgres knows which trips are assigned to that bus (trips.bus_id)
 *   3. The schedule tells us which of those trips is in service at this moment
 *   4. If more than one candidate survives (overlapping trips), the GPS fix
 *      breaks the tie: we pick the trip whose stops are closest to the bus.
 *
 * The result is cached per vehicle and only re-resolved when the current
 * trip's service window has elapsed. On a 10s telemetry interval this means
 * roughly one Postgres round-trip per bus per trip, not one per message.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TripResolutionService {

    private final ScheduledStopRepository scheduledStopRepo;
    private final StopRepository          stopRepository;
    private final RouteMatchingService    routeMatchingService;
    private final RoutePatternService     routePatternService;

    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");

    /** vehicleId → currently assigned trip */
    private final Map<String, ActiveTrip> cache = new ConcurrentHashMap<>();

    /**
     * A trip in service, with the window during which it is valid.
     *
     * @param routeName the route short name — what the UI shows as "route"
     */
    public record ActiveTrip(
            String tripId,
            String routeId,
            String routeName,
            String routeLongName,
            int    startSeconds,
            int    endSeconds,
            /** Terminus coordinates: how we know the bus has actually arrived. */
            Double terminusLat,
            Double terminusLon
    ) {
        boolean covers(int nowSeconds) {
            return nowSeconds >= startSeconds - PRE_TRIP_LEAD_SECONDS
                    && nowSeconds <= endSeconds;
        }
    }

    /**
     * Quanto prima della partenza un mezzo viene gia' associato alla sua corsa.
     *
     * PERCHE' ESISTE
     * --------------
     * Fra una corsa e l'altra il bus sta fermo al capolinea, e senza corsa e'
     * invisibile: ETAService non produce previsioni per lui, la mappa dei mezzi
     * in linea non lo mostra (la linea si deduce dalla corsa) e il pianificatore
     * annuncia "nessun bus in tempo reale per questa linea". Il passeggero alla
     * fermata vede solo l'orario di tabella fino all'istante della partenza,
     * proprio quando l'informazione piu' utile e' "il tuo autobus e' quello, ed
     * e' al capolinea".
     *
     * Un quarto d'ora copre l'intervallo fra due corse sulle linee brevi senza
     * arrivare a impegnare un mezzo su una corsa cosi' lontana da non essere
     * ancora credibile.
     */
    private static final int PRE_TRIP_LEAD_SECONDS = 15 * 60;

    /**
     * A trip is not released when its scheduled end passes — only when the bus
     * really gets to the terminus. A late bus is still running its trip, and
     * dropping it there would blank the route on the map and in SIRI exactly
     * when the delay makes that information most useful.
     */
    private static final double TERMINUS_RADIUS_M = 120.0;

    /**
     * Hard stop for that grace period. Without it a bus that never reaches its
     * terminus (diverted, broken down and towed, GPS drift) would hold its trip
     * for the rest of the day.
     */
    private static final int MAX_OVERRUN_SECONDS = 45 * 60;

    private static double metresBetween(double aLat, double aLon, double bLat, double bLon) {
        double R = 6_371_000;
        double dLat = Math.toRadians(bLat - aLat), dLon = Math.toRadians(bLon - aLon);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
    }

    /** True when the vehicle is standing at (or within a block of) the terminus. */
    private static boolean atTerminus(ActiveTrip trip, Double lat, Double lon) {
        if (lat == null || lon == null
                || trip.terminusLat() == null || trip.terminusLon() == null) return false;
        return metresBetween(lat, lon, trip.terminusLat(), trip.terminusLon()) <= TERMINUS_RADIUS_M;
    }

    /**
     * Resolve the trip this vehicle is currently running.
     *
     * @param busId     from buses.current_vehicle_id lookup — null means unknown vehicle
     * @param vehicleId used as the cache key
     * @param lat       current GPS latitude, used only to disambiguate
     * @param lon       current GPS longitude, used only to disambiguate
     * @return the active trip, or empty outside service hours
     */
    public Optional<ActiveTrip> resolve(Integer busId, String vehicleId,
                                        Double lat, Double lon) {
        if (busId == null || vehicleId == null) return Optional.empty();

        int now = LocalTime.now(ITALY_TZ).toSecondOfDay();

        ActiveTrip cached = cache.get(vehicleId);
        if (cached != null && cached.covers(now)) return Optional.of(cached);

        // Past the scheduled end, but the bus may simply be late. Hold the trip
        // until it actually reaches the terminus, so a delayed run keeps its
        // route on the map, in SIRI and in the adherence figures.
        if (cached != null && now > cached.endSeconds()) {
            boolean arrived  = atTerminus(cached, lat, lon);
            boolean gaveUp   = now > cached.endSeconds() + MAX_OVERRUN_SECONDS;
            if (!arrived && !gaveUp) {
                return Optional.of(cached);          // late, still out there
            }
            log.info("Vehicle {} released trip {} — {}", vehicleId, cached.tripId(),
                    arrived ? "arrived at terminus"
                            : "no arrival " + (MAX_OVERRUN_SECONDS / 60) + " min past its end");
            cache.remove(vehicleId);
            cached = null;
        }

        List<Object[]> rows = scheduledStopRepo.findActiveTripsForBus(
                busId, now, now + PRE_TRIP_LEAD_SECONDS);
        if (rows.isEmpty()) {
            if (cached != null) {
                log.info("Vehicle {} finished trip {} — no trip in service at {}s",
                        vehicleId, cached.tripId(), now);
            }
            cache.remove(vehicleId);
            return Optional.empty();
        }

        Object[] chosen = (rows.size() == 1) ? rows.get(0) : bestByGps(rows, lat, lon);

        // Terminus looked up once per trip assignment, not per message.
        String tripId = (String) chosen[0];
        Double tLat = null, tLon = null;
        List<Object[]> term = scheduledStopRepo.findTerminusOf(tripId);
        if (!term.isEmpty()) {
            Object[] t = term.get(0);
            tLat = t[1] == null ? null : ((Number) t[1]).doubleValue();
            tLon = t[2] == null ? null : ((Number) t[2]).doubleValue();
        }

        ActiveTrip trip = new ActiveTrip(
                tripId,
                (String) chosen[1],
                (String) chosen[2],
                (String) chosen[3],
                ((Number) chosen[4]).intValue(),
                ((Number) chosen[5]).intValue(),
                tLat, tLon);

        cache.put(vehicleId, trip);
        log.info("Vehicle {} (bus {}) → trip {} on route {} [{}s..{}s]",
                vehicleId, busId, trip.tripId(), trip.routeId(),
                trip.startSeconds(), trip.endSeconds());
        return Optional.of(trip);
    }

    /** Forget a vehicle's assignment — e.g. when it goes offline. */
    public void evict(String vehicleId) {
        cache.remove(vehicleId);
    }

    /**
     * Two trips of the same bus overlap in the timetable. The bus can only be
     * on one of them: pick the trip with a stop closest to the current fix.
     */
    private Object[] bestByGps(List<Object[]> rows, Double lat, Double lon) {
        if (lat == null || lon == null) return rows.get(0);

        Object[] best     = rows.get(0);
        double   bestDist = Double.MAX_VALUE;

        for (Object[] row : rows) {
            double dist = distanceToTrip((String) row[0], lat, lon);
            if (dist < bestDist) {
                bestDist = dist;
                best     = row;
            }
        }
        log.debug("Disambiguated {} overlapping trips by GPS → {} ({} m)",
                rows.size(), best[0], Math.round(bestDist));
        return best;
    }

    /**
     * La linea di una corsa, letta dalla prima riga di orario.
     *
     * Le query che alimentano questi percorsi usano JOIN FETCH su trip e route
     * proprio perché qui non c'è una sessione Hibernate aperta: il gestore MQTT
     * non è transazionale, e una navigazione pigra fallirebbe.
     */
    private static String routeIdOf(List<ScheduledStop> calls) {
        if (calls.isEmpty()) return null;
        ScheduledStop first = calls.get(0);
        return first.getTrip() != null && first.getTrip().getRoute() != null
                ? first.getTrip().getRoute().getId() : null;
    }

    /** Distance from the fix to the nearest stop of this trip. */
    private double distanceToTrip(String tripId, double lat, double lon) {
        double nearest = Double.MAX_VALUE;
        List<ScheduledStop> calls = scheduledStopRepo.findByTripIdOrderByStopSequenceAsc(tripId);
        // Tutte le righe appartengono alla stessa corsa, quindi alla stessa
        // linea: risolverla una volta invece che a ogni fermata.
        String routeId = routeIdOf(calls);
        for (ScheduledStop ss : calls) {
            String patternStopId = routePatternService.stopIdAt(routeId, ss.getStopSequence());
            if (patternStopId == null) continue;
            Optional<Stop> stopOpt = stopRepository.findById(patternStopId);
            if (stopOpt.isEmpty()) continue;
            Stop stop = stopOpt.get();
            if (stop.getLat() == null || stop.getLon() == null) continue;

            double d = routeMatchingService.haversineMetres(lat, lon, stop.getLat(), stop.getLon());
            if (d < nearest) nearest = d;
        }
        return nearest;
    }
}
