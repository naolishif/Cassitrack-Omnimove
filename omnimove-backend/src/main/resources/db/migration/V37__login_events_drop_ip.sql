-- =================================================================
-- V37: the access history stops storing the client IP
--
-- V19 gave login_events an ip_address column so the admin dashboard
-- could show where an access came from. In practice the column never
-- carried that information: the application reads getRemoteAddr(),
-- which behind the container's reverse proxy is the bridge address,
-- so every row of every user reads 172.x.x.x. It identifies the
-- network, not the visitor.
--
-- A column that answers nothing is still personal data under the
-- GDPR, and the dashboard already tells the operator what it actually
-- needs — when the access happened and from which browser. The
-- user_agent column stays; this one goes.
--
-- Forensics are unaffected: security_audit_events (V13) keeps
-- recording the unmasked IP of LOGIN_SUCCESS, under its own retention
-- and its own ownership. This drops the reporting copy, not the
-- audit trail.
-- =================================================================

ALTER TABLE login_events
    DROP COLUMN IF EXISTS ip_address;
