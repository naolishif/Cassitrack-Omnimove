-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- LINEA_11I: navy → cyan.
--
-- WHY A SECOND MIGRATION RATHER THAN AN EDIT TO V19
-- V19 has already been applied. Flyway records a checksum per migration and
-- refuses to start when a file that has run changes underneath it, so a
-- correction to applied history has to arrive as its own version.
--
-- WHY THE COLOUR CHANGES
-- V19's palette was validated against OmniMove's light OpenStreetMap tiles
-- only. The fleet monitor draws the same lines on a CARTO dark basemap, and
-- there 273786 scored 1.6:1 against the background — the line was effectively
-- invisible. It was the only one of the seventeen that failed; every other
-- swatch clears 2.0:1 on dark and 2.8:1 on light.
--
-- 049EA9 sits 22.8 ΔE2000 from its nearest neighbour in the palette, well clear
-- of the set's 11.5 worst pair, so the swap costs nothing in distinctness:
--
--   vs light tiles  2.83:1     vs dark basemap  5.38:1
--   text_color 111111 at 6.47:1
-- ────────────────────────────────────────────────────────────────

UPDATE routes
SET color      = '049EA9',
    text_color = '111111'
WHERE id = 'LINEA_11I';
