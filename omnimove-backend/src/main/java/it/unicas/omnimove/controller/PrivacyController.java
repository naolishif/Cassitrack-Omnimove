package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.model.UserConsent;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.FavoriteRouteRepository;
import it.unicas.omnimove.repository.FavoriteStopRepository;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.UserPreferencesRepository;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.service.ConsentService;
import it.unicas.omnimove.service.RateLimiterService;
import it.unicas.omnimove.service.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data-subject facing endpoints.
 *
 * <p>Controller: Università degli Studi di Cassino e del Lazio Meridionale.
 *
 * <ul>
 *   <li>{@code POST /consents} — records a consent decision. Deliberately open to
 *       anonymous visitors: the cookie banner is shown before sign-in.</li>
 *   <li>{@code GET  /consents} — current state, so the UI can pre-fill the toggles.</li>
 *   <li>{@code GET  /export}   — art. 15 / art. 20: everything we hold on the caller,
 *       as portable JSON.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/privacy")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Privacy", description = "Consent management and data subject rights (GDPR)")
public class PrivacyController {

    private final ConsentService consentService;
    private final UserRepository userRepo;
    private final UserPreferencesRepository preferencesRepo;
    private final FavoriteStopRepository favoriteStopRepo;
    private final FavoriteRouteRepository favoriteRouteRepo;
    private final JourneyLogRepository journeyLogRepo;
    private final RateLimiterService rateLimiter;
    private final SecurityAuditService securityAuditService;

    // ── POST /api/v1/privacy/consents ─────────────────────────────────────
    @PostMapping("/consents")
    @Operation(summary = "Record a consent decision",
               description = "Appends to the consent ledger. Works for anonymous visitors "
                           + "(cookie banner) and for signed-in users (settings).")
    public ResponseEntity<?> recordConsent(@RequestBody ConsentRequest body,
                                           @AuthenticationPrincipal UserDetails principal,
                                           HttpServletRequest request) {

        if (!consentService.isKnownType(body.getType()))
            return ResponseEntity.badRequest().body(Map.of("message", "Unknown consent type"));

        if (body.getGranted() == null)
            return ResponseEntity.badRequest().body(Map.of("message", "granted is required"));

        // The banner is reachable without authentication — throttle it so the
        // ledger cannot be flooded from a single address.
        if (!rateLimiter.isAllowed("consent:" + request.getRemoteAddr(), 30, Duration.ofMinutes(10)))
            return ResponseEntity.status(429).body(Map.of("message", "Too many consent updates"));

        Long userId = null;
        String source = UserConsent.SOURCE_BANNER;
        if (principal != null) {
            User user = userRepo.findByEmail(principal.getUsername()).orElse(null);
            if (user != null) {
                userId = user.getId();
                source = UserConsent.SOURCE_SETTINGS;
            }
        }

        String subjectKey = sanitiseKey(body.getSubjectKey());
        if (userId == null && subjectKey == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "subjectKey is required for anonymous visitors"));

        consentService.record(userId, subjectKey, body.getType(), body.getGranted(),
                              source, request);

        return ResponseEntity.ok(Map.of(
                "type",          body.getType(),
                "granted",       body.getGranted(),
                "policyVersion", consentService.currentPolicyVersion()));
    }

    // ── GET /api/v1/privacy/consents ──────────────────────────────────────
    @GetMapping("/consents")
    @Operation(summary = "Current consent state for the signed-in user")
    public ResponseEntity<?> myConsents(@AuthenticationPrincipal UserDetails principal) {
        User user = userRepo.findByEmail(principal.getUsername()).orElse(null);
        if (user == null) return ResponseEntity.status(401).build();

        return ResponseEntity.ok(Map.of(
                "policyVersion", consentService.currentPolicyVersion(),
                "consents",      consentService.currentStateFor(user.getId())));
    }

    // ── GET /api/v1/privacy/export ────────────────────────────────────────
    @GetMapping("/export")
    @Operation(summary = "Download all personal data held about the caller",
               description = "GDPR art. 15 (access) and art. 20 (portability). "
                           + "Returns machine-readable JSON as a file download.")
    public ResponseEntity<?> exportMyData(@AuthenticationPrincipal UserDetails principal,
                                          HttpServletRequest request) {

        User user = userRepo.findByEmail(principal.getUsername()).orElse(null);
        if (user == null) return ResponseEntity.status(401).build();

        // An export bundles everything we hold — keep it expensive to repeat.
        if (!rateLimiter.isAllowed("export:" + user.getEmail(), 3, Duration.ofHours(1)))
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Too many export requests. Please try again later."));

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("name", user.getName());
        account.put("email", user.getEmail());
        account.put("role", user.getRole());
        account.put("emailVerified", user.isVerified());
        // Deliberately omitted: password hash, verification and reset tokens.
        // They are credentials, not personal data the subject is entitled to receive,
        // and echoing them back would weaken account security.

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportedAt", ZonedDateTime.now().toString());
        payload.put("controller", "Università degli Studi di Cassino e del Lazio Meridionale");
        payload.put("account", account);

        payload.put("preferences", preferencesRepo.findByUserId(user.getId()).orElse(null));

        payload.put("favouriteStops", favoriteStopRepo.findByUserIdOrderByCreatedAtAsc(user.getId())
                .stream().map(f -> Map.of(
                        "stopId",    f.getStopId(),
                        "createdAt", String.valueOf(f.getCreatedAt())))
                .collect(Collectors.toList()));

        payload.put("favouriteRoutes", favoriteRouteRepo.findByUserId(user.getId())
                .stream().map(f -> mapOfNullable(
                        "mode",       f.getMode(),
                        "originName", f.getOriginName(),
                        "destName",   f.getDestName(),
                        "createdAt",  String.valueOf(f.getCreatedAt())))
                .collect(Collectors.toList()));

        payload.put("journeyHistory", journeyLogRepo.findByUserId(user.getId())
                .stream().map(j -> mapOfNullable(
                        "mode",       j.getMode(),
                        "originName", j.getOriginName(),
                        "destName",   j.getDestName(),
                        "distanceKm", j.getDistanceKm(),
                        "costEuros",  j.getCostEuros(),
                        "co2Grams",   j.getCo2Grams(),
                        "greenIndex", j.getGreenIndex(),
                        "createdAt",  String.valueOf(j.getCreatedAt())))
                .collect(Collectors.toList()));

        payload.put("consentHistory", consentService.historyFor(user.getId())
                .stream().map(c -> mapOfNullable(
                        "type",          c.getConsentType(),
                        "granted",       c.isGranted(),
                        "policyVersion", c.getPolicyVersion(),
                        "source",        c.getSource(),
                        "recordedAt",    String.valueOf(c.getRecordedAt())))
                .collect(Collectors.toList()));

        securityAuditService.dataExported(user.getEmail(), request.getRemoteAddr());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"omnimove-my-data.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Map.of rejects null values; the export must keep empty fields visible. */
    private static Map<String, Object> mapOfNullable(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Single definition lives in ConsentService, so the two cannot drift apart. */
    private static String sanitiseKey(String key) {
        return ConsentService.sanitiseKey(key);
    }

    @lombok.Data
    public static class ConsentRequest {
        private String  type;
        private Boolean granted;
        private String  subjectKey;
    }
}
