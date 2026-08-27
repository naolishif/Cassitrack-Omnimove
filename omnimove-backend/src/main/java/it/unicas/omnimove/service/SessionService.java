package it.unicas.omnimove.service;

import it.unicas.omnimove.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * Opening and closing a browser session.
 *
 * Logging out is not one action but four — blacklist the token, drop the
 * active-session key, expire the cookie, and only then let the client clean up —
 * and it is now triggered from more than one place: the logout button, and a
 * password reset requested from inside the app. Three of the four are easy to
 * forget, so they live together here rather than being written out again at
 * every call site.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    /** V-04: the JWT travels in an httpOnly cookie, unreadable by JavaScript. */
    public static final String JWT_COOKIE_NAME = "omnimove_jwt";

    private final JwtUtil               jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final ActiveSessionService  activeSessionService;

    // false = HTTP (dev + public server without TLS); true = HTTPS only.
    // Set COOKIE_SECURE=true once Nginx+TLS is in place.
    @Value("${omnimove.cookie.secure:false}")
    private boolean cookieSecure;

    /** Hands the browser a session cookie for a freshly minted token. */
    public void issue(HttpServletResponse response, String token, long expiresInMs) {
        String secureFlag = cookieSecure ? "; Secure" : "";
        response.setHeader("Set-Cookie",
                String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                        JWT_COOKIE_NAME, token, (int) (expiresInMs / 1000), secureFlag));
    }

    /**
     * Revokes the caller's session and expires the cookie.
     *
     * @return the email the dead token belonged to, or null if there was
     *         nothing live to revoke — the caller decides whether that is
     *         worth an audit line.
     */
    public String terminate(HttpServletRequest request, HttpServletResponse response) {
        String email = null;
        String token = resolveToken(request);

        if (token != null) {
            long remaining = jwtUtil.getRemainingValidityMs(token);
            if (remaining > 0) {
                email = jwtUtil.extractEmail(token);
                // Blacklisted for exactly as long as it would otherwise live:
                // no point holding a revoked token past its own expiry
                tokenBlacklistService.blacklist(token, remaining);
                activeSessionService.close(token);
                log.info("Token revoked");
            }
        }

        expireCookie(response);
        return email;
    }

    /** Authorization header first, then the cookie — API clients use the former. */
    public String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer "))
            return authHeader.substring(7);

        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> JWT_COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void expireCookie(HttpServletResponse response) {
        String secureFlag = cookieSecure ? "; Secure" : "";
        response.setHeader("Set-Cookie",
                JWT_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly" + secureFlag + "; SameSite=Strict");
    }
}
