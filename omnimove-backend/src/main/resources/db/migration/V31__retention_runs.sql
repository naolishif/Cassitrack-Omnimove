-- =================================================================
-- V31: record of what the retention jobs actually did
--
-- privacy.html § 7 states five retention rules. Until now exactly one
-- of them was enforced — erasure on request, through ON DELETE
-- CASCADE. The other four were text and nothing else: journey history
-- kept for ever, security logs for ever, the consent ledger for ever,
-- and unverified accounts never removed.
--
-- The jobs that now enforce them write one row here per run. The
-- point is accountability (art. 5(2)): "we delete after 12 months"
-- has to be demonstrable, and an operator has to be able to see that
-- the job ran, when, and how much it removed — including the runs
-- that removed nothing or failed.
--
-- Append-only. Nothing here is personal data: counts and timestamps.
-- =================================================================

CREATE TABLE IF NOT EXISTS retention_run (
    id            BIGSERIAL    PRIMARY KEY,

    -- JOURNEY_LOG | SECURITY_EVENTS | CONSENT_LEDGER | UNVERIFIED_ACCOUNTS
    rule          VARCHAR(40)  NOT NULL,

    ran_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Everything older than this was in scope for the run
    cutoff        TIMESTAMPTZ,

    rows_removed  BIGINT       NOT NULL DEFAULT 0,

    -- OK | SKIPPED | FAILED. SKIPPED is a real outcome, not a silence:
    -- the journey rule stands aside when the research pipeline owns
    -- that table, and an operator needs to see that it chose to.
    outcome       VARCHAR(20)  NOT NULL,

    detail        TEXT
);

CREATE INDEX IF NOT EXISTS idx_retention_run_rule
    ON retention_run (rule, ran_at DESC);

COMMENT ON TABLE retention_run IS
    'One row per retention job run. Evidence for art. 5(2) accountability.';
