package it.unicas.cassitrack.security;

import it.unicas.cassitrack.service.TokenBlacklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.http.Cookie;

import java.io.IOException;
import java.util.Arrays;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private SessionCookie sessionCookie;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Credential cred = parseJwt(request);
        String jwt = cred == null ? null : cred.token();

        if (jwt != null && jwtUtil.validateToken(jwt) && !tokenBlacklistService.isBlacklisted(jwt)) {
            String username = jwtUtil.getUsernameFromToken(jwt);

            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);

            if (cred.fromCookie()) rotateIfDue(jwt, response);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Finestra scorrevole: chi sta lavorando non viene mai interrotto, chi ha
     * lasciato la scheda aperta e se n'e' andato si', e nessuno resta collegato
     * oltre il tetto.
     *
     * SOLO PER I TOKEN ARRIVATI DAL COOKIE. Un client che manda
     * Authorization: Bearer non riceve l'intestazione Set-Cookie — non ha un
     * posto dove metterla — quindi continuerebbe a presentare il token vecchio,
     * che qui sotto abbiamo appena revocato: lo si chiuderebbe fuori a meta'
     * sessione invece di prolungargliela. Per quei client la scadenza resta
     * assoluta, ed e' il comportamento che si aspettano.
     *
     * IL VECCHIO VIENE REVOCATO. Senza, ogni rinnovo lascerebbe in circolo un
     * token ancora buono per la sua vita residua: dopo un turno ne esisterebbero
     * venti per la stessa persona, e il logout ne spegnerebbe uno solo.
     *
     * Se qualcosa va storto non si tocca niente: la richiesta in corso e' gia'
     * autenticata con un token valido, e un rinnovo fallito significa solo che
     * si ritentera' alla prossima richiesta.
     */
    private void rotateIfDue(String jwt, HttpServletResponse response) {
        if (!jwtUtil.shouldRenew(jwt) || !jwtUtil.withinSessionCap(jwt)) return;
        try {
            String fresh = jwtUtil.renew(jwt);
            tokenBlacklistService.blacklist(jwt, jwtUtil.getRemainingValidityMs(jwt));
            sessionCookie.issue(response, fresh, jwtUtil.getExpirationMs());
        } catch (Exception e) {
            logger.warn("Rinnovo della sessione non riuscito: " + e.getMessage());
        }
    }

    /** Un token e da dove arriva: il rinnovo vale solo per quelli dal cookie. */
    private record Credential(String token, boolean fromCookie) {}

    // V-04 FIX (OWASP A02): Read JWT from httpOnly cookie first; fall back to Authorization header
    // for API clients (mobile apps, CLI tools, Swagger, etc.).
    private Credential parseJwt(HttpServletRequest request) {
        // 1. Prefer httpOnly cookie — not accessible to JavaScript
        if (request.getCookies() != null) {
            String cookieToken = Arrays.stream(request.getCookies())
                    .filter(c -> SessionCookie.NAME.equals(c.getName()))
                    .map(Cookie::getValue)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            if (cookieToken != null) return new Credential(cookieToken, true);
        }

        // 2. Fall back to Authorization: Bearer <token> header (API clients)
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return new Credential(headerAuth.substring(7), false);
        }

        return null;
    }
}
