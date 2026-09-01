package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import it.unicas.cassitrack.dto.AuthResponse;
import it.unicas.cassitrack.dto.LoginRequest;
import it.unicas.cassitrack.dto.LoginResponse;
import it.unicas.cassitrack.dto.RegisterRequest;
import it.unicas.cassitrack.model.User;
import it.unicas.cassitrack.service.LoginAttemptService;
import it.unicas.cassitrack.service.SecurityAuditService;
import it.unicas.cassitrack.service.TokenBlacklistService;
import it.unicas.cassitrack.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import it.unicas.cassitrack.security.JwtUtil;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // V-04 FIX (OWASP A02/A07): Token is delivered as an httpOnly, Secure, SameSite=Strict
    // cookie rather than in the JSON response body, so JavaScript cannot read it.
    // The token is still included in the JSON body for backward compatibility with the
    // Spring Security filter chain and API clients that use the Authorization header.
    private static final String JWT_COOKIE_NAME = "cassitrack_jwt";

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserService userService;

    // false = HTTP (dev + public server without TLS); true = HTTPS only
    // Controlled via COOKIE_SECURE env var — set to true once Nginx+TLS is in place
    @Value("${cassitrack.cookie.secure:false}")
    private boolean cookieSecure;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private it.unicas.cassitrack.service.ManagerActivityService managerActivityService;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Autowired
    private SecurityAuditService securityAuditService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req,
                                                  HttpServletRequest request) {

        try {
            User saved = userService.registerUser(req);
            log.info("New user registered successfully via public flow: {}", saved.getEmail());
            securityAuditService.registration(saved.getEmail(), getClientIp(request));

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AuthResponse.builder()
                            .email(saved.getEmail())
                            .name(saved.getName())
                            .role(saved.getRole())
                            .expiresInMs(3600000L)
                            .message("Registration successful")
                            .build());

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder()
                            .message(e.getMessage())
                            .build());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LOGIN ACCOUNT
    // ─────────────────────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {

        String email = loginRequest.getEmail();

        if (loginAttemptService.isBlocked(email)) {
            securityAuditService.accountLocked(email);
            return ResponseEntity.status(429)
                .body("Too many failed login attempts. Please wait 15 minutes and try again.");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, loginRequest.getPassword())
            );

            loginAttemptService.resetAttempts(email);
            String token = jwtUtil.generateToken(authentication);
            User user = userService.getUserByEmail(email);
            securityAuditService.loginSuccess(email, getClientIp(request));
            // The same fact in a table the application may read back, so the user
            // card can show when this manager was last in. The audit row above
            // stays the proof; this is its visible echo.
            managerActivityService.recordLogin(user, getClientIp(request),
                    request.getHeader("User-Agent"));

            // V-04 FIX: Set token in httpOnly cookie — JS cannot read it
            // cookieSecure=false allows the cookie to be sent over plain HTTP (dev + server without TLS).
            // Set COOKIE_SECURE=true in .env once Nginx+TLS is in place.
            String secureFlag = cookieSecure ? "; Secure" : "";
            response.setHeader("Set-Cookie",
                String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                    JWT_COOKIE_NAME, token, (int) (jwtUtil.getExpirationMs() / 1000), secureFlag));

            LoginResponse resp = new LoginResponse();
            resp.setToken(token);   // kept for API clients using Authorization header
            resp.setUsername(user.getEmail());
            resp.setEmail(user.getEmail());
            resp.setRole(user.getRole());

            return ResponseEntity.ok(resp);

        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(email);
            securityAuditService.loginFailure(email, getClientIp(request));
            return ResponseEntity.badRequest().body("Username or password incorrect!!");
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout and invalidate the current JWT token")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Resolve token from Authorization header OR cookie
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (request.getCookies() != null) {
            token = Arrays.stream(request.getCookies())
                    .filter(c -> JWT_COOKIE_NAME.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        if (token != null) {
            long remaining = jwtUtil.getRemainingValidityMs(token);
            if (remaining > 0) {
                String email = jwtUtil.getUsernameFromToken(token);
                tokenBlacklistService.blacklist(token, remaining);
                log.info("Token revoked on logout");
                securityAuditService.logout(email);
            }
        }

        // V-04 FIX: Clear the httpOnly JWT cookie on logout
        String secureFlag = cookieSecure ? "; Secure" : "";
        response.setHeader("Set-Cookie",
            JWT_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly" + secureFlag + "; SameSite=Strict");

        return ResponseEntity.noContent().build();
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) return realIp.trim();
        return request.getRemoteAddr();
    }
}
