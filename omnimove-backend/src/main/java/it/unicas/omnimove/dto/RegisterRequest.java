package it.unicas.omnimove.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[^<>\"']*", message = "Name contains invalid characters")
    private String name;

    @NotBlank
    @Email
    @Size(max = 150)
    private String email;

    private String password;
    private String confirmPassword;

    /**
     * The user ticked "I have read the privacy notice". Mandatory: registration is
     * refused without it, because we cannot process the account lawfully unless the
     * art. 13 information has actually been presented.
     *
     * <p>This is an acknowledgement, not consent under art. 6(1)(a) — the lawful
     * basis for the account itself is performance of the contract.
     */
    private Boolean privacyNoticeAccepted;

    /**
     * Optional and free: personalised suggestions derived from travel history.
     * Registration must succeed whether this is true, false or absent.
     */
    private Boolean profilingConsent;

    /** Ties any choice already made in the cookie banner to the new account. */
    private String subjectKey;
}
