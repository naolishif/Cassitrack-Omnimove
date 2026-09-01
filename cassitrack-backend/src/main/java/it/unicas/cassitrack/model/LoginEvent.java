package it.unicas.cassitrack.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One successful access to the system.
 *
 * <p>Kept apart from {@link SecurityAuditEvent}: that table is forensic and the
 * application may only write to it, this one backs the user card and is read on
 * demand. Rows go with the account (ON DELETE CASCADE).
 */
@Entity
@Table(name = "login_events")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LoginEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "logged_in_at", nullable = false)
    private LocalDateTime loggedInAt;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    /** Raw User-Agent header, truncated to the column width. */
    @Column(name = "user_agent", length = 255)
    private String userAgent;
}
