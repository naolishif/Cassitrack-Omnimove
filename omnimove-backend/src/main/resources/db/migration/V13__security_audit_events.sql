-- =================================================================
-- V12: Security audit events table (OWASP A09 – Security Logging)
--
-- Logs store masked PII (email → m***@domain.com, IP → x.x.x.xxx)
-- so they are safe to write to disk / ship to log aggregators.
-- This table stores the FULL unmasked details so that authorised
-- security personnel can reconstruct exactly who did what and from
-- where. Access is restricted to the 'security_auditor' role.
-- =================================================================

CREATE TABLE IF NOT EXISTS security_audit_events (
    id               BIGSERIAL    PRIMARY KEY,
    event_type       VARCHAR(50)  NOT NULL,
    email            VARCHAR(100),           -- full unmasked email
    ip_address       VARCHAR(50),            -- full unmasked IP
    additional_info  TEXT,                   -- extra context (JSON key=value)
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Fast lookups by event type, email and time range (forensic queries)
CREATE INDEX IF NOT EXISTS idx_sae_event_type  ON security_audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_sae_email       ON security_audit_events(email);
CREATE INDEX IF NOT EXISTS idx_sae_created_at  ON security_audit_events(created_at);

-- =================================================================
-- Restricted access
--
-- The 'security_auditor' role is a READ-ONLY role.  It can SELECT
-- from this table but cannot touch any other application table.
-- Grant it to the DBA / SIEM service account — never to the app
-- user that Hibernate runs as.
-- =================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'security_auditor') THEN
        CREATE ROLE security_auditor NOLOGIN;
    END IF;
END
$$;

-- security_auditor can only read this table
GRANT SELECT ON security_audit_events TO security_auditor;

-- The application's runtime user (omnimove) needs INSERT only.
-- SELECT is intentionally withheld so the app cannot leak full PII
-- through an API endpoint even if a bug is introduced.
-- Adjust 'omnimove' to match the DB user in application.properties.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'omnimove') THEN
        GRANT INSERT ON security_audit_events TO omnimove;
        GRANT USAGE, SELECT ON SEQUENCE security_audit_events_id_seq TO omnimove;
    END IF;
END
$$;
