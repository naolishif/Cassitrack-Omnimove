package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Persistent security-audit record.
 *
 * Logs (via SLF4J / SECURITY_AUDIT logger) mask PII so that log files are
 * safe to store on disk and ship to aggregators.  This entity stores the
 * FULL unmasked details so that authorised security personnel can reconstruct
 * exactly who did what and from where.
 *
 * Access to the underlying table is restricted:
 *   - The app DB user has INSERT only (never SELECT).
 *   - The 'security_auditor' DB role has SELECT only.
 *
 * See migration V12__security_audit_events.sql for the DDL and grants.
 */
@Entity
@Table(name = "security_audit_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Event identifier — mirrors the log token (e.g. LOGIN_SUCCESS, ACCOUNT_LOCKED). */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** Full unmasked email address of the subject, if applicable. */
    @Column(name = "email", length = 100)
    private String email;

    /** Full unmasked IP address of the client, if applicable. */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /**
     * Extra context serialised as a simple key=value string,
     * e.g. "role=ADMIN target=bob@example.com count=42".
     */
    @Column(name = "additional_info", columnDefinition = "TEXT")
    private String additionalInfo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
