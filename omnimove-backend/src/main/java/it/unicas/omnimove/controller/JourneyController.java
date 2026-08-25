package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.JourneyRequest;
import it.unicas.omnimove.dto.JourneyResponse;
import it.unicas.omnimove.dto.StopArrivalDTO;
import it.unicas.omnimove.model.JourneyLog;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.StopRepository;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.service.GreenIndexService;
import it.unicas.omnimove.service.JourneyEventService;
import it.unicas.omnimove.service.JourneyPlannerService;
import it.unicas.omnimove.service.RateLimiterService;
import it.unicas.omnimove.service.WeatherService;
import it.unicas.omnimove.service.TrafficAwareETAService;
import it.unicas.omnimove.service.GoogleApiSettingsService;
import it.unicas.omnimove.dto.StopArrivalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import it.unicas.omnimove.client.CassitrackClient;
import it.unicas.omnimove.dto.BusTelemetryDTO;
import it.unicas.omnimove.dto.VehicleDTO;
import it.unicas.omnimove.model.Trip;
import it.unicas.omnimove.repository.TripRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/journeys")
@RequiredArgsConstructor
@Tag(name="Journey Planner", description="Multimodal journey planning")
public class JourneyController {

    private static final Pattern STOP_ID_RE      = Pattern.compile("^[A-Za-z0-9\\-_]{1,50}$");
    private static final int     MAX_ARRIVALS     = 10;

    private final JourneyPlannerService plannerService;
    private final JourneyEventService   journeyEventService;
    private final StopRepository        stopRepository;
    private final JourneyLogRepository  journeyLogRepository;
    private final UserRepository        userRepo;
    private final GreenIndexService     greenIndexService;
    private final RateLimiterService    rateLimiter;
    private final CassitrackClient      cassitrackClient;
    private final TrafficAwareETAService trafficAwareETAService;
    private final GoogleApiSettingsService googleApiSettings;
    private final StringRedisTemplate   redisTemplate;
    private final TripRepository        tripRepository;
    private final ObjectMapper          objectMapper;
    private final WeatherService        weatherService;
    private final it.unicas.omnimove.repository.RouteShapeRepository    routeShapeRepository;
    private final it.unicas.omnimove.repository.ScheduledStopRepository scheduledStopRepository;
    private final it.unicas.omnimove.repository.RouteRepository         routeRepository;

    @GetMapping("/stops")
    @Operation(summary = "List active stops for origin/destination pickers")
    public ResponseEntity<List<Map<String, Object>>> stops() {
        List<Map<String, Object>> result = stopRepository.findByActiveTrue().stream()
                .filter(s -> s.getLat() != null && s.getLon() != null)
                .sorted(Comparator.comparing(Stop::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .limit(500)
                .<Map<String, Object>>map(s -> Map.of(
                        "id",   s.getId(),
                        "name", s.getName() != null ? s.getName() : s.getId(),
                        "lat",  s.getLat(),
                        "lon",  s.getLon()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/weather")
    @Operation(summary = "Current weather condition for the weather pill")
    public ResponseEntity<Map<String, Object>> weather() {
        try {
            var w = weatherService.getCurrentWeather();
            return ResponseEntity.ok(Map.of(
                "condition",   w.condition != null ? w.condition.name() : "CLEAR",
                "temperature", Math.round(w.tempCelsius)
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("condition", "CLEAR", "temperature", 20));
        }
    }

    @PostMapping("/search")
    public ResponseEntity<JourneyResponse> search(
            @RequestBody JourneyRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        if (request.getOriginLat() == null || request.getDestLat() == null)
            return ResponseEntity.badRequest().build();

        if (principal != null && !rateLimiter.allowJourneySearch(principal.getUsername()))
            return ResponseEntity.status(429).build();

        journeyEventService.recordJourneySearchQuery(); // FR-OM-009: count raw searches
        JourneyResponse response = plannerService.plan(request);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/stops/{stopId}/arrivals")
    @Operation(summary = "Next buses at a stop: real-time + scheduled, up to 10")
    public ResponseEntity<?> arrivals(
            @PathVariable String stopId,
            @RequestParam(defaultValue = "10") int limit,
            @AuthenticationPrincipal UserDetails principal) {

        if (!STOP_ID_RE.matcher(stopId).matches())
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid stop ID."));

        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_ARRIVALS);

        if (principal != null && !rateLimiter.allowStopArrivalsLookup(principal.getUsername()))
            return ResponseEntity.status(429).body(Map.of("message", "Too many requests."));

        // 1. Real-time buses from CASSITRACK (GPS-based ETA)
        List<StopArrivalDTO> realTime = cassitrackClient.getArrivalsAtStop(stopId);

        Set<String> coveredTripIds = realTime.stream()
                .map(StopArrivalDTO::getTripId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> coveredVehicleIds = realTime.stream()
                .map(StopArrivalDTO::getVehicleId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 1b. Enrich real-time entries with crowding from /vehicles
        Map<String, String> vehicleCrowding = cassitrackClient.getActiveVehicles().stream()
                .filter(v -> v.getVehicleId() != null && v.getCrowdingLevel() != null)
                .collect(Collectors.toMap(VehicleDTO::getVehicleId, VehicleDTO::getCrowdingLevel,
                        (a, b) -> a));
        realTime.forEach(a -> {
            if (a.getVehicleId() != null && a.getCrowdingLevel() == null)
                a.setCrowdingLevel(vehicleCrowding.get(a.getVehicleId()));
        });

        // 2. Scheduled buses - skip any trip already covered by a live bus
        List<StopArrivalDTO> scheduled = cassitrackClient.getScheduleAtStop(stopId).stream()
                .filter(a -> {
                    if (a.getTripId() != null && coveredTripIds.contains(a.getTripId())) return false;
                    if (a.getVehicleId() != null && coveredVehicleIds.contains(a.getVehicleId())) return false;
                    return true;
                })
                .collect(Collectors.toList());

        // 3. Merge, sort by scheduled time (fall back to ETA), cap at limit
        List<StopArrivalDTO> result = Stream.concat(realTime.stream(), scheduled.stream())
                .filter(a -> a.getEstimatedArrival() != null)
                .sorted(Comparator.comparing(a -> {
                    Instant key = a.getScheduledArrival() != null
                            ? a.getScheduledArrival() : a.getEstimatedArrival();
                    return key != null ? key : java.time.Instant.MAX;
                }))
                .limit(effectiveLimit)
                .collect(Collectors.toList());

        // 4. Delay enrichment, gated by the google.stop_eta flag.
        //    Only departed buses (vehicleId != null) ever carry a delay.
        List<StopArrivalResponse> out;
        if (googleApiSettings.isStopEtaEnabled()) {
            // Google ON: real-time recompute from each bus's live position.
            var enriched = trafficAwareETAService.enrich(stopId, result);
            out = enriched.stream().map(e -> {
                var a = e.arrival();
                boolean departed = a.getVehicleId() != null;
                return StopArrivalResponse.builder()
                        .vehicleId(a.getVehicleId())
                        .routeId(a.getRouteId()).routeName(a.getRouteName())
                        .routeShortName(a.getRouteShortName())
                        .estimatedArrival(e.adjustedArrival())
                        .scheduledArrival(a.getScheduledArrival())
                        .scheduleStatus(a.getScheduleStatus())
                        .crowdingLevel(a.getCrowdingLevel())
                        .departed(departed)
                        .realTime(e.realTime())
                        .delayMinutes(departed ? e.delayMinutes() : null)
                        .delayStopName(a.getDelayStopName())
                        .build();
            }).toList();
        } else {
            // Google OFF: CassiTrack's retrospective delay (C1 notice), departed buses only.
            out = result.stream().map(a -> {
                boolean departed = a.getVehicleId() != null;
                return StopArrivalResponse.builder()
                        .vehicleId(a.getVehicleId())
                        .routeId(a.getRouteId()).routeName(a.getRouteName())
                        .routeShortName(a.getRouteShortName())
                        .estimatedArrival(a.getEstimatedArrival())
                        .scheduledArrival(a.getScheduledArrival())
                        .scheduleStatus(a.getScheduleStatus())
                        .crowdingLevel(a.getCrowdingLevel())
                        .departed(departed)
                        .realTime(false)
                        .delayMinutes(departed ? a.getDelayMinutes() : null)
                        .delayStopName(a.getDelayStopName())
                        .build();
            }).toList();
        }

        return ResponseEntity.ok(out);
    }

    @PostMapping("/select")
    @Operation(summary = "Record a journey mode selection")
    public ResponseEntity<?> select(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal UserDetails principal) {

        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String mode      = (String) body.get("mode");
        String originName = (String) body.get("origin_name");
        String destName   = (String) body.get("dest_name");

        if (mode == null || !java.util.Set.of("BUS", "WALK", "BIKE", "SCOOTER").contains(mode.toUpperCase()))
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid transport mode"));

        double distanceKm = body.get("distance_km") != null ? ((Number) body.get("distance_km")).doubleValue() : 0.0;
        if (distanceKm < 0 || distanceKm > 200)
            return ResponseEntity.badRequest().body(Map.of("message", "Distance out of valid range (0–200 km)"));

        double costEuros = body.get("cost_euros") != null ? ((Number) body.get("cost_euros")).doubleValue() : 0.0;
        if (costEuros < 0 || costEuros > 50)
            return ResponseEntity.badRequest().body(Map.of("message", "Cost out of valid range (0–50 €)"));

        // Always compute server-side — never trust the client-supplied green_index
        int greenIndex  = greenIndexService.computeGreenIndex(mode, distanceKm);
        double co2Grams = greenIndexService.computeCo2Grams(mode, distanceKm);

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        journeyEventService.recordJourneySearch(
                mode, now.getHour(), now.getDayOfWeek().toString(), greenIndex, distanceKm
        );

        journeyLogRepository.save(JourneyLog.builder()
                .userId(user.getId())
                .mode(mode)
                .distanceKm(distanceKm)
                .costEuros(costEuros)
                .co2Grams(co2Grams)
                .greenIndex(greenIndex)
                .originName(originName)
                .destName(destName)
                .createdAt(java.time.ZonedDateTime.now())
                .build());

        return ResponseEntity.ok(Map.of("message", "Journey recorded"));
    }

    /**
     * GET /api/v1/journeys/live-buses?route_ids=LINEA-16,LINEA-3
     *
     * Returns live positions of active buses, optionally filtered by route.
     *
     * Data source: OmniMove's own Redis cache (bus:latest:*), which is populated
     * every ~5 seconds via the SSE stream from CassiTrack. Route resolution is
     * done against OmniMove's own NeTEx DB (trips → route) — no CassiTrack HTTP
     * call needed, so this always works even if CassiTrack REST API is slow.
     *
     * A bus is considered stale if its GPS timestamp is older than 5 minutes.
     */
    @GetMapping("/live-buses")
    @Operation(summary = "Live bus positions for a set of routes")
    public ResponseEntity<List<VehicleDTO>> liveBuses(
            @RequestParam(name = "route_ids", required = false, defaultValue = "") String routeIds) {

        Set<String> filter = Stream.of(routeIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        Set<String> keys = redisTemplate.keys("bus:latest:*");
        if (keys == null || keys.isEmpty()) return ResponseEntity.ok(Collections.emptyList());

        Instant staleThreshold = Instant.now().minusSeconds(300); // 5 min
        List<VehicleDTO> vehicles = new ArrayList<>();

        for (String key : keys) {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) continue;
            try {
                BusTelemetryDTO t = objectMapper.readValue(json, BusTelemetryDTO.class);

                // Skip stale data
                if (t.getTimestamp() != null && t.getTimestamp().isBefore(staleThreshold)) continue;

                // Resolve route from OmniMove's own NeTEx DB via tripId
                String routeId   = null;
                String routeName = null;
                if (t.getTripId() != null) {
                    Optional<Trip> tripOpt = tripRepository.findById(t.getTripId());
                    if (tripOpt.isPresent() && tripOpt.get().getRoute() != null) {
                        routeId   = tripOpt.get().getRoute().getId();
                        routeName = tripOpt.get().getRoute().getShortName();
                    }
                }

                // Apply route filter (no filter = show all active buses)
                if (!filter.isEmpty() && (routeId == null || !filter.contains(routeId))) continue;

                VehicleDTO dto = new VehicleDTO();
                dto.setVehicleId(t.getBusId());
                dto.setLat(t.getLatitude() != 0 ? (double) t.getLatitude() : null);
                dto.setLon(t.getLongitude() != 0 ? (double) t.getLongitude() : null);
                dto.setSpeedKmh((double) t.getSpeed());
                dto.setRouteId(routeId);
                dto.setRouteName(routeName);
                dto.setDelayMinutes(t.getDelay());
                dto.setNextStopName(t.getNextStop());
                dto.setIsActive(true);
                dto.setLastSeen(t.getTimestamp());
                vehicles.add(dto);

            } catch (Exception e) {
                // log and skip malformed entries
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn("Failed to parse Redis key {}: {}", key, e.getMessage());
            }
        }

        return ResponseEntity.ok(vehicles);
    }

    /**
     * GET /api/v1/journeys/routes/{routeId}/stops
     *
     * Ordered stop list for a route — used by the vertical stop-list panel.
     * Returns each stop with its name, coords, and which route short-names serve it.
     */
    @GetMapping("/routes/{routeId}/stops")
    @Operation(summary = "Ordered stop list for a route with serving lines")
    public ResponseEntity<List<Map<String, Object>>> routeStops(
            @PathVariable String routeId) {

        if (!STOP_ID_RE.matcher(routeId).matches())
            return ResponseEntity.badRequest().build();

        // Get the representative ordered stop sequence for this route
        var allStops = scheduledStopRepository.findStopsForRoute(routeId);
        if (allStops.isEmpty()) return ResponseEntity.notFound().build();

        // De-duplicate by stopId preserving first-seen order (handles ring routes)
        java.util.LinkedHashMap<String, it.unicas.omnimove.model.ScheduledStop> seen = new java.util.LinkedHashMap<>();
        for (var ss : allStops) {
            seen.putIfAbsent(ss.getStopId(), ss);
        }

        List<Map<String, Object>> result = seen.values().stream().map(ss -> {
            String stopId = ss.getStopId();
            var stop = stopRepository.findById(stopId).orElse(null);
            List<String> lines = scheduledStopRepository.findRouteShortNamesByStopId(stopId)
                    .stream().sorted().toList();
            return Map.<String, Object>of(
                    "stop_id",  stopId,
                    "name",     stop != null && stop.getName() != null ? stop.getName() : stopId,
                    "lat",      stop != null && stop.getLat()  != null ? stop.getLat()  : 0.0,
                    "lon",      stop != null && stop.getLon()  != null ? stop.getLon()  : 0.0,
                    "lines",    lines
            );
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/journeys/routes/{routeId}/shape
     *
     * Full road geometry for a route, used to draw it on the map when a user
     * taps a bus card in the next-buses sheet.
     *
     * Each point is [lat, lon, isStop] — isStop=true marks vertices that are
     * scheduled stops, so the frontend can place dots only there.
     */
    @GetMapping("/routes/{routeId}/shape")
    @Operation(summary = "Full route shape with stop flags")
    public ResponseEntity<List<List<Object>>> routeShape(
            @PathVariable String routeId) {

        if (!STOP_ID_RE.matcher(routeId).matches())
            return ResponseEntity.badRequest().build();

        var points = routeShapeRepository.findByRouteIdOrderBySeqAsc(routeId);
        if (points.isEmpty()) return ResponseEntity.notFound().build();

        List<List<Object>> result = points.stream()
                .map(p -> List.<Object>of(p.getLat(), p.getLon(), Boolean.TRUE.equals(p.getIsStop())))
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/v1/journeys/routes/shapes
     *
     * Geometry of the whole network in a single call.
     *
     * The traveller map draws every line the moment it opens, so asking for one
     * route at a time would mean a request per line before the map is readable.
     * The payload is small — a few hundred vertices per line — and static enough
     * that the client fetches it once per session.
     *
     * Each entry is {route_id, short_name, long_name, points:[[lat, lon, isStop], ...]}.
     */
    @GetMapping("/routes/shapes")
    @Operation(summary = "Road geometry of every active route, for the network overview map")
    public ResponseEntity<List<Map<String, Object>>> allRouteShapes() {

        // Group the flat vertex table back into one path per route. The query is
        // already ordered by (route_id, seq), so a LinkedHashMap keeps both the
        // route order and the drawing order without a second sort.
        java.util.LinkedHashMap<String, List<List<Object>>> byRoute = new java.util.LinkedHashMap<>();
        for (var p : routeShapeRepository.findAllByOrderByRouteIdAscSeqAsc()) {
            byRoute.computeIfAbsent(p.getRouteId(), k -> new ArrayList<>())
                   .add(List.<Object>of(p.getLat(), p.getLon(), Boolean.TRUE.equals(p.getIsStop())));
        }
        if (byRoute.isEmpty()) return ResponseEntity.ok(List.of());

        // Route rows arrive at runtime from the NeTEx import, so geometry can
        // outlive its route: fall back to the id rather than dropping the line.
        Map<String, it.unicas.omnimove.model.Route> routes = routeRepository.findAll().stream()
                .collect(Collectors.toMap(it.unicas.omnimove.model.Route::getId, r -> r, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        byRoute.forEach((routeId, points) -> {
            var route = routes.get(routeId);
            if (route != null && !route.isActive()) return;   // retired line: not on the map
            String shortName = route != null && route.getShortName() != null
                    ? route.getShortName() : routeId;
            String longName  = route != null && route.getLongName() != null
                    ? route.getLongName() : "";

            // A LinkedHashMap, not Map.of: the colour is nullable for a line
            // CassiTrack never gave one, and Map.of throws on a null value.
            // Sending the key as null lets the client tell "no colour set" from
            // a real colour and fall back to its own.
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("route_id",   routeId);
            entry.put("short_name", shortName);
            entry.put("long_name",  longName);
            entry.put("color",      route != null ? route.getColor()     : null);
            entry.put("text_color", route != null ? route.getTextColor() : null);
            entry.put("points",     points);
            result.add(entry);
        });

        return ResponseEntity.ok(result);
    }
}