-- =================================================================
-- V20: Sign in with Google
--
-- The Google account is identified by its "sub" claim, not by the
-- address: Google lets a user change the email on an account, and
-- the sub is the only value guaranteed to stay put.
--
-- auth_provider records how the account came into being. It is not
-- a restriction — a Google account that later sets a password can
-- sign in either way — but it tells the UI what to offer and keeps
-- "this account has no password" distinguishable from "the password
-- happens to be missing".
-- =================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS google_sub    VARCHAR(64),
    ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

-- Partial: most rows have no Google account, and repeated NULLs must not
-- collide the way they would under a plain UNIQUE constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_google_sub
    ON users(google_sub) WHERE google_sub IS NOT NULL;

-- An account created through Google has no password to store. Everything that
-- reads users.password must already cope with an absent one — see
-- UserDetailsServiceImpl and the login endpoint.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
