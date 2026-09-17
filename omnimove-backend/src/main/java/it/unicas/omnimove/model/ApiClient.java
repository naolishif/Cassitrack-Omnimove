package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A partner system allowed to call {@code /api/partner/**}.
 *
 * <p>Only the SHA-256 of the key is kept: the plaintext is shown once, at
 * creation, and never again. {@link #keyPrefix} is the readable handle the
 * admin panel uses to tell the keys apart.
 */
@Entity
@Table(name = "api_clients")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApiClient {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who the key was issued to — "Partner A", "Partner B staging"… */
    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** {@code null} means the key does not expire. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Usable right now: neither revoked nor past its expiry. */
    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }
}
