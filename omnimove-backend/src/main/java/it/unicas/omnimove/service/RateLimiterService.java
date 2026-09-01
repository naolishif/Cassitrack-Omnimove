package it.unicas.omnimove.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Value;

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
 *   /google             → 20 attempts per IP    per hour
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {

    private final StringRedisTemplate redis;
    private final SecurityAuditService securityAuditService;

    /**
     * Journey searches allowed per account per hour.
     *
     * <p>Configurable rather than fixed because the right number depends on the
     * deployment: this is not an abuse limit so much as a cost ceiling. A search
     * can call Google Distance Matrix, so every one of these is potentially a
     * billed request — which is also why raising it is a decision about the
     * Google bill, not about security.
     *
     * <p>The bucket is keyed by the signed-in account, so reaching it already
     * requires a verified one. 30/hour — one search every two minutes — was
     * tight for someone genuinely comparing routes.
     */
    @Value("${omnimove.ratelimit.journey-search-per-hour:120}")
    private int journeySearchPerHour;

    /** Stop-arrival lookups per account per hour. Cheap: served from our own cache. */
    @Value("${omnimove.ratelimit.stop-arrivals-per-hour:300}")
    private int stopArrivalsPerHour;

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

    /**
     * 20 Google sign-ins per IP per hour. Looser than the email-keyed limits:
     * a whole university NAT can share one address, and every attempt here is
     * already a token Google itself had to mint.
     */
    public boolean allowGoogleLogin(String ip) {
        return isAllowed("rl:google:" + ip, 20, Duration.ofHours(1));
    }

    /** 3 forgot-password requests per email per hour */
    public boolean allowForgotPassword(String email) {
        return isAllowed("rl:forgot:" + email, 3, Duration.ofHours(1));
    }

    /** Journey searches per user per hour; see {@link #journeySearchPerHour}. */
    public boolean allowJourneySearch(String email) {
        return isAllowed("rl:journey-search:" + email, journeySearchPerHour, Duration.ofHours(1));
    }

    /** Stop-arrivals lookups per user per hour. */
    public boolean allowStopArrivalsLookup(String email) {
        return isAllowed("rl:stop-arrivals:" + email, stopArrivalsPerHour, Duration.ofHours(1));
    }

    /**
     * One message to the administrators every ten minutes.
     *
     * <p>Not an abuse counter so much as a pause. Every message is stored
     * against the account and answered by an automatic e-mail, so a jammed
     * button — or a frustrated traveller sending the same complaint five times —
     * fills the operator's inbox with the same report and buries the ones that
     * are different. Ten minutes is long enough to be a deliberate second
     * message and short enough that nobody with something to add is stopped.
     */
    public boolean allowUserMessage(String email) {
        return isAllowed("rl:user-message:" + email, 1, Duration.ofMinutes(10));
    }
}
