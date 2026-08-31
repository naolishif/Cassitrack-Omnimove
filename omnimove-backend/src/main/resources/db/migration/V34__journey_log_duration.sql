-- =================================================================
-- V34: how long a journey was expected to take
--
-- The selection recorded what a trip cost, how far it went and how
-- clean it was, but never how long it lasted. For a single mode that
-- is a gap; for a combined journey it is the question — a chain that
-- couples a bus with a shared vehicle is only worth proposing if the
-- time it takes is defensible, and until now nothing in the system
-- could answer "how long does a bus-and-scooter trip actually take".
--
-- Minutes as the planner proposed them at the moment of selection,
-- not measured afterwards: what is being studied is the offer the
-- traveller accepted.
--
-- Nullable on purpose: every trip already recorded has no duration,
-- and the averages simply leave those rows out rather than counting
-- them as zero-minute journeys.
-- =================================================================

ALTER TABLE journey_log
    ADD COLUMN IF NOT EXISTS duration_minutes INTEGER;
