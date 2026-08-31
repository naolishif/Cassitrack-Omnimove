package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.dto.StopArrivalDTO;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.RouteStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import it.unicas.cassitrack.service.ETAService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST controller for bus stop ETA queries.
 * GET /api/v1/stops/{stopId}/arrivals
 */
@RestController
@RequestMapping("/api/v1/stops")
@RequiredArgsConstructor
@Tag(name = "Stops",
        description = "Bus stop ETAs and predicted arrivals")
public class StopController {

    private static final Pattern STOP_ID_RE = Pattern.compile("^[A-Za-z0-9\\-_]{1,50}$");
    private static final ZoneId  ITALY_TZ   = ZoneId.of("Europe/Rome");

    private final ETAService              etaService;
    private final ScheduledStopRepository scheduledStopRepository;
    private final RouteRepository         routeRepository;
    private final StopRepository          stopRepository;
    private final RouteStopRepository     routeStopRepository;

    @GetMapping("/{stopId}/arrivals")
    @Operation(
            summary = "Get predicted arrivals at a stop",
            description =
                    "Returns predicted arrival times for all " +
                            "buses heading to this stop, computed from " +
                            "real-time GPS positions."
    )
    public ResponseEntity<List<StopArrivalDTO>>
    getArrivalsAtStop(
            @Parameter(description =
                    "Stop ID — e.g. PSB (Piazza San Benedetto), " +
                            "SFF (Stazione FF.SS.), UNI (Università Folcara), " +
                            "OSP (Ospedale), LIC (Liceo Scientifico)")
            @PathVariable String stopId
    ) {
        List<StopArrivalDTO> arrivals =
                etaService.getArrivalsAtStop(stopId);
        return ResponseEntity.ok(arrivals);
    }

    @GetMapping("/{stopId}/schedule")
    @Operation(summary = "Today's remaining scheduled arrivals at a stop (static timetable)")
    public ResponseEntity<List<StopArrivalDTO>> getScheduleAtStop(
            @PathVariable String stopId) {

        if (!STOP_ID_RE.matcher(stopId).matches())
            return ResponseEntity.badRequest().build();

        int nowSeconds = LocalTime.now(ITALY_TZ).toSecondOfDay();
        Instant todayMidnight = LocalDate.now(ITALY_TZ).atStartOfDay(ITALY_TZ).toInstant();

        Map<String, Route> routeMap = routeRepository.findAll().stream()
                .collect(Collectors.toMap(Route::getId, r -> r, (a, b) -> a));

        List<StopArrivalDTO> schedule = scheduledStopRepository
                .findUpcomingByStopId(stopId, nowSeconds)
                .stream()
                .map(ss -> {
                    Route r     = routeMap.get(ss.getTrip().getRoute().getId());
                    Instant arr = todayMidnight.plusSeconds(ss.getArrivalSeconds());
                    return StopArrivalDTO.builder()
                            .tripId(ss.getTrip().getId())
                            .routeId(ss.getTrip().getRoute().getId())
                            .routeName(r != null && r.getLongName() != null
                                    ? r.getLongName() : (r != null ? r.getShortName() : null))
                            .routeShortName(r != null ? r.getShortName() : null)
                            .scheduledArrival(arr)
                            .estimatedArrival(arr)
                            .delayMinutes(0)
                            .scheduleStatus("SCHEDULED")
                            .build();
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(schedule);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD management (Data Management panel). Writes gated to FLEET_MANAGER in
    // SecurityConfig (POST/PUT/DELETE /api/v1/stops/**). CSRF disabled app-wide.
    // ─────────────────────────────────────────────────────────────────────────

    /** Editable fields accepted from the client. */
    public record StopRequest(String id, String name, Double lat, Double lon,
                              String description, Boolean active) {}

    private static ResponseEntity<?> err(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    @GetMapping
    @Operation(summary = "List all stops (management)")
    public List<Stop> listStops() {
        return stopRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    // @Transactional: the check-then-write pairs below (existsById → save,
    // countByStopId → delete) must run as one unit, so a concurrent change
    // can't slip between the check and the write.
    @PostMapping
    @Transactional
    @Operation(summary = "Create a stop")
    public ResponseEntity<?> createStop(@RequestBody StopRequest req) {
        String id = req.id() == null ? null : req.id().trim();
        String bad = validateStop(id, req);
        if (bad != null) return err(HttpStatus.BAD_REQUEST, bad);
        if (stopRepository.existsById(id))
            return err(HttpStatus.CONFLICT, "A stop with id '" + id + "' already exists.");
        Stop s = new Stop();
        s.setId(id);
        applyStop(s, req);
        s.setCreatedAt(ZonedDateTime.now(ITALY_TZ));
        return ResponseEntity.status(HttpStatus.CREATED).body(stopRepository.save(s));
    }

    @PutMapping("/{id}")
    @Transactional
    @Operation(summary = "Update a stop")
    public ResponseEntity<?> updateStop(@PathVariable String id, @RequestBody StopRequest req) {
        Stop s = stopRepository.findById(id).orElse(null);
        if (s == null) return err(HttpStatus.NOT_FOUND, "Stop not found.");
        String bad = validateStop(id, req);   // id already exists; re-validate name/coords
        if (bad != null) return err(HttpStatus.BAD_REQUEST, bad);
        applyStop(s, req);
        return ResponseEntity.ok(stopRepository.save(s));
    }

    @DeleteMapping("/{id}")
    @Transactional
    @Operation(summary = "Delete a stop (blocked if used in the timetable)")
    public ResponseEntity<?> deleteStop(@PathVariable String id) {
        Stop s = stopRepository.findById(id).orElse(null);
        if (s == null) return err(HttpStatus.NOT_FOUND, "Stop not found.");
        // Da V27 è route_stops a referenziare stops, con ON DELETE RESTRICT.
        // Il database rifiuterebbe comunque, ma con un vincolo violato; qui si
        // dice prima, nominando le linee, così il gestore sa dove intervenire.
        List<String> servingRoutes = routeStopRepository.findRouteIdsServing(id);
        if (!servingRoutes.isEmpty())
            return err(HttpStatus.CONFLICT,
                    "Cannot delete: this stop is on the route of " + servingRoutes.size()
                            + " line(s) (" + String.join(", ", servingRoutes) + "). "
                            + "Remove it from those routes first.");
        stopRepository.delete(s);
        return ResponseEntity.noContent().build();
    }

    private static void applyStop(Stop s, StopRequest req) {
        s.setName(req.name().trim());
        s.setLat(req.lat());
        s.setLon(req.lon());
        s.setDescription(req.description() == null || req.description().isBlank()
                ? null : req.description().trim());
        s.setActive(req.active() == null ? Boolean.TRUE : req.active());
    }

    private static String validateStop(String id, StopRequest req) {
        if (id == null || !STOP_ID_RE.matcher(id).matches())
            return "Stop id is required (letters, digits, - or _, max 50 chars).";
        if (req.name() == null || req.name().trim().isEmpty()) return "Name is required.";
        if (req.name().trim().length() > 200)                  return "Name is too long (max 200 characters).";
        if (req.lat() == null || req.lon() == null)            return "Latitude and longitude are required.";
        if (req.lat() < -90 || req.lat() > 90)                 return "Latitude must be between -90 and 90.";
        if (req.lon() < -180 || req.lon() > 180)               return "Longitude must be between -180 and 180.";
        return null;
    }
}