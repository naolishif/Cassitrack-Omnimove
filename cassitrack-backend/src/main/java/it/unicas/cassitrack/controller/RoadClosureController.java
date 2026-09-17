package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.model.RoadClosure;
import it.unicas.cassitrack.repository.RoadClosureRepository;
import it.unicas.cassitrack.service.ApiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Emergencies reported by the other systems of the ecosystem.
 *
 * <p>One call, in the partners' own format: a POST says that an area is
 * not passable — {@code eventId}, centre, {@code radiusMeters}, what kind of
 * event and how severe, optionally a meeting point — and the same
 * {@code eventId} again with {@code status=false} says it is over. The
 * report is stored; the fleet manager's screens pick it up through
 * {@code /api/v1/hazards}.
 *
 * <p>Same {@code X-Api-Key} check as the NeTEx and telemetry feeds: the
 * path is open at the security layer and {@link ApiClientService#authorizeFeed}
 * decides here, so the OmniMove token and every partner key work alike.
 */
@RestController
@RequestMapping("/api/v1/road-closures")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Road closures", description = "Emergencies reported by partner systems — X-Api-Key required")
public class RoadClosureController {

    static final int RADIUS_MIN_M = 1;
    static final int RADIUS_MAX_M = 50_000;
    static final int RADIUS_DEFAULT_M = 50;
    static final String SEVERITY_DEFAULT = "MEDIUM";
    static final int ID_MAX = 100, TYPE_MAX = 40, SEVERITY_MAX = 20, CATEGORY_MAX = 40,
                     TITLE_MAX = 200, DESCRIPTION_MAX = 500, ADDRESS_MAX = 300;

    private final RoadClosureRepository repo;
    private final ApiClientService apiClientService;

    public record MeetingPoint(Double latitude, Double longitude, String address) {}

    /**
     * The partners' payload, as they already produce it.
     *
     * @param eventId      the report's id in the partner's system (required);
     *                     the same id again updates the report
     * @param eventType    FLOOD, ACCIDENT, ROADWORKS… (required)
     * @param severity     LOW, MEDIUM (default) or HIGH
     * @param radiusMeters metres around the centre, 1..50000 (default 50)
     * @param status       our one addition: true = in force (default),
     *                     false = the emergency is over
     */
    public record ClosureRequest(Long emergencyId, String eventId, String eventType,
                                 String severity, String title, String description,
                                 String category, Double latitude, Double longitude,
                                 Integer radiusMeters, MeetingPoint meetingPoint,
                                 Boolean status) {}

    @PostMapping
    @Operation(summary = "Report an emergency, or update one (status=false = over)")
    public ResponseEntity<?> report(@RequestBody ClosureRequest req,
                                    @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        Optional<String> caller = apiClientService.authorizeFeed(apiKey);
        if (caller.isEmpty()) return unauthorized();
        String reporter = caller.get();

        Optional<ResponseEntity<?>> invalid = validate(req);
        if (invalid.isPresent()) return invalid.get();

        Instant now = Instant.now();
        String externalId = req.eventId().trim();
        Optional<RoadClosure> existing = repo.findByReportedByAndExternalId(reporter, externalId);

        RoadClosure c = existing.orElseGet(() -> RoadClosure.builder()
                .externalId(externalId)
                .reportedBy(reporter)
                .createdAt(now)
                .build());
        apply(req, c, now);
        c = repo.save(c);

        log.info("Emergency '{}' {} by '{}': {} {} at {},{} r={}m status={}",
                c.getExternalId(), existing.isPresent() ? "updated" : "reported", reporter,
                c.getSeverity(), c.getEventType(), c.getLatitude(), c.getLongitude(),
                c.getRadiusMetres(), c.getStatus());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(row(c));
    }

    // ── Mapping, validation and projection ─────────────────────────────────

    static void apply(ClosureRequest req, RoadClosure c, Instant now) {
        c.setEmergencyId(req.emergencyId());
        c.setEventType(req.eventType().trim().toUpperCase());
        c.setSeverity(blank(req.severity()) ? SEVERITY_DEFAULT : req.severity().trim().toUpperCase());
        c.setCategory(blank(req.category()) ? null : req.category().trim().toUpperCase());
        c.setTitle(clean(req.title()));
        c.setDescription(clean(req.description()));
        c.setLatitude(req.latitude());
        c.setLongitude(req.longitude());
        c.setRadiusMetres(req.radiusMeters() == null ? RADIUS_DEFAULT_M : req.radiusMeters());
        MeetingPoint mp = req.meetingPoint();
        boolean hasMp = mp != null && mp.latitude() != null && mp.longitude() != null;
        c.setMeetingLat(hasMp ? mp.latitude() : null);
        c.setMeetingLon(hasMp ? mp.longitude() : null);
        c.setMeetingAddress(mp == null ? null : clean(mp.address()));
        c.setStatus(req.status() == null || req.status());
        c.setUpdatedAt(now);
    }

    static Optional<ResponseEntity<?>> validate(ClosureRequest req) {
        if (req == null) return Optional.of(bad("INVALID_BODY", "A JSON body is required."));
        if (blank(req.eventId()) || req.eventId().trim().length() > ID_MAX)
            return Optional.of(bad("INVALID_EVENT_ID", "eventId is required, up to " + ID_MAX + " characters."));
        if (blank(req.eventType()) || req.eventType().trim().length() > TYPE_MAX)
            return Optional.of(bad("INVALID_EVENT_TYPE", "eventType is required, up to " + TYPE_MAX + " characters."));
        if (req.severity() != null && req.severity().trim().length() > SEVERITY_MAX)
            return Optional.of(bad("INVALID_SEVERITY", "severity must be " + SEVERITY_MAX + " characters or fewer."));
        if (!validPoint(req.latitude(), req.longitude()))
            return Optional.of(bad("INVALID_POINT", "latitude/longitude are required and must be in range."));
        if (req.radiusMeters() != null && (req.radiusMeters() < RADIUS_MIN_M || req.radiusMeters() > RADIUS_MAX_M))
            return Optional.of(bad("INVALID_RADIUS",
                    "radiusMeters must be between " + RADIUS_MIN_M + " and " + RADIUS_MAX_M + "."));
        if (tooLong(req.category(), CATEGORY_MAX))
            return Optional.of(bad("INVALID_CATEGORY", "category must be " + CATEGORY_MAX + " characters or fewer."));
        if (tooLong(req.title(), TITLE_MAX))
            return Optional.of(bad("INVALID_TITLE", "title must be " + TITLE_MAX + " characters or fewer."));
        if (tooLong(req.description(), DESCRIPTION_MAX))
            return Optional.of(bad("INVALID_DESCRIPTION", "description must be " + DESCRIPTION_MAX + " characters or fewer."));
        MeetingPoint mp = req.meetingPoint();
        if (mp != null) {
            boolean any = mp.latitude() != null || mp.longitude() != null;
            if (any && !validPoint(mp.latitude(), mp.longitude()))
                return Optional.of(bad("INVALID_MEETING_POINT", "meetingPoint.latitude/longitude must both be given and in range."));
            if (tooLong(mp.address(), ADDRESS_MAX))
                return Optional.of(bad("INVALID_MEETING_POINT", "meetingPoint.address must be " + ADDRESS_MAX + " characters or fewer."));
        }
        return Optional.empty();
    }

    static Map<String, Object> row(RoadClosure c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("emergencyId",  c.getEmergencyId());
        m.put("eventId",      c.getExternalId());
        m.put("eventType",    c.getEventType());
        m.put("severity",     c.getSeverity());
        m.put("title",        c.getTitle());
        m.put("description",  c.getDescription());
        m.put("category",     c.getCategory());
        m.put("latitude",     c.getLatitude());
        m.put("longitude",    c.getLongitude());
        m.put("radiusMeters", c.getRadiusMetres());
        Map<String, Object> mp = null;
        if (c.getMeetingLat() != null || c.getMeetingAddress() != null) {
            mp = new LinkedHashMap<>();
            mp.put("latitude",  c.getMeetingLat());
            mp.put("longitude", c.getMeetingLon());
            mp.put("address",   c.getMeetingAddress());
        }
        m.put("meetingPoint", mp);
        m.put("status",       c.getStatus());
        m.put("reportedBy",   c.getReportedBy());
        m.put("createdAt",    c.getCreatedAt());
        m.put("updatedAt",    c.getUpdatedAt());
        return m;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String clean(String s) { return blank(s) ? null : s.trim(); }
    private static boolean tooLong(String s, int max) { return s != null && s.trim().length() > max; }
    private static boolean validPoint(Double lat, Double lon) {
        return lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static ResponseEntity<Map<String, String>> bad(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("error", code, "message", message));
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of(
                "error", "UNAUTHORIZED", "message", "A valid X-Api-Key header is required."));
    }
}
