package it.unicas.omnimove.dto;

import lombok.Data;

/** The ID token Google Identity Services hands to the browser. */
@Data
public class GoogleAuthRequest {
    private String credential;

    /**
     * Only meaningful when this token would CREATE an account.
     *
     * <p>Signing in and signing up are the same gesture with Google — one click,
     * no form — so there is nowhere for the art. 13 acknowledgement to be given.
     * The server therefore refuses to create an account without it and answers
     * {@code consentRequired}, and the page asks then and retries with the same
     * credential. Someone who already has an account never sees any of this: the
     * fields are ignored on a plain sign-in.
     */
    private Boolean privacyNoticeAccepted;

    /** Optional and free, exactly as on the registration form. */
    private Boolean profilingConsent;

    /** Carried over from the cookie banner, as the registration form does. */
    private Boolean cookieNoticeAccepted;
    private String subjectKey;
}
