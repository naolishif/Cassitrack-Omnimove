package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.SecurityAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link SecurityAuditEvent}.
 *
 * The application DB user has INSERT only on the underlying table — no SELECT.
 * Therefore this repository must ONLY be used for save() calls; any findBy*
 * queries will fail at the DB level by design (restricted access).
 *
 * Forensic queries should be run directly by the 'security_auditor' DB role
 * or through a dedicated back-office tool with that role's credentials.
 */
@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, Long> {
    // No query methods exposed — SELECT is restricted to the security_auditor DB role.
}
