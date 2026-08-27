package it.unicas.omnimove.service;

import it.unicas.omnimove.model.User;
import it.unicas.omnimove.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Issues a password-reset link.
 *
 * Reached from two places — the public "forgot password" form and the account
 * page of someone already signed in — and both must mint the same token with
 * the same lifetime and leave the same audit trail. Keeping the token logic
 * here means the expiry cannot end up different depending on which door the
 * request came through.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    /** How long a reset link stays usable. */
    public static final int RESET_EXPIRY_HOURS = 1;

    private final UserRepository       userRepo;
    private final EmailService         emailService;
    private final SecurityAuditService securityAuditService;

    /**
     * Replaces any outstanding token and emails a fresh link.
     * Requesting a second link therefore invalidates the first.
     */
    public void sendResetLink(User user, String lang) {
        String token = UUID.randomUUID().toString();
        user.setResetPasswordToken(token);
        user.setResetPasswordTokenExpiry(LocalDateTime.now().plusHours(RESET_EXPIRY_HOURS));
        userRepo.save(user);

        emailService.sendPasswordResetEmail(user.getEmail(), token, lang);
        securityAuditService.passwordResetRequested(user.getEmail());
    }
}
