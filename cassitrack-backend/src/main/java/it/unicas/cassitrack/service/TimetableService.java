package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.RouteStop;
import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Trip;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.RouteStopRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import it.unicas.cassitrack.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Timetable management — trips and their scheduled stops.
 *
 * Model recap (see V1/V5/V6):
 *   trips            id (VARCHAR), route_id, bus_id
 *   scheduled_stops  trip_id, stop_id, stop_sequence, arrival_seconds
 *
 * A trip is one run of a line at a given departure time. The stop sequence is
 * effectively a property of the LINE — every run calls at the same stops with
 * the same spacing — but the schema stores it per trip, so creating a run means
 * copying the sequence of an existing run of that line and shifting all times
 * to the new departure. That is exactly what the V5 migration did with its
 * gen_line() helper; this service does it at runtime instead.
 */
@Service
@RequiredArgsConstructor
public class TimetableService {

    private final TripRepository tripRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final RouteRepository routeRepository;
    private final BusRepository busRepository;
    private final StopRepository stopRepository;
    private final RoutePatternService routePatternService;
    private final RouteStopRepository routeStopRepository;

    // ── DTOs ────────────────────────────────────────────────────────

    /** One row of the timetable list. */
    public record TripSummary(String tripId, String routeId, String routeLabel,
                              Integer busId, String targa,
                              String departure, String arrival,
                              int departureSeconds, long stopCount) {}

    /** One call of a trip, as shown in the detail view. */
    public record TripStop(String stopId, String stopName, int sequence,
                           int arrivalSeconds, String arrival) {}

    /** Full detail of a trip. */
    public record TripDetail(String tripId, String routeId, String routeLabel,
                             Integer busId, String targa, List<TripStop> stops) {}

    /**
     * Payload for creating a run, in either mode:
     *   · stops null/empty → copy the line's existing stop pattern and shift it
     *     to {@code departure}
     *   · stops provided   → build the run from scratch with exactly these
     *     calls; {@code departure} is then ignored (the first stop defines it)
     */
    public record CreateTripRequest(String routeId, Integer busId, String departure,
                                    List<ManualStop> stops) {
        public record ManualStop(String stopId, String arrival) {}
    }

    /** Payload for editing the times of an existing run. */
    public record UpdateTimesRequest(List<StopTime> stops) {
        public record StopTime(String stopId, Integer sequence, String arrival) {}
    }

    // ── READ ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TripSummary> list(String routeId, Integer busId, String search) {
        List<TripSummary> all = new ArrayList<>();
        for (Object[] r : scheduledStopRepository.findTripSummaries()) {
            String  tripId = (String) r[0];
            String  rid    = (String) r[1];
            String  label  = routeLabel((String) r[2], (String) r[3], rid);
            Integer bid    = r[4] == null ? null : ((Number) r[4]).intValue();
            String  targa  = (String) r[5];
            int     start  = ((Number) r[6]).intValue();
            int     end    = ((Number) r[7]).intValue();
            long    count  = ((Number) r[8]).longValue();
            all.add(new TripSummary(tripId, rid, label, bid, targa,
                    hhmm(start), hhmm(end), start, count));
        }
        String needle = search == null ? "" : search.trim().toLowerCase();
        return all.stream()
                .filter(t -> routeId == null || routeId.isBlank() || routeId.equals(t.routeId()))
                .filter(t -> busId == null || busId.equals(t.busId()))
                .filter(t -> needle.isEmpty()
                        || t.tripId().toLowerCase().contains(needle)
                        || t.routeLabel().toLowerCase().contains(needle)
                        || (t.targa() != null && t.targa().toLowerCase().contains(needle))
                        || t.departure().contains(needle))
                .sorted(Comparator.comparing(TripSummary::routeId)
                        .thenComparingInt(TripSummary::departureSeconds))
                .toList();
    }

    /** One row of the exploded timetable: a single call of a single run. */
    public record StopTimeRow(String tripId, String routeId, String routeLabel,
                              Integer busId, String targa,
                              int sequence, String stopId, String stopName,
                              String arrival, int arrivalSeconds) {}

    /**
     * The whole timetable, one row per run+stop, honouring the same filters as
     * the run list so an export matches what the user is looking at.
     */
    @Transactional(readOnly = true)
    public List<StopTimeRow> stopTimes(String routeId, Integer busId, String search) {
        String needle = search == null ? "" : search.trim().toLowerCase();

        return scheduledStopRepository.findAllStopTimes().stream()
                .map(r -> {
                    String  tripId = (String) r[0];
                    String  rid    = (String) r[1];
                    String  label  = routeLabel((String) r[2], (String) r[3], rid);
                    Integer bid    = r[4] == null ? null : ((Number) r[4]).intValue();
                    String  targa  = (String) r[5];
                    int     seq    = ((Number) r[6]).intValue();
                    String  sid    = (String) r[7];
                    String  sname  = (String) r[8];
                    int     secs   = ((Number) r[9]).intValue();
                    return new StopTimeRow(tripId, rid, label, bid, targa,
                            seq, sid, sname, hhmm(secs), secs);
                })
                .filter(r -> routeId == null || routeId.isBlank() || routeId.equals(r.routeId()))
                .filter(r -> busId == null || busId.equals(r.busId()))
                .filter(r -> needle.isEmpty()
                        || r.tripId().toLowerCase().contains(needle)
                        || r.routeLabel().toLowerCase().contains(needle)
                        || (r.targa() != null && r.targa().toLowerCase().contains(needle))
                        || (r.stopName() != null && r.stopName().toLowerCase().contains(needle)))
                .toList();
    }

    /**
     * La fermata di una riga di orario, risolta dal pattern della sua linea.
     *
     * Da V26 scheduled_stops porta solo il tempo; il "quale fermata" sta in
     * route_stops. Restituisce "?" invece di null perché i chiamanti la usano
     * come chiave e in etichette: un buco visibile è meglio di un NPE.
     */
    private String patternStopId(ScheduledStop ss) {
        String routeId = ss.getTrip() != null && ss.getTrip().getRoute() != null
                ? ss.getTrip().getRoute().getId() : null;
        String id = routePatternService.stopIdAt(routeId, ss.getStopSequence());
        return id != null ? id : "?";
    }

    @Transactional(readOnly = true)
    public TripDetail detail(String tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found."));

        Map<String, String> stopNames = stopNameMap();
        List<TripStop> stops = scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(tripId)
                .stream()
                .map(ss -> new TripStop(patternStopId(ss),
                        stopNames.getOrDefault(patternStopId(ss), patternStopId(ss)),
                        ss.getStopSequence(), ss.getArrivalSeconds(),
                        hhmm(ss.getArrivalSeconds())))
                .toList();

        Route r = trip.getRoute();
        Bus   b = trip.getBus();
        return new TripDetail(trip.getId(), r == null ? null : r.getId(),
                r == null ? "—" : routeLabel(r.getShortName(), r.getLongName(), r.getId()),
                b == null ? null : b.getBusId(), b == null ? null : b.getTarga(), stops);
    }

    // ── CREATE ──────────────────────────────────────────────────────

    /**
     * Create a run by copying the stop pattern of an existing run of the same
     * line and shifting every arrival by the difference between the new and the
     * old departure. Relative spacing (travel + dwell) is therefore preserved.
     */
    @Transactional
    public TripDetail create(CreateTripRequest req) {
        if (req.routeId() == null || req.routeId().isBlank())
            throw bad("Pick a line for this run.");
        if (req.busId() == null)
            throw bad("Pick a bus for this run.");

        Route route = routeRepository.findById(req.routeId())
                .orElseThrow(() -> bad("No line found with id '" + req.routeId() + "'."));
        Bus bus = busRepository.findById(req.busId())
                .orElseThrow(() -> bad("No bus found with id " + req.busId() + "."));

        boolean manual = req.stops() != null && !req.stops().isEmpty();

        // (stopId, arrivalSeconds) pairs to write, in order.
        List<String> stopIds = new ArrayList<>();
        List<Integer> times  = new ArrayList<>();

        if (manual) {
            buildManualCalls(req.stops(), stopIds, times);
        } else {
            // Da V26 gli orari si propongono dagli scarti di default della
            // linea, non copiandoli da una corsa presa a campione. Cambia due
            // cose: una linea appena disegnata può avere la sua prima corsa
            // senza che ne esista già una, e la proposta non eredita più le
            // stranezze di quell'unica corsa scelta per caso.
            //
            // Sono valori di partenza: una volta scritti appartengono a questa
            // corsa e il gestore li corregge fermata per fermata, così la corsa
            // del rientro da scuola può essere più lenta di quella serale.
            int departure = parseHhmm(req.departure());
            var pattern = routePatternService.pattern(route.getId());
            if (pattern.isEmpty())
                throw bad("Line '" + route.getId() + "' has no stops yet. "
                        + "Draw its route first, or use \"Build stop by stop\".");
            for (var ps : pattern) {
                stopIds.add(ps.stopId());
                times.add(departure + ps.defaultOffsetSeconds());
            }
        }

        int departure = times.get(0);
        int arrival   = times.get(times.size() - 1);

        String tripId = route.getId() + "_" + departure;
        if (tripRepository.existsById(tripId))
            throw conflict("Line " + route.getId() + " already has a run departing at "
                    + hhmm(departure) + ".");

        ensureBusIsFree(bus.getBusId(), departure, arrival, null);

        // ── La corsa deve stare dentro il pattern della sua linea (V26) ──
        //
        // Da quando le fermate appartengono alla linea, una corsa non può più
        // fermarsi dove vuole: sarebbe di nuovo possibile che due corse della
        // stessa linea divergano, che è precisamente ciò che la normalizzazione
        // ha eliminato. Restano due casi legittimi, e sono diversi:
        //
        //   · la linea non ha ancora un percorso — allora questa prima corsa
        //     lo DEFINISCE, ed è il modo naturale di disegnare una linea nuova
        //     costruendola fermata per fermata;
        //   · la linea ce l'ha già — allora la corsa può scegliere solo gli
        //     orari, e un elenco di fermate diverso è un errore da segnalare,
        //     non da assecondare scrivendo dati incoerenti.
        List<String> pattern = routePatternService.stopIds(route.getId());
        if (pattern.isEmpty()) {
            List<RouteStop> newPattern = new ArrayList<>();
            for (int i = 0; i < stopIds.size(); i++) {
                newPattern.add(RouteStop.builder()
                        .routeId(route.getId())
                        .stopSequence(i + 1)
                        .stopId(stopIds.get(i))
                        .defaultOffsetSeconds(times.get(i) - departure)
                        .build());
            }
            routeStopRepository.saveAll(newPattern);
            routePatternService.invalidate(route.getId());
        } else if (!pattern.equals(stopIds)) {
            throw conflict("Line " + route.getId() + " already has a route: "
                    + pattern.size() + " stops, and this run lists " + stopIds.size()
                    + " different ones. A run can set its own times, but not its own "
                    + "stops — change the line's route instead.");
        }

        Trip trip = Trip.builder().id(tripId).route(route).bus(bus).build();
        tripRepository.save(trip);

        List<ScheduledStop> calls = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            calls.add(ScheduledStop.builder()
                    .trip(trip)
                    .stopSequence(i + 1)          // 1-based, matches the seed data
                    .arrivalSeconds(times.get(i))
                    .build());
        }
        scheduledStopRepository.saveAll(calls);

        return detail(tripId);
    }

    /**
     * Validate a hand-built stop list and fill the parallel id/time lists.
     *
     * Repeating the same stop is allowed on purpose: the Cassino lines are
     * loops, so the terminus legitimately appears both first and last. What is
     * not allowed is an unknown stop, fewer than two calls, or times that do
     * not strictly increase.
     */
    private void buildManualCalls(List<CreateTripRequest.ManualStop> stops,
                                  List<String> stopIds, List<Integer> times) {
        if (stops.size() < 2)
            throw bad("A run needs at least two stops.");

        Set<String> known = new HashSet<>();
        stopRepository.findAll().forEach(s -> known.add(s.getId()));

        int prev = -1;
        for (int i = 0; i < stops.size(); i++) {
            CreateTripRequest.ManualStop ms = stops.get(i);
            if (ms.stopId() == null || ms.stopId().isBlank())
                throw bad("Stop " + (i + 1) + ": pick a stop.");
            if (!known.contains(ms.stopId()))
                throw bad("Stop " + (i + 1) + ": no stop found with id '" + ms.stopId() + "'.");

            int t = parseHhmm(ms.arrival());
            if (t <= prev)
                throw bad("Stop " + (i + 1) + " (" + hhmm(t) + ") must be later than the previous one ("
                        + hhmm(prev) + ").");
            prev = t;

            stopIds.add(ms.stopId());
            times.add(t);
        }
    }

    // ── UPDATE (times only) ─────────────────────────────────────────

    /**
     * Rewrite the arrival times of an existing run. The stop sequence itself is
     * not editable here: changing which stops a line serves is a change to the
     * line, not to one of its runs.
     */
    @Transactional
    public TripDetail updateTimes(String tripId, UpdateTimesRequest req) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found."));
        if (req.stops() == null || req.stops().isEmpty())
            throw bad("No stop times submitted.");

        List<ScheduledStop> existing = scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(tripId);
        Map<Integer, ScheduledStop> bySeq = new HashMap<>();
        for (ScheduledStop ss : existing) bySeq.put(ss.getStopSequence(), ss);

        // Parse and validate before touching anything.
        Map<Integer, Integer> newTimes = new TreeMap<>();
        for (UpdateTimesRequest.StopTime st : req.stops()) {
            if (st.sequence() == null || !bySeq.containsKey(st.sequence()))
                throw bad("Unknown stop position " + st.sequence() + " for this run.");
            newTimes.put(st.sequence(), parseHhmm(st.arrival()));
        }
        // Times must increase along the sequence — a bus cannot arrive earlier
        // at a later stop.
        int prev = -1;
        for (Map.Entry<Integer, Integer> e : newTimes.entrySet()) {
            if (e.getValue() <= prev)
                throw bad("Times must increase along the route: stop " + e.getKey()
                        + " (" + hhmm(e.getValue()) + ") is not after the previous one.");
            prev = e.getValue();
        }

        int start = newTimes.values().iterator().next();
        int end   = prev;
        if (trip.getBus() != null)
            ensureBusIsFree(trip.getBus().getBusId(), start, end, tripId);

        newTimes.forEach((seq, secs) -> bySeq.get(seq).setArrivalSeconds(secs));
        scheduledStopRepository.saveAll(bySeq.values());
        return detail(tripId);
    }

    // ── DELETE ──────────────────────────────────────────────────────

    /**
     * Delete a run. scheduled_stops has ON DELETE CASCADE on trip_id, so its
     * calls go with it — which is correct here: a run's stop times are
     * meaningless without the run. We delete them explicitly anyway so the
     * behaviour does not depend on the schema.
     */
    @Transactional
    public void delete(String tripId) {
        if (!tripRepository.existsById(tripId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip not found.");
        scheduledStopRepository.deleteByTripId(tripId);
        tripRepository.deleteById(tripId);
    }

    // ── Guards / helpers ────────────────────────────────────────────

    /**
     * A bus cannot run two trips at once. Rejects any overlap with the runs
     * already assigned to this bus (ignoring the trip being edited).
     */
    private void ensureBusIsFree(Integer busId, int start, int end, String ignoreTripId) {
        for (Object[] r : scheduledStopRepository.findServiceWindowsForBus(busId)) {
            String otherId = (String) r[0];
            if (otherId.equals(ignoreTripId)) continue;
            int oStart = ((Number) r[1]).intValue();
            int oEnd   = ((Number) r[2]).intValue();
            if (start <= oEnd && oStart <= end)
                throw conflict("This bus is already running trip " + otherId
                        + " from " + hhmm(oStart) + " to " + hhmm(oEnd)
                        + ", which overlaps " + hhmm(start) + "–" + hhmm(end) + ".");
        }
    }

    private Map<String, String> stopNameMap() {
        Map<String, String> names = new HashMap<>();
        stopRepository.findAll().forEach(s -> names.put(s.getId(), s.getName()));
        return names;
    }

    private static String routeLabel(String shortName, String longName, String routeId) {
        if (shortName == null || shortName.isBlank())
            return longName == null || longName.isBlank() ? routeId : longName;
        return longName == null || longName.isBlank()
                ? shortName : shortName + " — " + longName;
    }

    /** "HH:mm" → seconds since midnight. */
    private static int parseHhmm(String value) {
        if (value == null || !value.matches("\\d{1,2}:\\d{2}"))
            throw bad("Time must be in HH:mm format (e.g. 08:30).");
        String[] parts = value.split(":");
        int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
        if (h > 23 || m > 59) throw bad("'" + value + "' is not a valid time.");
        return h * 3600 + m * 60;
    }

    private static String hhmm(int seconds) {
        int s = ((seconds % 86400) + 86400) % 86400;
        return String.format("%02d:%02d", s / 3600, (s % 3600) / 60);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
