package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import it.unicas.cassitrack.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteRepository routeRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;

    private static final Pattern ROUTE_ID_RE = Pattern.compile("^[A-Za-z0-9_\\-]{1,50}$");
    private static final Pattern COLOR_RE    = Pattern.compile("^[0-9A-Fa-f]{6}$");

    public record StopPoint(String id, String name, double lat, double lon) {}
    public record RouteGeometry(String id, String name, String longName,
                                String color, List<StopPoint> stops) {}

    @GetMapping
    public List<RouteGeometry> getRoutes() {
        Map<String, Stop> stops = new HashMap<>();
        for (Stop s : stopRepository.findAll()) stops.put(s.getId(), s);

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
                        r.getLongName(), r.getColor(), pts));
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD management (Data Management panel). Writes gated to FLEET_MANAGER in
    // SecurityConfig (POST/PUT/DELETE /api/v1/routes/**). The root GET above stays
    // public (map geometry for OMNIMOVE); listForManagement returns raw records.
    // ─────────────────────────────────────────────────────────────────────────

    /** Editable fields accepted from the client. */
    public record RouteRequest(String id, String shortName, String longName,
                               String color, Boolean active) {}

    private static ResponseEntity<?> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    @GetMapping("/manage")
    public List<Route> listForManagement() {
        return routeRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    @PostMapping
    public ResponseEntity<?> createRoute(@RequestBody RouteRequest req) {
        String id    = req.id() == null ? null : req.id().trim();
        String color = normalizeColor(req.color());
        String bad = validateRoute(id, req, color);
        if (bad != null) return err(HttpStatus.BAD_REQUEST, bad);
        if (routeRepository.existsById(id))
            return err(HttpStatus.CONFLICT, "A route with id '" + id + "' already exists.");
        Route r = new Route();
        r.setId(id);
        applyRoute(r, req, color);
        return ResponseEntity.status(HttpStatus.CREATED).body(routeRepository.save(r));
    }

    @PutMapping("/{id}")
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