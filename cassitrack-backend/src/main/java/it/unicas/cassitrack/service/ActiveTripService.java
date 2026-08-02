package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.ActiveTripDTO;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is running right now — the operational view behind the Active Trips tab.
 *
 * Built from three sources that answer different questions:
 *
 *   TIMETABLE  (trips + scheduled_stops)  — what SHOULD be running
 *   LIVE FEED  (VehicleStateCache)        — what the buses are ACTUALLY doing
 *   ARRIVALS   (InfluxDB stop_arrival)    — what they have DEMONSTRABLY done
 *
 * A TRIP'S LIFE, AS THIS VIEW SEES IT
 * -----------------------------------
 * The timetable window alone is a poor definition of "active": a bus that runs
 * early sits at its terminus with every stop done while the clock catches up,
 * and a trip that ends vanishes the instant it ends, giving nobody a chance to
 * see how it went. So a trip appears here when EITHER
 *
 *   • the clock is inside its scheduled window, or
 *   • it was observed finishing within the last {@value #LINGER_MINUTES} minutes
 *
 * and once the bus has been seen at the final stop it is marked COMPLETED
 * regardless of what the clock says.
 */
@Service
@RequiredArgsConstructor
public class ActiveTripService {

    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");

    /** How long a finished trip stays on screen, for the operator's benefit. */
    private static final int LINGER_MINUTES = 15;

    private final ScheduledStopRepository scheduledStopRepository;
    private final BusRepository busRepository;
    private final VehicleStateCache vehicleStateCache;
    private final TripCompletionService tripCompletionService;

    @Transactional(readOnly = true)
    public List<ActiveTripDTO> getActiveTrips() {
        int     now     = LocalTime.now(ITALY_TZ).toSecondOfDay();
        Instant nowInst = Instant.now();

        // Live positions keyed by bus, so each scheduled trip can be matched to
        // the vehicle that is supposed to be working it.
        Map<Integer, VehiclePosition> liveByBus = new HashMap<>();
        for (VehiclePosition p : vehicleStateCache.getActive()) {
            if (p.getBusId() != null) liveByBus.put(p.getBusId(), p);
        }

        Map<Integer, Bus> busById = new HashMap<>();
        for (Bus b : busRepository.findAll()) busById.put(b.getBusId(), b);

        Map<String, TripCompletionService.Arrival> arrivals =
                tripCompletionService.recentArrivalsByTrip();

        // Insertion-ordered: the scheduled rows arrive sorted by departure, and
        // the lingering ones are appended after, which reads naturally.
        Map<String, Object[]> rows = new LinkedHashMap<>();
        for (Object[] row : scheduledStopRepository.findActiveTrips(now)) {
            rows.put((String) row[0], row);
        }

        // Trips that have dropped out of the window but finished so recently
        // that an operator would still expect to see them.
        Set<String> lingering = new HashSet<>();
        for (Map.Entry<String, TripCompletionService.Arrival> e : arrivals.entrySet()) {
            if (rows.containsKey(e.getKey())) continue;
            long ago = Duration.between(e.getValue().finishedAt(), nowInst).toMinutes();
            if (ago >= 0 && ago < LINGER_MINUTES) lingering.add(e.getKey());
        }
        if (!lingering.isEmpty()) {
            // Only now do we know which extra trips are needed, so this second
            // query is unavoidable — but it is bounded by the linger window and
            // most of the time finds nothing at all.
            for (Object[] row : scheduledStopRepository.findTripSummaries(lingering)) {
                rows.putIfAbsent((String) row[0], row);
            }
        }

        List<ActiveTripDTO> out = new ArrayList<>();
        for (Object[] row : rows.values()) {
            String  tripId    = (String)  row[0];
            String  routeId   = (String)  row[1];
            String  shortName = (String)  row[2];
            String  longName  = (String)  row[3];
            int     startSec  = ((Number) row[4]).intValue();
            int     endSec    = ((Number) row[5]).intValue();
            Integer busId     = row[6] == null ? null : ((Number) row[6]).intValue();
            int     total     = ((Number) row[7]).intValue();

            Bus bus = busId == null ? null : busById.get(busId);
            VehiclePosition live = busId == null ? null : liveByBus.get(busId);

            // Only trust the live data if the bus is actually working THIS trip.
            // A vehicle can be running a different trip than the timetable
            // expects — after a reassignment, or when the feed ignores the
            // schedule entirely — and borrowing its progress would be a lie.
            boolean onThisTrip = live != null && tripId.equals(live.getTripId());

            TripCompletionService.Arrival arrival = arrivals.get(tripId);

            // Two counts of the same thing, kept apart on purpose.
            //
            // OBSERVED comes from arrivals we actually witnessed. INFERRED comes
            // from the live cache, whose lastStopSequence may have been seeded
            // from the clock when the bus was first anchored to the trip
            // ("by now it should have passed stop 4"). The larger is shown,
            // because a bus cannot un-pass a stop, but only the observed one is
            // allowed to decide that a trip is over.
            int observedDone = arrival == null ? 0 : Math.min(arrival.lastSequence(), total);
            int inferredDone = (onThisTrip && live.getLastStopSequence() != null)
                    // stop_sequence is 1-based, so the value IS the count reached.
                    ? Math.max(0, Math.min(live.getLastStopSequence(), total))
                    : 0;

            int     stopsDone = Math.max(observedDone, inferredDone);
            boolean observed  = arrival != null && observedDone >= inferredDone;

            // Finishing must be witnessed. Reaching the last stop "by the clock"
            // is exactly the inference that made completed trips indistinguish-
            // able from running ones in the first place — and claiming a finish
            // time from an arrival at some earlier stop would be worse still.
            boolean finished = total > 0 && observedDone >= total;

            out.add(ActiveTripDTO.builder()
                    .tripId(tripId)
                    .routeId(routeId)
                    .routeName(routeLabel(shortName, longName))
                    .busId(busId)
                    .plate(bus == null ? null : bus.getTarga())
                    .vehicleId(bus == null ? null : bus.getCurrentVehicleId())
                    .startTime(hhmm(startSec))
                    .endTime(hhmm(endSec))
                    .actualEndTime(finished ? hhmmAt(arrival.finishedAt()) : null)
                    .stopsDone(stopsDone)
                    .stopsTotal(total)
                    .progressObserved(observed)
                    .progressPct(total == 0 ? 0 : (int) Math.round(stopsDone * 100.0 / total))
                    .lastStopName(onThisTrip ? live.getLastStopRegistered() : null)
                    // A finished trip has no next stop, and showing the one it
                    // last approached would suggest it is still going.
                    .nextStopName(onThisTrip && !finished ? live.getNextStop() : null)
                    .status(statusFor(finished, onThisTrip, live))
                    .delayMinutes(delayFor(finished, onThisTrip, live, arrival))
                    .live(onThisTrip && !finished)
                    .build());
        }
        return out;
    }

    /**
     * Buses that could work this trip, for the reassignment form.
     *
     * A bus qualifies when it has no OTHER trip overlapping this one's window.
     * Idleness at this instant is not the test: a vehicle parked between two
     * duties is not free, and one whose only other trip ended an hour ago is.
     *
     * Buses in MAINTENANCE or INACTIVE are returned but marked, rather than
     * hidden. During a breakdown the manager may know something the registry
     * does not, and silently omitting a vehicle looks like a bug.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAvailableBuses(String tripId) {
        List<Object[]> summary = scheduledStopRepository.findTripSummaries(List.of(tripId));
        if (summary.isEmpty()) return List.of();

        Object[] row = summary.get(0);
        int     from      = ((Number) row[4]).intValue();
        int     to        = ((Number) row[5]).intValue();
        Integer currentId = row[6] == null ? null : ((Number) row[6]).intValue();

        Set<Integer> busy = new HashSet<>(
                scheduledStopRepository.findBusIdsBusyBetween(from, to, tripId));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Bus b : busRepository.findAll()) {
            boolean isCurrent = b.getBusId().equals(currentId);
            if (busy.contains(b.getBusId()) && !isCurrent) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bus_id",     b.getBusId());
            item.put("plate",      b.getTarga());
            item.put("vehicle_id", b.getCurrentVehicleId());
            item.put("status",     b.getStatus());
            item.put("current",    isCurrent);
            // A bus with no antenna cannot report telemetry, so the trip would
            // go dark if assigned to it. Allowed, but the form should say so.
            item.put("no_antenna", b.getCurrentVehicleId() == null);
            out.add(item);
        }
        return out;
    }

    /**
     * COMPLETED outranks everything else: once the bus has been seen at the last
     * stop, "on time" or "late" describe a run that is over, and the operator
     * needs to know it is over first. The delay is still reported alongside.
     */
    private String statusFor(boolean finished, boolean onThisTrip, VehiclePosition live) {
        if (finished)    return "COMPLETED";
        if (!onThisTrip) return "NO_SIGNAL";
        return live.getScheduleStatus() == null ? "UNKNOWN" : live.getScheduleStatus().name();
    }

    /**
     * For a finished trip prefer the delay measured at the final stop — that is
     * how the run actually ended — over whatever the cache happens to hold now,
     * which may already belong to the bus's next duty.
     */
    private Integer delayFor(boolean finished, boolean onThisTrip,
                             VehiclePosition live, TripCompletionService.Arrival arrival) {
        if (finished && arrival != null && arrival.delayMinutes() != null) {
            return arrival.delayMinutes();
        }
        return onThisTrip ? live.getDelayMinutes() : null;
    }

    /** "1 — Anello Folcara / Ausonia", falling back gracefully if either is blank. */
    private String routeLabel(String shortName, String longName) {
        boolean hasShort = shortName != null && !shortName.isBlank();
        boolean hasLong  = longName  != null && !longName.isBlank();
        if (hasShort && hasLong) return shortName + " — " + longName;
        if (hasShort)            return shortName;
        return hasLong ? longName : "";
    }

    /** Seconds of day → "HH:mm". Values past midnight wrap, as the timetable does. */
    private String hhmm(int seconds) {
        return String.format("%02d:%02d", (seconds / 3600) % 24, (seconds % 3600) / 60);
    }

    /** An instant → "HH:mm" in service-local time, comparable with the timetable. */
    private String hhmmAt(Instant instant) {
        if (instant == null) return null;
        LocalTime t = instant.atZone(ITALY_TZ).toLocalTime();
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
