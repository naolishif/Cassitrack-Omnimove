package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.AdminCreateUserRequest;
import it.unicas.omnimove.dto.AdminUpdateUserRequest;
import it.unicas.omnimove.dto.LoginEventDTO;
import it.unicas.omnimove.dto.UserDTO;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.service.ActiveSessionService;
import it.unicas.omnimove.service.AnalyticsService;
import it.unicas.omnimove.service.GoogleApiSettingsService;
import it.unicas.omnimove.service.LoginHistoryService;
import it.unicas.omnimove.service.TravellerProfileService;
import it.unicas.omnimove.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final GoogleApiSettingsService googleApiSettings;
    private final SecurityAuditService securityAuditService;
    private final LoginHistoryService loginHistoryService;
    private final TravellerProfileService travellerProfileService;
    private final ActiveSessionService activeSessionService;

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

        List<UserDTO> users = userRepo.findAll()
                .stream()
                .map(u -> toDTO(u, loginCounts.getOrDefault(u.getId(), 0L)))
                .collect(Collectors.toList());

        securityAuditService.adminListedUsers(principal.getUsername(), users.size());
        return ResponseEntity.ok(users);
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
            account.put("registeredAt", user.getCreatedAt());
            account.put("lastLoginAt",  user.getLastLoginAt());
            account.put("loginCount",   loginHistoryService.count(user.getId()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("account", account);

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
            @RequestParam(value = "range", defaultValue = "1M") String range) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kpis",             analyticsService.getSummaryKpis(range));
        payload.put("modeDistribution", analyticsService.getModeDistribution(range));
        payload.put("modeByHour",       analyticsService.getModeByHour(range));
        payload.put("greenIndexTrend",  analyticsService.getGreenIndexTrend(range));
        payload.put("dayOfWeek",        analyticsService.getModeByDayOfWeek(range));
        payload.put("topRoutes",        analyticsService.getTopRoutes(range));

        return ResponseEntity.ok(payload);
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
