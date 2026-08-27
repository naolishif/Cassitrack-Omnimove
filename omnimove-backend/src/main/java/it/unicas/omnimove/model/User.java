package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

@Entity @Table(name = "users")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    // ── Email verification ──────────────────────────────────────────
    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(name = "verification_token")
    private String verificationToken;

    @Column(name = "verification_token_expiry")
    private LocalDateTime verificationTokenExpiry;

    // ── Login attempt tracking ──────────────────────────────────────
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    // ── Sign-in provider ────────────────────────────────────────────
    /**
     * Google's "sub" claim — the stable id of the Google account. Null for an
     * account that has never signed in with Google. Not the email: Google lets
     * a user change that, the sub never moves.
     */
    @Column(name = "google_sub", length = 64)
    private String googleSub;

    /** How the account was created: LOCAL or GOOGLE. */
    @Column(name = "auth_provider", nullable = false, length = 20)
    @Builder.Default
    private String authProvider = "LOCAL";

    /** An account created through Google has no password until it sets one. */
    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }

    // ── Activity tracking ───────────────────────────────────────────
    /** First registration: set once, on insert, and never touched again. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Most recent successful access — mirror of the newest login_events row. */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // ── Password reset ──────────────────────────────────────────────
    @Column(name = "reset_password_token")
    private String resetPasswordToken;

    @Column(name = "reset_password_token_expiry")
    private LocalDateTime resetPasswordTokenExpiry;
}
