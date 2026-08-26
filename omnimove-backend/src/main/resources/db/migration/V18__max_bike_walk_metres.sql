-- Max walking distance (metres) a traveller accepts to reach a shared bike.
-- Used by JourneyPlannerService to ground BIKE/SCOOTER options in real
-- Elerent availability. Default 500 m.
-- Renumbered from V16: that slot was already taken by V16__route_colours.sql,
-- which is published on develop and applied on everyone's database. IF NOT
-- EXISTS keeps this safe for whoever already ran it under the old number.
ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS max_bike_walk_metres INT NOT NULL DEFAULT 500;
