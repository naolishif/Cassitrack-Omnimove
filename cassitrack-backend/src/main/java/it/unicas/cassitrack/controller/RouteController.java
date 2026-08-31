package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.RouteShape;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.model.Trip;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.RouteShapeRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import it.unicas.cassitrack.repository.TripRepository;
import it.unicas.cassitrack.service.RoutePatternEditService;
import it.unicas.cassitrack.service.RoutePatternService;
import it.unicas.cassitrack.service.VehicleStateCache;
import it.unicas.cassitrack.service.TimetableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class RouteController {

    private final RouteRepository routeRepository;
    private final RouteShapeRepository routeShapeRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final TimetableService timetableService;
    private final RoutePatternService routePatternService;
    private final RoutePatternEditService routePatternEditService;
    private final VehicleStateCache vehicleStateCache;

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
            // Da V26 le fermate della linea si leggono dal pattern, non da una
            // corsa presa a campione: una linea senza corse programmate ha
            // comunque un percorso, e prima spariva da questa mappa.
            List<StopPoint> pts = new ArrayList<>();
            for (var ps : routePatternService.pattern(r.getId())) {
                Stop s = stops.get(ps.stopId());
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

    /** "16 — Cassino-Universita", o l'id se la linea non ha nomi. */
    private static String routeLabel(Route r) {
        String s = r.getShortName(), l = r.getLongName();
        if (s != null && l != null) return s + " \u2014 " + l;
        return s != null ? s : (l != null ? l : r.getId());
    }

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

    /** Cosa sparirebbe cancellando questa linea. */
    public record DeleteImpact(String routeId, String label, long trips, long calls,
                               int patternStops, long shapeVertices, int busesOnLine) {}

    /**
     * L'impatto della cancellazione, senza cancellare.
     *
     * Le FK verso routes sono ON DELETE CASCADE, quindi il database porterebbe
     * via corse, orari, pattern e geometria in un colpo solo e in silenzio.
     * Proprio perché è così facile, val la pena dire prima quanto è grande il
     * colpo: "cancella LINEA_16" e "cancella 26 corse e 208 arrivi" sono la
     * stessa azione, ma non la stessa frase.
     */
    @GetMapping("/{id}/delete-impact")
    @Transactional(readOnly = true)
    public ResponseEntity<?> deleteImpact(@PathVariable String id) {
        Route r = routeRepository.findById(id).orElse(null);
        if (r == null) return err(HttpStatus.NOT_FOUND, "Route not found.");

        long trips = tripRepository.countByRouteId(id);
        long calls = 0;
        for (Trip t : tripRepository.findAllByRouteId(id))
            calls += scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(t.getId()).size();

        int buses = 0;
        for (VehiclePosition pos : vehicleStateCache.getAll())
            if (id.equals(pos.getRouteId())) buses++;

        return ResponseEntity.ok(new DeleteImpact(
                id,
                routeLabel(r),
                trips,
                calls,
                routePatternService.pattern(id).size(),
                routeShapeRepository.findByRouteIdOrderBySeqAsc(id).size(),
                buses));
    }

    /**
     * Cancella una linea.
     *
     * Senza {@code cascade} rifiuta se ci sono corse: è la protezione che
     * evita di svuotare un orario con un click distratto. Con {@code cascade}
     * porta via anche corse, arrivi, pattern e geometria — le FK sono già
     * ON DELETE CASCADE, quindi qui basta togliere la guardia.
     *
     * Non si blocca per i bus in strada: dopo la cancellazione
     * TripResolutionService semplicemente non trova più una corsa per loro e
     * spariscono dalla mappa al giro successivo. È una conseguenza corretta —
     * quella linea non esiste più — e bloccare la cancellazione fino a fine
     * servizio sarebbe peggio del problema.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> deleteRoute(@PathVariable String id,
                                         @RequestParam(defaultValue = "false") boolean cascade) {
        Route r = routeRepository.findById(id).orElse(null);
        if (r == null) return err(HttpStatus.NOT_FOUND, "Route not found.");

        long trips = tripRepository.countByRouteId(id);
        if (trips > 0 && !cascade)
            return err(HttpStatus.CONFLICT,
                    "Cannot delete: this route has " + trips + " trip(s) in the timetable. "
                            + "Remove those trips first, or delete the line with all of them.");

        routeRepository.delete(r);
        // La cache del pattern sopravviverebbe alla riga cancellata, e una
        // linea ricreata con lo stesso id erediterebbe il percorso vecchio.
        routePatternService.invalidate(id);

        log.info("Linea {} cancellata{}", id, trips > 0 ? " con le sue " + trips + " corse" : "");
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

    /**
     * One saved vertex, as returned to the map editor.
     *
     * stopId è popolato solo sui vertici che sono fermate, accoppiandoli in
     * ordine con route_stops. Serve perché l'editor deve poter RIMANDARE
     * indietro il percorso: senza l'id, ricaricare una linea e risalvarla
     * farebbe sembrare ogni fermata esistente una fermata nuova da creare.
     */
    public record ShapeVertex(double lat, double lon, boolean isStop, String stopId) {}

    /**
     * @param path      the saved vertices in drawing order
     * @param tripCount how many runs exist on this line — the editor warns that
     *                  re-marking stops will not rewrite them
     */
    /**
     * Una fermata del pattern, come la mostra l'editor in modifica.
     *
     * offsetSeconds è lo scarto dalla partenza proposto dalla linea. NON è
     * l'orario di una corsa: le corse hanno i propri, e in modifica non si
     * toccano — è la cascata a ricalcolarli. Serve a far vedere la forma del
     * percorso nel tempo ("la terza fermata cade cinque minuti dopo il via")
     * senza dare l'impressione di un campo compilabile.
     */
    public record PatternStop(String stopId, String name, int offsetSeconds) {}

    public record ShapeInfo(List<ShapeVertex> path, long tripCount,
                            List<PatternStop> stops) {}

    @GetMapping("/{id}/shape")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getShape(@PathVariable String id) {
        if (!routeRepository.existsById(id))
            return err(HttpStatus.NOT_FOUND, "Route not found.");
        // L'i-esimo vertice marcato come fermata è l'i-esima fermata del
        // pattern: è la stessa corrispondenza che il salvataggio costruisce,
        // letta al contrario. Se le due liste divergono (shape disegnata prima
        // di V26, o modificata a mano) i vertici in eccesso restano senza id e
        // l'editor li tratterà come fermate da riagganciare.
        List<String> patternStops = routePatternService.stopIds(id);
        List<ShapeVertex> path = new ArrayList<>();
        int stopIdx = 0;
        for (RouteShape s : routeShapeRepository.findByRouteIdOrderBySeqAsc(id)) {
            boolean isStop = Boolean.TRUE.equals(s.getIsStop());
            String stopId = (isStop && stopIdx < patternStops.size())
                    ? patternStops.get(stopIdx++) : null;
            path.add(new ShapeVertex(s.getLat(), s.getLon(), isStop, stopId));
        }
        Map<String, String> stopNames = new HashMap<>();
        for (Stop s : stopRepository.findAllById(patternStops)) stopNames.put(s.getId(), s.getName());

        List<PatternStop> pattern = routePatternService.pattern(id).stream()
                .map(ps -> new PatternStop(ps.stopId(),
                        stopNames.getOrDefault(ps.stopId(), ps.stopId()),
                        ps.defaultOffsetSeconds()))
                .toList();

        return ResponseEntity.ok(new ShapeInfo(path, tripRepository.countByRouteId(id), pattern));
    }

    /**
     * Cosa cambierebbe salvando questo percorso — senza salvarlo.
     *
     * POST e non GET perché il percorso proposto è un corpo, non una query
     * string: una linea può avere venti vertici e non stanno in un URL. Non
     * scrive nulla; il verbo dice solo dove viaggiano i dati.
     */
    @PostMapping("/{id}/shape/preview")
    @Transactional(readOnly = true)
    public ResponseEntity<?> previewShape(@PathVariable String id,
                                          @RequestBody List<PathVertex> path) {
        if (!routeRepository.existsById(id))
            return err(HttpStatus.NOT_FOUND, "Route not found.");
        if (path == null || path.size() < 2)
            return err(HttpStatus.BAD_REQUEST, "A path needs at least two points.");

        // Le fermate nuove qui NON vengono create: sarebbe una scrittura, e
        // un'anteprima che scrive non è un'anteprima. Si usa l'id che avrebbero,
        // così il conteggio è giusto anche prima che esistano.
        List<String> stopIds = path.stream()
                .filter(v -> Boolean.TRUE.equals(v.isStop()))
                .map(v -> v.stopId() != null && !v.stopId().isBlank()
                        ? v.stopId().trim() : generatedStopId(v.stopName()))
                .toList();

        if (stopIds.size() < 2)
            return err(HttpStatus.BAD_REQUEST,
                    "A line needs at least two stops marked on its path.");

        return ResponseEntity.ok(routePatternEditService.preview(id, stopIds));
    }

    /**
     * Ridefinisce il percorso di una linea: il tracciato disegnato E le sue
     * fermate, propagando il cambio alle corse.
     *
     * Fino a V26 questo endpoint RIFIUTAVA di cambiare le fermate, e con
     * ragione: la sequenza viveva duplicata in ogni corsa, quindi riscriverla
     * avrebbe voluto dire inventare gli orari di tutte. Ora le fermate
     * appartengono alla linea e gli orari alla corsa, quindi il cambio si
     * propaga per costruzione: le fermate sopravvissute NON cambiano orario, e
     * si interpola solo per quelle nuove — vedi RoutePatternEditService.
     *
     * Le due scritture restano distinte e sono entrambe necessarie:
     *   · route_shapes  — la geometria, cioè come la linea appare sulla mappa
     *   · route_stops   — quali vertici sono fermate, e in che ordine
     */
    @PutMapping("/{id}/shape")
    @Transactional
    public ResponseEntity<?> updateShape(@PathVariable String id,
                                         @RequestBody List<PathVertex> path) {
        if (!routeRepository.existsById(id))
            return err(HttpStatus.NOT_FOUND, "Route not found.");
        if (path == null || path.size() < 2)
            return err(HttpStatus.BAD_REQUEST, "A path needs at least two points.");

        // Le fermate inventate sul disegno vanno create prima: il pattern le
        // referenzia, e route_stops ha una FK verso stops.
        createStopsFromPath(path);

        List<String> stopIds = path.stream()
                .filter(v -> Boolean.TRUE.equals(v.isStop()))
                .map(v -> v.stopId() != null && !v.stopId().isBlank()
                        ? v.stopId().trim() : generatedStopId(v.stopName()))
                .toList();

        if (stopIds.size() < 2)
            return err(HttpStatus.BAD_REQUEST,
                    "A line needs at least two stops marked on its path.");

        RoutePatternEditService.Result r = routePatternEditService.replacePattern(id, stopIds);
        saveShape(id, path);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("saved",            path.size());
        body.put("stops",            r.stopsAfter());
        body.put("pattern_changed",  r.changed());
        body.put("trips_retimed",    r.tripsRetimed());
        body.put("calls_inserted",   r.callsInserted());
        body.put("calls_removed",    r.callsRemoved());
        // Il gestore deve sapere che per questi bus la misura del ritardo
        // riparte dalla prossima fermata: e' un buco previsto, non un guasto.
        body.put("buses_reanchored", r.busesReanchored());
        return ResponseEntity.ok(body);
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