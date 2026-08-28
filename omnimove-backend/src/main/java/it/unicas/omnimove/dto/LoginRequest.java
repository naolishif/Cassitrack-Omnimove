package it.unicas.omnimove.dto;
import lombok.Data;
@Data
public class LoginRequest {
    private String email;
    private String password;
    /** Solved reCAPTCHA, when the administrator has the check switched on. */
    private String captchaToken;
}
