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

    /**
     * The app is served under a context path, so a bare origin is not enough to
     * reach an endpoint. Composing it here rather than expecting it inside
     * APP_BASE_URL removes the failure it used to cause: a base URL without the
     * prefix produced verification and reset links that landed on a 404, and the
     * variable is also read as a plain origin elsewhere.
     */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    /** Absolute URL for an in-app path such as "/api/v1/auth/verify". */
    private String url(String path) {
        String base = trimSlash(baseUrl);
        String ctx  = trimSlash(contextPath);
        // Tolerated for compatibility: a deployment that already put the prefix
        // into APP_BASE_URL must not end up with it twice
        if (!ctx.isEmpty() && !base.endsWith(ctx)) base = base + ctx;
        return base + path;
    }

    private static String trimSlash(String v) {
        if (v == null) return "";
        String out = v.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    // ── Public API ──────────────────────────────────────────────────

    public void sendVerificationEmail(String to, String token) {
        sendVerificationEmail(to, token, "en");
    }

    public void sendVerificationEmail(String to, String token, String lang) {
        String link = url("/api/v1/auth/verify?token=" + token);
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
        String link = url("/api/v1/auth/reset-page?token=" + token);
        boolean it   = "it".equalsIgnoreCase(lang);
        String subject = it ? "OMNIMOVE — Reimposta la tua password"
                            : "OMNIMOVE — Reset your password";
        sendHtml(to, subject, buildResetHtml(link, it));
        log.info("[EMAIL] Password reset email ({}) sent to {}", lang, to);
    }

    /**
     * Sent once the account becomes usable — after email verification for a
     * local sign-up, immediately for a Google one, since Google has already
     * proven the address. Never sent alongside the verification email: two
     * messages arriving together read as a bug, and a welcome to an account
     * that may never be confirmed is premature.
     */
    public void sendWelcomeEmail(String to, String name, String lang) {
        boolean it = "it".equalsIgnoreCase(lang);
        String subject = it ? "Benvenuto su OMNIMOVE" : "Welcome to OMNIMOVE";
        sendHtml(to, subject, buildWelcomeHtml(name, it));
        log.info("[EMAIL] Welcome email ({}) sent to {}", lang, to);
    }

    /**
     * Acknowledges a message sent from inside the app.
     *
     * <p>Sent on the same path as every other e-mail here, so a failure is
     * logged and swallowed: the message is already stored, and the person who
     * wrote it should not be told their feedback was lost because our SMTP was
     * having a bad day.
     */
    public void sendMessageReceivedEmail(String to, String name, String messageBody, String lang) {
        boolean it = "it".equalsIgnoreCase(lang);
        String subject = it ? "Abbiamo ricevuto il tuo messaggio" : "We have received your message";
        sendHtml(to, subject, buildMessageReceivedHtml(name, messageBody, it));
        log.info("[EMAIL] Message acknowledgement ({}) sent to {}", lang, to);
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

    private String buildWelcomeHtml(String name, boolean it) {
        String title = it ? "Benvenuto a bordo!" : "Welcome aboard!";
        String hi    = it ? ("Ciao " + name + ",") : ("Hi " + name + ",");
        String body  = it
            ? "Grazie per esserti registrato a OMNIMOVE. Da adesso puoi pianificare i tuoi spostamenti a Cassino "
            + "mettendo insieme autobus, bici e monopattini in un unico percorso, vedere in tempo reale dove sono i "
            + "mezzi e sapere quanto ti costa — e quanta CO&#8322; risparmi — prima ancora di partire."
            : "Thank you for signing up to OMNIMOVE. From now on you can plan your journeys around Cassino by "
            + "combining buses, bikes and e-scooters into a single route, follow the vehicles live, and see what a "
            + "trip costs — and how much CO&#8322; it saves — before you set off.";
        String cta   = it ? "Apri OMNIMOVE" : "Open OMNIMOVE";
        String sign  = it ? "Buon viaggio,<br><strong>Il team OMNIMOVE</strong>"
                          : "Enjoy the ride,<br><strong>The OMNIMOVE team</strong>";
        String footer = it ? "OMNIMOVE – Università di Cassino, UNICAS 2025/2026"
                           : "OMNIMOVE – University of Cassino, UNICAS 2025/2026";

        String content = """
              <p style="font-size:20px;font-weight:bold;margin:0 0 16px;color:#0f172a;">%s</p>
              <p style="font-size:14px;margin:0 0 8px;">%s</p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
              <p style="margin:0 0 28px;">
                <a href="%s" style="display:inline-block;background:#3B82F6;color:#ffffff;text-decoration:none;
                   font-size:14px;font-weight:bold;padding:12px 22px;border-radius:6px;">%s</a>
              </p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
            """.formatted(title, hi, body, url("/omnimove-login.html"), cta, sign);

        return shell(content, footer);
    }

    private String buildMessageReceivedHtml(String name, String messageBody, boolean it) {
        String title = it ? "Grazie per averci scritto" : "Thank you for writing to us";
        String hi    = it ? ("Ciao " + name + ",") : ("Hi " + name + ",");
        String body  = it
            ? "Il tuo contributo &egrave; importante. Il tuo messaggio verr&agrave; preso presto in esame "
            + "dai nostri sviluppatori, e se serve ti ricontatteremo a questo indirizzo."
            : "Your contribution matters. Your message will be looked at shortly by our developers, "
            + "and we will get back to you at this address if we need to.";
        String quotedLabel = it ? "Il messaggio che ci hai inviato:" : "The message you sent us:";
        String sign  = it ? "A presto,<br><strong>Il team OMNIMOVE</strong>"
                          : "Talk soon,<br><strong>The OMNIMOVE team</strong>";
        String footer = it ? "OMNIMOVE – Universit&agrave; di Cassino, UNICAS 2025/2026"
                           : "OMNIMOVE – University of Cassino, UNICAS 2025/2026";

        // Quoted back so the sender has a record of what they wrote. Escaped:
        // it is their own text and must never be read as markup in an inbox.
        String content = """
              <p style="font-size:20px;font-weight:bold;margin:0 0 16px;color:#0f172a;">%s</p>
              <p style="font-size:14px;margin:0 0 8px;">%s</p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
              <p style="font-size:12px;color:#64748b;margin:0 0 8px;">%s</p>
              <blockquote style="margin:0 0 24px;padding:12px 16px;background:#f1f5f9;
                 border-left:3px solid #cbd5e1;border-radius:4px;font-size:14px;
                 line-height:1.6;color:#334155;white-space:pre-wrap;">%s</blockquote>
              <p style="font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
            """.formatted(title, hi, body, quotedLabel, escapeHtml(messageBody), sign);

        return shell(content, footer);
    }

    /** The message is the sender's own text: it goes into the mail as text. */
    private static String escapeHtml(String s) {
        return (s == null ? "" : s)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildHtml(String title, String hi, String body, String linkHref,
                              String cta, String copyLabel, String footer, String linkText) {
        String content = """
              <p style="font-size:16px;font-weight:bold;margin:0 0 16px;color:#0f172a;">%s</p>
              <p style="font-size:14px;margin:0 0 8px;">%s</p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 20px;">%s</p>
              <p style="margin:0 0 20px;">
                <a href="%s" style="display:inline-block;background:#3B82F6;color:#ffffff;text-decoration:none;
                   font-size:14px;font-weight:bold;padding:12px 22px;border-radius:6px;">%s</a>
              </p>
              <p style="font-size:12px;color:#666666;margin:0 0 6px;">%s</p>
              <p style="font-size:11px;color:#666666;word-break:break-all;margin:0 0 24px;">%s</p>
            """.formatted(title, hi, body, linkHref, cta, copyLabel, linkText);

        return shell(content, footer);
    }

    /**
     * Shared frame: wordmark, body, footer rule.
     *
     * The logo is drawn as styled text rather than attached as an image. Mail
     * clients block remote images until the reader allows them and Gmail drops
     * data: URIs outright, so a picture would leave a broken box at the top of
     * every message. The app's own logo is set type anyway — the login page
     * builds it exactly this way — so nothing is lost by rendering it as text.
     */
    private String shell(String content, String footer) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f1f5f9;">
              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f5f9;">
                <tr><td align="center" style="padding:28px 12px;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"
                         style="max-width:560px;background:#ffffff;border-radius:10px;
                                border:1px solid #e2e8f0;font-family:Arial,Helvetica,sans-serif;color:#111111;">
                    <tr><td style="padding:28px 32px 0;">
                      <div style="font-size:26px;font-weight:bold;letter-spacing:1px;line-height:1;">
                        <span style="color:#0f172a;">OMNI</span><span style="color:#3B82F6;">MOVE</span>
                      </div>
                      <div style="font-size:10px;letter-spacing:2px;color:#94a3b8;margin-top:6px;">
                        SMARTER URBAN MOBILITY
                      </div>
                      <hr style="border:none;border-top:1px solid #e2e8f0;margin:20px 0 24px;">
                    </td></tr>
                    <tr><td style="padding:0 32px;">
            %s
                    </td></tr>
                    <tr><td style="padding:0 32px 28px;">
                      <hr style="border:none;border-top:1px solid #e2e8f0;margin:0 0 16px;">
                      <p style="font-size:11px;color:#94a3b8;margin:0;line-height:1.6;">%s</p>
                    </td></tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(content, footer);
    }
}
