package it.unicas.omnimove.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Simple Redis-based rate limiter using the INCR + EXPIRE pattern.
 *
 * Each key maps to a counter in Redis. On the first request within a window
 * the key is created and an expiry is set. Subsequent requests in the same
 * window just increment the counter. When the TTL expires Redis deletes the
 * key automatically, resetting the counter for the next window.
 *
 * Fail-open design: if Redis is unreachable the request is allowed through
 * and a warning is logged. This avoids a Redis outage taking down auth
 * entirely — acceptable for a demo; in production you'd fail-closed or
 * use a circuit-breaker.
 *
 * Limits applied in AuthController:
 *   /register           → 5 attempts per IP  per hour
 *   /resend-verification → 3 attempts per email per hour
 *   /forgot-password    → 3 attempts per email per hour
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {

    private final StringRedisTemplate redis;
    private final SecurityAuditService securityAuditService;

    /**
     * @param key         Unique string identifying the bucket (e.g. "rl:register:192.168.1.1")
     * @param maxRequests Maximum number of requests allowed in the window
     * @param window      Length of the sliding window
     * @return true if the request is within the limit, false if it should be rejected
     */
    public boolean isAllowed(String key, int maxRequests, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) return true; // Redis returned null — fail open

            if (count == 1L) {
                // First request in this window — set the expiry
                redis.expire(key, window);
            }

            if (count > maxRequests) {
                // Pass the raw key — SecurityAuditService handles masking for
                // the log line and stores the full value in the audit table.
                securityAuditService.rateLimitExceeded(key, maxRequests, window);
                return false;
            }
            return true;

        } catch (Exception e) {
            // Redis unavailable — fail open so auth keeps working
            log.warn("[RATE-LIMIT] Redis unavailable, failing open: {}", e.getMessage());
            return true;
        }
    }

    // ── Convenience methods ─────────────────────────────────────────

    /** 5 registrations per IP per hour */
    public boolean allowRegister(String ip) {
        return isAllowed("rl:register:" + ip, 5, Duration.ofHours(1));
    }

    /** 3 resend-verification requests per email per hour */
    public boolean allowResendVerification(String email) {
        return isAllowed("rl:resend:" + email, 3, Duration.ofHours(1));
    }

    /** 3 forgot-password requests per email per hour */
    public boolean allowForgotPassword(String email) {
        return isAllowed("rl:forgot:" + email, 3, Duration.ofHours(1));
    }

    /** 30 journey searches per user per hour */
    public boolean allowJourneySearch(String email) {
        return isAllowed("rl:journey-search:" + email, 30, Duration.ofHours(1));
    }

    /** 60 stop-arrivals lookups per user per hour */
    public boolean allowStopArrivalsLookup(String email) {
        return isAllowed("rl:stop-arrivals:" + email, 60, Duration.ofHours(1));
    }
}
