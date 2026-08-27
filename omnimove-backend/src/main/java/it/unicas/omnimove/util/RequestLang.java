package it.unicas.omnimove.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Which language to answer a request in.
 *
 * The pages send X-Omnimove-Lang, but not every entry point carries it — the
 * email verification link is a plain GET from a mail client — so the browser's
 * own Accept-Language is the fallback. Shared rather than repeated: two copies
 * would eventually disagree about what counts as Italian.
 */
public final class RequestLang {

    private RequestLang() {}

    public static String of(HttpServletRequest request) {
        if (request == null) return "en";

        String header = request.getHeader("X-Omnimove-Lang");
        if (header != null) return header.equalsIgnoreCase("it") ? "it" : "en";

        String accept = request.getHeader("Accept-Language");
        return (accept != null && accept.toLowerCase().startsWith("it")) ? "it" : "en";
    }
}
