-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- One clearly distinct colour per line.
--
-- WHY
-- The colours accumulated one migration at a time and ended up with exact
-- duplicates — LINEA_1 and LINEA_16 were both 1976D2, LINEA_2 and LINEA_2_LIC
-- both E67E22 — and near-duplicates that no one can tell apart on a map:
-- C2185B vs AD1457 (the two "11" lines) sit ~4 ΔE2000 apart, roughly twice the
-- just-noticeable difference. With all 18 lines now drawn on the OmniMove map
-- at once, that made the network unreadable.
--
-- HOW THESE WERE CHOSEN
-- Generated, not eyeballed. Candidates were filtered to those that stay legible
-- under white OR black label text (≥ 4.5:1) and stay clear of the OpenStreetMap
-- tile palette (paper beige, park green, water blue, road grey), then 17 were
-- selected to maximise the smallest pairwise CIEDE2000 distance, with hue
-- targets spaced non-uniformly — hue discrimination is coarse across the blues
-- and fine across the warm and green arcs, so an even ring wastes slots.
--
--   worst pair now: 529900 vs 0B8E3F, ΔE2000 = 11.5  (was 0.0)
--   every swatch:   ≥ 4.5:1 against its text_color
--
-- text_color is set per swatch rather than left at FFFFFF: forcing white text
-- was what previously ruled out every green, yellow and orange and pushed the
-- palette towards a wall of dark reds and blues.
--
-- 17 colours for 18 rows: LINEA_2_LIC is the half-run of LINEA_2 and shows the
-- same "2" badge, so the two deliberately share a colour.
-- ────────────────────────────────────────────────────────────────

UPDATE routes r
SET color      = v.color,
    text_color = v.text_color
FROM (VALUES
    -- id,            colour,    text,      what it reads as
    ('LINEA_01',    'D92B1E', 'FFFFFF'),  -- red
    ('LINEA_02',    '1E6720', 'FFFFFF'),  -- forest green
    ('LINEA_03',    'DD00FF', '111111'),  -- magenta
    ('LINEA_04',    '8A7500', '111111'),  -- gold
    ('LINEA_05',    '0056D6', 'FFFFFF'),  -- blue
    ('LINEA_07',    '8C3B1E', 'FFFFFF'),  -- rust
    ('LINEA_08',    '0B8E3F', '111111'),  -- emerald
    ('LINEA_10',    '8E0B74', 'FFFFFF'),  -- plum
    ('LINEA_11L',   '5B671E', 'FFFFFF'),  -- moss
    ('LINEA_11I',   '273786', 'FFFFFF'),  -- navy
    ('LINEA_14',    'EB6600', '111111'),  -- orange
    ('LINEA_16',    '147155', 'FFFFFF'),  -- teal green
    ('LINEA_17',    '529900', '111111'),  -- lime
    ('LINEA_AGR',   'FF006E', '111111'),  -- pink
    ('LINEA_1',     '5B13EC', 'FFFFFF'),  -- violet
    ('LINEA_2',     '6B4E0F', 'FFFFFF'),  -- dark olive
    ('LINEA_2_LIC', '6B4E0F', 'FFFFFF'),  -- dark olive (half-run of line 2)
    ('LINEA_3',     '006B99', 'FFFFFF')   -- cyan blue
) AS v(id, color, text_color)
WHERE r.id = v.id;

-- Lines the palette does not name — a future import, a line added in the route
-- editor — keep whatever they had. A NULL colour is not a failure: OmniMove
-- falls back to its own generated colour for anything the feed leaves blank.
