-- ────────────────────────────────────────────────────────────────
-- OMNIMOVE
-- Local copy of CassiTrack's line colours.
--
-- Until now OmniMove had no colour at all: the traveller map generated one by
-- hashing the line's short name into a 10-entry palette. With 18 lines that
-- guaranteed collisions — and it meant the colour a traveller saw had nothing
-- to do with the colour the fleet manager had set in CassiTrack.
--
-- Both columns are filled by NetexImportService from the feed's
-- <Line><Presentation>. Nullable on purpose: a line whose colour CassiTrack
-- leaves blank still renders, using the generated fallback.
-- ────────────────────────────────────────────────────────────────

ALTER TABLE routes ADD COLUMN IF NOT EXISTS color      VARCHAR(6);
ALTER TABLE routes ADD COLUMN IF NOT EXISTS text_color VARCHAR(6);
