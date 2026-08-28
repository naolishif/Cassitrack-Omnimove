-- =================================================================
-- V24: two dead switches out, one real one in
--
-- 1. THE NOTIFICATION PREFERENCES GO
--    "Eco tip of the day" and "Ticket expiry reminders" were never
--    connected to anything. There is no notification mechanism in this
--    application — no push, no scheduled job reading user_preferences,
--    no mail on a delay — so the columns only ever recorded what the
--    toggles had been left on, and the toggles only ever showed what
--    the columns stored.
--
--    Dropped rather than left dormant: a column nothing reads is a
--    promise the schema keeps making and the code never honours, and
--    the next person to find it has to repeat the investigation. What
--    is lost is a preference for a feature that never existed.
--
--    Route delay alerts are deliberately NOT dropped: unlike these two
--    they are being implemented, tied to starting a journey.
--
-- 2. THE FIVE BEHAVIOURAL OPTIONS GAIN A SCOPE
--    Crowding warnings, walking options, bike-before-bus, rain and the
--    maximum walk to a shared vehicle always shape the Custom ranking,
--    because Custom is the traveller's own profile. Whether they also
--    shape Fast, Budget and Eco is now their choice: those three each
--    answer one question — what is quickest, cheapest, cleanest — and a
--    profile quietly filtering their results makes them answer a
--    different question than their name promises.
--
--    Defaults to TRUE, which is exactly today's behaviour: the
--    preferences apply to every ranking. Turning it off narrows their
--    reach, and nobody's results change on upgrade.
-- =================================================================

ALTER TABLE user_preferences
    DROP COLUMN IF EXISTS notify_eco_tip,
    DROP COLUMN IF EXISTS notify_ticket_expiry;

ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS apply_prefs_to_presets BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN user_preferences.apply_prefs_to_presets IS
    'When false, the five behavioural preferences shape the Custom ranking only, leaving Fast/Budget/Eco unfiltered.';
