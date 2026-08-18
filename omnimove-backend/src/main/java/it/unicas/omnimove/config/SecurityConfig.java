package it.unicas.omnimove.config;
import it.unicas.omnimove.security.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import java.util.List;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Configuration @EnableWebSecurity @RequiredArgsConstructor
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {
    private static final String LOGIN_PAGE = "omnimove-login.html";

    private final JwtFilter jwtFilter;

    @Value("${omnimove.cors.allowed-origins}")
    private List<String> corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(c -> c.configurationSource(corsSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a

                        // Allow internal forwards and error routing to pass through
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()

                        // ── 1. Public — login page, static assets, auth endpoints ──────
                        .requestMatchers(
                                "/", "/error", "/omnimove-login.html", "/reset-password.html",
                                "/favicon.ico", "/omnimove-login.css", "/omnimove-login.js",
                                "/omnimove-i18n.js",
                                "/reset-password.css","/reset-password.js", "/api/v1/auth/reset-page"
                        ).permitAll()
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/auth/verify",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/reset-password"
                        ).permitAll()
                        .requestMatchers( // API docs — require authentication
                                "/api/docs/**",
                                "/api/swagger-ui/**",
                                "/api/swagger-ui.html"
                        ).authenticated()

                        // ── 2. Admin only ────────────────────────────────────────────────
                        .requestMatchers(
                                "/omnimove-admin.html",
                                "/omnimove-admin.css",
                                "/omnimove-admin.js",
                                "/api/v1/admin/**"
                        ).hasAnyAuthority("ADMIN", "ROLE_ADMIN")

                        // ── 3. Traveller only ────────────────────────────────────────────
                        .requestMatchers(
                                "/omnimove-traveller.html",
                                "/omnimove-traveller.css",
                                "/omnimove-traveller.js",
                                "/api/v1/traveller/**"
                        ).hasAnyAuthority("TRAVELLER", "ROLE_TRAVELLER")

                        // ── 4. Shared — any authenticated user (traveller or admin) ──────
                        .requestMatchers(
                                "/api/v1/journeys/**",
                                "/api/v1/ai/**",
                                "/api/v1/traffic/**"
                        ).hasAnyAuthority("TRAVELLER", "ADMIN", "ROLE_TRAVELLER", "ROLE_ADMIN")

                        // ── 5. Actuator — health/info public, everything else admin only ──
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasAnyAuthority("ADMIN", "ROLE_ADMIN")

                        // ── 6. Everything else requires authentication ────────────────────
                        .anyRequest().authenticated()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticatedEntryPoint()))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .xssProtection(xss -> xss.disable())
                        .contentTypeOptions(ct -> {})
                        .httpStrictTransportSecurity(hsts ->
                                hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; " +
                                        // A08 FIX: standardised on jsdelivr for Leaflet/Chart.js, now loaded with SRI integrity=; unpkg/cdnjs dropped
                                "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                                        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; " +
                                        "font-src 'self' https://fonts.gstatic.com; " +
                                        "img-src 'self' data: https://*.tile.openstreetmap.org; " +
                                        "connect-src 'self'; " +
                                        "frame-ancestors 'none'; " +
                                        "object-src 'none'; " +
                                        "base-uri 'self'; " +
                                        "form-action 'self';"
                        ))
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * With no formLogin and no httpBasic, Spring falls back to
     * Http403ForbiddenEntryPoint and answers every *unauthenticated* request with a bare
     * 403. Two consequences: reopening the app after a logout showed an error page instead
     * of the login, and the front-end could not tell a dead session from a role denial.
     *
     * Anything an authenticated user is simply not allowed to reach still goes through the
     * access-denied handler and keeps returning 403.
     */
    private AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, ex) -> {
            String path   = request.getRequestURI().substring(request.getContextPath().length());
            String accept = request.getHeader("Accept");
            boolean wantsHtml = accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);

            // API and actuator answer in JSON whatever the Accept header says: an anonymous
            // caller has no business learning which endpoints exist
            if (!wantsHtml || path.startsWith("/api/") || path.startsWith("/actuator/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required\"}");
                return;
            }

            // A page that does not exist is a 404, not an invitation to log in.
            // Without this the login redirect would swallow every typo'd URL.
            if (!isKnownPage(path)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // Known page, just not logged in: park the destination in ?next= so the login
            // form can send the user back where they were headed
            String loginUrl = request.getContextPath() + "/" + LOGIN_PAGE;
            String wanted   = path.substring(1);
            if (!wanted.equals(LOGIN_PAGE)) {
                loginUrl += "?next=" + URLEncoder.encode(wanted, StandardCharsets.UTF_8);
            }
            response.sendRedirect(loginUrl);
        };
    }

    /** True when the path maps to a page actually shipped in static/. */
    private boolean isKnownPage(String path) {
        // Defensive: the container normalises the URI, but never build a classpath
        // lookup out of a path that still carries traversal segments
        if (path.contains("..") || !path.endsWith(".html")) return false;
        return new ClassPathResource("static" + path).exists();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);  // JWT is sent in Authorization header, not cookies
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}