package it.unicas.cassitrack.config;

import it.unicas.cassitrack.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.List;

/**
 * Security configuration for CASSITRACK.
 * <p>
 * Public endpoints (no auth needed):
 * <ul>
 *   <li>GET /api/v1/vehicles - real-time bus positions (for OMNIMOVE + public)</li>
 *   <li>GET /api/v1/stops/{stopId}/arrivals - ETA at stops (for OMNIMOVE + public)</li>
 *   <li>GET /api/v1/feed/gtfs-rt - GTFS Realtime feed (for NAP + 3rd parties)</li>
 *   <li>GET /api/docs/** - Swagger UI</li>
 *   <li>WS /ws/vehicles - WebSocket for fleet dashboard</li>
 * </ul>
 * <p>
 * Protected endpoints (JWT required):
 * <ul>
 *   <li>GET /api/v1/analytics/** - fleet analytics (fleet manager role)</li>
 *   <li>GET /api/v1/alerts - alerts (fleet manager role)</li>
 *   <li>POST /api/v1/auth/** - login/register</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

    @Value("${cassitrack.cors.allowed-origins}")
    private List<String> corsAllowedOrigins;

    // Extra origins for CSP connect-src — needed because the fleet manager JS calls OmniMove
    // (different port = different origin, so 'self' alone doesn't cover it).
    // Space-separated list, e.g. "http://localhost:8180 http://193.205.60.151:8180"
    @Value("${cassitrack.csp.connect-extra:http://localhost:8180}")
    private String cspConnectExtra;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                // Disable CSRF - we use JWT, not sessions
                .csrf(csrf -> csrf.disable())

                // Allow CORS from frontend
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Stateless — no HTTP sessions, JWT only
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .authorizeHttpRequests(auth -> auth

                        // Allow internal forwards and error routing to pass through
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()

                        // ── 1. Public Access points (UI and Auth) ───────────────
                        // V-13 FIX (OWASP A05): the login page's own CSS/JS must be reachable by
                        // unauthenticated visitors too — without this, the page itself was permitAll()
                        // but its stylesheet/script fell under the anyRequest().authenticated() catch-all
                        // below and would 401, leaving an unstyled/non-functional login page.
                        .requestMatchers("/error", "/", "/cassitrack-login.html", "/cassitrack-login.css",
                                "/cassitrack-login.js", "/api/v1/auth/login").permitAll()

                        // ── 2. Public APIs (Live Tracking, Swagger) ───────────────
                        .requestMatchers(org.springframework.http.HttpMethod.GET, // Public for OMNIMOVE
                                "/api/v1/vehicles/**",
                                "/api/v1/stops/**",
                                "/api/v1/routes/**",
                                "/api/v1/siri/**",
                                "/api/v1/journeys/**",
                                "/api/v1/telemetry/latest",
                                "/api/v1/telemetry/stream"
                        ).permitAll()
                        .requestMatchers( // Dev tools — H2 console removed (disabled in application.yml)
                                "/ws/**",
                                "/api/static/**"
                        ).permitAll()

                        // V-07 FIX (OWASP A05): Swagger UI now requires authentication — no free recon
                        .requestMatchers(
                                "/api/docs/**",
                                "/api/swagger-ui/**",
                                "/api/swagger-ui.html"
                        ).authenticated()

                        // ── 3. Role-Specific Protected HTML ───────────────
                        .requestMatchers(
                                "/cassitrack-fleetmanager.html",
                                "/cassitrack-fleetmanager.css",
                                "/cassitrack-fleetmanager.js",
                                "/api/v1/analytics/**",
                                "/api/v1/buses/**",
                                "/api/v1/trips/**"     // trips + their timetable (Data Management);
                                                       // /api/v1/timetable/** was merged into it
                        ).hasAnyAuthority("FLEET_MANAGER", "ROLE_FLEET_MANAGER")

                        // V-10 FIX (OWASP A01): /api/v1/ai/** was mapped to two conflicting rules;
                        // first-match-wins in Spring Security meant ADMIN was always denied.
                        // Now both FLEET_MANAGER and ADMIN can access the AI endpoint.
                        .requestMatchers("/api/v1/ai/**"
                        ).hasAnyAuthority("FLEET_MANAGER", "ROLE_FLEET_MANAGER", "ADMIN", "ROLE_ADMIN")

                        // V-13 FIX (OWASP A05): gate the page's external CSS/JS the same as the page itself
                        .requestMatchers(
                                "/cassitrack-admin.html",
                                "/cassitrack-admin.css",
                                "/cassitrack-admin.js",
                                "/api/v1/users/**",
                                "/api/v1/auth/register"
                        ).hasAnyAuthority("ADMIN", "ROLE_ADMIN")

                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/vehicles/**", "/api/v1/stops/**", "/api/v1/routes/**", "/api/v1/journeys/**","/api/v1/buses/**").hasAnyAuthority("FLEET_MANAGER", "ROLE_FLEET_MANAGER")
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/vehicles/**", "/api/v1/stops/**", "/api/v1/routes/**", "/api/v1/journeys/**","/api/v1/buses/**").hasAnyAuthority("FLEET_MANAGER", "ROLE_FLEET_MANAGER")
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/v1/vehicles/**", "/api/v1/stops/**", "/api/v1/routes/**", "/api/v1/journeys/**","/api/v1/buses/**").hasAnyAuthority("FLEET_MANAGER", "ROLE_FLEET_MANAGER")

                        // ── 4. Everything else requires authentication ───────
                        .anyRequest().authenticated()
                );

        // V-12 FIX (OWASP A05): Add Content-Security-Policy to prevent XSS and clickjacking
        http.headers(headers -> headers
            .frameOptions(frame -> frame.deny())
            .xssProtection(xss -> xss.disable()) // modern browsers use CSP, not X-XSS-Protection
            .contentTypeOptions(ct -> {})         // X-Content-Type-Options: nosniff (default on)
            .httpStrictTransportSecurity(hsts ->
                hsts.maxAgeInSeconds(31536000).includeSubDomains(true))
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; " +
                // A08 FIX: jsdelivr added for Leaflet/Chart.js, now loaded with SRI integrity=; cdnjs dropped (was unused by any page)
                // V-13 FIX (OWASP A05): 'unsafe-inline' dropped from both directives now that every
                // cassitrack-*.html page has its inline <style>/<script>/onX="" content extracted into
                // external .css/.js files — removes the single biggest XSS-mitigation gap CSP can close.
                "script-src 'self' https://cdn.jsdelivr.net; " +
                "style-src 'self' https://fonts.googleapis.com https://cdn.jsdelivr.net; " +
                "font-src 'self' https://fonts.gstatic.com; " +
                "img-src 'self' data: https:; " +
                "connect-src 'self' " + cspConnectExtra + " wss:; " +
                "frame-ancestors 'none'; " +
                // ZAP [10055]: directives that do NOT fall back to default-src must be explicit
                "object-src 'none'; " +
                "base-uri 'self'; " +
                "form-action 'self';"
            ))
        );
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // ZAP [10063] Permissions-Policy  |  ZAP [90004] Cross-Origin-Resource-Policy
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                // Restrict browser features not needed by the fleet dashboard
                res.setHeader("Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(self), payment=()");
                // Prevent other origins from embedding our resources (images, scripts)
                res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
                chain.doFilter(req, res);
            }
        }, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Api-Key"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public org.springframework.security.authentication.AuthenticationProvider authenticationProvider(
            org.springframework.security.core.userdetails.UserDetailsService userDetailsService) {
        org.springframework.security.authentication.dao.DaoAuthenticationProvider authProvider =
                new org.springframework.security.authentication.dao.DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }
}
