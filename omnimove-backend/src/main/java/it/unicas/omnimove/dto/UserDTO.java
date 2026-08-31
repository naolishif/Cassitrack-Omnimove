package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDTO {
    private Long   id;
    private String name;
    private String email;
    private String role;

    /** When the account was first registered. */
    private LocalDateTime registeredAt;

    /** Most recent access; null for an account that has never logged in. */
    private LocalDateTime lastLoginAt;

    /** Total accesses recorded — drives the "N accesses" hint in the dashboard. */
    private Long loginCount;

    /**
     * Messages from this person that nobody has opened yet.
     *
     * <p>Account metadata, not content: the list view shows that something is
     * waiting, the message itself is only readable from the individual record —
     * the same rule the privacy notice states for journey history.
     */
    private Long unreadMessages;
}
