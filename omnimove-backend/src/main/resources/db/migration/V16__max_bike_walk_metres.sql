-- Max walking distance (metres) a traveller accepts to reach a shared bike.
-- Used by JourneyPlannerService to ground BIKE/SCOOTER options in real
-- Elerent availability. Default 500 m.
ALTER TABLE user_preferences
    ADD COLUMN max_bike_walk_metres INT NOT NULL DEFAULT 500;
