package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.JourneyRequest;
import it.unicas.omnimove.dto.JourneyResponse;
import it.unicas.omnimove.dto.StopArrivalDTO;
import it.unicas.omnimove.model.JourneyLog;
import it.unicas.omnimove.model.Route;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.StopRepository;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;
import it.unicas.omnimove.service.BikeSharingService;
import it.unicas.omnimove.service.GreenIndexService;
import it.unicas.omnimove.service.JourneyEventService;
import it.unicas.omnimove.service.JourneyPlannerService;
import it.unicas.omnimove.service.RateLimiterService;
import it.unicas.omnimove.service.WeatherService;
import it.unicas.omnimove.service.TrafficAwareETAService;
import it.unicas.omnimove.service.GoogleApiSettingsService;
import it.unicas.omnimove.dto.StopArrivalResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import it.unicas.omnimove.repository.ScheduledStopRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
@Slf4j
@Tag(name="Journey Planner", description="Multimodal journey planning")
public class JourneyController {

    /**
     * Suffix marking the return direction of a line.
     *
     * <p>CassiTrack's V26 split each line's return runs onto a route of their
     * own — {@code LINEA_16_R} beside {@code LINEA_16} — because the stop
     * pattern introduced by V27 allows a route only one sequence of stops, and
     * a line that goes there and back has two.
     *
     * <p>That is a storage decision and the traveller should not meet it: to
     * them line 16 is line 16, both ways. So the return routes are folded back
     * into their outbound line here — hidden from the pickers, read together
     * with it in the timetable — and the direction is worked out the way it
     * always was, from the terminus of each run.
     */
    private static final String RETURN_SUFFIX = "_R";

    private static boolean isReturnRoute(String routeId) {
        return routeId != null && routeId.endsWith(RETURN_SUFFIX);
    }

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
    private final it.unicas.omnimove.service.GoogleMapsService googleMapsService;
    private final StringRedisTemplate   redisTemplate;
    private final TripRepository        tripRepository;
    private final ObjectMapper          objectMapper;
    private final WeatherService        weatherService;
    private final it.unicas.omnimove.repository.RouteShapeRepository    routeShapeRepository;
    private final it.unicas.omnimove.repository.ScheduledStopRepository scheduledStopRepository;
    private final it.unicas.omnimove.repository.RouteRepository         routeRepository;
    private final BikeSharingService    bikeSharingService;

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

    @GetMapping("/geocode/reverse")
    @Operation(summary = "The street a point sits on",
               description = "For a place tapped on the map. Answers with an empty "
                           + "street when geocoding is off or nothing is found — the "
                           + "caller falls back to the coordinates.")
    public ResponseEntity<?> reverseGeocode(@RequestParam double lat,
                                            @RequestParam double lon,
                                            @AuthenticationPrincipal UserDetails principal) {

        // Coordinates outside the world are a client bug, not a place
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
            return ResponseEntity.badRequest().body(Map.of("message", "Coordinates out of range."));

        // Every tap costs a Google call, so the same bucket that guards the
        // arrivals lookup guards this one
        if (principal != null && !rateLimiter.allowStopArrivalsLookup(principal.getUsername()))
            return ResponseEntity.status(429).body(Map.of("message", "Too many requests."));

        String street = googleMapsService.reverseGeocodeStreet(lat, lon).orElse("");
        return ResponseEntity.ok(Map.of("street", street));
    }

    @GetMapping("/timetable/routes")
    @Operation(summary = "Lines that have a timetable")
    public ResponseEntity<List<Map<String, Object>>> timetableRoutes() {
        List<Map<String, Object>> result = routeRepository.findAll().stream()
                .filter(Route::isActive)
                // The return route carries the same number and colour as its
                // outbound: listing both would put two identical "16" chips in
                // the picker. Its runs are shown inside the outbound's timetable.
                .filter(r -> !isReturnRoute(r.getId()))
                .sorted(Comparator.comparing(r -> r.getShortName() != null ? r.getShortName() : r.getId(),
                                             String.CASE_INSENSITIVE_ORDER))
                .<Map<String, Object>>map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("routeId",    r.getId());
                    m.put("routeShort", r.getShortName() != null ? r.getShortName() : r.getId());
                    m.put("routeLong",  r.getLongName());
                    m.put("color",      r.getColor());
                    m.put("textColor",  r.getTextColor());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/timetable/routes/{routeId}")
    @Operation(summary = "Timetable grid for one line",
               description = "Stops down the side, runs across the top, times at the "
                           + "intersections — the layout of a printed timetable. One "
                           + "block per direction.")
    public ResponseEntity<?> routeTimetable(@PathVariable String routeId) {

        if (!STOP_ID_RE.matcher(routeId).matches())
            return ResponseEntity.badRequest().build();

        Route route = routeRepository.findById(routeId).orElse(null);
        if (route == null || !route.isActive()) return ResponseEntity.notFound().build();

        // Both halves of the line. The return runs live on their own route since
        // V26, but a timetable that showed one direction only would be a
        // regression for the traveller, who had both before the split.
        var calls = new ArrayList<>(scheduledStopRepository.findCallsForRoute(routeId));
        calls.addAll(scheduledStopRepository.findCallsForRoute(routeId + RETURN_SUFFIX));
        if (calls.isEmpty()) return ResponseEntity.notFound().build();

        // Rows arrive grouped by trip and ordered by sequence, so one pass rebuilds
        // each run in the order it is actually driven
        Map<String, List<ScheduledStopRepository.RouteCall>> byTrip = new LinkedHashMap<>();
        for (var c : calls)
            byTrip.computeIfAbsent(c.getTripId(), k -> new ArrayList<>()).add(c);

        // A line usually runs two ways, and mixing them into one table would put
        // times in the wrong order down the column. The terminus is what tells
        // them apart — it is also what the traveller reads on the bus.
        Map<String, List<List<ScheduledStopRepository.RouteCall>>> byDirection = new LinkedHashMap<>();
        for (var trip : byTrip.values()) {
            String terminus = trip.get(trip.size() - 1).getStopId();
            byDirection.computeIfAbsent(terminus, k -> new ArrayList<>()).add(trip);
        }

        Map<String, String> stopNames = new HashMap<>();
        for (Stop st : stopRepository.findAll())
            stopNames.put(st.getId(), st.getName() != null ? st.getName() : st.getId());

        List<Map<String, Object>> directions = new ArrayList<>();
        for (var entry : byDirection.entrySet()) {
            List<List<ScheduledStopRepository.RouteCall>> trips = entry.getValue();

            // The run calling at most stops defines the rows; a short working that
            // skips a few then leaves those cells empty rather than shifting the
            // whole column up by one stop.
            List<ScheduledStopRepository.RouteCall> longest = trips.stream()
                    .max(Comparator.comparingInt(List::size)).orElse(trips.get(0));
            List<String> stopOrder = longest.stream()
                    .map(ScheduledStopRepository.RouteCall::getStopId).toList();
            Map<String, Integer> rowOf = new HashMap<>();
            for (int i = 0; i < stopOrder.size(); i++) rowOf.putIfAbsent(stopOrder.get(i), i);

            // Columns read left to right in departure order, like a printed table
            trips.sort(Comparator.comparingInt(t -> t.get(0).getSeconds()));

            List<Map<String, Object>> runs = new ArrayList<>();
            for (var trip : trips) {
                Integer[] times = new Integer[stopOrder.size()];
                for (var c : trip) {
                    Integer row = rowOf.get(c.getStopId());
                    if (row != null) times[row] = c.getSeconds();
                }
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("tripId", trip.get(0).getTripId());
                run.put("times",  Arrays.asList(times));
                runs.add(run);
            }

            List<Map<String, Object>> stops = stopOrder.stream()
                    .<Map<String, Object>>map(id -> Map.of(
                            "stopId", id,
                            "name",   stopNames.getOrDefault(id, id)))
                    .toList();

            Map<String, Object> dir = new LinkedHashMap<>();
            dir.put("headsign", stopNames.getOrDefault(entry.getKey(), entry.getKey()));
            dir.put("stops",    stops);
            dir.put("runs",     runs);
            directions.add(dir);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("routeId",    route.getId());
        body.put("routeShort", route.getShortName() != null ? route.getShortName() : route.getId());
        body.put("routeLong",  route.getLongName());
        body.put("color",      route.getColor());
        body.put("textColor",  route.getTextColor());
        body.put("directions", directions);
        return ResponseEntity.ok(body);
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

    /**
     * Plans a journey, and says why when it cannot.
     *
     * <p>Every failure used to leave here as a bare status with no body: a
     * missing coordinate and a planner that threw were both indistinguishable
     * from the outside, and the browser could only report "could not load
     * routes". Each one now carries an {@code error} code and a sentence, and a
     * planner failure is logged with the search that caused it — which is the
     * difference between a bug report that can be acted on and one that says
     * only that something went wrong.
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(
            @RequestBody JourneyRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        if (request.getOriginLat() == null || request.getDestLat() == null)
            return ResponseEntity.badRequest().body(Map.of(
                    "error",   "MISSING_ENDPOINT",
                    "message", "Both an origin and a destination are needed to plan a journey."));

        if (principal != null && !rateLimiter.allowJourneySearch(principal.getUsername()))
            return ResponseEntity.status(429).body(Map.of(
                    "error",   "RATE_LIMITED",
                    "message", "Too many searches in the last hour. Try again shortly."));

        // FR-OM-009: count raw searches — but only the ones a person asked for.
        // The results screen re-plans itself while it is open so the departure
        // times stay true, and those repeats are the same question, not new ones.
        if (!request.isRefresh())
            journeyEventService.recordJourneySearchQuery();

        try {
            return ResponseEntity.ok(plannerService.plan(request));

        } catch (Exception e) {
            // The search itself, in the log line: without the ends and the modes
            // a stack trace says a plan failed but not which one, and the first
            // question anyone asks is "with which filters?".
            log.error("Journey search failed: {} -> {} modes={} arriveBy={} : {}",
                    request.getOriginName(), request.getDestName(),
                    request.getModes(), request.isArriveBy(), e.toString(), e);

            return ResponseEntity.internalServerError().body(Map.of(
                    "error",   "PLANNER_FAILED",
                    "message", "The journey could not be planned. Please try again.",
                    // Echoed back so a report from the field identifies the case
                    // without anyone having to reproduce it first.
                    "modes",   request.getModes() == null ? List.of() : request.getModes()));
        }
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

    private static final java.util.Set<String> SIMPLE_MODES =
            java.util.Set.of("BUS", "WALK", "BIKE", "SCOOTER");

    /**
     * A combined journey arrives as its pieces joined by underscores — BUS_BIKE,
     * SCOOTER_BUS — so every piece has to be a mode we know. Checking the whole
     * string against a fixed list rejected exactly the trips the planner had just
     * worked hardest to find: the traveller pressed Start Journey, the server
     * answered 400, and the eco score and the route history never moved.
     */
    private static boolean isKnownMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        // A string of nothing but separators splits into no parts at all, and a
        // loop over nothing would report it valid.
        String[] parts = mode.toUpperCase().split("_");
        if (parts.length == 0) return false;
        for (String part : parts) {
            if (!SIMPLE_MODES.contains(part)) return false;
        }
        return true;
    }

    /** Kilometres accepted only within a tenth of the distance already declared. */
    private static final double SPLIT_TOLERANCE = 0.10;

    /**
     * The per-mode kilometres, or null when they cannot be trusted to describe the
     * journey that was declared: a mode the trip never claimed, or a total that
     * disagrees with the distance. Walking is always allowed — every journey has
     * some, and it emits nothing either way.
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Double> usableSplit(Object raw, String mode, double distanceKm) {
        if (!(raw instanceof Map) || ((Map<?, ?>) raw).isEmpty() || distanceKm <= 0) return null;

        java.util.Set<String> declared = new java.util.HashSet<>(
                java.util.List.of(mode.toUpperCase().split("_")));
        declared.add("WALK");

        java.util.Map<String, Double> out = new java.util.HashMap<>();
        double sum = 0;
        for (var e : ((Map<String, Object>) raw).entrySet()) {
            String m = e.getKey() == null ? "" : e.getKey().toUpperCase();
            if (!declared.contains(m)) return null;
            if (!(e.getValue() instanceof Number n)) return null;
            double km = n.doubleValue();
            if (km < 0) return null;
            out.merge(m, km, Double::sum);
            sum += km;
        }
        if (Math.abs(sum - distanceKm) > distanceKm * SPLIT_TOLERANCE) return null;
        return out;
    }

    /** Longest itinerary worth believing, in minutes: a day of travel inside a city. */
    private static final int MAX_DURATION_MIN = 600;

    /**
     * The client's duration, or null when it cannot be believed. A journey that
     * takes no time, negative time, or longer than {@link #MAX_DURATION_MIN} did
     * not happen as described, and an average is easier to poison than a total.
     */
    private static Integer readDuration(Object raw) {
        if (!(raw instanceof Number n)) return null;
        double v = n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        int minutes = (int) Math.round(v);
        return (minutes > 0 && minutes <= MAX_DURATION_MIN) ? minutes : null;
    }

    /**
     * How many moving pieces the itinerary has, counted from the per-mode
     * kilometres the client sent and falling back to the chain's own name.
     *
     * <p>The fallback matters: a chain always names its modes, so a combined
     * journey is never recorded as a single leg even when the split is missing
     * or unusable.
     */
    private static int countLegs(Object rawLegsKm, String mode) {
        int named = mode == null ? 1 : mode.split("_").length;
        if (!(rawLegsKm instanceof Map<?, ?> m)) return named;
        int moving = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String k)) continue;
            String up = k.toUpperCase();
            if ("WAIT".equals(up) || "TRANSFER".equals(up)) continue;
            if (e.getValue() instanceof Number n && n.doubleValue() > 0) moving++;
        }
        return Math.max(moving, named);
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

        if (!isKnownMode(mode))
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid transport mode"));

        double distanceKm = body.get("distance_km") != null ? ((Number) body.get("distance_km")).doubleValue() : 0.0;
        if (distanceKm < 0 || distanceKm > 200)
            return ResponseEntity.badRequest().body(Map.of("message", "Distance out of valid range (0–200 km)"));

        double costEuros = body.get("cost_euros") != null ? ((Number) body.get("cost_euros")).doubleValue() : 0.0;
        if (costEuros < 0 || costEuros > 50)
            return ResponseEntity.badRequest().body(Map.of("message", "Cost out of valid range (0–50 €)"));

        // Emissions are always computed here, never taken from the client. What the
        // client may supply is how its kilometres split between modes, because a
        // combined trip cannot be scored without that: charging every kilometre to
        // the dirtiest leg recorded a bus-and-scooter journey as pure bus. The
        // split is used only when it agrees with the mode that was declared and
        // with the distance that was declared — it can shift kilometres between
        // the modes of a trip, it cannot invent a trip that was not claimed.
        java.util.Map<String, Double> split = usableSplit(body.get("legs_km"), mode, distanceKm);

        double co2Grams = split != null
                ? greenIndexService.computeCo2Grams(split)
                : greenIndexService.computeCo2Grams(mode, distanceKm);
        int greenIndex = greenIndexService.greenIndexFor(co2Grams, distanceKm);

        // How long the accepted itinerary was expected to take. Rejecting a bad
        // value would stop a traveller from starting a journey over a statistic,
        // so an unusable duration is simply not recorded — the trip is written
        // without one and the averages leave it out.
        Integer durationMinutes = readDuration(body.get("duration_minutes"));

        // Moving pieces only: waiting for a bus and changing at a stop take time
        // but cover no ground, and counting them would make every bus trip look
        // like a chain. Falls back to the number of modes named in the chain when
        // the client sent no legs at all.
        int legs = countLegs(body.get("legs_km"), mode);

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        journeyEventService.recordJourneySearch(
                mode, now.getHour(), now.getDayOfWeek().toString(), greenIndex, distanceKm,
                durationMinutes, costEuros, legs
        );

        journeyLogRepository.save(JourneyLog.builder()
                .userId(user.getId())
                .mode(mode)
                .distanceKm(distanceKm)
                .costEuros(costEuros)
                .co2Grams(co2Grams)
                .greenIndex(greenIndex)
                .durationMinutes(durationMinutes)
                .originName(originName)
                .destName(destName)
                // Kept so the trip can be replayed even when an end was a point
                // on the map, which no stop name can resolve back to, and so the
                // journey can later be generalised to a zone for research.
                // Out-of-area or malformed values are stored as null rather than
                // rejected: a bad coordinate must not stop a traveller from
                // starting a journey, it just keeps the row out of research.
                .originLat(serviceAreaCoord(body.get("origin_lat"), true))
                .originLon(serviceAreaCoord(body.get("origin_lon"), false))
                .destLat(serviceAreaCoord(body.get("dest_lat"), true))
                .destLon(serviceAreaCoord(body.get("dest_lon"), false))
                .createdAt(java.time.ZonedDateTime.now())
                .build());

        return ResponseEntity.ok(Map.of("message", "Journey recorded"));
    }

    /**
     * Accepts a client-supplied coordinate only if it falls inside the service
     * area, otherwise returns null.
     *
     * <p>The bounds match {@code research.zone_of} in V23: a point outside them
     * would end up alone in its own grid cell, which singles the traveller out
     * instead of generalising them. Keeping the two in step matters — if one day
     * the service area grows, both have to change together.
     */
    private static Double serviceAreaCoord(Object raw, boolean isLatitude) {
        if (!(raw instanceof Number n)) return null;
        double v = n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        boolean inside = isLatitude ? (v >= 41.0 && v <= 42.0)
                                    : (v >= 13.0 && v <= 14.5);
        return inside ? v : null;
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
            // The return route's geometry is the outbound's, travelled the other
            // way: drawing both would stack two identical lines on the map and
            // put the same number twice in the legend.
            if (isReturnRoute(routeId)) return;
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

        // Route ids sort as text, so the shapes arrived in the order LINEA_08,
        // LINEA_1, LINEA_10, LINEA_11I… and the map legend listed 08, 18, 10,
        // 12, 11. Sorting here rather than in the client keeps every consumer
        // of this endpoint in the same, obvious order.
        result.sort((a, b) -> compareLineLabel((String) a.get("short_name"),
                                               (String) b.get("short_name")));

        return ResponseEntity.ok(result);
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

    /**
     * GET /api/v1/journeys/bikes
     *
     * Available Elerent shared bikes/scooters around Cassino, for the
     * traveller map. Data comes from BikeSharingService (60 s cache over
     * the RideAtom API, or the mock provider when no key is configured).
     * Read-only: never errors, at worst returns [].
     */
    @GetMapping("/bikes")
    @Operation(summary = "Available shared bikes/scooters (Elerent) around Cassino")
    public ResponseEntity<List<BikeVehicleDTO>> bikes() {
        try {
            return ResponseEntity.ok(bikeSharingService.getAvailableBikes());
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * GET /api/v1/journeys/bikes/zones
     *
     * Operating / no-parking zones of the bike-sharing service.
     */
    @GetMapping("/bikes/zones")
    @Operation(summary = "Bike-sharing operating and parking zones")
    public ResponseEntity<List<BikeZoneDTO>> bikeZones() {
        try {
            return ResponseEntity.ok(bikeSharingService.getZones());
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }
}