package it.unicas.omnimove.security;

import it.unicas.omnimove.model.ApiClient;
import it.unicas.omnimove.service.ApiClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates partner systems on {@code /api/partner/**} from the
 * {@code X-Api-Key} header.
 *
 * <p>Deliberately confined to that prefix: a key must never open the
 * traveller or admin API, and a traveller's JWT must never open the partner
 * one. A valid key becomes an authentication with the single authority
 * {@link #AUTHORITY}, whose principal is the {@link ApiClient} row, so a
 * controller can rate-limit and log per partner.
 *
 * <p>A header that is present but wrong is answered here with a 401 that
 * says so. Left to the generic entry point it would read "Authentication
 * required", which sends a partner checking their configuration down the
 * wrong path.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String AUTHORITY = "PARTNER";
    private static final String PARTNER_PREFIX = "/api/partner/";

    private final ApiClientService apiClientService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PARTNER_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String raw = req.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            chain.doFilter(req, res);          // no key: the authorization rules answer 401
            return;
        }

        Optional<ApiClient> client = apiClientService.authenticate(raw);
        if (client.isEmpty()) {
            logger.warn("Rejected " + HEADER + " on " + req.getRequestURI()
                    + " (prefix " + prefixOf(raw) + ")");
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"invalid_api_key\","
                    + "\"message\":\"The API key is unknown, expired or revoked.\"}");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                client.get(), null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(req, res);
    }

    /** Enough of a bad key to find it in the panel, never enough to use it. */
    private static String prefixOf(String raw) {
        return raw.length() <= 12 ? "***" : raw.substring(0, 12) + "…";
    }
}
