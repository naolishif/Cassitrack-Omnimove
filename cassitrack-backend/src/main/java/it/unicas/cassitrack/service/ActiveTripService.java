package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.ActiveTripDTO;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Trip;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * Minimum gap between two trips of the same bus.
     *
     * Not merely "must not overlap": a vehicle needs time to reach the next
     * origin, let passengers off, and absorb the lateness it arrived with. A
     * timetable that is only just feasible on paper produces a bus that is
     * permanently sprinting — see LINEA_3, whose runs were four minutes longer
     * than its headway and which never held a punctuality status as a result.
     */
    private static final int TURNAROUND_SECONDS = 15 * 60;

    private final ScheduledStopRepository scheduledStopRepository;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;
    private final VehicleStateCache vehicleStateCache;
    private final TripCompletionService tripCompletionService;

    /**
     * Beyond this silence we stop claiming to know where a bus is. Past its
     * scheduled end it closes the trip; during one it raises NO_SIGNAL.
     */
    private static final long SILENCE_SECONDS = 5 * 60;

    /**
     * A bus that has not moved for this long WHILE RUNNING is worth flagging.
     * Not necessarily a breakdown — traffic, an accident, a driver change —
     * but something a fleet manager should look at. Standing at a terminus
     * between runs is not covered: that bus has no trip in progress.
     */
    private static final long STALL_SECONDS = 10 * 60;

    /** True when the vehicle has been sitting in the same spot too long. */
    private static boolean isStalled(VehiclePosition live) {
        if (live == null || live.getStationarySince() == null) return false;
        return Duration.between(live.getStationarySince(), Instant.now())
                       .getSeconds() >= STALL_SECONDS;
    }

    /**
     * The whole service day, in departure order — what the Trips tab lists.
     *
     * The timetable has no service date, so "the day" is the same set every
     * day. Finished, running and not-yet-departed trips all come back together
     * with a {@code phase} telling them apart; the browser filters from there.
     */
    @Transactional(readOnly = true)
    public List<ActiveTripDTO> getDayTrips() {
        return buildTrips(scheduledStopRepository.findAllTripSummaries());
    }

    @Transactional(readOnly = true)
    public List<ActiveTripDTO> getActiveTrips() {
        int     now     = LocalTime.now(ITALY_TZ).toSecondOfDay();
        Instant nowInst = Instant.now();

        // Needed here only to work out which finished trips are still worth
        // showing; buildTrips fetches its own copy for the rows themselves.
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

        return buildTrips(rows.values());
    }

    /**
     * Turn timetable rows into DTOs, joining in whatever the live feed and the
     * arrival history know about each one.
     *
     * Shared by both endpoints: the difference between "what is running" and
     * "the whole day" is only WHICH rows are passed in, never how they are
     * interpreted — so the two views can never disagree about a trip.
     */
    private List<ActiveTripDTO> buildTrips(Collection<Object[]> summaries) {
        int now = LocalTime.now(ITALY_TZ).toSecondOfDay();

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

        List<ActiveTripDTO> out = new ArrayList<>();
        for (Object[] row : summaries) {
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

            // How long since this bus last said anything. Used below to tell a
            // late bus (still reporting, still out there) from one we simply
            // lost track of.
            long silentFor = (onThisTrip && live != null && live.getReceivedAt() != null)
                    ? Duration.between(live.getReceivedAt(), Instant.now()).getSeconds()
                    : Long.MAX_VALUE;
            boolean reporting = silentFor <= SILENCE_SECONDS;

            // A trip ends when we WATCH it reach the last stop — or, past its
            // scheduled end, when the bus has gone quiet long enough that we
            // can no longer claim to be following it.
            //
            // The elapsed clock alone is not enough: a bus running late is
            // still running, and calling that "finished" hid exactly the case
            // worth looking at. It gets its own phase instead.
            String phase;
            if (finished)                                    phase = "FINISHED";
            else if (now < startSec)                         phase = "NOT_STARTED";
            else if (now <= endSec)                          phase = "ACTIVE";
            else if (reporting)                              phase = "OVERDUE";
            else                                             phase = "FINISHED";

            // Vehicle health, deliberately independent of the phase above: a bus
            // can be OVERDUE *because* it is STALLED, and seeing both tells the
            // fleet manager what is happening and why.
            //
            // Only STALLED lives here. "We hear nothing from this bus" is already
            // reported by the status column (NO_SIGNAL, below), and duplicating it
            // would put two identical badges on the same row.
            String health = "OK";
            if (!"NOT_STARTED".equals(phase) && !"FINISHED".equals(phase)
                    && reporting && isStalled(live)) {
                health = "STALLED";
            }

            out.add(ActiveTripDTO.builder()
                    .phase(phase)
                    .health(health)
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
                    // Punctuality is meaningless before departure — a trip that
                    // has not left cannot be late — so it stays null rather
                    // than being reported as NO_SIGNAL.
                    .status("NOT_STARTED".equals(phase)
                            ? null : statusFor(finished, onThisTrip, live))
                    .delayMinutes("NOT_STARTED".equals(phase)
                            ? null : delayFor(finished, onThisTrip, live, arrival))
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
     * Move a trip's departure, carrying every stop with it.
     *
     * The whole trip shifts by one delta, so the running time and the spacing
     * between stops are preserved — this reschedules a run, it does not re-time
     * the route. Retiming individual legs belongs in a timetable editor.
     *
     * WHAT IS DELIBERATELY NOT DONE
     * -----------------------------
     * The trip id is left alone, even though ids look like LINEA_3_46800 and
     * the number is the original departure. Renaming would orphan every
     * stop_arrival point already written to InfluxDB under the old id, and
     * OmniMove holds the same ids from its NeTEx import. A stale-looking id is
     * a smaller price than losing the history that explains a delay.
     *
     * @throws ResponseStatusException 404 unknown trip, 409 if it has already
     *         started, would run past midnight, or would collide with another
     *         trip of the same bus
     */
    @Transactional
    public Map<String, Object> rescheduleDeparture(String tripId, int newStartSeconds) {
        List<ScheduledStop> stops =
                scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(tripId);
        if (stops.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No trip found with id " + tripId);
        }

        int oldStart = stops.stream().mapToInt(ScheduledStop::getArrivalSeconds).min().orElseThrow();
        int oldEnd   = stops.stream().mapToInt(ScheduledStop::getArrivalSeconds).max().orElseThrow();
        int delta    = newStartSeconds - oldStart;
        if (delta == 0) {
            return Map.of("trip_id", tripId,
                          "start_time", hhmm(oldStart), "end_time", hhmm(oldEnd));
        }

        // Only a trip that has not left may move. Shifting one already under
        // way would move stops the bus has physically passed, and every delay
        // measured against them would silently change meaning.
        int now = LocalTime.now(ITALY_TZ).toSecondOfDay();
        if (now >= oldStart) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Trip " + tripId + " has already departed (" + hhmm(oldStart)
                    + "). Only trips that have not started can be rescheduled.");
        }

        int newEnd = oldEnd + delta;
        // The timetable is seconds-since-midnight with no date, so a trip
        // cannot legally cross midnight — the arrival would sort before its
        // own departure and every window comparison would invert.
        if (newStartSeconds < 0 || newEnd > 86_399) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That departure would push the trip past midnight (it would end at "
                    + hhmm(Math.floorMod(newEnd, 86_400)) + "). Choose an earlier time.");
        }

        Integer busId = stops.get(0).getTrip() == null || stops.get(0).getTrip().getBus() == null
                ? null : stops.get(0).getTrip().getBus().getBusId();
        requireBusFree(busId, newStartSeconds, newEnd, tripId);

        for (ScheduledStop ss : stops) {
            ss.setArrivalSeconds(ss.getArrivalSeconds() + delta);
        }
        scheduledStopRepository.saveAll(stops);

        return Map.of("trip_id", tripId,
                      "start_time", hhmm(newStartSeconds),
                      "end_time",   hhmm(newEnd),
                      "shifted_by_minutes", delta / 60);
    }

    /**
     * Add a trip to the timetable.
     *
     * WHERE THE STOPS COME FROM
     * -------------------------
     * A route's stop pattern and leg times exist only inside the gen_line calls
     * in the migrations; nothing stores "the canonical shape of LINEA_3". So an
     * existing trip on the route is used as the template: each stop's offset
     * from its departure is preserved and re-applied at the new time. A route
     * with no trips yet therefore cannot be extended from the UI, which is
     * reported rather than guessed at.
     *
     * The new trip is permanent, like every other row here — the timetable has
     * no service date, so this adds a run to every day.
     */
    @Transactional
    public Map<String, Object> createTrip(String routeId, Integer busId, int departureSeconds) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No route found with id " + routeId));

        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No bus found with id " + busId));

        List<ScheduledStop> template = scheduledStopRepository.findRepresentativeSequence(routeId);
        if (template.size() < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Route " + routeId + " has no existing trip to copy its stop pattern from, "
                    + "so a new trip cannot be timed. Add the route's timetable first.");
        }

        // Offsets from the template's own departure, so the new trip keeps the
        // route's running time and stop spacing exactly.
        int base = template.stream().mapToInt(ScheduledStop::getArrivalSeconds).min().orElseThrow();
        int span = template.stream().mapToInt(ScheduledStop::getArrivalSeconds).max().orElseThrow() - base;

        if (departureSeconds < 0 || departureSeconds + span > 86_399) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A trip departing at " + hhmm(departureSeconds) + " would run past midnight "
                    + "(it lasts " + (span / 60) + " min). The timetable cannot cross midnight.");
        }

        requireBusFree(busId, departureSeconds, departureSeconds + span, "");

        Trip trip = Trip.builder()
                .id(nextTripId(routeId, departureSeconds))
                .route(route)
                .bus(bus)
                .build();
        tripRepository.save(trip);

        // Non si copia più lo stopId: quale fermata occupi ogni posizione lo
        // dice il pattern della linea, e la corsa nuova lo eredita per il solo
        // fatto di appartenere a quella linea. Restano gli orari, che da qui
        // in poi sono suoi e modificabili singolarmente.
        List<ScheduledStop> created = new ArrayList<>();
        for (ScheduledStop t : template) {
            ScheduledStop ss = new ScheduledStop();
            ss.setTrip(trip);
            ss.setStopSequence(t.getStopSequence());
            ss.setArrivalSeconds(departureSeconds + (t.getArrivalSeconds() - base));
            created.add(ss);
        }
        scheduledStopRepository.saveAll(created);

        return Map.of("trip_id",    trip.getId(),
                      "route_id",   routeId,
                      "bus_id",     busId,
                      "start_time", hhmm(departureSeconds),
                      "end_time",   hhmm(departureSeconds + span),
                      "stops",      created.size());
    }

    /**
     * Ids follow the existing convention, ROUTE_secondsOfDay.
     *
     * Two buses may legitimately work one route at the same minute, which would
     * collide, so a suffix is added rather than the request refused — the id is
     * a key, not a statement of uniqueness in the timetable.
     */
    private String nextTripId(String routeId, int departureSeconds) {
        String base = routeId + "_" + departureSeconds;
        if (!tripRepository.existsById(base)) return base;
        for (int n = 2; n < 100; n++) {
            String candidate = base + "_" + n;
            if (!tripRepository.existsById(candidate)) return candidate;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Too many trips already depart at " + hhmm(departureSeconds) + " on " + routeId + ".");
    }

    /**
     * The bus must be free for this window, with a turnaround margin either side.
     *
     * The window is widened by {@link #TURNAROUND_SECONDS} before it is tested,
     * which makes the check symmetric: 15 minutes clear of the previous trip's
     * arrival AND 15 minutes before the next one departs. Enforcing it in one
     * direction only would let a trip be inserted that leaves the following one
     * impossible — the gap belongs to the pair, not to either trip.
     *
     * @param excludeTripId the trip being moved, or "" when creating
     */
    private void requireBusFree(Integer busId, int startSeconds, int endSeconds,
                                String excludeTripId) {
        if (busId == null) return;                 // unassigned: nothing to clash with

        // The ±1 makes the rule "at least 15 minutes", not "more than". The
        // query's HAVING uses <= / >=, so widening by exactly the buffer would
        // still match a trip sitting precisely 15 minutes away and reject a gap
        // that in fact satisfies the requirement.
        List<Object[]> clashes = scheduledStopRepository.findConflictingTrips(
                busId,
                startSeconds - TURNAROUND_SECONDS + 1,
                endSeconds   + TURNAROUND_SECONDS - 1,
                excludeTripId == null ? "" : excludeTripId);
        if (clashes.isEmpty()) return;

        Object[] c        = clashes.get(0);
        int      otherEnd = ((Number) c[2]).intValue();
        int      otherStart = ((Number) c[1]).intValue();

        // Distinguish a true overlap from a turnaround that is merely too short:
        // they call for different corrections, and "overlaps" would be untrue.
        boolean overlaps = otherStart <= endSeconds && otherEnd >= startSeconds;
        int gap = overlaps ? 0
                : (otherStart > endSeconds ? otherStart - endSeconds : startSeconds - otherEnd);

        throw new ResponseStatusException(HttpStatus.CONFLICT, overlaps
                ? "That overlaps trip " + c[0] + " (" + hhmm(otherStart) + "–" + hhmm(otherEnd)
                  + "), which the same bus is already working."
                : "Only " + (gap / 60) + " min from trip " + c[0] + " ("
                  + hhmm(otherStart) + "–" + hhmm(otherEnd) + "). The same bus needs at least "
                  + (TURNAROUND_SECONDS / 60) + " min between trips.");
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
