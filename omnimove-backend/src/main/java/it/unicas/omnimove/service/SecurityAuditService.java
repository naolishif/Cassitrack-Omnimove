package it.unicas.omnimove.service;

import it.unicas.omnimove.model.SecurityAuditEvent;
import it.unicas.omnimove.repository.SecurityAuditEventRepository;
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
 *     the 'security_auditor' DB role (see V12 migration).
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
            if (parts.length <= 2) return ip; // loopback ::1
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

    public void emailVerified(String email) {
        log.info("EMAIL_VERIFIED email={}", maskEmail(email));
        persist("EMAIL_VERIFIED", email, null, null);
    }

    public void passwordReset(String email) {
        log.info("PASSWORD_RESET email={}", maskEmail(email));
        persist("PASSWORD_RESET", email, null, null);
    }

    public void passwordResetRequested(String email) {
        log.info("PASSWORD_RESET_REQUESTED email={}", maskEmail(email));
        persist("PASSWORD_RESET_REQUESTED", email, null, null);
    }

    public void accountDeleted(String email) {
        log.warn("ACCOUNT_DELETED email={}", maskEmail(email));
        persist("ACCOUNT_DELETED", email, null, null);
    }

    public void weakPasswordRejected(String email, String ip) {
        log.warn("WEAK_PASSWORD_REJECTED email={} ip={}", maskEmail(email), maskIp(ip));
        persist("WEAK_PASSWORD_REJECTED", email, ip, null);
    }

    public void adminUserCreated(String adminEmail, String targetEmail, String role) {
        log.info("ADMIN_USER_CREATED admin={} target={} role={}",
                maskEmail(adminEmail), maskEmail(targetEmail), role);
        persist("ADMIN_USER_CREATED", adminEmail, null,
                "target=" + targetEmail + " role=" + role);
    }

    public void adminUserDeleted(String adminEmail, long targetId, String targetEmail) {
        log.warn("ADMIN_USER_DELETED admin={} targetId={} target={}",
                maskEmail(adminEmail), targetId, maskEmail(targetEmail));
        persist("ADMIN_USER_DELETED", adminEmail, null,
                "targetId=" + targetId + " target=" + targetEmail);
    }

    public void adminListedUsers(String adminEmail, int count) {
        log.info("ADMIN_USER_LIST_FETCHED admin={} count={}", maskEmail(adminEmail), count);
        persist("ADMIN_USER_LIST_FETCHED", adminEmail, null, "count=" + count);
    }

    /**
     * Traveller changed their own email address from within the profile page.
     * Both old and new email are stored in the DB for forensic tracing.
     */
    public void profileEmailChanged(String oldEmail, String newEmail) {
        log.warn("PROFILE_EMAIL_CHANGED old={} new={}", maskEmail(oldEmail), maskEmail(newEmail));
        persist("PROFILE_EMAIL_CHANGED", oldEmail, null, "newEmail=" + newEmail);
    }

    /** Traveller changed their own password from within the profile page (not via reset token). */
    public void passwordChanged(String email) {
        log.warn("PASSWORD_CHANGED email={}", maskEmail(email));
        persist("PASSWORD_CHANGED", email, null, null);
    }

    /** Traveller updated their profile (name or any field other than email/password). */
    public void profileUpdated(String email) {
        log.info("PROFILE_UPDATED email={}", maskEmail(email));
        persist("PROFILE_UPDATED", email, null, null);
    }

    /** A new verification email was resent at the user's request. */
    public void verificationEmailResent(String email) {
        log.info("VERIFICATION_EMAIL_RESENT email={}", maskEmail(email));
        persist("VERIFICATION_EMAIL_RESENT", email, null, null);
    }

    /**
     * Rate-limit exceeded.
     *
     * @param rawKey      The raw Redis bucket key, e.g. "rl:register:192.168.1.5"
     *                    or "rl:forgot:user@example.com". PII is extracted here
     *                    for the DB row and masked for the log line.
     * @param maxRequests Configured limit for that bucket.
     * @param window      Window duration.
     */
    public void rateLimitExceeded(String rawKey, int maxRequests, Object window) {
        String maskedKey = maskRateLimitKey(rawKey);
        log.warn("RATE_LIMIT_EXCEEDED key='{}' limit={} window={}", maskedKey, maxRequests, window);

        // Extract the identifier (IP or email) from "rl:<type>:<identifier>"
        String identifier = extractIdentifier(rawKey);
        boolean isEmail   = identifier != null && identifier.contains("@");
        persist("RATE_LIMIT_EXCEEDED",
                isEmail ? identifier : null,
                isEmail ? null : identifier,
                "key=" + rawKey + " limit=" + maxRequests + " window=" + window);
    }

    // ── Rate-limit key helpers ────────────────────────────────────────────

    /** "rl:register:192.168.1.5" → "192.168.1.5" */
    private String extractIdentifier(String key) {
        if (key == null) return null;
        int last = key.lastIndexOf(':');
        return last >= 0 ? key.substring(last + 1) : key;
    }

    /** Masks the PII segment of a rate-limit key for the log line. */
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
