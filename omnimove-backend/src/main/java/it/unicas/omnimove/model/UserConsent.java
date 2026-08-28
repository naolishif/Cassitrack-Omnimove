package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

/**
 * One row per consent decision — GDPR art. 7(1) requires the controller to be
 * able to demonstrate that consent was given.
 *
 * <p>The table is append-only: withdrawing consent inserts a new row with
 * {@code granted = false}. Never update or delete rows here except when the
 * retention period expires.
 */
@Entity
@Table(name = "user_consents")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserConsent {

    /** Acknowledgement of the art. 13 privacy notice — mandatory, not "consent" in the art. 6(1)(a) sense. */
    public static final String TYPE_PRIVACY_NOTICE     = "PRIVACY_NOTICE";
    /** Optional: personalised journey suggestions built on travel history. */
    public static final String TYPE_PROFILING          = "PROFILING";
    /** Optional: non-technical third-party assets (only relevant if a CDN is ever reintroduced). */
    public static final String TYPE_THIRD_PARTY        = "THIRD_PARTY_CONTENT";

    public static final String SOURCE_REGISTRATION = "REGISTRATION";
    public static final String SOURCE_BANNER       = "BANNER";
    public static final String SOURCE_SETTINGS     = "SETTINGS";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null while the visitor is anonymous (banner choice made before signing up). */
    @Column(name = "user_id")
    private Long userId;

    /** Opaque browser-side id, lets an anonymous choice be reconciled after registration. */
    @Column(name = "subject_key", length = 64)
    private String subjectKey;

    @Column(name = "consent_type", nullable = false, length = 40)
    private String consentType;

    @Column(nullable = false)
    private boolean granted;

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "recorded_at", nullable = false)
    private ZonedDateTime recordedAt;
}
