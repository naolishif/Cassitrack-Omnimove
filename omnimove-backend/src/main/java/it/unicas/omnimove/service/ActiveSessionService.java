package it.unicas.omnimove.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Live count of who is signed in, for the admin dashboard.
 *
 * Authentication is stateless — there is no server-side session to count — so
 * one Redis key is opened per issued token and left to expire with it. A key
 * that is still there means a token that has not expired and was not revoked:
 *
 *   login   → key created with TTL = the token's own lifetime
 *   logout  → key deleted alongside the blacklist entry
 *   expiry  → Redis drops the key on its own, no sweeper needed
 *
 * Keyed by a hash of the token, not the token: two devices are two sessions
 * and signing out of one must not clear the other, but the dashboard has no
 * business holding credentials it never needs to read back.
 *
 * Fail-soft like {@link RateLimiterService}: a Redis outage must never break a
 * login; so every call swallows its errors and the count reports -1 (unknown)
 * rather than a wrong number.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ActiveSessionService {

    private static final String PREFIX = "session:";

    /** Reported when Redis cannot answer — the dashboard renders it as "—". */
    public static final long UNKNOWN = -1L;

    private final StringRedisTemplate redis;

    /** Opens a session that dies with the token that created it. */
    public void open(String token, String email, long ttlMs) {
        if (ttlMs <= 0) return;
        try {
            redis.opsForValue().set(PREFIX + fingerprint(token), email, Duration.ofMillis(ttlMs));
        } catch (Exception e) {
            log.warn("[SESSIONS] Could not open session: {}", e.getMessage());
        }
    }

    /** Closes one session. Deleting a key that already expired is a no-op. */
    public void close(String token) {
        try {
            redis.delete(PREFIX + fingerprint(token));
        } catch (Exception e) {
            log.warn("[SESSIONS] Could not close session: {}", e.getMessage());
        }
    }

    /**
     * Number of tokens currently alive, or {@link #UNKNOWN}.
     * SCAN rather than KEYS: this runs on every dashboard refresh and must not
     * block Redis for other callers.
     */
    public long count() {
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(PREFIX + "*").count(500).build())) {

            long n = 0;
            while (cursor.hasNext()) { cursor.next(); n++; }
            return n;

        } catch (Exception e) {
            log.warn("[SESSIONS] Could not count sessions: {}", e.getMessage());
            return UNKNOWN;
        }
    }

    private String fingerprint(String token) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
