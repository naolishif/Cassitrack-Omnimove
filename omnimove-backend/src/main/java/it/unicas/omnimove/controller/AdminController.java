package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.AdminCreateUserRequest;
import it.unicas.omnimove.dto.AdminUpdateUserRequest;
import it.unicas.omnimove.dto.LoginEventDTO;
import it.unicas.omnimove.dto.UserDTO;
import it.unicas.omnimove.model.AdminExport;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.AdminExportRepository;
import it.unicas.omnimove.repository.UserMessageRepository;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.service.ActiveSessionService;
import it.unicas.omnimove.service.AnalyticsExportService;
import it.unicas.omnimove.service.AnalyticsService;
import it.unicas.omnimove.service.ConsentService;
import it.unicas.omnimove.service.DataRetentionService;
import it.unicas.omnimove.service.UiSettingsService;
import it.unicas.omnimove.service.GoogleApiSettingsService;
import it.unicas.omnimove.service.LoginHistoryService;
import it.unicas.omnimove.service.RecaptchaService;
import it.unicas.omnimove.service.TravellerProfileService;
import it.unicas.omnimove.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyAuthority('ADMIN', 'ROLE_ADMIN')")
@Tag(name = "Admin", description = "User management — ADMIN role required")
public class AdminController {

    /** Deliberately loose: the address is confirmed by mail, not by regex. */
    private static final java.util.regex.Pattern EMAIL_RE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private static final int NAME_MAX  = 100;   // users.name  VARCHAR(100)
    private static final int EMAIL_MAX = 100;   // users.email VARCHAR(100)

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AnalyticsService analyticsService;
    private final AnalyticsExportService analyticsExport;
    private final GoogleApiSettingsService googleApiSettings;
    private final SecurityAuditService securityAuditService;
    private final LoginHistoryService loginHistoryService;
    private final TravellerProfileService travellerProfileService;
    private final ActiveSessionService activeSessionService;
    private final RecaptchaService recaptchaService;
    private final ConsentService consentService;
    private final DataRetentionService dataRetentionService;
    private final UiSettingsService uiSettingsService;
    private final UserMessageRepository messageRepository;
    private final AdminExportRepository exportRepository;

    private UserDTO toDTO(User u) {
        return toDTO(u, null);
    }

    private UserDTO toDTO(User u, Long loginCount) {
        return UserDTO.builder()
                .id(u.getId())
                .name(u.getName())
                .email(u.getEmail())
                .role(u.getRole())
                .registeredAt(u.getCreatedAt())
                .lastLoginAt(u.getLastLoginAt())
                .loginCount(loginCount)
                .build();
    }

    // ── GET /api/v1/admin/users ───────────────────────────────────────────
    @GetMapping("/users")
    @Operation(summary = "List all users", description = "Returns all registered users. ADMIN only.")
    public ResponseEntity<?> listUsers(
            @AuthenticationPrincipal UserDetails principal) {

        Map<Long, Long> loginCounts = loginHistoryService.countsByUser();

        // One query for the whole page rather than one per row: the marker on a
        // row is "has unread messages", and asking per user would be a query per
        // user on a list that shows all of them.
        Map<Long, Long> unread = new java.util.HashMap<>();
        messageRepository.countUnreadByUser()
                .forEach(row -> unread.put((Long) row[0], (Long) row[1]));

        List<UserDTO> users = userRepo.findAll()
                .stream()
                .map(u -> {
                    UserDTO dto = toDTO(u, loginCounts.getOrDefault(u.getId(), 0L));
                    dto.setUnreadMessages(unread.getOrDefault(u.getId(), 0L));
                    return dto;
                })
                .collect(Collectors.toList());

        securityAuditService.adminListedUsers(principal.getUsername(), users.size());
        return ResponseEntity.ok(users);
    }

    // ── Traveller interface settings ──────────────────────────────────────
    @GetMapping("/settings/ui")
    @Operation(summary = "Interface settings the operator can tune")
    public ResponseEntity<?> uiSettings() {
        return ResponseEntity.ok(Map.of("aiNudgeMinutes", uiSettingsService.getAiNudgeMinutes()));
    }

    @PutMapping("/settings/ui")
    @Operation(summary = "Set how often the assistant introduces itself",
               description = "Minutes between nudges; 0 turns them off. Out-of-range "
                           + "values are clamped and the stored value is returned.")
    public ResponseEntity<?> setUiSettings(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("aiNudgeMinutes");
        if (!(raw instanceof Number n))
            return ResponseEntity.badRequest().body(Map.of("message", "aiNudgeMinutes must be a number"));

        return ResponseEntity.ok(Map.of("aiNudgeMinutes",
                uiSettingsService.setAiNudgeMinutes(n.intValue())));
    }

    // ── GET /api/v1/admin/retention ───────────────────────────────────────
    @GetMapping("/retention")
    @Operation(summary = "Retention rules and what each last removed",
               description = "The periods privacy.html § 7 states, with the outcome of "
                           + "each rule's most recent run. Counts and timestamps only.")
    public ResponseEntity<?> retention() {
        return ResponseEntity.ok(dataRetentionService.status());
    }

    // ── POST /api/v1/admin/users/export-log ───────────────────────────────
    @PostMapping("/users/export-log")
    @Operation(summary = "Record that the user list was downloaded",
               description = "The file itself is built in the browser from the rows already "
                           + "on screen, so that it matches the filters exactly. This records "
                           + "that a copy was taken, which reading the list does not.")
    public ResponseEntity<?> logUserExport(@RequestBody(required = false) Map<String, Object> body,
                                           @AuthenticationPrincipal UserDetails principal) {
        Object count   = body == null ? null : body.get("count");
        Object filters = body == null ? null : body.get("filters");
        int rows = count instanceof Number n ? n.intValue() : -1;
        String scope = (filters == null ? "none" : String.valueOf(filters)) + "; " + rows + " rows";

        recordExport(principal.getUsername(), AdminExport.KIND_USER_LIST, scope, rows);
        return ResponseEntity.ok(Map.of("logged", true));
    }

    // ── POST /api/v1/admin/users ──────────────────────────────────────────
    @PostMapping("/users")
    @Operation(summary = "Create a new user", description = "Creates user with any role. ADMIN only.")
    public ResponseEntity<?> createUser(
            @RequestBody AdminCreateUserRequest req,
            @AuthenticationPrincipal UserDetails principal) {

        if (req.getName() == null || req.getEmail() == null || req.getPassword() == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "name, email and password are required"));

        if (userRepo.existsByEmail(req.getEmail()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Email already registered"));

        String role = (req.getRole() != null &&
                       req.getRole().equalsIgnoreCase("ADMIN")) ? "ADMIN" : "TRAVELLER";

        User user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role(role)
                .build();
        userRepo.save(user);

        securityAuditService.adminUserCreated(principal.getUsername(), req.getEmail(), role);
        return ResponseEntity.ok(toDTO(user));
    }

    // ── DELETE /api/v1/admin/users/{id} ──────────────────────────────────
    @DeleteMapping("/users/{id}")
    @Operation(summary = "Delete a user by ID", description = "ADMIN only. Cannot delete yourself.")
    public ResponseEntity<?> deleteUser(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {

        return userRepo.findById(id).map(target -> {
            // Impedisce all'admin di cancellare se stesso
            if (target.getEmail().equalsIgnoreCase(principal.getUsername()))
                return ResponseEntity.badRequest()
                        .<Object>body(Map.of("message", "Cannot delete your own account"));

            if ("ADMIN".equalsIgnoreCase(target.getRole()))
                return ResponseEntity.status(403)
                        .<Object>body(Map.of("message", "Cannot delete another admin"));

            userRepo.delete(target);
            securityAuditService.adminUserDeleted(principal.getUsername(), id, target.getEmail());
            return ResponseEntity.ok().<Object>body(Map.of("message", "User deleted", "id", id));
        }).orElse(ResponseEntity.notFound().<Object>build());
    }

    // ── GET /api/v1/admin/users/{id}/logins ──────────────────────────────
    @GetMapping("/users/{id}/logins")
    @Operation(summary = "Access history for one user",
               description = "Every recorded login, newest first. ADMIN only.")
    public ResponseEntity<?> loginHistory(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails principal) {

        return userRepo.findById(id).map(user -> {
            List<LoginEventDTO> events = loginHistoryService.history(id).stream()
                    .map(e -> LoginEventDTO.builder()
                            .id(e.getId())
                            .loggedInAt(e.getLoggedInAt())
                            .ipAddress(e.getIpAddress())
                            .userAgent(e.getUserAgent())
                            .build())
                    .collect(Collectors.toList());

            long total = loginHistoryService.count(id);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId",       user.getId());
            body.put("name",         user.getName());
            body.put("email",        user.getEmail());
            body.put("registeredAt", user.getCreatedAt());
            body.put("lastLoginAt",  user.getLastLoginAt());
            body.put("total",        total);
            // The list is capped, so the UI can say "showing the latest N of M"
            body.put("truncated",    total > events.size());
            body.put("events",       events);

            securityAuditService.adminViewedLoginHistory(
                    principal.getUsername(), user.getEmail());
            return ResponseEntity.ok().<Object>body(body);
        }).orElse(ResponseEntity.notFound().<Object>build());
    }

    // ── GET /api/v1/admin/users/{id}/profile ─────────────────────────────
    @GetMapping("/users/{id}/profile")
    @Operation(summary = "Everything the dashboard shows about one user",
               description = "Account details plus the travel figures the user "
                           + "sees in their own app. ADMIN only.")
    public ResponseEntity<?> userProfile(
            @PathVariable Long id,
            @RequestParam(value = "trips", required = false) Integer trips,
            @AuthenticationPrincipal UserDetails principal) {

        // Default matches the app's own history panel; the operator can ask for
        // more, but not for an unbounded dump.
        int limit = (trips == null)
                ? TravellerProfileService.HISTORY_LIMIT
                : Math.max(1, Math.min(trips, 100));

        return userRepo.findById(id).map(user -> {
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("id",           user.getId());
            account.put("name",         user.getName());
            account.put("email",        user.getEmail());
            account.put("role",         user.getRole());
            account.put("verified",     user.isVerified());
            // How the account came into being — a Google sign-up never had a
            // password, so the operator needs to know before offering one
            account.put("authProvider", user.getAuthProvider());
            account.put("hasPassword",  user.hasPassword());
            account.put("registeredAt", user.getCreatedAt());
            account.put("lastLoginAt",  user.getLastLoginAt());
            account.put("loginCount",   loginHistoryService.count(user.getId()));
            // Whether this person has been shown, and acknowledged, the two
            // notices. Account metadata, so it belongs on the card rather than
            // behind the travel block — and it is the only way an operator can
            // answer "was this user ever presented the informativa?".
            account.put("acknowledgements", consentService.acknowledgementsFor(user.getId()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("account", account);

            // Messages, newest first — and opening the card is what marks them
            // read. The unread marker in the list means "nobody has looked at
            // this yet", so it has to clear exactly when somebody does.
            var messages = messageRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
            body.put("messages", messages.stream().map(m -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", m.getId());
                row.put("body", m.getBody());
                row.put("createdAt", m.getCreatedAt());
                row.put("read", m.getReadAt() != null);
                return row;
            }).toList());
            messageRepository.markRead(user.getId(), java.time.ZonedDateTime.now());

            // What this person has taken out of the system. Shown for everyone,
            // not only operators: an ordinary traveller has no exports and the
            // section simply says so, which is itself worth being able to see.
            var exports = exportRepository.findByUserIdOrderByExportedAtDesc(
                    user.getId(), org.springframework.data.domain.PageRequest.of(0, 20));
            Map<String, Object> exportSummary = new LinkedHashMap<>();
            exportSummary.put("total", exportRepository.countByUserId(user.getId()));
            exportSummary.put("recent", exports.stream().map(x -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind",   x.getKind());
                row.put("detail", x.getDetail());
                row.put("at",     x.getExportedAt());
                return row;
            }).toList());
            body.put("exports", exportSummary);

            // An operator account never travels: the figures would all read zero
            // and the queries behind them are pure waste, so they are not run.
            boolean travels = !"ADMIN".equalsIgnoreCase(user.getRole());
            body.put("travelData", travels);

            if (travels) {
                List<Map<String, Object>> history = travellerProfileService.history(user.getId(), limit);
                int totalTrips = travellerProfileService.tripCount(user.getId());

                body.put("stats",          travellerProfileService.stats(user.getId()));
                body.put("history",        history);
                body.put("totalTrips",     totalTrips);
                body.put("truncated",      totalTrips > history.size());
                body.put("favoriteRoutes", travellerProfileService.favoriteRoutes(user.getId()));
                body.put("favoriteStops",  travellerProfileService.favoriteStops(user.getId()));
            }

            securityAuditService.adminViewedUserProfile(
                    principal.getUsername(), user.getEmail());
            return ResponseEntity.ok().<Object>body(body);
        }).orElse(ResponseEntity.notFound().<Object>build());
    }

    // ── PUT /api/v1/admin/users/{id} ─────────────────────────────────────
    @PutMapping("/users/{id}")
    @Operation(summary = "Correct a user's name or email",
               description = "Identity fields only — role and password are not "
                           + "editable here. ADMIN only.")
    public ResponseEntity<?> updateUser(
            @PathVariable Long id,
            @RequestBody AdminUpdateUserRequest req,
            @AuthenticationPrincipal UserDetails principal) {

        return userRepo.findById(id).map(user -> {
            // Same rule as delete: one admin does not reach into another's account
            if ("ADMIN".equalsIgnoreCase(user.getRole())
                    && !user.getEmail().equalsIgnoreCase(principal.getUsername()))
                return ResponseEntity.status(403)
                        .<Object>body(Map.of("message", "Cannot edit another admin"));

            String oldEmail   = user.getEmail();
            boolean nameChanged  = false;
            boolean emailChanged = false;

            if (req.getName() != null) {
                String name = req.getName().trim();
                if (name.isEmpty())
                    return ResponseEntity.badRequest()
                            .<Object>body(Map.of("message", "Name cannot be empty"));
                if (name.length() > NAME_MAX)
                    return ResponseEntity.badRequest()
                            .<Object>body(Map.of("message", "Name must be " + NAME_MAX + " characters or less"));
                nameChanged = !name.equals(user.getName());
                user.setName(name);
            }

            if (req.getEmail() != null) {
                String email = req.getEmail().trim();
                if (!email.equalsIgnoreCase(oldEmail)) {
                    if (email.length() > EMAIL_MAX)
                        return ResponseEntity.badRequest()
                                .<Object>body(Map.of("message", "Email must be " + EMAIL_MAX + " characters or less"));
                    if (!EMAIL_RE.matcher(email).matches())
                        return ResponseEntity.badRequest()
                                .<Object>body(Map.of("message", "Invalid email address"));
                    if (userRepo.existsByEmail(email))
                        return ResponseEntity.badRequest()
                                .<Object>body(Map.of("message", "Email already registered"));
                    user.setEmail(email);
                    emailChanged = true;
                }
            }

            if (!nameChanged && !emailChanged) {
                Map<String, Object> unchanged = new LinkedHashMap<>();
                unchanged.put("user",           toDTO(user, loginHistoryService.count(user.getId())));
                unchanged.put("sessionRevoked", false);
                unchanged.put("message",        "No changes.");
                return ResponseEntity.ok().<Object>body(unchanged);
            }

            userRepo.save(user);

            if (emailChanged) securityAuditService.adminUserEmailChanged(
                    principal.getUsername(), oldEmail, user.getEmail());
            else              securityAuditService.adminUserUpdated(
                    principal.getUsername(), user.getEmail());

            // The JWT subject is the email address, so the user's current session
            // stops resolving the moment it changes — they are signed out and
            // must log in with the new address.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user",           toDTO(user, loginHistoryService.count(user.getId())));
            body.put("sessionRevoked", emailChanged);
            body.put("message",        emailChanged
                    ? "User updated. The new address is now their login."
                    : "User updated.");
            return ResponseEntity.ok().<Object>body(body);
        }).orElse(ResponseEntity.notFound().<Object>build());
    }

    // ── GET /api/v1/admin/users/stats ─────────────────────────────────────
    @GetMapping("/users/stats")
    @Operation(summary = "User counts by role", description = "Returns total, admins, travellers. ADMIN only.")
    public ResponseEntity<?> userStats() {

        List<User> all = userRepo.findAll();
        long total     = all.size();
        long admins    = all.stream().filter(u -> "ADMIN".equalsIgnoreCase(u.getRole())).count();
        long travellers= all.stream().filter(u -> "TRAVELLER".equalsIgnoreCase(u.getRole())).count();

        // -1 when Redis cannot answer: the tile shows "—" rather than a wrong count
        long activeSessions = activeSessionService.count();

        return ResponseEntity.ok(Map.of(
                "total",          total,
                "admins",         admins,
                "travellers",     travellers,
                "activeSessions", activeSessions
        ));
    }

    // ── GET /api/v1/admin/analytics?range=1M ─────────────────────────────
    @GetMapping("/analytics")
    @Operation(summary = "Transport mode analytics",
               description = "InfluxDB aggregates. range = 1W | 1M | 3M | 6M | 1Y. ADMIN only.")
    public ResponseEntity<?> analytics(
            @RequestParam(value = "range", defaultValue = "1M") String range,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to",   required = false) String to) {

        range = resolveRange(range, from, to);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kpis",             analyticsService.getSummaryKpis(range));
        payload.put("modeDistribution", analyticsService.getModeDistribution(range));
        payload.put("modeByHour",       analyticsService.getModeByHour(range));
        payload.put("greenIndexTrend",  analyticsService.getGreenIndexTrend(range));
        payload.put("dayOfWeek",        analyticsService.getModeByDayOfWeek(range));
        payload.put("topRoutes",        analyticsService.getTopRoutes(range));
        // The multimodal panel. It travels with the rest so the whole page is
        // one window and one request — a second call for the MaaS figures could
        // land on a different period and quietly disagree with the charts above it.
        payload.put("combined",         analyticsService.getCombinedStats(range));

        return ResponseEntity.ok(payload);
    }

    /**
     * Records a copy leaving the system, in both registers.
     *
     * <p>security_audit_events is the proof and the application can only write to
     * it; admin_exports is the same fact in a table the application may read, so
     * the operator's own card can show it. Neither is allowed to fail the
     * download: the file is what was asked for, and a bookkeeping error is ours.
     */
    private void recordExport(String adminEmail, String kind, String detail, int rows) {
        try {
            if (AdminExport.KIND_USER_LIST.equals(kind))
                securityAuditService.adminExportedUsers(adminEmail, rows, detail);
            else
                securityAuditService.adminExportedAnalytics(adminEmail, detail);
        } catch (Exception e) {
            log.warn("Could not audit the export by {}: {}", adminEmail, e.getMessage());
        }
        try {
            userRepo.findByEmail(adminEmail).ifPresent(u -> exportRepository.save(
                    AdminExport.builder()
                            .userId(u.getId())
                            .kind(kind)
                            .detail(detail)
                            .exportedAt(java.time.ZonedDateTime.now())
                            .build()));
        } catch (Exception e) {
            log.warn("Could not record the export by {}: {}", adminEmail, e.getMessage());
        }
    }

    /**
     * Folds an explicit period into the range string the services already take.
     *
     * <p>Both dates or neither: half a period is a mistake, and guessing the
     * missing end would answer a question nobody asked. When they are missing or
     * unusable the preset is used, so a mistyped URL degrades to the default
     * rather than to an error page.
     */
    private static String resolveRange(String range, String from, String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()) return range;
        try {
            java.time.LocalDate.parse(from.trim());
            java.time.LocalDate.parse(to.trim());
            return "CUSTOM:" + from.trim() + ":" + to.trim();
        } catch (Exception e) {
            return range;
        }
    }

    // ── GET /api/v1/admin/analytics/export?range=1M&format=csv|xlsx|pdf ──
    @GetMapping("/analytics/export")
    @Operation(summary = "Download the analytics report",
               description = "Aggregate figures only — no personal data. One dataset "
                           + "rendered as CSV, XLSX or PDF, so the three cannot disagree.")
    public ResponseEntity<byte[]> exportAnalytics(
            @RequestParam(value = "range", defaultValue = "1M") String range,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to",   required = false) String to,
            @RequestParam(value = "format", defaultValue = "csv") String format,
            @AuthenticationPrincipal UserDetails principal) {

        // The same window the dashboard is showing: a report that covered a
        // different period from the screen it was downloaded from would be worse
        // than no report.
        AnalyticsExportService.Report report = analyticsExport.build(resolveRange(range, from, to));
        String base = analyticsExport.fileBaseName(report);

        byte[] body;
        String contentType, extension;
        switch (format == null ? "" : format.toLowerCase()) {
            case "xlsx" -> {
                body = analyticsExport.toXlsx(report);
                contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                extension = "xlsx";
            }
            case "pdf" -> {
                body = analyticsExport.toPdf(report);
                contentType = "application/pdf";
                extension = "pdf";
            }
            case "csv" -> {
                body = analyticsExport.toCsv(report);
                contentType = "text/csv; charset=UTF-8";
                extension = "csv";
            }
            default -> {
                return ResponseEntity.badRequest().build();
            }
        }

        recordExport(principal.getUsername(), AdminExport.KIND_ANALYTICS,
                     extension + ", " + report.range(), 0);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + base + "." + extension + "\"")
                .body(body);
    }

    // == GET /api/v1/admin/settings/security =============================
    @GetMapping("/settings/security")
    @Operation(summary = "Read the security switches")
    public ResponseEntity<?> getSecuritySettings() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(RecaptchaService.KEY_ENABLED, recaptchaService.isFlagEnabled());
        // The switch can be ON while the check is not running, because no key
        // pair is configured. The dashboard says so rather than claiming the
        // login form is protected when it is not.
        body.put("recaptchaConfigured", recaptchaService.isConfigured());
        body.put("recaptchaActive",     recaptchaService.isActive());
        return ResponseEntity.ok(body);
    }

    // == PUT /api/v1/admin/settings/security =============================
    // Body: { "security.recaptcha": true }
    @PutMapping("/settings/security")
    @Operation(summary = "Turn the login reCAPTCHA on or off")
    public ResponseEntity<?> setSecuritySettings(@RequestBody Map<String, Boolean> body,
                                                 @AuthenticationPrincipal UserDetails principal) {
        if (body == null || !body.containsKey(RecaptchaService.KEY_ENABLED))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Expected " + RecaptchaService.KEY_ENABLED));

        Boolean enabled = body.get(RecaptchaService.KEY_ENABLED);
        if (enabled == null)
            return ResponseEntity.badRequest().body(Map.of("message", "Value must be true or false"));

        recaptchaService.setEnabled(enabled);
        // Turning off a defence for everyone is worth a line in the audit trail
        securityAuditService.captchaToggled(principal.getUsername(), enabled);

        return getSecuritySettings();
    }

    // == GET /api/v1/admin/settings/google ===============================
    @GetMapping("/settings/google")
    @Operation(summary = "Read the two Google Maps feature flags")
    public ResponseEntity<?> getGoogleSettings() {
        return ResponseEntity.ok(googleApiSettings.snapshot());
    }

    // == PUT /api/v1/admin/settings/google ===============================
    // Body: { "google.search": true, "google.stop_eta": false }
    @PutMapping("/settings/google")
    @Operation(summary = "Toggle the Google Maps feature flags")
    public ResponseEntity<?> setGoogleSettings(@RequestBody Map<String, Boolean> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Empty body."));
        }
        try {
            body.forEach((key, value) -> {
                if (value != null) googleApiSettings.set(key, value);
            });
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.ok(googleApiSettings.snapshot());
    }
}
