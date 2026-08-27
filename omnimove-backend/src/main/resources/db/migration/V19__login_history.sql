-- =================================================================
-- V19: Registration timestamp + login history
--
-- The admin dashboard shows, for every user, when the account was
-- created and when it was last used, and lets the operator open the
-- full list of accesses.
--
-- security_audit_events already records LOGIN_SUCCESS, but that table
-- is deliberately write-only for the application (see V13), so it can
-- never back an API response. login_events is a normal application
-- table: same facts, no unmasked forensic payload, readable by the app.
-- =================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS login_events (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    logged_in_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    ip_address   VARCHAR(50),
    user_agent   VARCHAR(255)
);

-- The history modal reads one user's accesses newest-first; the list view
-- reads users.last_login_at, so this index only serves the detail query.
CREATE INDEX IF NOT EXISTS idx_login_events_user
    ON login_events(user_id, logged_in_at DESC);

-- =================================================================
-- Best-effort backfill from the audit trail
--
-- Accounts that already exist would otherwise all report "registered
-- today" and "never logged in". If this DB user can still read
-- security_audit_events, the real dates are recovered from it.
-- Where it cannot (ownership moved to security_auditor as V13
-- intends), the columns keep their defaults and only new activity is
-- tracked — hence the exception handler rather than a hard failure.
-- =================================================================

DO $$
BEGIN
    -- Registration date = first REGISTRATION event for that email
    UPDATE users u
       SET created_at = e.first_seen
      FROM (SELECT lower(email) AS email, MIN(created_at) AS first_seen
              FROM security_audit_events
             WHERE event_type = 'REGISTRATION'
             GROUP BY lower(email)) e
     WHERE lower(u.email) = e.email;

    -- Access history = every LOGIN_SUCCESS event for that email
    INSERT INTO login_events (user_id, logged_in_at, ip_address, user_agent)
    SELECT u.id, s.created_at, s.ip_address, NULL
      FROM security_audit_events s
      JOIN users u ON lower(u.email) = lower(s.email)
     WHERE s.event_type = 'LOGIN_SUCCESS';

    UPDATE users u
       SET last_login_at = e.last_seen
      FROM (SELECT user_id, MAX(logged_in_at) AS last_seen
              FROM login_events
             GROUP BY user_id) e
     WHERE u.id = e.user_id;

EXCEPTION
    WHEN insufficient_privilege OR undefined_table THEN
        RAISE NOTICE 'V19: security_audit_events not readable — '
                     'created_at/login_events start from now on.';
END
$$;
