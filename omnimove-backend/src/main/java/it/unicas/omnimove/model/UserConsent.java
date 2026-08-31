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
    /**
     * Acknowledgement of the cookie notice shown by the in-app banner.
     *
     * <p>Deliberately separate from {@link #TYPE_PRIVACY_NOTICE}. The registration
     * form already records that one the moment the account is created, so keying
     * the banner off it would mean the banner never appears for anyone. They also
     * cover different things and different articles: the art. 13 notice describes
     * the processing, the cookie notice covers what is written to the device
     * (art. 122 Codice Privacy), and they are two separate documents.
     */
    public static final String TYPE_COOKIE_NOTICE      = "COOKIE_NOTICE";
    /** Optional: personalised journey suggestions built on travel history. */
    public static final String TYPE_PROFILING          = "PROFILING";
    /** Optional: non-technical third-party assets (only relevant if a CDN is ever reintroduced). */
    public static final String TYPE_THIRD_PARTY        = "THIRD_PARTY_CONTENT";

    /**
     * Reuse of mobility data for the University's scientific research.
     *
     * <p><b>This is an objection register, not a consent register.</b> The lawful
     * basis is art. 6(1)(e) — public interest — not consent, so the absence of any
     * row means the subject IS included. Only a row with {@code granted = false}
     * excludes them, exercising the right to object under art. 21(6).
     *
     * <p>Consequence: unlike a real consent, this must NOT expire when the privacy
     * notice is reworded. See {@code V23__research_tiers.sql} and DPIA §3.2.
     */
    public static final String TYPE_RESEARCH_USE       = "RESEARCH_USE";

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
