-- =================================================================
-- V21: reCAPTCHA on the login form, switchable from the dashboard
--
-- Stored in app_settings next to the Google Maps flags, so the admin
-- console has one place to read runtime switches from.
--
-- Seeded ON, but the flag alone does not turn the check on: the
-- service also requires a site key and a secret to be configured.
-- A deployment without them keeps letting people in rather than
-- presenting a widget that could never be solved.
-- =================================================================

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('security.recaptcha', 'true')
ON CONFLICT (setting_key) DO NOTHING;
