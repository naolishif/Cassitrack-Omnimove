-- =================================================================
-- V7: Security audit events table (OWASP A09 – Security Logging)
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
    additional_info  TEXT,                   -- extra context (key=value pairs)
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sae_event_type  ON security_audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_sae_email       ON security_audit_events(email);
CREATE INDEX IF NOT EXISTS idx_sae_created_at  ON security_audit_events(created_at);

-- =================================================================
-- Restricted access
--
-- 'security_auditor' is a READ-ONLY role for forensic queries.
-- Grant it to the DBA / SIEM service account, never to the app user.
-- The app DB user (cassitrack) gets INSERT only — no SELECT.
-- =================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'security_auditor') THEN
        CREATE ROLE security_auditor NOLOGIN;
    END IF;
END
$$;

GRANT SELECT ON security_audit_events TO security_auditor;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cassitrack') THEN
        GRANT INSERT ON security_audit_events TO cassitrack;
        GRANT USAGE, SELECT ON SEQUENCE security_audit_events_id_seq TO cassitrack;
    END IF;
END
$$;
