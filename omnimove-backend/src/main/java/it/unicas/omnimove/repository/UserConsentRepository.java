package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    /** Full ledger for one user, newest first — used by the data export (art. 15). */
    List<UserConsent> findByUserIdOrderByRecordedAtDesc(Long userId);

    /** Current state of one consent type = the most recent row for it. */
    Optional<UserConsent> findFirstByUserIdAndConsentTypeOrderByRecordedAtDesc(
            Long userId, String consentType);

    /** Same, for a visitor who has not signed up yet. */
    Optional<UserConsent> findFirstBySubjectKeyAndConsentTypeOrderByRecordedAtDesc(
            String subjectKey, String consentType);

    /** Used to attach anonymous banner choices to the account created afterwards. */
    List<UserConsent> findBySubjectKeyAndUserIdIsNull(String subjectKey);
}
