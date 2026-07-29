package it.unicas.omnimove.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.*;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.security.JwtUtil;
import it.unicas.omnimove.service.SecurityAuditService;
import it.unicas.omnimove.service.TokenBlacklistService;
import it.unicas.omnimove.service.EmailService;
import it.unicas.omnimove.service.RateLimiterService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Register, login, email verification, password reset")
public class AuthController {

    // V-04 FIX (OWASP A02/A07): JWT delivered as httpOnly cookie — not readable by JavaScript
    private static final String JWT_COOKIE_NAME   = "omnimove_jwt";

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int VERIFY_EXPIRY_HOURS = 24;
    private static final int RESET_EXPIRY_HOURS  = 1;

    private final UserRepository    userRepo;
    private final PasswordEncoder   passwordEncoder;
    private final JwtUtil           jwtUtil;
    private final EmailService      emailService;
    private final RateLimiterService rateLimiter;
    private final TokenBlacklistService tokenBlacklistService;
    private final SecurityAuditService securityAuditService;

    // false = HTTP (dev + public server without TLS); true = HTTPS only
    // Controlled via COOKIE_SECURE env var — set to true once Nginx+TLS is in place
    @Value("${omnimove.cookie.secure:false}")
    private boolean cookieSecure;

    // ── REGISTER ────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new passenger account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req,
                                                  HttpServletRequest request) {
        // Rate limit: 5 registrations per IP per hour
        if (!rateLimiter.allowRegister(getClientIp(request)))
            return tooManyRequests("Too many registration attempts. Please try again later.");

        if (req.getName() == null || req.getEmail() == null || req.getPassword() == null)
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Name, email and password are required").build());

        if (!req.getPassword().equals(req.getConfirmPassword()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Passwords do not match").build());

        if (!isPasswordValid(req.getPassword())) {
            securityAuditService.weakPasswordRejected(req.getEmail(), getClientIp(request));
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder()
                            .message("Password must be at least 8 characters and include an uppercase letter, a lowercase letter, a number, and a special character.")
                            .build());
        }
        if (userRepo.existsByEmail(req.getEmail()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Email already registered").build());

        String verificationToken = UUID.randomUUID().toString();

        User user = User.builder()
                .name(req.getName())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))
                .role("TRAVELLER")
                .verified(false)
                .verificationToken(verificationToken)
                .verificationTokenExpiry(LocalDateTime.now().plusHours(VERIFY_EXPIRY_HOURS))
                .failedLoginAttempts(0)
                .build();
        userRepo.save(user);

        emailService.sendVerificationEmail(req.getEmail(), verificationToken);
        securityAuditService.registration(user.getEmail(), getClientIp(request));

        return ResponseEntity.ok(AuthResponse.builder()
                .email(user.getEmail())
                .name(user.getName())
                .id(user.getId())
                .role(user.getRole())
                .message("Registration successful! Please check your email to verify your account.")
                .build());
    }

    // ── LOGIN ────────────────────────────────────────────────────────

    @PostMapping("/login")
    @Operation(summary = "Login with email and password")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest req,
                                              HttpServletRequest httpReq,
                                              HttpServletResponse httpResp) {

        var userOpt = userRepo.findByEmail(req.getEmail());
        if (userOpt.isEmpty()) {
            securityAuditService.loginFailure(req.getEmail(), getClientIp(httpReq));
            return ResponseEntity.status(401)
                    .body(AuthResponse.builder().message("Invalid email or password").build());
        }

        User user = userOpt.get();

        if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
            securityAuditService.loginFailure(req.getEmail(), getClientIp(httpReq));
            return ResponseEntity.status(429)
                    .body(AuthResponse.builder()
                            .message("Account locked due to too many failed login attempts. Please reset your password to unlock it.")
                            .suggestPasswordReset(Boolean.TRUE)
                            .build());
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            userRepo.save(user);

            boolean suggestReset = user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS;
            securityAuditService.loginFailure(req.getEmail(), getClientIp(httpReq));
            if (suggestReset) securityAuditService.accountLocked(req.getEmail());

            return ResponseEntity.status(401)
                    .body(AuthResponse.builder()
                            .message("Invalid email or password")
                            .suggestPasswordReset(suggestReset ? Boolean.TRUE : null)
                            .build());
        }

        if (!user.isVerified())
            return ResponseEntity.status(403)
                    .body(AuthResponse.builder()
                            .message("Please verify your email address before logging in. Check your inbox.")
                            .build());

        user.setFailedLoginAttempts(0);
        userRepo.save(user);

        String token = jwtUtil.generateToken(user.getEmail());
        long expiresInMs = jwtUtil.getExpirationMs();
        securityAuditService.loginSuccess(req.getEmail(), getClientIp(httpReq));

        // V-04 FIX: Deliver token as httpOnly cookie.
        // cookieSecure=false allows the cookie over plain HTTP (dev + server without TLS).
        // Set COOKIE_SECURE=true in .env once Nginx+TLS is in place.
        String secureFlag = cookieSecure ? "; Secure" : "";
        httpResp.setHeader("Set-Cookie",
            String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly%s; SameSite=Strict",
                JWT_COOKIE_NAME, token, (int) (expiresInMs / 1000), secureFlag));

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)       // kept for API clients using Authorization header
                .email(user.getEmail())
                .name(user.getName())
                .id(user.getId())
                .role(user.getRole())
                .expiresInMs(expiresInMs)
                .message("Login successful")
                .build());
    }

    // ── EMAIL VERIFICATION ───────────────────────────────────────────

    @GetMapping("/verify")
    @Operation(summary = "Verify email address via link sent by email")
    public void verifyEmail(@RequestParam String token,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {

        String ctx = request.getContextPath(); // e.g. "/omnimove" or ""
        var userOpt = userRepo.findByVerificationToken(token);
        if (userOpt.isEmpty()) {
            response.sendRedirect(ctx + "/omnimove-login.html?verified=invalid");
            return;
        }

        User user = userOpt.get();
        if (user.getVerificationTokenExpiry() != null
                && LocalDateTime.now().isAfter(user.getVerificationTokenExpiry())) {
            response.sendRedirect(ctx + "/omnimove-login.html?verified=expired");
            return;
        }

        user.setVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);
        userRepo.save(user);

        securityAuditService.emailVerified(user.getEmail());
        response.sendRedirect(ctx + "/omnimove-login.html?verified=true");
    }

    // ── RESEND VERIFICATION ──────────────────────────────────────────

    @PostMapping("/resend-verification")
    @Operation(summary = "Resend the email verification link")
    public ResponseEntity<AuthResponse> resendVerification(@RequestBody LoginRequest req) {

        // Rate limit: 3 resends per email per hour
        if (!rateLimiter.allowResendVerification(req.getEmail()))
            return tooManyRequests("Too many resend attempts. Please wait before requesting another link.");

        var userOpt = userRepo.findByEmail(req.getEmail());
        if (userOpt.isEmpty() || userOpt.get().isVerified())
            return ResponseEntity.ok(AuthResponse.builder()
                    .message("If that email exists and is unverified, a new link has been sent.")
                    .build());

        User user = userOpt.get();
        String newToken = UUID.randomUUID().toString();
        user.setVerificationToken(newToken);
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(VERIFY_EXPIRY_HOURS));
        userRepo.save(user);

        emailService.sendVerificationEmail(user.getEmail(), newToken);
        securityAuditService.verificationEmailResent(user.getEmail());

        return ResponseEntity.ok(AuthResponse.builder()
                .message("Verification email resent. Please check your inbox.")
                .build());
    }

    // ── FORGOT PASSWORD ──────────────────────────────────────────────

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset link via email")
    public ResponseEntity<AuthResponse> forgotPassword(@RequestBody LoginRequest req) {

        // Rate limit: 3 requests per email per hour
        if (!rateLimiter.allowForgotPassword(req.getEmail()))
            return tooManyRequests("Too many password reset attempts. Please wait before requesting another link.");

        var userOpt = userRepo.findByEmail(req.getEmail());
        if (userOpt.isPresent() && userOpt.get().isVerified()) {
            User user = userOpt.get();
            String resetToken = UUID.randomUUID().toString();
            user.setResetPasswordToken(resetToken);
            user.setResetPasswordTokenExpiry(LocalDateTime.now().plusHours(RESET_EXPIRY_HOURS));
            userRepo.save(user);
            emailService.sendPasswordResetEmail(user.getEmail(), resetToken);
            securityAuditService.passwordResetRequested(user.getEmail());
        }

        // Always return the same message to prevent email enumeration
        return ResponseEntity.ok(AuthResponse.builder()
                .message("If that email is registered, you will receive a reset link shortly.")
                .build());
    }

    // ── RESET PASSWORD ───────────────────────────────────────────────

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using the reset token")
    public ResponseEntity<AuthResponse> resetPassword(@RequestBody ResetPasswordRequest req) {

        if (req.getToken() == null || req.getNewPassword() == null)
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Token and new password are required").build());

        if (!isPasswordValid(req.getNewPassword()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder()
                            .message("Password must be at least 8 characters and include an uppercase letter, a lowercase letter, a number, and a special character.")
                            .build());

        if (!req.getNewPassword().equals(req.getConfirmPassword()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Passwords do not match").build());

        var userOpt = userRepo.findByResetPasswordToken(req.getToken());
        if (userOpt.isEmpty())
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Invalid or expired reset link").build());

        User user = userOpt.get();
        if (user.getResetPasswordTokenExpiry() != null
                && LocalDateTime.now().isAfter(user.getResetPasswordTokenExpiry()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Reset link has expired. Please request a new one.").build());

        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        user.setResetPasswordToken(null);
        user.setResetPasswordTokenExpiry(null);
        user.setFailedLoginAttempts(0);
        userRepo.save(user);

        securityAuditService.passwordReset(user.getEmail());

        return ResponseEntity.ok(AuthResponse.builder()
                .message("Password updated successfully! You can now log in.")
                .build());
    }

    // ── RESET PAGE (email link lands here) ──────────────────────────
    @GetMapping("/reset-page")
    public void resetPage(@RequestParam(required = false) String token,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {

        String ctx = request.getContextPath(); // e.g. "/omnimove" or ""
        // Allow only characters present in JWT tokens (base64url + dots)
        String safeToken = (token != null) ? token.replaceAll("[^a-zA-Z0-9._\\-]", "") : "";

        if (safeToken.isBlank()) {
            response.sendRedirect(ctx + "/omnimove-login.html");
            return;
        }

        var userOpt = userRepo.findByResetPasswordToken(safeToken);
        boolean valid = userOpt.isPresent() && (
            userOpt.get().getResetPasswordTokenExpiry() == null ||
            !LocalDateTime.now().isAfter(userOpt.get().getResetPasswordTokenExpiry())
        );

        if (!valid) {
            response.sendRedirect(ctx + "/reset-password.html?expired=true");
            return;
        }

        response.sendRedirect(ctx + "/reset-password.html#" + safeToken);
    }

    @PostMapping("/logout")
    @Operation(summary="Logout and invalidate the current JWT token")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Resolve token from Authorization header OR httpOnly cookie
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
                String email = jwtUtil.extractEmail(token);
                tokenBlacklistService.blacklist(token, remaining);
                log.info("Token revoked on logout");
                securityAuditService.logout(email);
            }
        }

        // V-04 FIX: Clear the httpOnly JWT cookie
        String secureFlag = cookieSecure ? "; Secure" : "";
        response.setHeader("Set-Cookie",
            JWT_COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly" + secureFlag + "; SameSite=Strict");

        return ResponseEntity.noContent().build();
    }


    // ── CURRENT USER ─────────────────────────────────────────────────

    @GetMapping("/me")
    @Operation(summary = "Get current user profile")
    public ResponseEntity<AuthResponse> me(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails userDetails) {

        if (userDetails == null) return ResponseEntity.status(401).build();

        return userRepo.findByEmail(userDetails.getUsername())
                .map(u -> ResponseEntity.ok(AuthResponse.builder()
                        .id(u.getId()).email(u.getEmail()).name(u.getName()).role(u.getRole()).build()))
            .orElse(ResponseEntity.notFound().build());
    }

    // ── DELETE ACCOUNT ────────────────────────────────────────────────

    @DeleteMapping("/account")
    @Operation(summary = "Permanently delete the authenticated user's account")
    public ResponseEntity<AuthResponse> deleteAccount(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
            org.springframework.security.core.userdetails.UserDetails userDetails,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {

        if (userDetails == null) return ResponseEntity.status(401).build();

        // Blacklist the current token so it cannot be used after deletion
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
            if (remaining > 0) tokenBlacklistService.blacklist(token, remaining);
        }

        return userRepo.findByEmail(userDetails.getUsername())
                .map(u -> {
                    userRepo.delete(u);
                    securityAuditService.accountDeleted(u.getEmail());
                    return ResponseEntity.ok(AuthResponse.builder()
                            .message("Account deleted successfully.").build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── HELPERS ──────────────────────────────────────────────────────

    /**
     * Extracts the real client IP, respecting X-Forwarded-For set by a reverse proxy.
     */
    /**
     * Password must have ≥8 chars, 1 uppercase, 1 lowercase, 1 digit, 1 special char.
     */
    private boolean isPasswordValid(String password) {
        if (password == null || password.length() < 8) return false;
        return password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^a-zA-Z0-9]).{8,}$");
    }

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private ResponseEntity<AuthResponse> tooManyRequests(String message) {
        return ResponseEntity.status(429)
                .body(AuthResponse.builder().message(message).build());
    }
}
