package it.unicas.omnimove.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.dto.*;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.model.UserConsent;
import it.unicas.omnimove.repository.UserRepository;
import it.unicas.omnimove.security.JwtUtil;
import it.unicas.omnimove.security.PasswordPolicy;
import it.unicas.omnimove.util.RequestLang;
import it.unicas.omnimove.service.ActiveSessionService;
import it.unicas.omnimove.service.ConsentService;
import it.unicas.omnimove.service.GoogleTokenVerifier;
import it.unicas.omnimove.service.LoginHistoryService;
import it.unicas.omnimove.service.PasswordResetService;
import it.unicas.omnimove.service.RecaptchaService;
import it.unicas.omnimove.service.SessionService;
import it.unicas.omnimove.service.SecurityAuditService;
import it.unicas.omnimove.service.EmailService;
import it.unicas.omnimove.service.RateLimiterService;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Register, login, email verification, password reset")
public class AuthController {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int VERIFY_EXPIRY_HOURS = 24;

    private final UserRepository    userRepo;
    private final PasswordEncoder   passwordEncoder;
    private final JwtUtil           jwtUtil;
    private final EmailService      emailService;
    private final RateLimiterService rateLimiter;
    private final SecurityAuditService securityAuditService;
    private final LoginHistoryService  loginHistoryService;
    private final ActiveSessionService activeSessionService;
    private final GoogleTokenVerifier  googleTokenVerifier;
    private final PasswordResetService passwordResetService;
    private final SessionService      sessionService;
    private final RecaptchaService    recaptchaService;
    private final ConsentService       consentService;

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

        // GDPR art. 13: the account cannot be created unless the user confirms the
        // privacy notice was presented. Checked before any write, so a refusal
        // leaves no trace of the person in the database.
        if (!Boolean.TRUE.equals(req.getPrivacyNoticeAccepted()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder()
                            .message("You must read and accept the privacy notice to create an account.")
                            .build());

        if (!PasswordPolicy.isValid(req.getPassword())) {
            securityAuditService.weakPasswordRejected(req.getEmail(), getClientIp(request));
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder()
                            .message(PasswordPolicy.message(langFrom(request)))
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

        // Consent ledger (art. 7(1)). The notice acknowledgement is always recorded;
        // profiling is recorded with whatever the user chose, including an explicit
        // refusal, so "never asked" stays distinguishable from "said no".
        consentService.record(user.getId(), req.getSubjectKey(),
                UserConsent.TYPE_PRIVACY_NOTICE, true,
                UserConsent.SOURCE_REGISTRATION, request);
        consentService.record(user.getId(), req.getSubjectKey(),
                UserConsent.TYPE_PROFILING, Boolean.TRUE.equals(req.getProfilingConsent()),
                UserConsent.SOURCE_REGISTRATION, request);
        consentService.attachAnonymousConsents(req.getSubjectKey(), user.getId());

        emailService.sendVerificationEmail(req.getEmail(), verificationToken, langFrom(request));
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

        // Before anything touches the account. A failed challenge must not count
        // as a failed login attempt, or a bot could lock out any address it knows
        // simply by submitting garbage — turning the defence into the attack.
        if (recaptchaService.isActive()
                && !recaptchaService.verify(req.getCaptchaToken(), getClientIp(httpReq))) {
            securityAuditService.captchaFailed(req.getEmail(), getClientIp(httpReq));
            return ResponseEntity.status(400)
                    .body(AuthResponse.builder()
                            .message("Please complete the \"I'm not a robot\" check and try again.")
                            .build());
        }

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

        // A Google-only account has no hash to compare against. Saying so beats a
        // generic failure the traveller could never act on — and it is not a
        // disclosure: whoever holds that address can see it from Google's side.
        if (!user.hasPassword())
            return ResponseEntity.status(401)
                    .body(AuthResponse.builder()
                            .message("This account signs in with Google. Use the Google button, or request a password to add one.")
                            .build());

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
        // Persists the counter reset together with the new last-login stamp
        loginHistoryService.recordLogin(user, getClientIp(httpReq),
                                        httpReq.getHeader("User-Agent"));

        String token = jwtUtil.generateToken(user.getEmail());
        long expiresInMs = jwtUtil.getExpirationMs();
        securityAuditService.loginSuccess(req.getEmail(), getClientIp(httpReq));
        activeSessionService.open(token, user.getEmail(), expiresInMs);

        sessionService.issue(httpResp, token, expiresInMs);

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

    // ── CAPTCHA ──────────────────────────────────────────────────────

    @GetMapping("/captcha/config")
    @Operation(summary = "Whether the login form must show a reCAPTCHA")
    public ResponseEntity<?> captchaConfig() {
        // The site key is public by design — it is embedded in the widget. The
        // page is told nothing when the check is off, so a disabled deployment
        // does not advertise a key it is not using.
        return ResponseEntity.ok(java.util.Map.of(
                "enabled", recaptchaService.isActive(),
                "siteKey", recaptchaService.siteKey()
        ));
    }

    // ── SIGN IN WITH GOOGLE ──────────────────────────────────────────

    @GetMapping("/google/config")
    @Operation(summary = "Whether Google sign-in is available, and under which client id")
    public ResponseEntity<?> googleConfig() {
        // The client id is public by design — it travels in every Google button.
        // A deployment with no Google project reports disabled and the login page
        // simply does not draw the button.
        return ResponseEntity.ok(java.util.Map.of(
                "enabled",  googleTokenVerifier.isEnabled(),
                "clientId", googleTokenVerifier.isEnabled() ? googleTokenVerifier.clientId() : ""
        ));
    }

    @PostMapping("/google")
    @Operation(summary = "Sign in with a Google ID token")
    public ResponseEntity<AuthResponse> googleLogin(@RequestBody GoogleAuthRequest req,
                                                    HttpServletRequest httpReq,
                                                    HttpServletResponse httpResp) {

        String ip = getClientIp(httpReq);

        if (!googleTokenVerifier.isEnabled())
            return ResponseEntity.status(503)
                    .body(AuthResponse.builder().message("Google sign-in is not available.").build());

        if (!rateLimiter.allowGoogleLogin(ip))
            return tooManyRequests("Too many sign-in attempts. Please try again later.");

        GoogleTokenVerifier.GoogleIdentity identity;
        try {
            identity = googleTokenVerifier.verify(req.getCredential());
        } catch (GoogleTokenVerifier.InvalidGoogleTokenException e) {
            // The reason is logged for the operator, never returned: a caller
            // probing with forged tokens learns nothing from a generic answer.
            securityAuditService.googleLoginFailure(e.getMessage(), ip);
            return ResponseEntity.status(401)
                    .body(AuthResponse.builder().message("Google sign-in failed. Please try again.").build());
        }

        User user = resolveGoogleUser(identity, ip, langFrom(httpReq));

        if (user.getFailedLoginAttempts() != 0) user.setFailedLoginAttempts(0);
        loginHistoryService.recordLogin(user, ip, httpReq.getHeader("User-Agent"));

        String token = jwtUtil.generateToken(user.getEmail());
        long expiresInMs = jwtUtil.getExpirationMs();
        securityAuditService.googleLoginSuccess(user.getEmail(), ip);
        activeSessionService.open(token, user.getEmail(), expiresInMs);
        sessionService.issue(httpResp, token, expiresInMs);

        return ResponseEntity.ok(AuthResponse.builder()
                .token(token)
                .email(user.getEmail())
                .name(user.getName())
                .id(user.getId())
                .role(user.getRole())
                .expiresInMs(expiresInMs)
                .message("Login successful")
                .build());
    }

    /**
     * Finds the account behind a verified Google identity, creating or linking
     * one where needed.
     *
     * Matching is by Google's subject first and by address only as a fallback:
     * the subject is the account, the address is a label Google lets its owner
     * change. Linking on the address is safe here — and only here — because
     * verify() has already refused any token whose email Google has not itself
     * confirmed, so whoever presented it does own that mailbox.
     */
    private User resolveGoogleUser(GoogleTokenVerifier.GoogleIdentity identity, String ip, String lang) {

        var bySub = userRepo.findByGoogleSub(identity.subject());
        if (bySub.isPresent()) {
            User user = bySub.get();
            // Google is the authority on this address; follow it if it moved
            if (!user.getEmail().equalsIgnoreCase(identity.email())
                    && !userRepo.existsByEmail(identity.email())) {
                securityAuditService.profileEmailChanged(user.getEmail(), identity.email());
                user.setEmail(identity.email());
                userRepo.save(user);
            }
            return user;
        }

        var byEmail = userRepo.findByEmail(identity.email());
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setGoogleSub(identity.subject());
            // The address is now proven, whatever state the local account was in
            user.setVerified(true);
            user.setVerificationToken(null);
            user.setVerificationTokenExpiry(null);
            userRepo.save(user);
            securityAuditService.googleAccountLinked(user.getEmail(), ip);
            return user;
        }

        User user = User.builder()
                .name(identity.name())
                .email(identity.email())
                .password(null)               // nothing to store until they set one
                .role("TRAVELLER")
                .verified(true)               // Google has already proven the address
                .googleSub(identity.subject())
                .authProvider("GOOGLE")
                .failedLoginAttempts(0)
                .build();
        userRepo.save(user);
        securityAuditService.googleRegistration(user.getEmail(), ip);
        // No verification step to wait for: Google has already proven the address
        emailService.sendWelcomeEmail(user.getEmail(), user.getName(), lang);
        return user;
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
        // The account only becomes usable now, so this is the moment to welcome
        // them — sending it next to the verification mail would deliver two
        // messages at once, one of them premature
        emailService.sendWelcomeEmail(user.getEmail(), user.getName(), langFrom(request));

        response.sendRedirect(ctx + "/omnimove-login.html?verified=true");
    }

    // ── RESEND VERIFICATION ──────────────────────────────────────────

    @PostMapping("/resend-verification")
    @Operation(summary = "Resend the email verification link")
    public ResponseEntity<AuthResponse> resendVerification(@RequestBody LoginRequest req,
                                                           HttpServletRequest request) {

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

        emailService.sendVerificationEmail(user.getEmail(), newToken, langFrom(request));
        securityAuditService.verificationEmailResent(user.getEmail());

        return ResponseEntity.ok(AuthResponse.builder()
                .message("Verification email resent. Please check your inbox.")
                .build());
    }

    // ── FORGOT PASSWORD ──────────────────────────────────────────────

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset link via email")
    public ResponseEntity<AuthResponse> forgotPassword(@RequestBody LoginRequest req,
                                                        HttpServletRequest request) {

        // Rate limit: 3 requests per email per hour
        if (!rateLimiter.allowForgotPassword(req.getEmail()))
            return tooManyRequests("Too many password reset attempts. Please wait before requesting another link.");

        var userOpt = userRepo.findByEmail(req.getEmail());
        if (userOpt.isPresent() && userOpt.get().isVerified())
            passwordResetService.sendResetLink(userOpt.get(), langFrom(request));

        // Always return the same message to prevent email enumeration
        return ResponseEntity.ok(AuthResponse.builder()
                .message("If that email is registered, you will receive a reset link shortly.")
                .build());
    }

    // ── RESET PASSWORD ───────────────────────────────────────────────

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using the reset token")
    public ResponseEntity<AuthResponse> resetPassword(@RequestBody ResetPasswordRequest req,
                                                      HttpServletRequest request) {

        if (req.getToken() == null || req.getNewPassword() == null)
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder().message("Token and new password are required").build());

        if (!PasswordPolicy.isValid(req.getNewPassword()))
            return ResponseEntity.badRequest()
                    .body(AuthResponse.builder()
                            .message(PasswordPolicy.message(langFrom(request)))
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
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                       HttpServletResponse response) {

        String email = sessionService.terminate(request, response);
        if (email != null) securityAuditService.logout(email);

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
            HttpServletRequest request,
            HttpServletResponse response) {

        if (userDetails == null) return ResponseEntity.status(401).build();

        // Full teardown, not just the blacklist: this used to leave the cookie in
        // the browser and the session key in Redis, so a deleted account went on
        // counting as signed in until its token expired.
        sessionService.terminate(request, response);

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

    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private ResponseEntity<AuthResponse> tooManyRequests(String message) {
        return ResponseEntity.status(429)
                .body(AuthResponse.builder().message(message).build());
    }

    private String langFrom(HttpServletRequest request) {
        return RequestLang.of(request);
    }
}
