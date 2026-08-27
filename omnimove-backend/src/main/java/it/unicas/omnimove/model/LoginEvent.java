package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One successful access to the app.
 *
 * Kept separate from {@link SecurityAuditEvent}: that table is forensic and
 * write-only for the application, this one backs the admin dashboard and is
 * read on demand. Rows disappear with the user (ON DELETE CASCADE).
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
