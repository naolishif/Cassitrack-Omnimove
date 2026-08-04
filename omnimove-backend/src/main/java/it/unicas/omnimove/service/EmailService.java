package it.unicas.omnimove.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private final JavaMailSender mailSender;

    @Value("${omnimove.mail.from:OMNIMOVE <noreply@omnimove.it>}")
    private String from;

    @Value("${omnimove.mail.base-url:http://localhost:8180}")
    private String baseUrl;

    // ── Public API ──────────────────────────────────────────────────

    public void sendVerificationEmail(String to, String token) {
        sendVerificationEmail(to, token, "en");
    }

    public void sendVerificationEmail(String to, String token, String lang) {
        String link = baseUrl + "/api/v1/auth/verify?token=" + token;
        boolean it   = "it".equalsIgnoreCase(lang);
        String subject = it ? "OMNIMOVE — Verifica il tuo indirizzo email"
                            : "OMNIMOVE — Verify your email address";
        sendHtml(to, subject, buildVerificationHtml(link, it));
        log.info("[EMAIL] Verification email ({}) sent to {}", lang, to);
    }

    public void sendPasswordResetEmail(String to, String token) {
        sendPasswordResetEmail(to, token, "en");
    }

    public void sendPasswordResetEmail(String to, String token, String lang) {
        String link = baseUrl + "/api/v1/auth/reset-page?token=" + token;
        boolean it   = "it".equalsIgnoreCase(lang);
        String subject = it ? "OMNIMOVE — Reimposta la tua password"
                            : "OMNIMOVE — Reset your password";
        sendHtml(to, subject, buildResetHtml(link, it));
        log.info("[EMAIL] Password reset email ({}) sent to {}", lang, to);
    }

    // ── Internal helpers ────────────────────────────────────────────

    private void sendHtml(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("[EMAIL] Sent '{}' to {}", subject, to);
        } catch (Exception e) {
            log.warn("[EMAIL] Could not send email to {}: {}. Check MAIL_USERNAME / MAIL_PASSWORD .env vars.", to, e.getMessage());
        }
    }

    // ── HTML templates ──────────────────────────────────────────────

    private String buildVerificationHtml(String link, boolean it) {
        String title  = it ? "OMNIMOVE – Verifica la tua email"            : "OMNIMOVE – Verify your email";
        String hi     = it ? "Ciao,"                                        : "Hi,";
        String body   = it ? "Clicca il link qui sotto per verificare il tuo indirizzo email. Il link scade in 24 ore."
                           : "Click the link below to verify your email address. The link expires in 24 hours.";
        String cta    = it ? "Verifica il mio indirizzo email"              : "Verify my email address";
        String copy   = it ? "Oppure copia e incolla questo URL nel browser:" : "Or copy and paste this URL into your browser:";
        String footer = it ? "Se non hai creato un account OMNIMOVE, ignora questa email.<br>OMNIMOVE – Università di Cassino, UNICAS 2025/2026"
                           : "If you did not create an OMNIMOVE account, ignore this email.<br>OMNIMOVE – University of Cassino, UNICAS 2025/2026";
        return buildHtml(title, hi, body, link, cta, copy, footer, link);
    }

    private String buildResetHtml(String link, boolean it) {
        String title  = it ? "OMNIMOVE – Reimposta la password"             : "OMNIMOVE – Password reset";
        String hi     = it ? "Ciao,"                                        : "Hi,";
        String body   = it ? "Abbiamo ricevuto una richiesta di reimpostazione della password. Clicca il link qui sotto per impostarne una nuova. Il link scade in 1 ora."
                           : "We received a request to reset your password. Click the link below to set a new one. The link expires in 1 hour.";
        String cta    = it ? "Reimposta la mia password"                    : "Reset my password";
        String copy   = it ? "Oppure copia e incolla questo URL nel browser:" : "Or copy and paste this URL into your browser:";
        String footer = it ? "Se non hai richiesto un reset della password, ignora questa email. La tua password non cambierà.<br>OMNIMOVE – Università di Cassino, UNICAS 2025/2026"
                           : "If you did not request a password reset, ignore this email. Your password will not change.<br>OMNIMOVE – University of Cassino, UNICAS 2025/2026";
        return buildHtml(title, hi, body, link, cta, copy, footer, link);
    }

    private String buildHtml(String title, String hi, String body, String linkHref,
                              String cta, String copyLabel, String footer, String linkText) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:32px 20px;background:#ffffff;font-family:Arial,sans-serif;color:#111111;">
              <p style="font-size:16px;font-weight:bold;margin:0 0 16px;">%s</p>
              <p style="font-size:14px;margin:0 0 8px;">%s</p>
              <p style="font-size:14px;margin:0 0 20px;">%s</p>
              <p style="margin:0 0 20px;">
                <a href="%s" style="font-size:14px;color:#3B82F6;">%s</a>
              </p>
              <p style="font-size:12px;color:#666666;margin:0 0 6px;">%s</p>
              <p style="font-size:11px;color:#666666;word-break:break-all;margin:0 0 24px;">%s</p>
              <hr style="border:none;border-top:1px solid #dddddd;margin:0 0 16px;">
              <p style="font-size:11px;color:#999999;margin:0;">%s</p>
            </body>
            </html>
            """.formatted(title, hi, body, linkHref, cta, copyLabel, linkText, footer);
    }
}
