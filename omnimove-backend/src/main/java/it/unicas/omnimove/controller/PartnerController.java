package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.JourneyLeg;
import it.unicas.omnimove.dto.JourneyOption;
import it.unicas.omnimove.dto.JourneyRequest;
import it.unicas.omnimove.dto.JourneyResponse;
import it.unicas.omnimove.model.ApiClient;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.repository.StopRepository;
import it.unicas.omnimove.service.GreenIndexService;
import it.unicas.omnimove.service.JourneyPlannerService;
import it.unicas.omnimove.service.RateLimiterService;
import it.unicas.omnimove.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * What OmniMove offers to the other systems of the Cassino ecosystem.
 *
 * <p>Three services, all read-only and none touching a traveller's data:
 * <ul>
 *   <li><b>reachability</b> — given two points, how long it takes to get
 *       there on the network and with which departure: the planner without
 *       an account behind it;</li>
 *   <li><b>co2</b> — the carbon accounting of a trip, with the same factors
 *       the traveller app uses, so every system in the ecosystem measures
 *       with one ruler;</li>
 *   <li><b>stops/nearby</b> — the stops around a point, for a matching
 *       engine that needs to know where a journey can start.</li>
 * </ul>
 *
 * <p>Every call is authenticated by {@link it.unicas.omnimove.security.ApiKeyFilter}
 * and rate-limited per key: reachability like a journey search (it is one,
 * Google bill included), the rest more loosely.
 */
@RestController
@RequestMapping("/api/partner/v1")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('PARTNER')")
@Tag(name = "Partner", description = "Services for partner systems — X-Api-Key required")
public class PartnerController {

    private static final Set<String> MODES    = Set.of("BUS", "WALK", "BIKE", "SCOOTER");
    private static final Set<String> RANKINGS = Set.of("FAST", "BUDGET", "ECO");
    private static final int NEARBY_MAX_RADIUS_M = 3000;
    private static final int NEARBY_MAX_LIMIT    = 20;

    private final JourneyPlannerService planner;
    private final GreenIndexService greenIndex;
    private final StopRepository stopRepository;
    private final RateLimiterService rateLimiter;

    @Value("${elerent.api.centre-lat:41.4901}") private double centreLat;
    @Value("${elerent.api.centre-lon:13.8303}") private double centreLon;
    @Value("${elerent.api.radius-km:5}")        private int    radiusKm;

    // ── Request bodies ─────────────────────────────────────────────────────

    public record Point(Double lat, Double lon, String name) {}

    /**
     * @param departure_time "HH:mm" Europe/Rome; absent = now
     * @param modes          subset of BUS, WALK, BIKE, SCOOTER; absent = BUS and WALK
     * @param ranking        FAST (default), BUDGET or ECO
     */
    public record ReachabilityRequest(Point origin, Point destination,
                                      String departure_time, List<String> modes,
                                      String ranking, String lang) {}

    public record Co2Leg(String mode, Double km) {}
    public record Co2Request(List<Co2Leg> legs) {}

    // ── GET /meta ──────────────────────────────────────────────────────────

    @GetMapping("/meta")
    @Operation(summary = "Coverage, conventions and the emission factors in force")
    public ResponseEntity<?> meta(@AuthenticationPrincipal ApiClient client) {
        if (!rateLimiter.allowPartnerCall(client.getId())) return tooMany();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "OmniMove Partner API");
        out.put("version", "v1");
        out.put("city", "Cassino (FR), Italy");
        out.put("coverage", Map.of("centre", Map.of("lat", centreLat, "lon", centreLon),
                                   "radius_km", radiusKm));
        out.put("conventions", Map.of(
                "coordinates", "WGS84 decimal degrees, lat/lon",
                "timestamps",  "ISO-8601 UTC; departure_time is HH:mm Europe/Rome",
                "route_ids",   "line number, '_R' suffix for the return direction",
                "modes",       List.of("BUS", "WALK", "BIKE", "SCOOTER")));
        out.put("emission_factors_g_per_pax_km", greenIndex.emissionFactors());
        out.put("car_baseline_g_per_km", greenIndex.carBaselineGramsPerKm());
        out.put("green_index", "100 - (co2 / co2_of_same_trip_by_car) * 100, clamped to 0..100");
        out.put("docs", "/omnimove/api/docs/partner");
        return ResponseEntity.ok(out);
    }

    // ── GET /stops/nearby ──────────────────────────────────────────────────

    @GetMapping("/stops/nearby")
    @Operation(summary = "Active stops within a radius of a point, nearest first")
    public ResponseEntity<?> stopsNearby(@RequestParam("lat") double lat,
                                         @RequestParam("lon") double lon,
                                         @RequestParam(name = "radius_m", defaultValue = "800") int radiusM,
                                         @RequestParam(name = "limit", defaultValue = "5") int limit,
                                         @AuthenticationPrincipal ApiClient client) {
        if (!rateLimiter.allowPartnerCall(client.getId())) return tooMany();
        if (!validCoords(lat, lon)) return bad("INVALID_POINT", "lat/lon out of range.");

        int r = Math.max(1, Math.min(radiusM, NEARBY_MAX_RADIUS_M));
        int n = Math.max(1, Math.min(limit, NEARBY_MAX_LIMIT));

        List<Map<String, Object>> rows = stopRepository.findByActiveTrue().stream()
                .filter(s -> s.getLat() != null && s.getLon() != null)
                .map(s -> Map.entry(s, GeoUtils.haversineMetres(lat, lon, s.getLat(), s.getLon())))
                .filter(e -> e.getValue() <= r)
                .sorted(Map.Entry.comparingByValue())
                .limit(n)
                .map(e -> stopRow(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("point", Map.of("lat", lat, "lon", lon));
        out.put("radius_m", r);
        out.put("stops", rows);
        return ResponseEntity.ok(out);
    }

    // ── POST /reachability ─────────────────────────────────────────────────

    @PostMapping("/reachability")
    @Operation(summary = "How to get from one point to another on the network, and how long it takes")
    public ResponseEntity<?> reachability(@RequestBody ReachabilityRequest req,
                                          @AuthenticationPrincipal ApiClient client) {
        if (!rateLimiter.allowPartnerReachability(client.getId())) return tooMany();

        if (req == null || req.origin() == null || req.destination() == null
                || req.origin().lat() == null || req.origin().lon() == null
                || req.destination().lat() == null || req.destination().lon() == null)
            return bad("MISSING_ENDPOINT", "origin and destination need lat and lon.");
        if (!validCoords(req.origin().lat(), req.origin().lon())
                || !validCoords(req.destination().lat(), req.destination().lon()))
            return bad("INVALID_POINT", "lat/lon out of range.");

        List<String> modes = req.modes() == null || req.modes().isEmpty()
                ? List.of("BUS", "WALK")
                : req.modes().stream().map(String::toUpperCase).distinct().collect(Collectors.toList());
        if (!MODES.containsAll(modes))
            return bad("INVALID_MODE", "modes must be among " + MODES);

        String ranking = req.ranking() == null ? "FAST" : req.ranking().toUpperCase();
        if (!RANKINGS.contains(ranking))
            return bad("INVALID_RANKING", "ranking must be among " + RANKINGS);

        JourneyRequest plan = new JourneyRequest();
        plan.setOriginLat(req.origin().lat());
        plan.setOriginLon(req.origin().lon());
        plan.setOriginName(labelOf(req.origin()));
        plan.setDestLat(req.destination().lat());
        plan.setDestLon(req.destination().lon());
        plan.setDestName(labelOf(req.destination()));
        plan.setModes(modes);
        plan.setSortPreset(ranking);
        plan.setDepartureTime(req.departure_time());
        plan.setLang("it".equalsIgnoreCase(req.lang()) ? "it" : "en");
        // No userId: no preference profile, no history, nothing written.

        JourneyResponse res;
        try {
            res = planner.plan(plan);
        } catch (Exception e) {
            log.error("Partner reachability failed for key {}: {}", client.getId(), e.toString(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "PLANNER_FAILED", "message", "The journey could not be planned."));
        }

        Instant base = departureBase(req.departure_time());
        List<Map<String, Object>> options = res.getOptions() == null ? List.of()
                : res.getOptions().stream().map(o -> optionRow(o, base)).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("origin",      Map.of("lat", req.origin().lat(), "lon", req.origin().lon(), "name", plan.getOriginName()));
        out.put("destination", Map.of("lat", req.destination().lat(), "lon", req.destination().lon(), "name", plan.getDestName()));
        out.put("computed_at", Instant.now());
        out.put("ranking", ranking);
        out.put("reachable", !options.isEmpty());
        out.put("best_minutes", options.stream()
                .map(o -> (Integer) o.get("duration_minutes"))
                .filter(Objects::nonNull).min(Integer::compareTo).orElse(null));
        out.put("options", options);
        return ResponseEntity.ok(out);
    }

    // ── POST /co2 ──────────────────────────────────────────────────────────

    @PostMapping("/co2")
    @Operation(summary = "CO₂ and Green Index of a trip, by mode and kilometres")
    public ResponseEntity<?> co2(@RequestBody Co2Request req,
                                 @AuthenticationPrincipal ApiClient client) {
        if (!rateLimiter.allowPartnerCall(client.getId())) return tooMany();
        if (req == null || req.legs() == null || req.legs().isEmpty())
            return bad("MISSING_LEGS", "legs[] with mode and km is required.");

        Map<String, Double> kmByMode = new LinkedHashMap<>();
        List<Map<String, Object>> legs = new ArrayList<>();
        double totalKm = 0;
        for (Co2Leg leg : req.legs()) {
            if (leg == null || leg.mode() == null || leg.km() == null || leg.km() < 0 || leg.km() > 1000)
                return bad("INVALID_LEG", "each leg needs a mode and km between 0 and 1000.");
            String mode = leg.mode().toUpperCase();
            if (!greenIndex.emissionFactors().containsKey(mode))
                return bad("INVALID_MODE", "mode must be among " + greenIndex.emissionFactors().keySet());

            double grams = greenIndex.computeCo2Grams(mode, leg.km());
            kmByMode.merge(mode, leg.km(), Double::sum);
            totalKm += leg.km();

            Map<String, Object> l = new LinkedHashMap<>();
            l.put("mode", mode);
            l.put("km", leg.km());
            l.put("co2_grams", round1(grams));
            legs.add(l);
        }

        double co2 = greenIndex.computeCo2Grams(kmByMode);
        double car = greenIndex.carBaselineGramsPerKm() * totalKm;
        int index = greenIndex.greenIndexFor(co2, totalKm);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distance_km", round1(totalKm));
        out.put("co2_grams", round1(co2));
        out.put("car_baseline_grams", round1(car));
        out.put("co2_avoided_grams", round1(Math.max(0, car - co2)));
        out.put("green_index", index);
        out.put("green_label", greenIndex.getGreenLabel(index));
        out.put("legs", legs);
        out.put("factors_g_per_pax_km", greenIndex.emissionFactors());
        return ResponseEntity.ok(out);
    }

    // ── Projections ────────────────────────────────────────────────────────

    /**
     * The option as a partner needs it: figures and identifiers, not the
     * sentences, weather hints and map geometry the traveller screen draws.
     */
    private Map<String, Object> optionRow(JourneyOption o, Instant base) {
        // Under "depart at" the planner leaves departsAt empty: the traveller
        // leaves at the base and the option starts with the walk and the wait.
        // A partner wants the hour the bus is boarded, so it is read off the legs.
        Instant departs = o.getDepartsAt() == null ? base : Instant.ofEpochMilli(o.getDepartsAt());
        Instant boarding = null;
        if (o.getLegs() != null) {
            int before = 0;
            for (JourneyLeg l : o.getLegs()) {
                if ("BUS".equals(l.getMode())) { boarding = departs.plusSeconds(60L * before); break; }
                before += l.getDurationMinutes() == null ? 0 : l.getDurationMinutes();
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode",             o.getMode());
        m.put("duration_minutes", o.getDurationMinutes());
        m.put("distance_metres",  o.getDistanceMetres());
        m.put("cost_euros",       o.getCostEuros());
        m.put("co2_grams",        o.getCo2Grams());
        m.put("green_index",      o.getGreenIndex());
        m.put("departs_at",       departs);
        m.put("boarding_at",      boarding);
        m.put("arrives_at",       o.getDurationMinutes() == null ? null
                                    : departs.plusSeconds(60L * o.getDurationMinutes()));
        m.put("route_id",         o.getBusRouteId());
        m.put("boarding_stop_id", o.getBoardingStopId());
        m.put("alight_stop_id",   o.getAlightStopId());
        m.put("transfer_stop_id", o.getTransferStopId());
        m.put("delay_minutes",    o.getDelayMinutes());
        m.put("delay_status",     o.getDelayStatus());
        m.put("delay_real_time",  o.getDelayRealTime());
        m.put("reliability_score", o.getReliabilityScore());
        m.put("summary",          o.getSummary());
        m.put("legs", o.getLegs() == null ? List.of()
                : o.getLegs().stream().map(this::legRow).collect(Collectors.toList()));
        return m;
    }

    private Map<String, Object> legRow(JourneyLeg l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode",             l.getMode());
        m.put("from",             l.getFrom());
        m.put("to",               l.getTo());
        m.put("duration_minutes", l.getDurationMinutes());
        m.put("distance_metres",  l.getDistanceMetres());
        m.put("route_id",         l.getRouteId());
        m.put("trip_id",          l.getTripId());
        m.put("transfer",         l.getTransfer());
        return m;
    }

    private static Map<String, Object> stopRow(Stop s, double distanceM) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stop_id",    s.getId());
        m.put("name",       s.getName());
        m.put("lat",        s.getLat());
        m.put("lon",        s.getLon());
        m.put("distance_m", (int) Math.round(distanceM));
        return m;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * The instant the planner plans from: now, or the given HH:mm in Rome,
     * rolled to tomorrow once it has passed — the same reading the planner
     * gives it, repeated here because the planner does not hand it back.
     */
    private static Instant departureBase(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return Instant.now();
        try {
            java.time.ZoneId tz = java.time.ZoneId.of("Europe/Rome");
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(tz);
            java.time.ZonedDateTime cand = now.with(java.time.LocalTime.parse(hhmm.trim()));
            if (cand.isBefore(now)) cand = cand.plusDays(1);
            return cand.toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String labelOf(Point p) {
        if (p.name() != null && !p.name().isBlank()) return p.name().trim();
        return String.format(Locale.ROOT, "%.5f, %.5f", p.lat(), p.lon());
    }

    private static boolean validCoords(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static ResponseEntity<Map<String, String>> bad(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("error", code, "message", message));
    }

    private static ResponseEntity<Map<String, String>> tooMany() {
        return ResponseEntity.status(429).body(Map.of(
                "error", "RATE_LIMITED", "message", "Hourly quota for this API key reached."));
    }
}
