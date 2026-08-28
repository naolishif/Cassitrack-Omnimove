-- =================================================================
-- Consent ledger — GDPR art. 7(1): the controller must be able to
-- DEMONSTRATE that the data subject consented.
--
-- Append-only by design: a withdrawal is a new row with granted=false,
-- never an UPDATE. That way the full history (when it was given, under
-- which version of the notice, from where) stays provable.
--
-- Titolare del trattamento: Università degli Studi di Cassino e del
-- Lazio Meridionale.
--
-- Numbering: this file was written as V22, but by the time it was merged
-- massi_sprint_10 had reached V26 and had its own V22–V24. Two migrations
-- sharing a version stop Flyway at startup, so the privacy set was moved up
-- to V27–V29 during the merge.
-- =================================================================

CREATE TABLE IF NOT EXISTS user_consents (
    id             BIGSERIAL    PRIMARY KEY,

    -- NULL for a visitor who has not signed up yet (cookie banner choice).
    user_id        BIGINT       REFERENCES users(id) ON DELETE CASCADE,

    -- Opaque random id kept in the browser, so an anonymous banner choice
    -- can be reconciled with the account once the visitor registers.
    subject_key    VARCHAR(64),

    -- PRIVACY_NOTICE | PROFILING | THIRD_PARTY_CONTENT
    consent_type   VARCHAR(40)  NOT NULL,

    granted        BOOLEAN      NOT NULL,

    -- Which version of the informativa/cookie policy was shown.
    -- A new version invalidates old consents and must be re-collected.
    policy_version VARCHAR(20)  NOT NULL,

    -- REGISTRATION | BANNER | SETTINGS
    source         VARCHAR(30)  NOT NULL,

    -- Proof-of-consent metadata (art. 7(1)). Retained 24 months, then purged.
    ip_address     VARCHAR(64),
    user_agent     VARCHAR(255),

    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_user_consents_subject
        CHECK (user_id IS NOT NULL OR subject_key IS NOT NULL)
);

-- Latest consent per user and per type is the hot query.
CREATE INDEX IF NOT EXISTS idx_user_consents_user
    ON user_consents (user_id, consent_type, recorded_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_consents_subject
    ON user_consents (subject_key, consent_type, recorded_at DESC);

COMMENT ON TABLE  user_consents IS
    'Append-only GDPR consent ledger. Never UPDATE or DELETE a row except for retention purging.';
COMMENT ON COLUMN user_consents.consent_type IS
    'PRIVACY_NOTICE = acknowledgement of the art. 13 notice; PROFILING = optional personalised suggestions; THIRD_PARTY_CONTENT = optional non-technical third-party assets.';
