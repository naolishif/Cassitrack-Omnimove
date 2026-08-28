package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fleet analytics endpoints for the CASSITRACK manager dashboard.
 *
 * All endpoints accept optional filter params:
 *   startTime  — ISO-8601 instant (e.g. 2026-06-23T00:00:00Z)
 *   endTime    — ISO-8601 instant
 *   routeIds   — comma-separated route IDs (e.g. LINEA-16,LINEA-9)
 *   busId      — single vehicle ID
 *   groupBy    — "hour" (default) or "day"
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('FLEET_MANAGER', 'ROLE_FLEET_MANAGER')")
@Tag(name = "Analytics", description = "Fleet manager dashboard data")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    // ── Existing endpoints (now with optional filter params) ──────────────────

    @GetMapping("/summary")
    @Operation(summary = "Today's fleet summary",
        description = "Active buses, trips today, on-time percentage (live)")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(analyticsService.getSummary());
    }

    @GetMapping("/adherence")
    @Operation(summary = "Schedule adherence breakdown",
        description = "On-time / late / early counts per bus (live)")
    public ResponseEntity<Map<String, Object>> getAdherence() {
        return ResponseEntity.ok(analyticsService.getAdherenceBreakdown());
    }

    @GetMapping("/busiest-hours")
    @Operation(summary = "Busiest hours of the day",
        description = "GPS reports per hour of day; filter by startTime/endTime/busId")
    public ResponseEntity<Map<String, Object>> getBusiestHours(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String busId) {
        validateAnalyticsParams(startTime, endTime, busId, null, Collections.emptyList());
        return ResponseEntity.ok(analyticsService.getBusiestHours(startTime, endTime, busId));
    }

    @GetMapping("/passengers-by-route")
    @Operation(summary = "Passengers per route per time slot",
        description = "groupBy=hour (default) or day; filter by startTime/endTime/routeIds/busId")
    public ResponseEntity<Map<String, Object>> getPassengersByRoute(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String routeIds,
            @RequestParam(required = false) String busId,
            @RequestParam(required = false, defaultValue = "hour") String groupBy) {
        List<String> routes = parseRouteIds(routeIds);
        validateAnalyticsParams(startTime, endTime, busId, groupBy, routes);
        return ResponseEntity.ok(
            analyticsService.getPassengersByRouteAndHour(startTime, endTime, routes, busId, groupBy));
    }

    @GetMapping("/delay-by-route")
    @Operation(summary = "Average delay per route per time slot",
        description = "groupBy=hour (default) or day; filter by startTime/endTime/routeIds/busId")
    public ResponseEntity<Map<String, Object>> getDelayByRoute(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String routeIds,
            @RequestParam(required = false) String busId,
            @RequestParam(required = false, defaultValue = "hour") String groupBy) {
        List<String> routes = parseRouteIds(routeIds);
        validateAnalyticsParams(startTime, endTime, busId, groupBy, routes);
        return ResponseEntity.ok(
            analyticsService.getDelayByRouteAndHour(startTime, endTime, routes, busId, groupBy));
    }

    @GetMapping("/network")
    @Operation(summary = "Everything the Analytics dashboard needs, in one call",
        description = "Active lines, average delay and its change vs the previous window, "
                    + "buses on road per line (live), delay by weekday, occupancy by hour "
                    + "and delay by line. Filter by startTime/endTime/busId.")
    public ResponseEntity<Map<String, Object>> getNetworkOverview(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String busId) {
        validateAnalyticsParams(startTime, endTime, busId, null, Collections.emptyList());
        return ResponseEntity.ok(analyticsService.getNetworkOverview(startTime, endTime, busId));
    }

    // ── New endpoints ─────────────────────────────────────────────────────────

    @GetMapping("/co2")
    @Operation(summary = "CO2 saved vs private cars",
        description = "Real calculation: passenger-km × (170 - 68) gCO2/km ÷ 1000, aligned with OmniMove GreenIndex")
    public ResponseEntity<Map<String, Object>> getCo2(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String routeIds,
            @RequestParam(required = false) String busId) {
        List<String> routes = parseRouteIds(routeIds);
        validateAnalyticsParams(startTime, endTime, busId, null, routes);
        return ResponseEntity.ok(analyticsService.getCo2Saved(startTime, endTime, routes, busId));
    }

    @GetMapping("/operating-hours")
    @Operation(summary = "Operating hours per route from schedule",
        description = "Returns firstHour/lastHour derived from scheduled_stops table")
    public ResponseEntity<Map<String, Object>> getOperatingHours() {
        return ResponseEntity.ok(analyticsService.getOperatingHours());
    }

    @GetMapping("/routes")
    @Operation(summary = "All routes for filter dropdowns")
    public ResponseEntity<List<Map<String, Object>>> getRoutes() {
        return ResponseEntity.ok(analyticsService.getRoutes());
    }

    @GetMapping("/routes-map")
    @Operation(summary = "Routes with ordered stops for map rendering",
        description = "Returns each active route with its stops in schedule order (from scheduled_stops table)")
    public ResponseEntity<List<Map<String, Object>>> getRoutesMap() {
        return ResponseEntity.ok(analyticsService.getRoutesWithStops());
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private List<String> parseRouteIds(String routeIds) {
        if (routeIds == null || routeIds.isBlank()) return Collections.emptyList();
        return Arrays.asList(routeIds.split(","));
    }

    // ── Input validation (A03 — Flux injection prevention) ───────────────────

    // ISO-8601 UTC instant: 2026-06-23T00:00:00Z
    private static final Pattern ISO_INSTANT = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");

    // Vehicle / route IDs: alphanumeric, underscore, hyphen, dot — max 100 chars
    private static final Pattern SAFE_ID = Pattern.compile(
        "^[A-Za-z0-9_\\-\\.]{1,100}$");

    private static final Set<String> VALID_GROUP_BY = Set.of("hour", "day");

    private void validateAnalyticsParams(String startTime, String endTime,
                                         String busId, String groupBy,
                                         List<String> routeIds) {
        if (startTime != null && !startTime.isBlank() && !ISO_INSTANT.matcher(startTime).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid startTime format");
        if (endTime != null && !endTime.isBlank() && !ISO_INSTANT.matcher(endTime).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid endTime format");
        if (busId != null && !busId.isBlank() && !SAFE_ID.matcher(busId).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid busId format");
        if (groupBy != null && !VALID_GROUP_BY.contains(groupBy))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupBy must be 'hour' or 'day'");
        if (routeIds != null) {
            for (String id : routeIds) {
                if (!id.isBlank() && !SAFE_ID.matcher(id.trim()).matches())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid routeId: " + id);
            }
        }
    }
}
