package it.unicas.omnimove.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Drives the three-tier research lifecycle defined in V23__research_tiers.sql.
 *
 * <p>The SQL functions hold the logic; this class only decides <em>when</em> they
 * run. Keeping the rules in the database means they are auditable in one place
 * and cannot be bypassed by another client.
 *
 * <p>See {@code docs/privacy/DPIA-omnimove-cassitrack.md} for why the tiers exist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResearchPipelineService {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final JdbcTemplate jdbc;

    /**
     * Master switch. Off by default: the pipeline moves personal data between
     * tiers and deletes it, so it must be turned on deliberately, and only once
     * the DPIA has been signed off.
     */
    @Value("${omnimove.research.enabled:false}")
    private boolean enabled;

    /**
     * Secret used to derive the subject pseudonym.
     *
     * <p><b>It must never change once the pipeline has run.</b> A different salt
     * produces different pseudonyms, which silently splits one person into two
     * subjects across promotions and makes {@link #forgetSubject(long)} unable to
     * find their earlier rows — an objection would then go unhonoured.
     *
     * <p>It must also live outside the database it protects: if the salt were
     * stored next to the pseudonyms, a dump of the research schema would be
     * re-linkable to identities and the pseudonymisation would be worthless.
     */
    @Value("${omnimove.research.pseudonym-salt:}")
    private String salt;

    /** Tier 1 retention. Must match what the privacy notice states (§ 7). */
    @Value("${omnimove.research.operational-retention-days:365}")
    private int operationalRetentionDays;

    /** Tier 2 retention. Long, but finite: "indefinite" on personal data is not defensible. */
    @Value("${omnimove.research.tier2-retention-days:3650}")
    private int tier2RetentionDays;

    /** Small-cell suppression threshold. The migration refuses anything below 5. */
    @Value("${omnimove.research.k-threshold:10}")
    private int kThreshold;

    @PostConstruct
    void validateConfiguration() {
        if (!enabled) {
            log.info("Research pipeline disabled (omnimove.research.enabled=false). "
                   + "Journey retention is NOT being enforced.");
            return;
        }
        // Fail fast rather than self-disabling: a silently inactive pipeline means
        // journeys are kept forever while the privacy notice promises 12 months.
        if (salt == null || salt.length() < 32)
            throw new IllegalStateException(
                    "omnimove.research.pseudonym-salt must be set to at least 32 characters "
                  + "when omnimove.research.enabled=true. It must be stable forever and stored "
                  + "outside the database (e.g. RESEARCH_PSEUDONYM_SALT in the environment).");

        log.info("Research pipeline enabled — tier 1 retention {} days, tier 2 {} days, k={}",
                operationalRetentionDays, tier2RetentionDays, kThreshold);
    }

    // ── Daily: tier 1 → tier 2, then enforce tier 1 retention ────────────
    @Scheduled(cron = "${omnimove.research.promote-cron:0 15 3 * * *}", zone = "Europe/Rome")
    public void promoteAndPurge() {
        if (!enabled) return;

        ZonedDateTime cutoff = ZonedDateTime.now(ROME).minusDays(operationalRetentionDays);

        try {
            Long promoted = jdbc.queryForObject(
                    "SELECT research.promote_journeys(?, ?)", Long.class,
                    salt, Timestamp.from(cutoff.toInstant()));

            // Only ever purges what was just promoted — the SQL function refuses to
            // go past the promotion watermark, so a failure above cannot cascade
            // into data loss here.
            Long purged = jdbc.queryForObject(
                    "SELECT research.purge_operational(?)", Long.class,
                    Timestamp.from(cutoff.toInstant()));

            log.info("Research pipeline: promoted {} journeys, purged {} operational rows older than {}",
                    promoted, purged, cutoff.toLocalDate());
        } catch (Exception e) {
            // Never rethrow from a scheduled task: it would kill the whole schedule.
            log.error("Research pipeline promote/purge failed — retention NOT enforced this run", e);
        }
    }

    // ── Quarterly: tier 2 → tier 3, one closed quarter at a time ─────────
    @Scheduled(cron = "${omnimove.research.release-cron:0 0 4 1 1,4,7,10 *}", zone = "Europe/Rome")
    public void publishQuarterlyRelease() {
        if (!enabled) return;

        // The quarter that has just closed. Whole, disjoint periods are what keeps
        // successive releases from being subtracted from one another (DPIA §5.2);
        // V24 enforces the disjointness in the database.
        LocalDate today = LocalDate.now(ROME);
        LocalDate quarterStart = today.withDayOfMonth(1)
                                      .withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1)
                                      .minusMonths(3);
        LocalDate quarterEnd = quarterStart.plusMonths(3).minusDays(1);
        String label = quarterStart.getYear() + "-Q" + ((quarterStart.getMonthValue() - 1) / 3 + 1);

        try {
            Long published = jdbc.queryForObject(
                    "SELECT research.build_od_matrix(?, ?, ?, ?)", Long.class,
                    label, java.sql.Date.valueOf(quarterStart),
                    java.sql.Date.valueOf(quarterEnd), kThreshold);

            log.info("Research release {} published: {} aggregate cells (k={})",
                    label, published, kThreshold);
        } catch (Exception e) {
            log.error("Research release {} failed", label, e);
        }
    }

    // ── Yearly: tier 2 retention ─────────────────────────────────────────
    @Scheduled(cron = "${omnimove.research.tier2-purge-cron:0 30 4 2 1 *}", zone = "Europe/Rome")
    public void purgeResearchTier() {
        if (!enabled) return;

        LocalDate cutoff = LocalDate.now(ROME).minusDays(tier2RetentionDays);
        try {
            Long deleted = jdbc.queryForObject(
                    "SELECT research.purge_research(?)", Long.class,
                    java.sql.Date.valueOf(cutoff));
            log.info("Research tier 2: purged {} rows older than {}", deleted, cutoff);
        } catch (Exception e) {
            log.error("Research tier 2 purge failed", e);
        }
    }

    // ── Right to object, art. 21(6) ──────────────────────────────────────

    /**
     * Removes a subject's already-promoted rows from tier 2.
     *
     * <p>Called when someone objects to research use. The objection has to reach
     * data promoted <em>before</em> it was raised, otherwise it would only apply
     * going forward — which is not what art. 21 grants.
     *
     * <p>Tier 3 is deliberately untouched: it is anonymous, contains no subject
     * identifier, and there is nothing there to erase.
     *
     * @return rows removed, or 0 when the pipeline is not configured
     */
    public long forgetSubject(long userId) {
        if (!enabled) return 0;
        try {
            Long deleted = jdbc.queryForObject(
                    "SELECT research.forget_subject(?, ?)", Long.class, salt, userId);
            log.info("Objection honoured: removed {} research rows for user {}", deleted, userId);
            return deleted == null ? 0 : deleted;
        } catch (Exception e) {
            // Surface it: an objection that silently fails is a compliance breach,
            // not a background nuisance.
            log.error("Failed to honour research objection for user {}", userId, e);
            throw new IllegalStateException("Could not remove research data for this user", e);
        }
    }
}
