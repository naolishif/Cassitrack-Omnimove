package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.ApiClient;
import it.unicas.cassitrack.model.ApiClientUsage;
import it.unicas.cassitrack.repository.ApiClientRepository;
import it.unicas.cassitrack.repository.ApiClientUsageRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Issues, checks and revokes the keys partner systems present on the
 * interoperability feeds ({@code /api/static/**}, the SSE stream).
 *
 * <p>A key is {@code ctk_} followed by 256 random bits in base64url. The
 * plaintext exists only in the response that creates it; the table keeps
 * its SHA-256, so a database read cannot be turned into a feed request.
 *
 * <p>{@link #authorizeFeed(String)} is the one check the feed controllers
 * make: it accepts the OmniMove token ({@code SSE_API_TOKEN}) exactly as
 * before, and any partner key that is neither revoked nor expired.
 */
@Service
@Slf4j
public class ApiClientService {

    public static final String KEY_PREFIX = "ctk_";
    /** The caller name reported when the OmniMove token, not a partner key, was used. */
    public static final String OMNIMOVE_CALLER = "omnimove";
    /** How much of the key the panel may show to tell keys apart. */
    private static final int VISIBLE_CHARS = 12;
    public static final int MAX_EXPIRY_DAYS = 3650;
    /** last_used_at is a hint for the admin, not a log: one write a minute is plenty. */
    private static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(1);

    private final ApiClientRepository repo;
    private final ApiClientUsageRepository usage;
    private final byte[] omnimoveToken;
    private final SecureRandom random = new SecureRandom();

    public ApiClientService(ApiClientRepository repo, ApiClientUsageRepository usage,
                            @Value("${sse.api-token}") String omnimoveToken) {
        this.repo = repo;
        this.usage = usage;
        this.omnimoveToken = omnimoveToken.getBytes(StandardCharsets.UTF_8);
    }

    /** The one moment the plaintext is available. */
    public record IssuedKey(ApiClient client, String plaintext) {}

    /**
     * Who is behind an {@code X-Api-Key} on a feed, if anyone: the OmniMove
     * token gives {@link #OMNIMOVE_CALLER}, a valid partner key gives its
     * label, anything else gives empty. Constant-time on the token compare,
     * hash lookup on the key, so neither path leaks by timing.
     */
    @Transactional
    public Optional<String> authorizeFeed(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        if (MessageDigest.isEqual(omnimoveToken, raw.getBytes(StandardCharsets.UTF_8)))
            return Optional.of(OMNIMOVE_CALLER);
        return authenticate(raw).map(ApiClient::getLabel);
    }

    /**
     * @param expiresInDays {@code null} or {@code 0} for a key that does not expire
     */
    @Transactional
    public IssuedKey issue(String label, Integer expiresInDays, String createdBy) {
        if (label == null || label.isBlank())
            throw new IllegalArgumentException("A label is required.");
        if (label.length() > 100)
            throw new IllegalArgumentException("Label must be 100 characters or fewer.");
        if (expiresInDays != null && (expiresInDays < 0 || expiresInDays > MAX_EXPIRY_DAYS))
            throw new IllegalArgumentException("Expiry must be between 0 and " + MAX_EXPIRY_DAYS + " days.");

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String plaintext = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        Instant now = Instant.now();
        ApiClient client = ApiClient.builder()
                .label(label.trim())
                .keyPrefix(plaintext.substring(0, VISIBLE_CHARS))
                .keyHash(sha256(plaintext))
                .createdBy(createdBy)
                .createdAt(now)
                .expiresAt(expiresInDays == null || expiresInDays == 0
                        ? null : now.plus(expiresInDays, ChronoUnit.DAYS))
                .build();

        return new IssuedKey(repo.save(client), plaintext);
    }

    /**
     * The client behind a presented key, if the key is known and still valid.
     * A revoked or expired key is treated exactly like an unknown one.
     */
    @Transactional
    public Optional<ApiClient> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) return Optional.empty();

        Instant now = Instant.now();
        Optional<ApiClient> found = repo.findByKeyHash(sha256(rawKey.trim()))
                .filter(c -> c.isActive(now));

        found.ifPresent(c -> {
            if (c.getLastUsedAt() == null
                    || Duration.between(c.getLastUsedAt(), now).compareTo(LAST_USED_GRANULARITY) > 0) {
                c.setLastUsedAt(now);
                repo.save(c);
            }
            recordUsage(c, now);
        });
        return found;
    }

    /**
     * One row per authenticated call, for the usage chart in the admin panel.
     * The endpoint is read from the request this runs in — every caller is a
     * request handler or filter — so no call site has to pass it along.
     */
    private void recordUsage(ApiClient c, Instant now) {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            var req = attrs.getRequest();
            String path = req.getRequestURI().substring(req.getContextPath().length());
            if (path.length() > 200) path = path.substring(0, 200);
            usage.save(ApiClientUsage.builder()
                    .clientId(c.getId()).method(req.getMethod()).endpoint(path).calledAt(now).build());
        } catch (Exception e) {
            log.debug("API key usage not recorded: {}", e.getMessage());
        }
    }

    public record EndpointCount(String method, String endpoint, long count) {}
    public record DayCount(String day, long count) {}
    public record Usage(long total, List<EndpointCount> byEndpoint, List<DayCount> byDay) {}

    /** How a key has been used: in total, per endpoint, and per day over the last {@code days}. */
    @Transactional(readOnly = true)
    public Optional<Usage> usage(Long id, int days) {
        return repo.findById(id).map(c -> {
            Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
            List<EndpointCount> byEndpoint = usage.countByEndpoint(id).stream()
                    .map(r -> new EndpointCount((String) r[0], (String) r[1], ((Number) r[2]).longValue()))
                    .collect(Collectors.toList());
            List<DayCount> byDay = usage.countByDay(id, since).stream()
                    .map(r -> new DayCount(String.valueOf(r[0]), ((Number) r[1]).longValue()))
                    .collect(Collectors.toList());
            return new Usage(usage.countByClientId(id), byEndpoint, byDay);
        });
    }

    /** @return the client, or empty if there is no key with that id */
    @Transactional
    public Optional<ApiClient> revoke(Long id) {
        return repo.findById(id).map(c -> {
            if (!c.isRevoked()) {
                c.setRevokedAt(Instant.now());
                repo.save(c);
            }
            return c;
        });
    }

    public List<ApiClient> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
