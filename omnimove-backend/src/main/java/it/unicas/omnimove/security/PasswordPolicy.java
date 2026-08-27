package it.unicas.omnimove.security;

import java.util.regex.Pattern;

/**
 * The one definition of what counts as an acceptable password.
 *
 * It is enforced on three different paths — sign-up, reset-by-email, and the
 * traveller's own account page — and the rule and the sentence that explains it
 * have to agree on all three. Kept here so a change to the rule cannot leave a
 * stale message, or a path enforcing yesterday's policy, behind.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    private static final Pattern STRONG = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{" + MIN_LENGTH + ",}$");

    private PasswordPolicy() {}

    public static boolean isValid(String password) {
        return password != null
                && password.length() >= MIN_LENGTH
                && STRONG.matcher(password).matches();
    }

    /** English wording — the default for API clients and the OpenAPI docs. */
    public static String message() {
        return message("en");
    }

    /**
     * Wording shown to whoever tripped the rule, in their language.
     *
     * The pages mirror this rule in JavaScript for live feedback, but the
     * server's answer is what the user reads when the mirror is bypassed, so it
     * cannot stay English-only.
     */
    public static String message(String lang) {
        if ("it".equalsIgnoreCase(lang))
            return "La password deve contenere almeno " + MIN_LENGTH + " caratteri e includere "
                 + "una lettera maiuscola, una minuscola, un numero e un carattere speciale.";

        return "Password must be at least " + MIN_LENGTH + " characters and include "
             + "an uppercase letter, a lowercase letter, a number, and a special character.";
    }
}
