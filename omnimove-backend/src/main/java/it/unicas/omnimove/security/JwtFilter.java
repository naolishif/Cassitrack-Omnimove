package it.unicas.omnimove.security;

import it.unicas.omnimove.service.SessionService;
import it.unicas.omnimove.service.TokenBlacklistService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final String JWT_COOKIE_NAME = "omnimove_jwt";

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;
    private final SessionService sessionService;

    @Lazy
    public JwtFilter(JwtUtil jwtUtil, UserDetailsService uds,
                     TokenBlacklistService tokenBlacklistService,
                     SessionService sessionService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = uds;
        this.tokenBlacklistService = tokenBlacklistService;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        // V-04 FIX (OWASP A02): Prefer httpOnly cookie; fall back to Authorization header
        Credential cred = resolveToken(req);
        String token = cred == null ? null : cred.token();

        if (token != null && jwtUtil.isValid(token) && !tokenBlacklistService.isBlacklisted(token)) {
            String email = jwtUtil.extractEmail(token);
            try {
                UserDetails ud = userDetailsService.loadUserByUsername(email);
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);

                if (cred.fromCookie()) rotateIfDue(token, res);
            } catch (UsernameNotFoundException e) {
                // Account deleted but token not yet expired — treat as unauthenticated
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(req, res);
    }

    /**
     * Finestra scorrevole: chi usa l'applicazione non viene interrotto, chi la
     * lascia aperta e se ne va viene disconnesso, e nessuno resta collegato
     * oltre il tetto fissato da jwt.session-max-ms.
     *
     * SOLO PER I TOKEN ARRIVATI DAL COOKIE. Un client che manda
     * Authorization: Bearer non ha dove mettere un Set-Cookie: continuerebbe a
     * presentare il token vecchio, che la rotazione ha appena revocato, e si
     * ritroverebbe chiuso fuori a meta' sessione. Per quei client la scadenza
     * resta assoluta, come si aspettano.
     *
     * Il rinnovo passa da SessionService e non da qui, cosi' la chiave Redis
     * della sessione attiva segue il token invece di restare appesa a uno
     * ormai morto.
     */
    private void rotateIfDue(String token, HttpServletResponse res) {
        if (!jwtUtil.shouldRenew(token) || !jwtUtil.withinSessionCap(token)) return;
        try {
            sessionService.rotate(res, token, jwtUtil.renew(token));
        } catch (Exception e) {
            // La richiesta in corso e' gia' autenticata con un token valido:
            // un rinnovo fallito si ritenta da solo alla chiamata successiva.
            logger.warn("Rinnovo della sessione non riuscito: " + e.getMessage());
        }
    }

    /** Un token e da dove arriva: il rinnovo vale solo per quelli dal cookie. */
    private record Credential(String token, boolean fromCookie) {}

    private Credential resolveToken(HttpServletRequest req) {
        // 1. httpOnly cookie — not accessible to JavaScript
        if (req.getCookies() != null) {
            String cookieToken = Arrays.stream(req.getCookies())
                    .filter(c -> JWT_COOKIE_NAME.equals(c.getName()))
                    .map(Cookie::getValue)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .orElse(null);
            if (cookieToken != null) return new Credential(cookieToken, true);
        }

        // 2. Authorization: Bearer <token> header (API clients, Swagger, mobile)
        String authHeader = req.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return new Credential(authHeader.substring(7), false);
        }

        return null;
    }
}
