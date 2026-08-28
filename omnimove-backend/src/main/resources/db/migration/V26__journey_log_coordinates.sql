-- =================================================================
-- V26: the ends of a journey, as coordinates
--
-- A trip in Last Routes is reused by writing its origin and
-- destination NAMES back into the search fields, which are then
-- resolved against the list of stops. That works for a trip between
-- two stops and fails for every other kind: a point chosen on the map
-- carries a street name, no stop answers to it, and reuse ended with
-- "one of these stops is no longer served".
--
-- The name is what the traveller reads; the coordinates are what the
-- planner needs. Storing both means a journey can be replayed
-- whatever its ends were.
--
-- Nullable on purpose: every trip already recorded has names only,
-- and reuse falls back to resolving them by name exactly as before.
-- =================================================================

ALTER TABLE journey_log
    ADD COLUMN IF NOT EXISTS origin_lat DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS origin_lon DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS dest_lat   DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS dest_lon   DOUBLE PRECISION;
