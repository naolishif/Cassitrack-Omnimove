package it.unicas.omnimove.service;

import it.unicas.omnimove.model.UserConsent;
import it.unicas.omnimove.repository.UserConsentRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes and reads the GDPR consent ledger.
 *
 * <p>Every decision is an INSERT. Withdrawing consent does not modify the
 * previous row — it appends a {@code granted = false} one, so the history stays
 * provable (art. 7(1)) and a user can be shown when they changed their mind.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {

    private final UserConsentRepository consentRepo;

    /**
     * Version of the informativa / cookie policy currently published.
     * Bump this whenever the text materially changes: consents recorded under an
     * older version stop counting as current and are collected again.
     */
    @Value("${omnimove.privacy.policy-version:2026-08-28}")
    private String policyVersion;

    private static final Set<String> KNOWN_TYPES = Set.of(
            UserConsent.TYPE_PRIVACY_NOTICE,
            UserConsent.TYPE_PROFILING,
            UserConsent.TYPE_THIRD_PARTY,
            UserConsent.TYPE_RESEARCH_USE);

    /**
     * Types whose validity does not lapse when the policy version changes.
     *
     * <p>An objection to research use is not a consent: rewording the notice must
     * not quietly re-include someone who asked to be left out. Everything else is
     * a genuine consent and is re-collected on a new policy version.
     */
    private static final Set<String> VERSION_INDEPENDENT = Set.of(
            UserConsent.TYPE_RESEARCH_USE);

    public String currentPolicyVersion() {
        return policyVersion;
    }

    public boolean isKnownType(String type) {
        return type != null && KNOWN_TYPES.contains(type);
    }

    /** Appends one decision to the ledger. */
    @Transactional
    public UserConsent record(Long userId, String subjectKey, String type, boolean granted,
                              String source, HttpServletRequest request) {

        UserConsent row = UserConsent.builder()
                .userId(userId)
                // Client-supplied and stored in a VARCHAR(64): sanitise here rather than
                // at each call site, otherwise an oversized value from /auth/register
                // would fail the insert and break sign-up.
                .subjectKey(sanitiseKey(subjectKey))
                .consentType(type)
                .granted(granted)
                .policyVersion(policyVersion)
                .source(source)
                .ipAddress(request == null ? null : request.getRemoteAddr())
                .userAgent(truncate(request == null ? null : request.getHeader("User-Agent"), 255))
                .recordedAt(ZonedDateTime.now())
                .build();

        return consentRepo.save(row);
    }

    /**
     * Current state per consent type for one user. A type absent from the map has
     * never been decided; a type recorded under an outdated policy version is
     * reported as not granted, so the UI asks again.
     */
    @Transactional(readOnly = true)
    public Map<String, Boolean> currentStateFor(Long userId) {
        Map<String, Boolean> state = new HashMap<>();
        for (String type : KNOWN_TYPES) {
            var latest = consentRepo
                    .findFirstByUserIdAndConsentTypeOrderByRecordedAtDesc(userId, type);

            if (latest.isEmpty()) {
                // RESEARCH_USE is an objection register on an art. 6(1)(e) basis:
                // having never spoken means included. A real consent, by contrast,
                // stays absent until it is actually given.
                if (UserConsent.TYPE_RESEARCH_USE.equals(type)) state.put(type, true);
                continue;
            }

            UserConsent c = latest.get();
            boolean stillCurrent = VERSION_INDEPENDENT.contains(type)
                                || policyVersion.equals(c.getPolicyVersion());
            state.put(type, c.isGranted() && stillCurrent);
        }
        return state;
    }

    /** Full ledger for the art. 15 data export. */
    @Transactional(readOnly = true)
    public List<UserConsent> historyFor(Long userId) {
        return consentRepo.findByUserIdOrderByRecordedAtDesc(userId);
    }

    /**
     * Links the choices a visitor made in the banner before signing up to the
     * account they just created, so the record is not orphaned.
     */
    @Transactional
    public void attachAnonymousConsents(String rawSubjectKey, Long userId) {
        String subjectKey = sanitiseKey(rawSubjectKey);
        if (subjectKey == null) return;
        List<UserConsent> orphans = consentRepo.findBySubjectKeyAndUserIdIsNull(subjectKey);
        orphans.forEach(c -> c.setUserId(userId));
        consentRepo.saveAll(orphans);
        if (!orphans.isEmpty())
            log.info("Attached {} anonymous consent rows to user {}", orphans.size(), userId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Opaque browser id: accept only what the banner generates, drop anything else. */
    public static String sanitiseKey(String key) {
        if (key == null) return null;
        String trimmed = key.trim();
        return trimmed.matches("[A-Za-z0-9_-]{8,64}") ? trimmed : null;
    }
}
