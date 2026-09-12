package it.unicas.omnimove.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces the retention periods privacy.html § 7 states.
 *
 * <p>Four of the five rules in that table were text only: journey history,
 * security logs, the consent ledger and unverified accounts were all kept
 * indefinitely. The fifth — erasure on request — already worked, through
 * ON DELETE CASCADE.
 *
 * <p>Every run is recorded in {@code retention_run}, successes and failures
 * alike, because art. 5(2) asks us to demonstrate the rule is applied and not
 * merely to assert it. The admin console reads that table back.
 *
 * <p><b>This service deletes data.</b> Each window is configurable and each rule
 * can be switched off on its own, but the defaults are the ones the published
 * notice promises — anything else puts the text back out of step with the code,
 * which is the problem this exists to solve.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    /**
     * The deletion date as it appears in the warning e-mail. Rendered in the
     * locale the notice is written in, and in Europe/Rome — the reader is in
     * Cassino, and a date shifted by a UTC server would name the wrong day for
     * anything sent late in the evening.
     */
    private static final DateTimeFormatter DATE_IN_NOTICE =
            DateTimeFormatter.ofPattern("d MMMM yyyy",
                    Locale.forLanguageTag(EmailService.DEFAULT_LANG));

    public static final String RULE_JOURNEY   = "JOURNEY_LOG";
    public static final String RULE_SECURITY  = "SECURITY_EVENTS";
    public static final String RULE_CONSENT   = "CONSENT_LEDGER";
    public static final String RULE_ACCOUNTS  = "UNVERIFIED_ACCOUNTS";
    public static final String RULE_INACTIVE  = "INACTIVE_ACCOUNTS";

    private final JdbcTemplate jdbc;

    @Value("${omnimove.retention.enabled:true}")
    private boolean enabled;

    /** privacy.html § 7: "12 mesi dalla registrazione del viaggio". */
    @Value("${omnimove.retention.journey-days:365}")
    private int journeyDays;

    /** privacy.html § 7: "Registri di sicurezza — 12 mesi". */
    @Value("${omnimove.retention.security-days:365}")
    private int securityDays;

    /** privacy.html § 7: "24 mesi dalla revoca". */
    @Value("${omnimove.retention.consent-days:730}")
    private int consentDays;

    /** privacy.html § 7: "Gli account non verificati entro 24 ore vengono eliminati". */
    @Value("${omnimove.retention.unverified-hours:24}")
    private int unverifiedHours;

    /** privacy.html § 7: "24 mesi senza alcun accesso". */
    @Value("${omnimove.retention.inactive-months:24}")
    private int inactiveMonths;

    /**
     * How long before the deletion the warning goes out.
     *
     * <p>Also the minimum notice the deletion pass will accept: an account is
     * removed only once its warning is at least this old, so shortening this
     * cannot retroactively cut short a notice already sent.
     */
    @Value("${omnimove.retention.inactive-warning-days:7}")
    private int inactiveWarningDays;

    /**
     * When the research pipeline is on it owns journey_log: it promotes rows to
     * tier 2 and only then purges them. Deleting underneath it would destroy the
     * data before it was pseudonymised, so this rule stands aside and says so.
     */
    @Value("${omnimove.research.enabled:false}")
    private boolean researchEnabled;

    private final EmailService email;

    public DataRetentionService(JdbcTemplate jdbc, EmailService email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    // ════════════════════════════════════════════════════════════════
    //  THE DAILY SWEEP
    // ════════════════════════════════════════════════════════════════

    /**
     * Runs before the research pipeline's own 03:15 slot, so on a day when both
     * are active the ordering is deterministic rather than incidental.
     */
    @Scheduled(cron = "${omnimove.retention.cron:0 45 2 * * *}", zone = "Europe/Rome")
    public void sweep() {
        if (!enabled) {
            log.info("Data retention disabled (omnimove.retention.enabled=false). "
                   + "privacy.html § 7 promises these deletions — leaving this off puts "
                   + "the published notice out of step with the system.");
            return;
        }
        purgeJourneys();
        purgeSecurityEvents();
        purgeConsentLedger();
        purgeUnverifiedAccounts();
        purgeInactiveAccounts();
    }

    // ── 1. Journey history ──────────────────────────────────────────
    void purgeJourneys() {
        if (researchEnabled) {
            record(RULE_JOURNEY, null, 0, "SKIPPED",
                   "Research pipeline is enabled and owns journey_log: it promotes rows "
                 + "to tier 2 and purges them behind the promotion watermark.");
            return;
        }
        if (!windowIsSane(RULE_JOURNEY, journeyDays, "days")) return;
        ZonedDateTime cutoff = ZonedDateTime.now(ROME).minusDays(journeyDays);
        run(RULE_JOURNEY, cutoff, () -> jdbc.update(
                "DELETE FROM journey_log WHERE created_at < ?", Timestamp.from(cutoff.toInstant())));
    }

    // ── 2. Security logs ────────────────────────────────────────────
    /**
     * Both registers of the same events age out together: security_audit_events
     * and the readable copy of the export records shown on an operator's card.
     * Keeping one after the other would leave the card claiming a history the
     * audit can no longer corroborate.
     */
    void purgeSecurityEvents() {
        if (!windowIsSane(RULE_SECURITY, securityDays, "days")) return;
        ZonedDateTime cutoff = ZonedDateTime.now(ROME).minusDays(securityDays);
        Timestamp ts = Timestamp.from(cutoff.toInstant());
        run(RULE_SECURITY, cutoff, () -> {
            int events  = jdbc.update("DELETE FROM security_audit_events WHERE created_at < ?", ts);
            int exports = jdbc.update("DELETE FROM admin_exports WHERE exported_at < ?", ts);
            return events + exports;
        });
    }

    // ── 3. Consent ledger ───────────────────────────────────────────
    /**
     * "24 months from withdrawal" — so what ages out is a decision that has since
     * been SUPERSEDED, never the one currently in force. Deleting a still-current
     * row because it happens to be old would reset the person's choices and put
     * the banner back in front of them, which is the opposite of what the ledger
     * is for.
     *
     * <p>Orphaned anonymous rows go too. They date from the flow where dismissing
     * the banner wrote a row keyed only by a random subjectKey: nobody can be
     * identified from one, so nobody can ask for its erasure, and it carries an IP
     * address. Nothing writes them any more.
     */
    void purgeConsentLedger() {
        if (!windowIsSane(RULE_CONSENT, consentDays, "days")) return;
        ZonedDateTime cutoff = ZonedDateTime.now(ROME).minusDays(consentDays);
        Timestamp ts = Timestamp.from(cutoff.toInstant());
        run(RULE_CONSENT, cutoff, () -> {
            int superseded = jdbc.update("""
                    DELETE FROM user_consents c
                     WHERE c.recorded_at < ?
                       AND EXISTS (
                           SELECT 1 FROM user_consents newer
                            WHERE newer.consent_type = c.consent_type
                              AND newer.recorded_at  > c.recorded_at
                              AND ((newer.user_id IS NOT NULL AND newer.user_id = c.user_id)
                                OR (newer.user_id IS NULL AND c.user_id IS NULL
                                    AND newer.subject_key = c.subject_key)))
                    """, ts);
            int orphans = jdbc.update(
                    "DELETE FROM user_consents WHERE user_id IS NULL AND recorded_at < ?", ts);
            return superseded + orphans;
        });
    }

    // ── 4. Unverified accounts ──────────────────────────────────────
    /**
     * Only accounts left stranded by the e-mail sign-up flow.
     *
     * <p>The predicate is deliberately narrower than "verified = false". An
     * account created by an operator through POST /admin/users is built without
     * {@code verified(true)} and so is unverified for ever; a blanket rule would
     * have quietly deleted every one of them a day after it was created. Those
     * have no verification token, which is what separates them here. ADMIN
     * accounts are excluded outright as a second line of defence.
     */
    void purgeUnverifiedAccounts() {
        if (!windowIsSane(RULE_ACCOUNTS, unverifiedHours, "hours")) return;
        ZonedDateTime cutoff = ZonedDateTime.now(ROME).minusHours(unverifiedHours);
        run(RULE_ACCOUNTS, cutoff, () -> {
            // DELETE ... RETURNING, not SELECT-then-DELETE.
            //
            // The two-statement version has a gap: somebody can click the link in
            // the seconds between the read and the write, and then we either
            // delete a freshly verified account or tell a perfectly valid account
            // holder that their sign-up was cancelled. RETURNING hands back the
            // rows this statement actually removed, so the list and the deletion
            // cannot disagree.
            List<Map<String, Object>> deleted = jdbc.queryForList("""
                    DELETE FROM users
                     WHERE verified = FALSE
                       AND verification_token IS NOT NULL
                       AND UPPER(COALESCE(role, '')) <> 'ADMIN'
                       AND created_at < ?
                    RETURNING email, name, language
                    """, Timestamp.from(cutoff.toInstant()));

            notifyUnverified(deleted);
            return deleted.size();
        });
    }

    /**
     * Tells the addresses whose sign-up just lapsed, once the rows are gone.
     *
     * <p>Runs after the statement has committed, so nobody is told their account
     * was removed before it actually was.
     *
     * <p>WRAPPED INDIVIDUALLY. EmailService already swallows its own failures, but
     * a burst of messages goes through an SMTP server that can refuse mid-way, and
     * an exception escaping here would land in {@code run()} and record the whole
     * sweep as FAILED — a rule that did its job would be reported as broken, and
     * the console would show the retention period unenforced when it was enforced.
     * The deletion is the obligation; the message is a courtesy, and a courtesy
     * must not be able to discredit the record of the obligation.
     */
    private void notifyUnverified(List<Map<String, Object>> deleted) {
        for (Map<String, Object> row : deleted) {
            Object address = row.get("email");
            if (address == null) continue;
            try {
                email.sendUnverifiedAccountDeletedEmail(
                        address.toString(),
                        str(row.get("name")),
                        unverifiedHours,
                        EmailService.langOf(str(row.get("language"))));
            } catch (Exception e) {
                log.warn("Unverified-account notice could not be sent to {}: {}",
                         address, e.getMessage());
            }
        }
    }

    // ── 5. Dormant accounts ─────────────────────────────────────────
    /**
     * Closes accounts nobody has signed in to for {@code inactiveMonths}, a week
     * after telling their owner it is about to happen.
     *
     * <p>TWO PASSES, IN THIS ORDER, AND THE ORDER IS THE SAFETY. The warning pass
     * stamps today's date on everyone newly approaching the limit; the deletion
     * pass will only touch an account whose stamp is already {@code
     * inactiveWarningDays} old. Nobody can therefore be warned and deleted by the
     * same sweep, however far past the limit they were when the rule first ran.
     *
     * <p>WHY NOT COUNT THE DAYS FROM THE LAST LOGIN INSTEAD. Because that assumes
     * this job ran on the day it should have. Let it stop for a month — a server
     * off, a failed deploy — and on the next run every dormant account would be
     * past 24 months with no e-mail ever sent, and all of them would go at once.
     * Reading the recorded warning date instead makes the notice a fact that
     * happened rather than a date we assume was reached, which is what the
     * promise in § 7 actually is.
     *
     * <p>SIGNING IN CANCELS IT, with nothing to reset. The predicate requires the
     * warning to be more recent than the last sign-in; come back after being
     * warned and that stops being true, so the deletion simply never matches. Go
     * quiet again later and the warning pass, reading the same comparison, sends
     * a fresh notice. No flag to clear on login, and therefore no flag anyone can
     * forget to clear.
     *
     * <p>Unverified and operator-created accounts are excluded: the first belong
     * to the 24-hour rule above, and the second were made deliberately by someone
     * who is still the right person to decide their fate.
     */
    void purgeInactiveAccounts() {
        if (!windowIsSane(RULE_INACTIVE, inactiveMonths, "months")) return;
        if (!windowIsSane(RULE_INACTIVE, inactiveWarningDays, "warning days")) return;

        ZonedDateTime now       = ZonedDateTime.now(ROME);
        ZonedDateTime dormant   = now.minusMonths(inactiveMonths);
        ZonedDateTime approaching = dormant.plusDays(inactiveWarningDays);
        ZonedDateTime noticeGiven = now.minusDays(inactiveWarningDays);

        runDetailed(RULE_INACTIVE, dormant, () -> {
            int warned  = warnDormant(approaching, now);
            int deleted = deleteDormant(dormant, noticeGiven);
            return new Result(deleted, warned == 0
                    ? null
                    : warned + " account(s) warned; they are deleted no sooner than "
                      + inactiveWarningDays + " days from now unless they sign in.");
        });
    }

    /**
     * Sends the notice and records that it went, in one statement.
     *
     * <p>The UPDATE ... RETURNING is what makes "warn exactly once" true: the row
     * is stamped and handed back together, so a second pass cannot pick the same
     * person up again, and the e-mail list cannot contain anyone whose stamp
     * failed to save.
     */
    private int warnDormant(ZonedDateTime approaching, ZonedDateTime now) {
        List<Map<String, Object>> warned = jdbc.queryForList("""
                UPDATE users
                   SET inactivity_warned_at = ?
                 WHERE verified = TRUE
                   AND UPPER(COALESCE(role, '')) <> 'ADMIN'
                   AND COALESCE(last_login_at, created_at) < ?
                   AND (inactivity_warned_at IS NULL
                        OR inactivity_warned_at <= COALESCE(last_login_at, created_at))
                RETURNING email, name, language,
                          COALESCE(last_login_at, created_at) AS inactive_since
                """,
                Timestamp.from(now.toInstant()),
                Timestamp.from(approaching.toInstant()));

        for (Map<String, Object> row : warned) {
            Object address = row.get("email");
            if (address == null) continue;
            try {
                email.sendInactivityWarningEmail(
                        address.toString(),
                        str(row.get("name")),
                        inactiveMonths,
                        deletionDate(row.get("inactive_since"), now),
                        EmailService.langOf(str(row.get("language"))));
            } catch (Exception e) {
                log.warn("Inactivity warning could not be sent to {}: {}", address, e.getMessage());
            }
        }
        return warned.size();
    }

    /**
     * The date the notice must state: whichever of the two conditions is satisfied
     * LAST.
     *
     * <p>Normally both land on the same day. They come apart when the rule meets
     * an account that was already long dormant when it first ran — its 24 months
     * elapsed months ago, so the date that governs is the end of the seven days
     * starting today. Naming the earlier of the two would promise a deletion on a
     * date when nothing will happen, and the message would be wrong about the
     * only thing it exists to say.
     */
    private String deletionDate(Object inactiveSince, ZonedDateTime now) {
        ZonedDateTime byNotice = now.plusDays(inactiveWarningDays);
        ZonedDateTime when = byNotice;

        if (inactiveSince instanceof Timestamp ts) {
            ZonedDateTime byDormancy = ts.toInstant().atZone(ROME).plusMonths(inactiveMonths);
            if (byDormancy.isAfter(byNotice)) when = byDormancy;
        }
        return when.format(DATE_IN_NOTICE);
    }

    /**
     * Removes only accounts that were warned, have stayed away since, and whose
     * notice period has genuinely elapsed. Each of the three is checked against a
     * stored timestamp; none of them is inferred from the fact that the job is
     * running today.
     */
    private int deleteDormant(ZonedDateTime dormant, ZonedDateTime noticeGiven) {
        List<Map<String, Object>> deleted = jdbc.queryForList("""
                DELETE FROM users
                 WHERE verified = TRUE
                   AND UPPER(COALESCE(role, '')) <> 'ADMIN'
                   AND COALESCE(last_login_at, created_at) < ?
                   AND inactivity_warned_at IS NOT NULL
                   AND inactivity_warned_at > COALESCE(last_login_at, created_at)
                   AND inactivity_warned_at <= ?
                RETURNING email, name, language
                """,
                Timestamp.from(dormant.toInstant()),
                Timestamp.from(noticeGiven.toInstant()));

        for (Map<String, Object> row : deleted) {
            Object address = row.get("email");
            if (address == null) continue;
            try {
                email.sendInactiveAccountDeletedEmail(
                        address.toString(),
                        str(row.get("name")),
                        inactiveMonths,
                        EmailService.langOf(str(row.get("language"))));
            } catch (Exception e) {
                log.warn("Inactive-account notice could not be sent to {}: {}",
                         address, e.getMessage());
            }
        }
        return deleted.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  PLUMBING
    // ════════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface Purge { int run(); }

    /** A nullable column as a String, since every one of these may be absent. */
    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * Refuses a window that would empty the table.
     *
     * <p>Every rule here is "older than now minus N", so N = 0 makes the cut-off
     * this instant and deletes everything, and a negative N puts it in the future
     * and deletes everything including rows not yet written. A typo in an
     * environment variable should not be able to wipe a table on the next tick,
     * and the run is recorded as FAILED so the console shows the period is not
     * being enforced instead of showing a successful run that removed the lot.
     */
    private boolean windowIsSane(String rule, int window, String unit) {
        if (window >= 1) return true;
        record(rule, null, 0, "FAILED",
               "Refusing to run: the configured window is " + window + " " + unit
             + ". That cut-off is now or in the future and would delete every row. "
             + "Fix the configuration; nothing was touched.");
        log.error("Retention {} NOT run: window of {} {} would delete everything", rule, window, unit);
        return false;
    }

    /** What a rule did: rows removed, plus anything worth saying about it. */
    private record Result(int rows, String detail) {}

    @FunctionalInterface
    private interface Sweep { Result run(); }

    /**
     * As {@link #run}, for a rule that has something to report beyond a count.
     *
     * <p>Separate name rather than an overload: a lambda could not be told apart
     * between the two functional interfaces, and the compiler error that produces
     * is far less obvious than the extra word here.
     */
    private void runDetailed(String rule, ZonedDateTime cutoff, Sweep sweep) {
        try {
            Result r = sweep.run();
            record(rule, cutoff, r.rows(), "OK", r.detail());
            log.info("Retention {}: removed {} rows older than {}{}",
                     rule, r.rows(), cutoff, r.detail() == null ? "" : " — " + r.detail());
        } catch (Exception e) {
            record(rule, cutoff, 0, "FAILED", e.getMessage());
            log.error("Retention {} FAILED — the period is not being enforced", rule, e);
        }
    }

    /** Runs one rule and records the outcome, whatever it is. */
    private void run(String rule, ZonedDateTime cutoff, Purge purge) {
        try {
            int removed = purge.run();
            record(rule, cutoff, removed, "OK", null);
            log.info("Retention {}: removed {} rows older than {}", rule, removed, cutoff);
        } catch (Exception e) {
            // Never rethrow from a scheduled task: it would take down the schedule
            // and the remaining rules with it.
            record(rule, cutoff, 0, "FAILED", e.getMessage());
            log.error("Retention {} FAILED — the period is not being enforced", rule, e);
        }
    }

    private void record(String rule, ZonedDateTime cutoff, long rows, String outcome, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO retention_run (rule, cutoff, rows_removed, outcome, detail)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    rule,
                    cutoff == null ? null : Timestamp.from(cutoff.toInstant()),
                    rows, outcome, detail);
        } catch (Exception e) {
            log.error("Could not record retention run for {}", rule, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  WHAT THE ADMIN CONSOLE READS
    // ════════════════════════════════════════════════════════════════

    /**
     * Every rule with the period it enforces, what its last run did, and what
     * every run has removed in total.
     *
     * <p>{@code neverRun} is reported as its own state rather than folded into
     * "0 rows removed": a job that has never fired and a job that found nothing
     * to delete look identical in a count, and only one of them is a problem.
     *
     * <p>THE TWO COUNTS ANSWER DIFFERENT QUESTIONS. {@code rows_removed} is last
     * night's figure and says whether the rule is working now; a sudden zero on a
     * rule that usually removes hundreds is a symptom. {@code totalRemoved} is the
     * running total and is what art. 5(2) actually asks for — evidence that the
     * period has been enforced all along, not merely once.
     *
     * <p>It travels with {@code totalSince}, the first run on record, because a
     * total with no starting point invites the reading that it covers the whole
     * life of the service. It does not: the ledger begins when retention_run was
     * created, and anything deleted before that was never counted here.
     */
    public Map<String, Object> status() {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule(RULE_JOURNEY,  "Journey history",     journeyDays + " days",
                       researchEnabled ? "Handled by the research pipeline" : null));
        rules.add(rule(RULE_SECURITY, "Security logs",       securityDays + " days", null));
        rules.add(rule(RULE_CONSENT,  "Consent ledger",      consentDays + " days",
                       "Superseded entries and orphaned anonymous ones"));
        rules.add(rule(RULE_ACCOUNTS, "Unverified accounts", unverifiedHours + " hours",
                       "Only sign-ups that never confirmed their e-mail"));
        rules.add(rule(RULE_INACTIVE, "Dormant accounts",    inactiveMonths + " months",
                       "Warned " + inactiveWarningDays + " days first; signing in cancels it"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("rules", rules);
        return out;
    }

    private Map<String, Object> rule(String key, String label, String period, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", key);
        m.put("label", label);
        m.put("period", period);
        if (note != null) m.put("note", note);

        List<Map<String, Object>> last = jdbc.queryForList("""
                SELECT ran_at, cutoff, rows_removed, outcome, detail
                  FROM retention_run WHERE rule = ? ORDER BY ran_at DESC LIMIT 1
                """, key);

        if (last.isEmpty()) {
            m.put("neverRun", true);
        } else {
            m.put("neverRun", false);
            m.putAll(last.get(0));
        }

        // Summed over every run, not just the successful ones — a FAILED or
        // SKIPPED run records zero rows, so they contribute nothing and the
        // total stays a count of rows actually deleted.
        Map<String, Object> agg = jdbc.queryForMap("""
                SELECT COALESCE(SUM(rows_removed), 0) AS total, MIN(ran_at) AS since
                  FROM retention_run WHERE rule = ?
                """, key);
        m.put("totalRemoved", agg.get("total"));
        m.put("totalSince",   agg.get("since"));
        return m;
    }
}
