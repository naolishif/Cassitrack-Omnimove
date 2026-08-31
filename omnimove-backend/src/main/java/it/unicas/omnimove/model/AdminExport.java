package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

/**
 * A copy of data taken out of the system by an operator.
 *
 * <p>The forensic record of the same act lives in {@code security_audit_events},
 * which the application may only write to. This one exists so the operator's own
 * card can show what they downloaded and when — accountability the people who
 * run the service can see, rather than only an auditor with database access.
 */
@Entity
@Table(name = "admin_exports")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdminExport {

    /** Aggregate analytics — no personal data leaves with it. */
    public static final String KIND_ANALYTICS = "ANALYTICS";
    /** The user list: names and e-mail addresses. */
    public static final String KIND_USER_LIST = "USER_LIST";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String kind;

    /** What was covered: format and period, or filters and row count. */
    @Column(length = 255)
    private String detail;

    @Column(name = "exported_at", nullable = false)
    private ZonedDateTime exportedAt;
}
