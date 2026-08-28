-- =================================================================
-- V23: the profile answers become INTEGER
--
-- V22 declared them SMALLINT — two bytes is plenty for a 0..5 scale
-- and a percentage. But the entity maps them to java.lang.Integer,
-- and Hibernate runs with ddl-auto: validate, which compares the two
-- and refuses to start:
--
--   Schema-validation: wrong column type encountered in column
--   [answer_cost] in table [user_preferences]; found [int2], but
--   expecting [integer]
--
-- The fix goes here rather than in the entity. Short would satisfy the
-- validator but would spread a narrower type through the DTOs, the
-- controller and the weight arithmetic to save six bytes per row on a
-- table with one row per user. Integer ↔ integer is the mapping the
-- rest of this schema already uses — max_bike_walk_metres included.
--
-- V22 is left as it is: Flyway keys a checksum to an applied
-- migration, so editing it would break every database that has already
-- run it. A fresh database creates the columns as SMALLINT and widens
-- them here a moment later, which costs nothing on an empty table.
--
-- The CHECK constraint on these columns survives the change; Postgres
-- re-validates it against the widened type.
-- =================================================================

ALTER TABLE user_preferences
    ALTER COLUMN answer_time             TYPE INTEGER,
    ALTER COLUMN answer_cost             TYPE INTEGER,
    ALTER COLUMN answer_eco              TYPE INTEGER,
    ALTER COLUMN answer_reliability      TYPE INTEGER,
    ALTER COLUMN occupancy_threshold_pct TYPE INTEGER;
