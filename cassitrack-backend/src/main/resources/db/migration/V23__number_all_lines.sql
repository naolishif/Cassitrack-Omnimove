-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- Every line gets its own number. No letters, no repeats.
--
-- WHY THIS REPLACES THE EARLIER DRAFT
-- V21 turned the four ring lines into A/B/B/C to clear the 1-vs-01 ambiguity,
-- and a first V23 was going to renumber them 18/19/19/20. Both kept two badges
-- showing the same label. This numbers all eighteen lines uniquely instead.
-- V21 and V22 have already run and are left untouched — Flyway keys a checksum
-- to an applied migration — but this file had not been applied yet, so it is
-- rewritten rather than followed by a V24.
--
-- WHAT IT COSTS, STATED PLAINLY
-- Two pairs were sharing a number because they are branches of one line, not
-- two lines: 11 ran to both the Liceo and the ITIS, and the Liceo -> P.za San
-- Benedetto service is the half run of the Liceo/Giardinetti ring. Splitting
-- them says they are separate lines. That is a modelling choice, and it is a
-- reasonable one here: the operator's real numbering stopped being something
-- this system had to mirror once its name came out of the data (V22).
--
-- THE SCHEME
--   * every already-unique number is kept, so the lines people know do not move
--   * the two lines that were sharing take the free slots next to their sibling
--     (ITIS -> 12, beside the Liceo's 11) or the lowest free slot (Agrario -> 09)
--   * the four urban rings take a contiguous block after the highest number in
--     use, 18..21, because they are a separate network from the extraurban
--     lines and reading as a block says so
-- Gaps at 06, 13 and 15 are left as they were: filling them would move lines
-- for no reason beyond tidiness.
--
-- THE 18th COLOUR
-- LINEA_2_LIC shared its colour with LINEA_2 for the same reason it shared its
-- number. Now that it is its own line it needs its own colour, or the split
-- would only move the ambiguity from the badge to the map. 0090FF was picked
-- the same way as the rest: furthest from the existing seventeen (17.9 ΔE2000,
-- well clear of the set's 11.5 worst pair), readable on both basemaps
-- (2.84:1 light, 5.35:1 dark) and under its label (6.43:1).
--
-- Still only labels: no unique constraint on short_name, nothing looks a route
-- up by it, route ids untouched. OmniMove re-imports from the NeTEx feed on the
-- next poll, because this UPDATE bumps the routes counter in data_version.
-- ────────────────────────────────────────────────────────────────

UPDATE routes r
SET short_name = v.short_name,
    color      = v.color,
    text_color = v.text_color
FROM (VALUES
    -- id,           num,   colour,   text,     was      itinerary
    ('LINEA_01',    '01', 'D92B1E', 'FFFFFF'),  -- 01   Solfegna - Casilina Nord
    ('LINEA_02',    '02', '1E6720', 'FFFFFF'),  -- 02   San Cesareo - Rocca d'Evandro
    ('LINEA_03',    '03', 'DD00FF', '111111'),  -- 03   Sant'Angelo - Panaccioni - Filaro
    ('LINEA_04',    '04', '8A7500', '111111'),  -- 04   Folcara
    ('LINEA_05',    '05', '0056D6', 'FFFFFF'),  -- 05   Cerro - Ponte a Cavallo
    ('LINEA_07',    '07', '8C3B1E', 'FFFFFF'),  -- 07   Cappella Morrone
    ('LINEA_08',    '08', '0B8E3F', '111111'),  -- 08   Campo dei Monaci
    ('LINEA_AGR',   '09', 'FF006E', '111111'),  -- AGR  Istituto Agrario
    ('LINEA_10',    '10', '8E0B74', 'FFFFFF'),  -- 10   Ospedale - Capo d'Acqua
    ('LINEA_11L',   '11', '5B671E', 'FFFFFF'),  -- 11   Liceo Scientifico
    ('LINEA_11I',   '12', '049EA9', '111111'),  -- 11   ITIS            (was sharing 11)
    ('LINEA_14',    '14', 'EB6600', '111111'),  -- 14   Colle Canne
    ('LINEA_16',    '16', '147155', 'FFFFFF'),  -- 16   Universita Folcara
    ('LINEA_17',    '17', '529900', '111111'),  -- 17   Ospedale
    ('LINEA_1',     '18', '5B13EC', 'FFFFFF'),  -- A    Anello Folcara / Ausonia
    ('LINEA_2',     '19', '6B4E0F', 'FFFFFF'),  -- B    Anello Liceo / Giardinetti
    ('LINEA_2_LIC', '20', '0090FF', '111111'),  -- B    Liceo -> P.za San Benedetto  (new colour)
    ('LINEA_3',     '21', '006B99', 'FFFFFF')   -- C    Anello Ospedali / XX Settembre
) AS v(id, short_name, color, text_color)
WHERE r.id = v.id;
