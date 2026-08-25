package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.RouteShape;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.RouteShapeRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import it.unicas.cassitrack.repository.TripRepository;
import it.unicas.cassitrack.service.TimetableService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteRepository routeRepository;
    private final RouteShapeRepository routeShapeRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final TimetableService timetableService;

    private static final Pattern ROUTE_ID_RE = Pattern.compile("^[A-Za-z0-9_\\-]{1,50}$");
    private static final Pattern COLOR_RE    = Pattern.compile("^[0-9A-Fa-f]{6}$");

    public record StopPoint(String id, String name, double lat, double lon) {}

    /** One vertex of the road geometry. Deliberately tiny: a route path is a
     *  few hundred of these, and they are sent on every map load. */
    public record PathPoint(double lat, double lon) {}

    /**
     * @param stops the scheduled stops, in order — unchanged, still the basis
     *              for stop markers and for the legacy rendering
     * @param path  the polyline following the real streets, or an EMPTY list
     *              when this route has no shape yet. Clients draw {@code path}
     *              when it is non-empty and fall back to {@code stops}
     *              otherwise, so routes without geometry keep working.
     */
    public record RouteGeometry(String id, String name, String longName,
                                String color, List<StopPoint> stops,
                                List<PathPoint> path) {}

    @GetMapping
    public List<RouteGeometry> getRoutes() {
        Map<String, Stop> stops = new HashMap<>();
        for (Stop s : stopRepository.findAll()) stops.put(s.getId(), s);

        // All road geometry in one query, grouped by route. Cheaper than a
        // query per route, and the repository already returns it sorted by seq
        // so the insertion order here IS the drawing order.
        Map<String, List<PathPoint>> paths = new HashMap<>();
        for (var sh : routeShapeRepository.findAllByOrderByRouteIdAscSeqAsc()) {
            paths.computeIfAbsent(sh.getRouteId(), k -> new ArrayList<>())
                 .add(new PathPoint(sh.getLat(), sh.getLon()));
        }

        List<RouteGeometry> out = new ArrayList<>();
        for (Route r : routeRepository.findAll()) {
            if (!r.isActive()) continue;
            List<StopPoint> pts = new ArrayList<>();
            for (var ss : scheduledStopRepository.findRepresentativeSequence(r.getId())) {
                Stop s = stops.get(ss.getStopId());
                if (s != null && s.getLat() != null)
                    pts.add(new StopPoint(s.getId(), s.getName(), s.getLat(), s.getLon()));
            }
            if (pts.size() >= 2)
                out.add(new RouteGeometry(r.getId(), r.getShortName(),
                        r.getLongName(), r.getColor(), pts,
                        paths.getOrDefault(r.getId(), List.of())));
        }

        // findAll() returns whatever order the database feels like, and route
        // ids sort as text anyway (LINEA_08, LINEA_1, LINEA_10…), so the fleet
        // map's route filter listed the lines scrambled. Order them the way a
        // timetable does, once, here.
        out.sort((a, b) -> compareLineLabel(a.name(), b.name()));
        return out;
    }

    /**
     * Orders line labels the way a timetable does: numbers first and in
     * numeric order, so 9 comes before 10 and "09" sits with "9"; anything
     * non-numeric (a lettered code such as "AGR") after them, alphabetically.
     *
     * Length-then-text rather than parsing an int: short_name is VARCHAR(20),
     * and twenty digits overflow every integer type Java has.
     */
    private static int compareLineLabel(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        boolean nx = !x.isEmpty() && x.chars().allMatch(Character::isDigit);
        boolean ny = !y.isEmpty() && y.chars().allMatch(Character::isDigit);
        if (nx != ny) return nx ? -1 : 1;
        if (nx) {
            String sx = x.replaceFirst("^0+(?=.)", "");
            String sy = y.replaceFirst("^0+(?=.)", "");
            if (sx.length() != sy.length()) return Integer.compare(sx.length(), sy.length());
            int c = sx.compareTo(sy);
            if (c != 0) return c;
        }
        return x.compareToIgnoreCase(y);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD management (Data Management panel). Writes gated to FLEET_MANAGER in
    // SecurityConfig (POST/PUT/DELETE /api/v1/routes/**). The root GET above stays
    // public (map geometry for OMNIMOVE); listForManagement returns raw records.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Editable fields accepted from the client.
     *
     * On CREATE the caller may also describe the line's itinerary: the ordered
     * stops plus the bus running its first journey. The schema has no table for
     * "the stops of a line" — that information only exists inside a trip's
     * scheduled_stops, and the rest of the system reads it back through
     * findRepresentativeSequence(). So defining a line's path means creating
     * its first run, which we do here in the same transaction.
     *
     * On UPDATE these fields are ignored: changing an existing line's itinerary
     * would silently rewrite the schedule of every run on it.
     */
    public record RouteRequest(String id, String shortName, String longName,
                               String color, Boolean active,
                               Integer busId,
                               List<TimetableService.CreateTripRequest.ManualStop> stops,
                               List<PathVertex> path) {}

    /**
     * One vertex of a hand-drawn route, as sent by the map editor.
     *
     * A vertex is either plain geometry or also a scheduled stop. When it is a
     * stop it carries either {@code stopId} (an existing stop the editor
     * snapped to) or {@code stopName} (a new stop to create at these
     * coordinates), plus the arrival time of the line's first run.
     */
    public record PathVertex(Double lat, Double lon, Boolean isStop,
                             String stopId, String stopName, String arrival) {}

    private static ResponseEntity<?> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    @GetMapping("/manage")
    public List<Route> listForManagement() {
        return routeRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    // @Transactional: keeps each check-then-write pair (existsById → save,
    // countByRouteId → delete) atomic against concurrent edits.
    @PostMapping
    @Transactional
    public ResponseEntity<?> createRoute(@RequestBody RouteRequest req) {
        String id    = req.id() == null ? null : req.id().trim();
        String color = normalizeColor(req.color());
        String bad = validateRoute(id, req, color);
        if (bad != null) return err(HttpStatus.BAD_REQUEST, bad);
        if (routeRepository.existsById(id))
            return err(HttpStatus.CONFLICT, "A route with id '" + id + "' already exists.");

        boolean withPath = req.path() != null && req.path().size() >= 2;
        // The map editor sends its stops inside `path`; the plain form sends
        // them in `stops`. Either way the line ends up with a first run.
        List<TimetableService.CreateTripRequest.ManualStop> runStops =
                withPath ? stopsFromPath(req.path()) : req.stops();
        boolean withRun = runStops != null && !runStops.isEmpty();

        // Validate BEFORE the first write. A `return err(...)` after save()
        // would end the method normally, so the transaction would COMMIT and
        // leave a line with no run — i.e. a line with no path at all. Only a
        // thrown exception rolls back, hence this check comes first.
        if (withRun && req.busId() == null)
            return err(HttpStatus.BAD_REQUEST,
                    "Pick the bus that runs this line's first journey.");

        Route r = new Route();
        r.setId(id);
        applyRoute(r, req, color);
        routeRepository.save(r);

        // Road geometry drawn on the map. Saved before the run so the stops it
        // may have created already exist when scheduled_stops references them.
        if (withPath) {
            createStopsFromPath(req.path());
            saveShape(id, req.path());
        }

        // Materialise the itinerary as the line's first run, in this same
        // transaction. create() validates the stops and — importantly here —
        // refuses if the bus is already running another trip in that window;
        // it throws, so the line is rolled back with it.
        if (withRun) {
            timetableService.create(new TimetableService.CreateTripRequest(
                    id, req.busId(), null, runStops));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(r);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateRoute(@PathVariable String id, @RequestBody RouteRequest req) {
        Route r = routeRepository.findById(id).orElse(null);
        if (r == null) return err(HttpStatus.NOT_FOUND, "Route not found.");
        String color = normalizeColor(req.color());
        String bad = validateRoute(id, req, color);
        if (bad != null) return err(HttpStatus.BAD_REQUEST, bad);
        applyRoute(r, req, color);
        return ResponseEntity.ok(routeRepository.save(r));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteRoute(@PathVariable String id) {
        Route r = routeRepository.findById(id).orElse(null);
        if (r == null) return err(HttpStatus.NOT_FOUND, "Route not found.");
        long trips = tripRepository.countByRouteId(id);
        if (trips > 0)
            return err(HttpStatus.CONFLICT,
                    "Cannot delete: this route has " + trips + " trip(s) in the timetable. "
                            + "Remove those trips first.");
        routeRepository.delete(r);
        return ResponseEntity.noContent().build();
    }

    private static void applyRoute(Route r, RouteRequest req, String color) {
        r.setShortName(req.shortName().trim());
        r.setLongName(req.longName() == null || req.longName().isBlank() ? null : req.longName().trim());
        r.setColor(color);
        r.setActive(req.active() == null ? true : req.active());
    }

    private static String normalizeColor(String c) {
        if (c == null) return null;
        String t = c.trim().replaceFirst("^#", "");
        return t.isEmpty() ? null : t.toUpperCase();
    }

    // ── Road geometry of an existing line ────────────────────────────────────

    /** One saved vertex, as returned to the map editor. */
    public record ShapeVertex(double lat, double lon, boolean isStop) {}

    /**
     * @param path      the saved vertices in drawing order
     * @param tripCount how many runs exist on this line — the editor warns that
     *                  re-marking stops will not rewrite them
     */
    public record ShapeInfo(List<ShapeVertex> path, long tripCount) {}

    @GetMapping("/{id}/shape")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getShape(@PathVariable String id) {
        if (!routeRepository.existsById(id))
            return err(HttpStatus.NOT_FOUND, "Route not found.");
        List<ShapeVertex> path = routeShapeRepository.findByRouteIdOrderBySeqAsc(id).stream()
                .map(s -> new ShapeVertex(s.getLat(), s.getLon(), Boolean.TRUE.equals(s.getIsStop())))
                .toList();
        return ResponseEntity.ok(new ShapeInfo(path, tripRepository.countByRouteId(id)));
    }

    /**
     * Replace the drawn geometry of an existing line.
     *
     * Deliberately limited to route_shapes: it is read only to draw the line on
     * the maps and to publish it over NeTEx, never by schedule adherence or ETA
     * (those use scheduled_stops). So redrawing a path cannot disturb timings.
     *
     * Stops marked here are NOT propagated to the existing runs: rewriting the
     * calls of every run would mean inventing their times. New stops drawn on
     * the map are created, so they can then be used from the Timetable tab.
     */
    @PutMapping("/{id}/shape")
    @Transactional
    public ResponseEntity<?> updateShape(@PathVariable String id,
                                         @RequestBody List<PathVertex> path) {
        if (!routeRepository.existsById(id))
            return err(HttpStatus.NOT_FOUND, "Route not found.");
        if (path == null || path.size() < 2)
            return err(HttpStatus.BAD_REQUEST, "A path needs at least two points.");

        // The stop vertices must stay exactly as they were. route_shapes.is_stop
        // mirrors the calls in scheduled_stops: letting the path declare a
        // different set would (a) make the drawing disagree with what the runs
        // actually serve, and (b) break the scheduled simulator, which binds
        // each call to a vertex by coordinates and drops the whole run when it
        // finds none. Stops are changed from the Timetable tab, per run.
        List<RouteShape> current = routeShapeRepository.findByRouteIdOrderBySeqAsc(id);
        if (!current.isEmpty()) {
            Set<String> before = current.stream()
                    .filter(s -> Boolean.TRUE.equals(s.getIsStop()))
                    .map(s -> coordKey(s.getLat(), s.getLon()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            Set<String> after = path.stream()
                    .filter(v -> Boolean.TRUE.equals(v.isStop()))
                    .map(v -> coordKey(v.lat(), v.lon()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            if (!before.equals(after))
                return err(HttpStatus.CONFLICT,
                        "The stops of this line cannot be changed here: they must keep matching "
                                + "the calls of its runs. Reshape the road path freely, and edit "
                                + "stops from the Timetable tab.");
        }

        createStopsFromPath(path);   // no-op unless the line had no shape yet
        saveShape(id, path);
        return ResponseEntity.ok(Map.of("saved", path.size()));
    }

    /**
     * Coordinate key at ~1 m, the same tolerance the scheduled simulator uses to
     * bind a call to its vertex. Comparing rounded strings avoids treating a
     * float round-trip through JSON as a moved stop.
     */
    private static String coordKey(Double lat, Double lon) {
        if (lat == null || lon == null) return "?";
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon);
    }

    // ── Map editor support ───────────────────────────────────────────────────

    /** Vertices flagged as stops, in drawing order → the first run's calls. */
    private static List<TimetableService.CreateTripRequest.ManualStop>
    stopsFromPath(List<PathVertex> path) {
        List<TimetableService.CreateTripRequest.ManualStop> out = new ArrayList<>();
        for (PathVertex v : path) {
            if (!Boolean.TRUE.equals(v.isStop())) continue;
            String stopId = v.stopId() != null && !v.stopId().isBlank()
                    ? v.stopId().trim()
                    : generatedStopId(v.stopName());
            out.add(new TimetableService.CreateTripRequest.ManualStop(stopId, v.arrival()));
        }
        return out;
    }

    /**
     * Create the stops the editor invented (a vertex marked as a stop with a
     * name but no existing id). Stops it snapped to are left untouched.
     */
    private void createStopsFromPath(List<PathVertex> path) {
        for (PathVertex v : path) {
            if (!Boolean.TRUE.equals(v.isStop())) continue;
            if (v.stopId() != null && !v.stopId().isBlank()) continue;   // snapped to an existing stop
            if (v.stopName() == null || v.stopName().isBlank())
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "A stop drawn on the map needs a name.");

            String newId = generatedStopId(v.stopName());
            if (stopRepository.existsById(newId)) continue;              // same name drawn twice
            Stop s = new Stop();
            s.setId(newId);
            s.setName(v.stopName().trim());
            s.setLat(v.lat());
            s.setLon(v.lon());
            s.setActive(true);
            s.setCreatedAt(java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Rome")));
            stopRepository.save(s);
        }
    }

    /** Replace this route's geometry with the drawn vertices. */
    private void saveShape(String routeId, List<PathVertex> path) {
        routeShapeRepository.deleteAll(routeShapeRepository.findByRouteIdOrderBySeqAsc(routeId));
        List<RouteShape> shape = new ArrayList<>();
        int seq = 0;
        for (PathVertex v : path) {
            if (v.lat() == null || v.lon() == null) continue;
            RouteShape rs = new RouteShape();
            rs.setRouteId(routeId);
            rs.setSeq(seq++);
            rs.setLat(v.lat());
            rs.setLon(v.lon());
            rs.setIsStop(Boolean.TRUE.equals(v.isStop()));
            shape.add(rs);
        }
        routeShapeRepository.saveAll(shape);
    }

    /**
     * Stop id derived from the name: uppercase, non-alphanumerics collapsed to
     * underscore, capped to the column length. Deterministic, so drawing the
     * same stop name twice reuses the same row instead of duplicating it.
     */
    private static String generatedStopId(String name) {
        if (name == null || name.isBlank())
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A stop drawn on the map needs a name.");
        String id = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")            // drop accents: "Università" → "Universita"
                .toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (id.isEmpty()) id = "STOP";
        return id.length() > 50 ? id.substring(0, 50) : id;
    }

    /**
     * TimetableService signals invalid itineraries with ResponseStatusException
     * (unknown stop, times not increasing, bus already busy…). Translate it to
     * the {status, error, message} shape the Data Management UI reads, instead
     * of letting it surface as a bare 500.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        String msg = ex.getReason() == null ? "Request failed" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("status", ex.getStatusCode().value(), "error", msg, "message", msg));
    }

    private static String validateRoute(String id, RouteRequest req, String color) {
        if (id == null || !ROUTE_ID_RE.matcher(id).matches())
            return "Route id is required (letters, digits, - or _, max 50 chars).";
        if (req.shortName() == null || req.shortName().trim().isEmpty()) return "Short name is required.";
        if (req.shortName().trim().length() > 20)  return "Short name is too long (max 20 characters).";
        if (req.longName() != null && req.longName().trim().length() > 200)
            return "Long name is too long (max 200 characters).";
        if (color != null && !COLOR_RE.matcher(color).matches())
            return "Color must be a 6-digit hex code (e.g. 1976D2).";
        return null;
    }
}