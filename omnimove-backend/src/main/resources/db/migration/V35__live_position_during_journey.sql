-- =================================================================
-- V35: the live position arrow during a started journey
--
-- While a journey is running the map can show an arrow on the drawn
-- route saying where the traveller is along it, and — because it
-- knows that — recognise arrival without waiting for End Journey.
--
-- The setting is the traveller's, and revocable at any moment: with
-- it off, a started journey shows nothing about where they are and
-- arrival is only ever declared by the button or the clock.
--
-- What is NOT here is any position. The fix never leaves the device:
-- it moves a marker and is compared against the destination, and
-- nothing about it is sent anywhere or written down. This column
-- stores one boolean — whether to look at all.
--
-- Defaults to true: the arrow is the behaviour a traveller expects
-- from a map that is following a journey, and it is one tap away in
-- Preferences for anyone who would rather not have it.
-- =================================================================

ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS show_live_position BOOLEAN NOT NULL DEFAULT TRUE;
