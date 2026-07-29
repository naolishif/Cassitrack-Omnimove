package it.unicas.omnimove.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.AdminCreateUserRequest;
import it.unicas.omnimove.dto.UserDTO;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.service.AnalyticsService;
import it.unicas.omnimove.service.GoogleApiSettingsService;
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

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AnalyticsService analyticsService;
    private final GoogleApiSettingsService googleApiSettings;
    private final SecurityAuditService securityAuditService;

    private UserDTO toDTO(User u) {
        return UserDTO.builder()
                .id(u.getId())
                .name(u.getName())
                .email(u.getEmail())
                .role(u.getRole())
                .build();
    }

    // ── GET /api/v1/admin/users ───────────────────────────────────────────
    @GetMapping("/users")
    @Operation(summary = "List all users", description = "Returns all registered users. ADMIN only.")
    public ResponseEntity<?> listUsers(
            @AuthenticationPrincipal UserDetails principal) {

        List<UserDTO> users = userRepo.findAll()
                .stream().map(this::toDTO)
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

    // ── GET /api/v1/admin/users/stats ─────────────────────────────────────
    @GetMapping("/users/stats")
    @Operation(summary = "User counts by role", description = "Returns total, admins, travellers. ADMIN only.")
    public ResponseEntity<?> userStats() {

        List<User> all = userRepo.findAll();
        long total     = all.size();
        long admins    = all.stream().filter(u -> "ADMIN".equalsIgnoreCase(u.getRole())).count();
        long travellers= all.stream().filter(u -> "TRAVELLER".equalsIgnoreCase(u.getRole())).count();

        return ResponseEntity.ok(Map.of(
                "total",      total,
                "admins",     admins,
                "travellers", travellers
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
