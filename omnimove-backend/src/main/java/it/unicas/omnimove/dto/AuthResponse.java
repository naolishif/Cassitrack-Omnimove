package it.unicas.omnimove.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    private String token;
    private String email;
    private String name;
    private String role;
    @JsonProperty("expires_in_ms")
    private long expiresInMs;
    private Long id;
    private String message;

    /** true → el frontend muestra el botón "Olvidé mi contraseña" */
    @JsonProperty("suggest_password_reset")
    private Boolean suggestPasswordReset;

    /**
     * The Google token is valid but belongs to nobody yet, and creating the
     * account would need the privacy notice acknowledged first. No user was
     * written. The page collects the acknowledgement and posts the same
     * credential again.
     */
    private Boolean consentRequired;
}
