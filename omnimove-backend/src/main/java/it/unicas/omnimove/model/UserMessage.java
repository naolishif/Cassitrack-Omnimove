package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

/**
 * A message written by a traveller for whoever runs the service.
 *
 * <p>One direction only, deliberately: the reply goes out by e-mail, to the
 * address on the account. Building a two-way inbox would mean another place
 * where a conversation lives and another set of notifications to keep in step
 * with it — for a feedback channel that is not worth it.
 */
@Entity
@Table(name = "user_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private ZonedDateTime createdAt;

    /** Null until an operator has opened the sender's card. */
    @Column(name = "read_at")
    private ZonedDateTime readAt;
}
