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
import it.unicas.omnimove.dto.VehicleDTO;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
}