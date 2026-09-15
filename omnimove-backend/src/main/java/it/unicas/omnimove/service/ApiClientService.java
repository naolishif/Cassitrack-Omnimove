package it.unicas.omnimove.service;

import it.unicas.omnimove.model.ApiClient;
import it.unicas.omnimove.repository.ApiClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Optional;

/**
 * Issues, checks and revokes the keys partner systems present on
 * {@code /api/partner/**}.
 *
 * <p>A key is {@code omk_} followed by 256 random bits in base64url. The
 * plaintext exists only in the response that creates it; the table keeps
 * its SHA-256, so a database read cannot be turned into an API call.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApiClientService {

    public static final String KEY_PREFIX = "omk_";
    /** How much of the key the panel may show to tell keys apart. */
    private static final int VISIBLE_CHARS = 12;
    public static final int MAX_EXPIRY_DAYS = 3650;
    /** last_used_at is a hint for the admin, not a log: one write a minute is plenty. */
    private static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(1);

    private final ApiClientRepository repo;
    private final SecureRandom random = new SecureRandom();

    /** The one moment the plaintext is available. */
    public record IssuedKey(ApiClient client, String plaintext) {}

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
        });
        return found;
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
