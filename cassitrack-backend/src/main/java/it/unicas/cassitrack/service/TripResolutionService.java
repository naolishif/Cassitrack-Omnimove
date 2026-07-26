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
            int    endSeconds
    ) {
        boolean covers(int nowSeconds) {
            return nowSeconds >= startSeconds && nowSeconds <= endSeconds;
        }
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

        List<Object[]> rows = scheduledStopRepo.findActiveTripsForBus(busId, now);
        if (rows.isEmpty()) {
            if (cached != null) {
                log.info("Vehicle {} finished trip {} — no trip in service at {}s",
                        vehicleId, cached.tripId(), now);
            }
            cache.remove(vehicleId);
            return Optional.empty();
        }

        Object[] chosen = (rows.size() == 1) ? rows.get(0) : bestByGps(rows, lat, lon);

        ActiveTrip trip = new ActiveTrip(
                (String) chosen[0],
                (String) chosen[1],
                (String) chosen[2],
                (String) chosen[3],
                ((Number) chosen[4]).intValue(),
                ((Number) chosen[5]).intValue());

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

    /** Distance from the fix to the nearest stop of this trip. */
    private double distanceToTrip(String tripId, double lat, double lon) {
        double nearest = Double.MAX_VALUE;
        for (ScheduledStop ss : scheduledStopRepo.findByTripIdOrderByStopSequenceAsc(tripId)) {
            Optional<Stop> stopOpt = stopRepository.findById(ss.getStopId());
            if (stopOpt.isEmpty()) continue;
            Stop stop = stopOpt.get();
            if (stop.getLat() == null || stop.getLon() == null) continue;

            double d = routeMatchingService.haversineMetres(lat, lon, stop.getLat(), stop.getLon());
            if (d < nearest) nearest = d;
        }
        return nearest;
    }
}
