-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- The four urban ring lines become A / B / C, so no line number is
-- ambiguous any more.
--
-- WHY
-- V17 added the real Magni timetable — 01, 02, 03, 04, 05, 07, 08, 10, 11,
-- 14, 16, 17, AGR — next to the four pre-existing ring lines numbered 1, 2 and
-- 3, and deliberately left them alone (see its header, point 3). The two sets
-- are unrelated: "1" is the Folcara / Ausonia ring, "01" is Solfegna –
-- Casilina Nord. They only LOOK like the same line, because one set is
-- zero-padded and the other is not, and a badge showing "1" next to a badge
-- showing "01" reads as a duplicate.
--
-- Now that OmniMove draws every line on the map at once, that ambiguity is in
-- front of the traveller on every screen.
--
-- WHY RENAME RATHER THAN DEACTIVATE
-- Deactivating the four rings was the other option V17 suggested, but they are
-- not dead weight: they carry 83 trips and their own routed geometry, and the
-- journey planner uses them. Renaming keeps every one of them working and only
-- changes the label.
--
-- WHY LETTERS
-- Nothing numeric is left to collide with, one character fits the round badge,
-- and it is what these lines were called before V5 renumbered them (V4 seeded
-- them as LINEA_A_*, LINEA_B_*, LINEA_C_*). AGR is already a non-numeric short
-- name, so letters are not out of place here.
--
-- SAFE BECAUSE short_name IS A LABEL, NOT A KEY
-- Nothing looks a route up by it: there is no unique constraint, no
-- findByShortName, and every Java use is a SELECT column or a display label.
-- Route ids are untouched, so trips, scheduled_stops, route_shapes and the
-- shape-import tooling (which maps BUS1 → LINEA_1) all keep working. Stored
-- user data is unaffected too — favourites and journey logs keep stop names,
-- not line numbers.
--
-- OmniMove needs no migration of its own: it mirrors routes from the NeTEx
-- feed, and this UPDATE bumps the routes counter in data_version, so the next
-- poll re-imports the new labels within the minute.
-- ────────────────────────────────────────────────────────────────

UPDATE routes r
SET short_name = v.short_name
FROM (VALUES
    -- id,            new,   was,  itinerary
    ('LINEA_1',     'A'),  -- 1    Anello Folcara / Ausonia
    ('LINEA_2',     'B'),  -- 2    Anello Liceo / Giardinetti
    ('LINEA_2_LIC', 'B'),  -- 2    Liceo -> P.za San Benedetto (half run of B,
                           --      so it deliberately keeps B's badge and colour)
    ('LINEA_3',     'C')   -- 3    Anello Ospedali / XX Settembre
) AS v(id, short_name)
WHERE r.id = v.id;
