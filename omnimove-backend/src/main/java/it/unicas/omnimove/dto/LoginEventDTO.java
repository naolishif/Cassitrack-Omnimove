package it.unicas.omnimove.dto;

import lombok.*;

import java.time.LocalDateTime;

/** One row of a user's access history, as shown in the admin dashboard. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LoginEventDTO {
    private Long          id;
    private LocalDateTime loggedInAt;
    private String        ipAddress;
    private String        userAgent;
}
