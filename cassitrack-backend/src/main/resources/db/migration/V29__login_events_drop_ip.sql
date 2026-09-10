-- =================================================================
-- V29: the manager access history stops storing the client IP
--
-- V28 gave login_events an ip_address column so the account card
-- could show where an access came from. It never showed that: the
-- application read getRemoteAddr(), which behind the container's
-- reverse proxy is the bridge address, so every row of every manager
-- read the same 172.x.x.x. The header handling has since been fixed
-- (util/ClientIp), but the column is not worth keeping either way —
-- what the card needs to answer is when someone was in and from which
-- browser, and user_agent already says the second part.
--
-- Dropped rather than left empty: an address is personal data under
-- the GDPR whether or not anything reads it.
--
-- The audit trail is untouched. security_audit_events (V8) still
-- records the IP of every LOGIN_SUCCESS, under its own ownership and
-- its own retention — that is the forensic register, this was only
-- its visible echo. OMNIMOVE dropped the same column from its own
-- login_events in its V37, so the two dashboards still read alike.
-- =================================================================

ALTER TABLE login_events
    DROP COLUMN IF EXISTS ip_address;
