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

    /**
     * What is used when nobody ever told us which language to write in.
     *
     * <p>Italian, not English: the service is Cassino's, the privacy notice
     * exists only in Italian, and the pages themselves already fall back to
     * Italian for an Italian browser. This is the last resort behind a stored
     * preference and behind the request's own header, so it is reached only for
     * someone we know nothing about at a moment when they are not here to ask.
     */
    public static final String DEFAULT_LANG = "it";

    /**
     * The language to write to someone in, from whatever is known about them.
     *
     * <p>ONE PLACE, because the fallback order is a decision and not an
     * implementation detail: the person's own stored choice first, then whatever
     * the current request suggests, then the service default. Spelled out at each
     * call site instead, the order would eventually differ between two of them
     * and nobody would notice until a traveller got a form letter in the wrong
     * language.
     *
     * <p>Anything that is not Italian is treated as English, the same rule
     * {@code RequestLang} applies, so a stale or malformed value in the column
     * cannot produce a message in no language at all.
     */
    public static String langOf(String stored, String fromRequest) {
        String chosen = recognised(stored);
        if (chosen == null) chosen = recognised(fromRequest);
        return chosen == null ? DEFAULT_LANG : chosen;
    }

    /** As {@link #langOf(String, String)} where there is no request to fall back on. */
    public static String langOf(String stored) {
        return langOf(stored, null);
    }

    /**
     * A language we can actually write in, or null if this value is not one.
     *
     * <p>Matched on the prefix, like {@code RequestLang} does with
     * Accept-Language, so a full locale tag such as "it-IT" is Italian rather
     * than something unrecognised.
     *
     * <p>ANYTHING ELSE FALLS THROUGH rather than being forced to English. A value
     * we do not recognise carries no information, and treating it as a preference
     * would let one stray row outrank the request header — which does carry
     * information — and answer a reader in a language nobody chose.
     */
    private static String recognised(String v) {
        if (v == null) return null;
        String t = v.trim().toLowerCase();
        if (t.startsWith("it")) return "it";
        if (t.startsWith("en")) return "en";
        return null;
    }

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

    /**
     * The account is gone, and this is the last thing we send to that address.
     *
     * <p>Covers the deletions that are somebody's DECISION: the traveller pressing
     * delete on their own profile, and an operator removing the account from the
     * admin console. Both leave the person with nothing in the app to read a
     * confirmation in — the session is torn down in the same breath — so the
     * mailbox is the only place left to say it happened.
     *
     * <p>The invitation to come back is not a pleasantry: erasure under art. 17
     * removes the data, not the right to use the service, and someone who deleted
     * an account by mistake would otherwise be left assuming the door is shut.
     *
     * <p>Deliberately says nothing about WHO deleted it. The operator path is
     * reached from a console the traveller cannot see, and "an administrator
     * removed your account" raises a question this message cannot answer.
     */
    /**
     * Seven days' notice that an account is about to lapse for inactivity.
     *
     * <p>THE ONLY E-MAIL HERE THAT ASKS FOR SOMETHING BACK. The others report
     * what has already happened; this one is sent while the outcome can still be
     * changed, and the thing that changes it — signing in — is also the thing
     * that proves the address still reaches someone who wants the account.
     *
     * <p>{@code deletionDate} is computed from the account's own timestamps and
     * passed in already formatted, rather than written here as "in 7 days".
     * A reader opening the message three days late would take "7 days" to mean
     * three days more than it does, and the one date that matters would be the
     * one detail the notice got wrong.
     */
    public void sendInactivityWarningEmail(String to, String name, int months,
                                           String deletionDate, String lang) {
        boolean it = "it".equalsIgnoreCase(lang);
        String subject = it ? "OMNIMOVE — Il tuo account sta per essere eliminato"
                            : "OMNIMOVE — Your account is about to be deleted";
        sendHtml(to, subject, buildInactivityWarningHtml(name, months, deletionDate, it));
        log.info("[EMAIL] Inactivity warning ({}) sent to {}, deletion on {}", lang, to, deletionDate);
    }

    /**
     * The notice above went unanswered and the account has now gone.
     *
     * <p>Still a thank-you: this person did use the service, unlike the lapsed
     * sign-ups, and the fact that they stopped is not a reason to see them off
     * curtly. What it adds is the REASON — without it the message would read as
     * an account deleted out of nowhere, and the warning sent a week earlier
     * would look unrelated to anyone who had already forgotten it.
     */
    public void sendInactiveAccountDeletedEmail(String to, String name, int months, String lang) {
        boolean it = "it".equalsIgnoreCase(lang);
        String subject = it ? "Il tuo account OMNIMOVE è stato eliminato"
                            : "Your OMNIMOVE account has been deleted";
        sendHtml(to, subject, buildAccountDeletedHtml(name, it, months));
        log.info("[EMAIL] Inactive-account deletion notice ({}) sent to {}", lang, to);
    }

    public void sendAccountDeletedEmail(String to, String name, String lang) {
        boolean it = "it".equalsIgnoreCase(lang);
        String subject = it ? "Il tuo account OMNIMOVE è stato eliminato"
                            : "Your OMNIMOVE account has been deleted";
        sendHtml(to, subject, buildAccountDeletedHtml(name, it));
        log.info("[EMAIL] Account deletion notice ({}) sent to {}", lang, to);
    }

    /**
     * The sign-up was never confirmed and the window has run out.
     *
     * <p>A SEPARATE MESSAGE, not a variant of the one above, because there is
     * nothing to thank anybody for: this account was never used. Thanking someone
     * for a service they never reached reads as a form letter, and it would also
     * misdescribe what happened — worse here than elsewhere, since the recipient
     * may well be a person whose address somebody else typed in by mistake.
     *
     * <p>{@code hours} is passed in rather than written into the text so the
     * message always states the window actually enforced. Hard-coding "24 hours"
     * would put the mail out of step with the configuration the moment anyone
     * changed it — the same drift this whole area exists to prevent.
     */
    public void sendUnverifiedAccountDeletedEmail(String to, String name, int hours, String lang) {
        boolean it = "it".equalsIgnoreCase(lang);
        String subject = it ? "OMNIMOVE — Account non verificato, registrazione annullata"
                            : "OMNIMOVE — Unverified account, sign-up cancelled";
        sendHtml(to, subject, buildUnverifiedDeletedHtml(name, hours, it));
        log.info("[EMAIL] Unverified-account deletion notice ({}) sent to {}", lang, to);
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

    private String buildAccountDeletedHtml(String name, boolean it) {
        return buildAccountDeletedHtml(name, it, 0);
    }

    /**
     * @param inactiveMonths 0 when somebody chose to delete the account, otherwise
     *                       the inactivity period that caused it to lapse. The one
     *                       sentence that differs is worth more than a second
     *                       template that would drift out of step with this one.
     */
    private String buildAccountDeletedHtml(String name, boolean it, int inactiveMonths) {
        String title = it ? "Grazie per aver viaggiato con noi" : "Thank you for travelling with us";
        String hi    = greeting(name, it);
        // Only the opening sentence differs. Prefixing the reason instead would
        // close the account and then delete it two clauses later, which reads as
        // two separate events happening to the same person.
        //
        // "as we told you by e-mail" rather than "a week ago": the notice period
        // is configurable, and a sentence that names a length this text cannot
        // see is a sentence that will eventually be wrong.
        String opening = it
            ? (inactiveMonths <= 0
                ? "Il tuo account OMNIMOVE &egrave; stato eliminato"
                : "Non usavi OMNIMOVE da " + inactiveMonths + " mesi e, come ti avevamo "
                + "anticipato via e-mail, il tuo account &egrave; stato eliminato")
            : (inactiveMonths <= 0
                ? "Your OMNIMOVE account has been deleted"
                : "You had not used OMNIMOVE for " + inactiveMonths + " months and, as we told "
                + "you by e-mail, your account has now been deleted");

        String body  = it
            ? opening
            + ", e con esso tutti i dati collegati: preferenze, preferiti e storico dei viaggi. "
            + "Non conserviamo pi&ugrave; nulla che ti riguardi, salvo il registro dei consensi, "
            + "che la normativa ci impone di tenere ancora per un periodo limitato proprio per "
            + "poter dimostrare le scelte che avevi fatto."
            : opening
            + ", and with it everything attached to it: preferences, favourites and journey "
            + "history. We no longer hold anything about you, apart from the consent ledger, "
            + "which the law requires us to keep for a limited period precisely so that the "
            + "choices you made can be evidenced.";
        String back  = it
            ? "Grazie per aver usato OMNIMOVE. Se un giorno ti servisse di nuovo, puoi creare un "
            + "nuovo account quando vuoi: ripartirai semplicemente da zero."
            : "Thank you for using OMNIMOVE. If you ever need it again, you can create a new "
            + "account whenever you like — you will simply be starting fresh.";
        String cta   = it ? "Crea un nuovo account" : "Create a new account";
        String sign  = it ? "Buon viaggio,<br><strong>Il team OMNIMOVE</strong>"
                          : "Safe travels,<br><strong>The OMNIMOVE team</strong>";
        String footer = it
            ? "Hai ricevuto questo messaggio perch&eacute; il tuo account era associato a questo "
            + "indirizzo. &Egrave; l'ultima e-mail che ti inviamo."
            + "<br>OMNIMOVE – Universit&agrave; di Cassino, UNICAS 2025/2026"
            : "You received this message because your account was registered to this address. "
            + "This is the last e-mail we will send you."
            + "<br>OMNIMOVE – University of Cassino, UNICAS 2025/2026";

        return shell(closingContent(title, hi, body, back, cta, sign), footer);
    }

    private String buildInactivityWarningHtml(String name, int months, String deletionDate, boolean it) {
        String title = it ? "Il tuo account sta per essere eliminato"
                          : "Your account is about to be deleted";
        String hi    = greeting(name, it);
        String body  = it
            ? "Non accedi a OMNIMOVE da " + months + " mesi. La nostra informativa privacy prevede "
            + "che gli account rimasti inattivi cos&igrave; a lungo vengano chiusi, quindi il "
            + "<strong>" + deletionDate + "</strong> il tuo account e tutti i dati collegati "
            + "&mdash; preferenze, preferiti e storico dei viaggi &mdash; verranno eliminati."
            : "You have not signed in to OMNIMOVE for " + months + " months. Our privacy notice "
            + "provides for accounts left inactive this long to be closed, so on "
            + "<strong>" + deletionDate + "</strong> your account and everything attached to it "
            + "&mdash; preferences, favourites and journey history &mdash; will be deleted.";
        String back  = it
            ? "<strong>Se vuoi tenerlo, ti basta accedere.</strong> Un solo accesso prima di quella "
            + "data annulla la cancellazione, e non devi fare nient'altro. Se invece non ti serve "
            + "pi&ugrave;, puoi ignorare questo messaggio: alla data indicata faremo tutto noi."
            : "<strong>If you want to keep it, just sign in.</strong> A single sign-in before that "
            + "date calls the deletion off, and there is nothing else to do. If you no longer need "
            + "it, you can ignore this message: we will take care of it on the date above.";
        String cta   = it ? "Accedi e tieni il mio account" : "Sign in and keep my account";
        String sign  = it ? "A presto,<br><strong>Il team OMNIMOVE</strong>"
                          : "Hope to see you soon,<br><strong>The OMNIMOVE team</strong>";
        String footer = it
            ? "Hai ricevuto questo messaggio perch&eacute; il tuo account &egrave; associato a "
            + "questo indirizzo.<br>OMNIMOVE – Universit&agrave; di Cassino, UNICAS 2025/2026"
            : "You received this message because your account is registered to this address."
            + "<br>OMNIMOVE – University of Cassino, UNICAS 2025/2026";

        return shell(closingContent(title, hi, body, back, cta, sign), footer);
    }

    private String buildUnverifiedDeletedHtml(String name, int hours, boolean it) {
        String window = it ? humanWindowIt(hours) : humanWindowEn(hours);
        String title = it ? "Account non verificato" : "Account not verified";
        String hi    = greeting(name, it);
        String body  = it
            ? "Un account OMNIMOVE era stato creato con questo indirizzo e-mail, ma l'indirizzo non "
            + "&egrave; mai stato confermato entro " + window + ". Come indicato nella nostra "
            + "informativa privacy, la registrazione &egrave; quindi stata annullata e i dati "
            + "inseriti sono stati eliminati."
            : "An OMNIMOVE account was created with this e-mail address, but the address was never "
            + "confirmed within " + window + ". As stated in our privacy notice, the sign-up has "
            + "therefore been cancelled and the details entered were deleted.";
        String back  = it
            ? "Se eri tu e vuoi ancora usare OMNIMOVE, puoi registrarti di nuovo quando preferisci: "
            + "richiede meno di un minuto. Se invece non hai mai creato questo account, non devi "
            + "fare nulla — non resta traccia di nulla a tuo nome."
            : "If this was you and you still want to use OMNIMOVE, you can sign up again whenever "
            + "you like — it takes under a minute. If you never created this account, there is "
            + "nothing to do: nothing remains in your name.";
        String cta   = it ? "Registrati di nuovo" : "Sign up again";
        String sign  = it ? "<strong>Il team OMNIMOVE</strong>" : "<strong>The OMNIMOVE team</strong>";
        String footer = it
            ? "Hai ricevuto questo messaggio perch&eacute; questo indirizzo era stato usato per una "
            + "registrazione mai confermata."
            + "<br>OMNIMOVE – Universit&agrave; di Cassino, UNICAS 2025/2026"
            : "You received this message because this address was used for a sign-up that was never "
            + "confirmed."
            + "<br>OMNIMOVE – University of Cassino, UNICAS 2025/2026";

        return shell(closingContent(title, hi, body, back, cta, sign), footer);
    }

    /**
     * Shared body for the two closing messages: heading, greeting, what happened,
     * what can still be done, and the one button that acts on it.
     */
    private String closingContent(String title, String hi, String body, String back,
                                  String cta, String sign) {
        return """
              <p style="font-size:20px;font-weight:bold;margin:0 0 16px;color:#0f172a;">%s</p>
              <p style="font-size:14px;margin:0 0 8px;">%s</p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 16px;">%s</p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
              <p style="margin:0 0 28px;">
                <a href="%s" style="display:inline-block;background:#3B82F6;color:#ffffff;text-decoration:none;
                   font-size:14px;font-weight:bold;padding:12px 22px;border-radius:6px;">%s</a>
              </p>
              <p style="font-size:14px;line-height:1.6;margin:0 0 24px;">%s</p>
            """.formatted(title, hi, body, back, url("/omnimove-login.html"), cta, sign);
    }

    /**
     * An account can be deleted without a name on it — an operator-created row, or
     * a sign-up abandoned before the field was filled — and "Ciao ," is worse than
     * no name at all. The name is escaped: it is user-supplied text.
     */
    private static String greeting(String name, boolean it) {
        boolean has = name != null && !name.isBlank();
        if (it) return has ? "Ciao " + escapeHtml(name.trim()) + "," : "Ciao,";
        return has ? "Hi " + escapeHtml(name.trim()) + "," : "Hi,";
    }

    /** "24 ore" reads better than "24 ore" when it is really a day, and vice versa. */
    private static String humanWindowIt(int hours) {
        if (hours % 24 == 0 && hours >= 24) {
            int d = hours / 24;
            return d == 1 ? "24 ore" : d + " giorni";
        }
        return hours == 1 ? "un'ora" : hours + " ore";
    }

    private static String humanWindowEn(int hours) {
        if (hours % 24 == 0 && hours >= 24) {
            int d = hours / 24;
            return d == 1 ? "24 hours" : d + " days";
        }
        return hours == 1 ? "one hour" : hours + " hours";
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
