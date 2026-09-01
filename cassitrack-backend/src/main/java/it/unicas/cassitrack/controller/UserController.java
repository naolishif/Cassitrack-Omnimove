package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import it.unicas.cassitrack.dto.RegisterRequest;
import it.unicas.cassitrack.dto.UserDTO;
import it.unicas.cassitrack.model.User;
import it.unicas.cassitrack.service.UserService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@CrossOrigin
@PreAuthorize("hasAnyAuthority('ADMIN', 'ROLE_ADMIN')")
public class UserController {

    private final UserService userService;
    private final it.unicas.cassitrack.service.ManagerActivityService activityService;

    public UserController(UserService userService,
                          it.unicas.cassitrack.service.ManagerActivityService activityService) {
        this.userService = userService;
        this.activityService = activityService;
    }

    // ─────────────────────────────────────────────────────────────────
    // ACTIVITY — who has been in, and what has left with them
    // ─────────────────────────────────────────────────────────────────

    /**
     * The user panel's list: every account with when it was created, when it was
     * last used, and how much it has done.
     *
     * <p>The two totals come from one grouped query each rather than two per
     * row — the N+1 that makes a panel feel slow as soon as there is more than a
     * handful of accounts.
     */
    @GetMapping("/activity")
    @Operation(summary = "Accounts with their access and download totals")
    public ResponseEntity<List<Map<String, Object>>> activity() {
        Map<Long, long[]> totals = activityService.totalsByUser();

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (User u : userService.getAllUsersRaw()) {
            long[] t = totals.getOrDefault(u.getId(), new long[2]);

            // Exactly what GET /users returns, masking included, plus the
            // activity. A superset rather than a second shape: the admin table
            // reads this one instead, and Edit still finds every field it fills
            // its form from.
            UserDTO dto = UserDTO.from(u);

            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id",          dto.getId());
            row.put("taxId",       dto.getTaxId());
            row.put("name",        dto.getName());
            row.put("surname",     dto.getSurname());
            row.put("email",       dto.getEmail());
            row.put("telephone",   dto.getTelephone());
            row.put("role",        dto.getRole());
            row.put("createdAt",   u.getCreatedAt());
            row.put("lastLoginAt", u.getLastLoginAt());
            row.put("logins",      t[0]);
            row.put("downloads",   t[1]);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * One account's card: its accesses and its downloads, newest first.
     *
     * <p>No file content is held anywhere, so a download reads as what it
     * covered — the table, the format, the filters and how many rows.
     */
    @GetMapping("/{id}/activity")
    @Operation(summary = "One account's accesses and downloads")
    public ResponseEntity<Map<String, Object>> userActivity(@PathVariable Long id) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();

        body.put("logins", activityService.logins(id).stream().map(e -> {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("at",        e.getLoggedInAt());
            r.put("ip",        e.getIpAddress());
            r.put("userAgent", e.getUserAgent());
            return r;
        }).toList());

        body.put("downloads", activityService.downloads(id).stream().map(x -> {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("at",       x.getExportedAt());
            r.put("dataset",  x.getDataset());
            r.put("format",   x.getFormat());
            r.put("rows",     x.getRowCount());
            r.put("detail",   x.getDetail());
            return r;
        }).toList());

        body.put("loginCount",    activityService.loginCount(id));
        body.put("downloadCount", activityService.downloadCount(id));
        return ResponseEntity.ok(body);
    }

    // ─────────────────────────────────────────────────────────────────
    // GET ALL USERS
    // ─────────────────────────────────────────────────────────────────
    @GetMapping
    @Operation(summary = "Get all users to populate the admin dashboard")
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        log.info("REST request to get all users");
        return ResponseEntity.ok(userService.getAllUsers());
    }

    // ─────────────────────────────────────────────────────────────────
    // CREATE USER (MAPPED WITH REGISTER_REQUEST DTO FOR VALIDATION)
    // ─────────────────────────────────────────────────────────────────
    @PostMapping
    @Operation(summary = "Create a new user verifying strong password rules")
    public ResponseEntity<User> createUser(
            @Valid @RequestBody RegisterRequest registerRequest
    ) {
        log.info("REST request to create user with email: {}", registerRequest.getEmail());

        // We pass the validated DTO to the service layer
        User createdUser = userService.createUser(registerRequest);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(createdUser);
    }

    // ─────────────────────────────────────────────────────────────────
    // UPDATE USER (MAPPED WITH REGISTER_REQUEST DTO)
    // ─────────────────────────────────────────────────────────────────
    @PutMapping("/{id}")
    @Operation(summary = "Update an existing user verifying password optional rules")
    public ResponseEntity<User> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody RegisterRequest registerRequest
    ) {
        log.info("REST request to update user ID: {}", id);

        User updatedUser = userService.updateUser(id, registerRequest);
        return ResponseEntity.ok(updatedUser);
    }

    // ─────────────────────────────────────────────────────────────────
    // DELETE USER
    // ─────────────────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a user from the system")
    public ResponseEntity<Void> deleteUser(
            @PathVariable Long id
    ) {
        log.info("REST request to delete user ID: {}", id);
        userService.deleteUser(id);
        return ResponseEntity.noContent().build(); // 204 No Content
    }

    // ─────────────────────────────────────────────────────────────────
    // ERROR HANDLER
    // ─────────────────────────────────────────────────────────────────
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid request");
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<String> handleSecurityException(SecurityException ex) {
        log.warn("Authorization denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
    }
}