package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.SecurityAuditEvent;
import it.unicas.cassitrack.repository.SecurityAuditEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Security-audit facade.
 *
 * Every audit event is handled in two ways:
 *
 *  1. SLF4J log line (SECURITY_AUDIT logger / topic) — PII is MASKED so the
 *     log file is safe to write to disk, ship to aggregators, or grep by ops.
 *
 *  2. DB row in security_audit_events — PII is stored UNMASKED so that
 *     authorised security personnel can run forensic queries.
 *     The INSERT is executed asynchronously so the caller is never blocked
 *     by a slow database write.
 *     The app DB user has INSERT-only on that table; SELECT is reserved for
 *     the 'security_auditor' DB role (see V7 migration).
 */
@Service
@Slf4j(topic = "SECURITY_AUDIT")
@RequiredArgsConstructor
public class SecurityAuditService {

    private final SecurityAuditEventRepository auditRepo;

    // ── PII masking helpers (used for log lines only) ─────────────────────

    /** Masks email to first char + *** + domain: m***@example.com */
    private String maskEmail(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    /**
     * Truncates the last octet/group of an IP address.
     * IPv4: 192.168.1.123 → 192.168.1.xxx
     * IPv6: 2001:db8::1   → 2001:db8:xxx  (loopback ::1 kept as-is)
     */
    private String maskIp(String ip) {
        if (ip == null) return "null";
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            if (parts.length <= 2) return ip;
            return parts[0] + ":" + parts[1] + ":xxx";
        }
        int last = ip.lastIndexOf('.');
        if (last < 0) return ip;
        return ip.substring(0, last) + ".xxx";
    }

    // ── DB persistence (async, fire-and-forget) ───────────────────────────

    @Async
    protected void persist(String eventType, String email, String ip, String additionalInfo) {
        try {
            auditRepo.save(SecurityAuditEvent.builder()
                    .eventType(eventType)
                    .email(email)
                    .ipAddress(ip)
                    .additionalInfo(additionalInfo)
                    .build());
        } catch (Exception ex) {
            // Never let a DB failure silence the log line that was already written
            log.error("Failed to persist audit event type={} : {}", eventType, ex.getMessage());
        }
    }

    // ── Audit methods ─────────────────────────────────────────────────────

    public void registration(String email, String ip) {
        log.info("REGISTRATION email={} ip={}", maskEmail(email), maskIp(ip));
        persist("REGISTRATION", email, ip, null);
    }

    public void loginSuccess(String email, String ip) {
        log.info("LOGIN_SUCCESS email={} ip={}", maskEmail(email), maskIp(ip));
        persist("LOGIN_SUCCESS", email, ip, null);
    }

    public void loginFailure(String email, String ip) {
        log.warn("LOGIN_FAILURE email={} ip={}", maskEmail(email), maskIp(ip));
        persist("LOGIN_FAILURE", email, ip, null);
    }

    public void accountLocked(String email) {
        log.warn("ACCOUNT_LOCKED email={}", maskEmail(email));
        persist("ACCOUNT_LOCKED", email, null, null);
    }

    public void logout(String email) {
        log.info("LOGOUT email={}", maskEmail(email));
        persist("LOGOUT", email, null, null);
    }

    public void adminUserCreated(String adminEmail, String targetEmail, String role) {
        log.info("ADMIN_USER_CREATED admin={} target={} role={}",
                maskEmail(adminEmail), maskEmail(targetEmail), role);
        persist("ADMIN_USER_CREATED", adminEmail, null,
                "target=" + targetEmail + " role=" + role);
    }

    public void adminUserUpdated(String adminEmail, long targetId, String targetEmail) {
        log.info("ADMIN_USER_UPDATED admin={} targetId={} target={}",
                maskEmail(adminEmail), targetId, maskEmail(targetEmail));
        persist("ADMIN_USER_UPDATED", adminEmail, null,
                "targetId=" + targetId + " target=" + targetEmail);
    }

    public void adminUserDeleted(String adminEmail, long targetId, String targetEmail) {
        log.warn("ADMIN_USER_DELETED admin={} targetId={} target={}",
                maskEmail(adminEmail), targetId, maskEmail(targetEmail));
        persist("ADMIN_USER_DELETED", adminEmail, null,
                "targetId=" + targetId + " target=" + targetEmail);
    }

    /**
     * Rate-limit / login-lockout exceeded.
     *
     * @param rawKey      Raw identifier — email or IP — as stored in Redis.
     *                    PII is extracted and masked here for the log line;
     *                    the full value is stored in the DB row.
     * @param maxRequests Configured limit for that bucket.
     * @param window      Window duration.
     */
    public void rateLimitExceeded(String rawKey, int maxRequests, Object window) {
        String masked = maskRateLimitKey(rawKey);
        log.warn("RATE_LIMIT_EXCEEDED key='{}' limit={} window={}", masked, maxRequests, window);

        String identifier = extractIdentifier(rawKey);
        boolean isEmail   = identifier != null && identifier.contains("@");
        persist("RATE_LIMIT_EXCEEDED",
                isEmail ? identifier : null,
                isEmail ? null : identifier,
                "key=" + rawKey + " limit=" + maxRequests + " window=" + window);
    }

    /**
     * An MQTT message arrived on a monitored topic with an invalid / unparseable payload.
     * Recorded as a security event because malformed payloads can indicate spoofing attempts.
     *
     * @param topic  The MQTT topic the bad message arrived on.
     * @param detail Optional extra context (e.g. parse error message).
     */
    public void mqttInvalidPayload(String topic, String detail) {
        log.warn("MQTT_INVALID_PAYLOAD topic={}", topic);
        persist("MQTT_INVALID_PAYLOAD", null, null,
                "topic=" + topic + (detail != null ? " detail=" + detail : ""));
    }

    // ── Rate-limit key helpers ────────────────────────────────────────────

    private String extractIdentifier(String key) {
        if (key == null) return null;
        int last = key.lastIndexOf(':');
        return last >= 0 ? key.substring(last + 1) : key;
    }

    private String maskRateLimitKey(String key) {
        if (key == null) return "***";
        int last = key.lastIndexOf(':');
        if (last < 0) return "***";
        String prefix     = key.substring(0, last + 1);
        String identifier = key.substring(last + 1);
        int at = identifier.indexOf('@');
        if (at > 0) return prefix + identifier.charAt(0) + "***" + identifier.substring(at);
        int dot = identifier.lastIndexOf('.');
        if (dot > 0) return prefix + identifier.substring(0, dot) + ".xxx";
        if (identifier.contains(":")) {
            String[] parts = identifier.split(":");
            return prefix + parts[0] + ":xxx";
        }
        return prefix + "***";
    }
}
