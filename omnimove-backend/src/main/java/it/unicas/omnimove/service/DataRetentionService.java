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

    public static final String RULE_JOURNEY   = "JOURNEY_LOG";
    public static final String RULE_SECURITY  = "SECURITY_EVENTS";
    public static final String RULE_CONSENT   = "CONSENT_LEDGER";
    public static final String RULE_ACCOUNTS  = "UNVERIFIED_ACCOUNTS";

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

    /**
     * When the research pipeline is on it owns journey_log: it promotes rows to
     * tier 2 and only then purges them. Deleting underneath it would destroy the
     * data before it was pseudonymised, so this rule stands aside and says so.
     */
    @Value("${omnimove.research.enabled:false}")
    private boolean researchEnabled;

    public DataRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
    void purgeSecurityEvents() {
        if (!windowIsSane(RULE_SECURITY, securityDays, "days")) return;
        ZonedDateTime cutoff = ZonedDateTime.now(ROME).minusDays(securityDays);
        run(RULE_SECURITY, cutoff, () -> jdbc.update(
                "DELETE FROM security_audit_events WHERE created_at < ?",
                Timestamp.from(cutoff.toInstant())));
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
        run(RULE_ACCOUNTS, cutoff, () -> jdbc.update("""
                DELETE FROM users
                 WHERE verified = FALSE
                   AND verification_token IS NOT NULL
                   AND UPPER(COALESCE(role, '')) <> 'ADMIN'
                   AND created_at < ?
                """, Timestamp.from(cutoff.toInstant())));
    }

    // ════════════════════════════════════════════════════════════════
    //  PLUMBING
    // ════════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface Purge { int run(); }

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
     * Every rule with the period it enforces and what its last run did.
     *
     * <p>{@code neverRun} is reported as its own state rather than folded into
     * "0 rows removed": a job that has never fired and a job that found nothing
     * to delete look identical in a count, and only one of them is a problem.
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
        return m;
    }
}
