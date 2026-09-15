package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import it.unicas.cassitrack.model.ApiClient;
import it.unicas.cassitrack.service.ApiClientService;
import it.unicas.cassitrack.service.SecurityAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The partner API keys, from the admin panel.
 *
 * <p>The keys other systems present as {@code X-Api-Key} on the NeTEx,
 * version and SSE feeds. The plaintext is in the response that creates it
 * and nowhere else: the list shows only the prefix, and a lost key is
 * replaced, not recovered. Revocation keeps the row, so the panel can still
 * say which partner had a key, when, and that it was withdrawn.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/api-keys")
@PreAuthorize("hasAnyAuthority('ADMIN', 'ROLE_ADMIN')")
public class ApiKeyAdminController {

    private final ApiClientService apiClientService;
    private final SecurityAuditService securityAuditService;

    public ApiKeyAdminController(ApiClientService apiClientService,
                                 SecurityAuditService securityAuditService) {
        this.apiClientService = apiClientService;
        this.securityAuditService = securityAuditService;
    }

    @GetMapping
    @Operation(summary = "List the partner API keys (prefix only, never the key)")
    public ResponseEntity<?> list() {
        Instant now = Instant.now();
        return ResponseEntity.ok(apiClientService.list().stream()
                .map(c -> row(c, now))
                .collect(Collectors.toList()));
    }

    // Body: { "label": "FARO UniSannio", "expiresInDays": 90 }   0 or absent = never
    @PostMapping
    @Operation(summary = "Issue a partner API key — the plaintext is returned this once")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("message", "Empty body."));

        String label = body.get("label") instanceof String s ? s : null;
        Integer days;
        try {
            Object d = body.get("expiresInDays");
            days = d == null ? null : Integer.valueOf(String.valueOf(d));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "expiresInDays must be a whole number of days."));
        }

        ApiClientService.IssuedKey issued;
        try {
            issued = apiClientService.issue(label, days, auth.getName());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }

        ApiClient c = issued.client();
        securityAuditService.apiKeyIssued(auth.getName(), c.getId(), c.getLabel(),
                c.getExpiresAt() == null ? "never" : c.getExpiresAt().toString());

        Map<String, Object> out = row(c, Instant.now());
        out.put("key", issued.plaintext());
        return ResponseEntity.status(201).body(out);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke a partner API key")
    public ResponseEntity<?> revoke(@PathVariable("id") Long id, Authentication auth) {
        return apiClientService.revoke(id)
                .map(c -> {
                    securityAuditService.apiKeyRevoked(auth.getName(), c.getId(), c.getLabel());
                    return ResponseEntity.ok(row(c, Instant.now()));
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "No key with id " + id)));
    }

    private static Map<String, Object> row(ApiClient c, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         c.getId());
        m.put("label",      c.getLabel());
        m.put("prefix",     c.getKeyPrefix());
        m.put("createdBy",  c.getCreatedBy());
        m.put("createdAt",  c.getCreatedAt());
        m.put("expiresAt",  c.getExpiresAt());
        m.put("revokedAt",  c.getRevokedAt());
        m.put("lastUsedAt", c.getLastUsedAt());
        m.put("status", c.isRevoked() ? "REVOKED" : c.isExpired(now) ? "EXPIRED" : "ACTIVE");
        return m;
    }
}
