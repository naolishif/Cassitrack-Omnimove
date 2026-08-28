-- =================================================================
-- V22: the traveller's own weighting of the four criteria
--
-- Ranking used to be three fixed presets. This stores what the person
-- actually cares about, asked once at first sign-in in plain language
-- and editable from Preferences afterwards.
--
-- THE RAW ANSWERS ARE STORED, NOT THE WEIGHTS
-- The three weights are the answers normalised to sum 1. Keeping the
-- answers means the normalisation happens in one place and cannot go
-- stale: change how the weights are derived and every profile follows,
-- with no migration to rewrite numbers nobody can check by eye. It also
-- keeps "I said 5 for time and 1 for cost" recoverable, which a
-- normalised 0.83/0.17 no longer is.
--
-- All default to 3 — the middle of the 0..5 scale — so an account that
-- never answers is weighted evenly rather than arbitrarily.
-- =================================================================

ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS answer_time             SMALLINT NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS answer_cost             SMALLINT NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS answer_eco              SMALLINT NOT NULL DEFAULT 3,
    -- 0 = wants wide transfer margins, 5 = accepts tight connections to save time
    ADD COLUMN IF NOT EXISTS answer_reliability      SMALLINT NOT NULL DEFAULT 3,
    -- Q7: the threshold the "avoid crowded buses" filter uses, as % of seats
    ADD COLUMN IF NOT EXISTS occupancy_threshold_pct SMALLINT NOT NULL DEFAULT 80,
    -- Asked once; the panel in Preferences is the way back to it
    ADD COLUMN IF NOT EXISTS onboarding_done         BOOLEAN  NOT NULL DEFAULT FALSE;

-- Values outside the scale would silently skew every ranking that reads them
ALTER TABLE user_preferences
    ADD CONSTRAINT chk_pref_answers CHECK (
        answer_time        BETWEEN 0 AND 5 AND
        answer_cost        BETWEEN 0 AND 5 AND
        answer_eco         BETWEEN 0 AND 5 AND
        answer_reliability BETWEEN 0 AND 5 AND
        occupancy_threshold_pct BETWEEN 10 AND 100
    );

-- =================================================================
-- Rain: from hiding to ordering
--
-- only_bus_when_raining removed every bike, scooter and walking option
-- from the results. That is the app deciding for the traveller: a
-- five-minute walk in light rain is a choice they are entitled to make,
-- and hiding it left searches with no options at all when no bus runs.
--
-- The column is renamed rather than replaced so nobody's setting is
-- lost: someone who asked for "bus only in the rain" clearly wants the
-- bus first, which is exactly what the softened behaviour gives them.
-- =================================================================
ALTER TABLE user_preferences
    RENAME COLUMN only_bus_when_raining TO rain_prefers_bus;

COMMENT ON COLUMN user_preferences.rain_prefers_bus IS
    'When it rains, sort bus options first and explain why. Never hides the others.';

-- Accounts that already exist have never seen the questions; the panel
-- opens for them on their next sign-in exactly as for a new account.
UPDATE user_preferences SET onboarding_done = FALSE WHERE onboarding_done IS NULL;
