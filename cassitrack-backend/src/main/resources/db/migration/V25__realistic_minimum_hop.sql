-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V25__realistic_minimum_hop.sql
--
-- Puts a floor under the time a bus is scheduled to take between two
-- consecutive stops. Nine pairs were timetabled at under two minutes, four of
-- them at barely a minute, which no bus can do once you count slowing down,
-- opening the doors, letting people on and pulling away again.
--
-- WHERE THE SHORT TIMES ACTUALLY COME FROM
-- ----------------------------------------
-- Not from V24. That migration rebuilt the departures every half hour and
-- explicitly replayed each line's existing offsets; checked against the
-- database, LINEA_04 still carries V17's exact figures (+67, +126, +255, +408,
-- +561, +719 from departure), so nothing was lost there.
--
-- They come from V17, which had only departure times in the operator's PDF and
-- estimated the intermediate ones as straight-line distance at 24 km/h plus a
-- 45 s dwell. That model is reasonable between real stops kilometres apart. It
-- falls apart on the stops V17 itself interpolated between two known ones,
-- which ended up very close together:
--
--     Via Lombardia → Via XX Settembre     100 m  →  59 s   (6.1 km/h)
--     P.za San Benedetto → Via Lombardia   150 m  →  67 s   (8.0 km/h)
--     Via Garibaldi → S. Francesco         256 m  →  83 s  (11.1 km/h)
--
-- The distance is right; what is missing is that the bus is stationary for a
-- good part of that time. At 100 m the dwell alone outweighs the driving.
--
-- WHAT THIS DOES
-- --------------
-- Any hop shorter than 120 s becomes 120 s. Nothing else is touched: the
-- kilometre-scale hops out to Rocca d'Evandro or Cerro are sound and are left
-- exactly as they are, so this corrects a floor rather than re-deriving a
-- timetable that is mostly right.
--
-- Two minutes, not ninety seconds, because the timetable is read to the minute:
-- 90 s between two stops still prints as a one-minute gap on some runs, which
-- is the very thing that looked wrong.
--
-- Departure times do not move. The correction pushes the later stops of a run
-- later, which is what a longer journey means.
--
-- WHY THE FLEET STILL WORKS
-- -------------------------
-- V24 gives each line 2·ceil(duration / 30 min) vehicles, with no layover, so a
-- run that grew past a half-hour boundary would put one bus in two places at
-- once. Checked before writing this: the nine affected lines gain between 157 s
-- and 234 s and not one of them crosses a boundary — every slot count is
-- unchanged, so the allocation V24 made is still exactly right.
--
--     LINEA_03  2615 → 2849 s      LINEA_04   719 →  953 s
--     LINEA_05  1349 → 1583 s      LINEA_10   741 →  914 s
--     LINEA_11I  773 →  930 s      LINEA_11L 1067 → 1224 s
--     LINEA_16   854 → 1071 s      LINEA_17  1396 → 1594 s
--     LINEA_AGR  838 →  995 s
--
-- OmniMove picks the change up on its own: the data_version trigger on
-- scheduled_stops (V15) bumps on this UPDATE and the next poll re-imports.
-- ────────────────────────────────────────────────────────────────

-- Rebuild every run from its own departure, hop by hop, with the floor applied.
-- Done in one statement over all trips: correcting a hop moves every stop after
-- it, so the offsets have to be re-accumulated rather than patched in place.
WITH gap AS (
    SELECT id,
           trip_id,
           stop_sequence,
           arrival_seconds - lag(arrival_seconds)
               OVER (PARTITION BY trip_id ORDER BY stop_sequence) AS secs
    FROM scheduled_stops
),
floored AS (
    -- The first stop of a run has no preceding hop and must contribute nothing.
    -- Spelled out with CASE rather than COALESCE(GREATEST(secs,120),0): Postgres
    -- GREATEST *skips* NULL arguments instead of returning NULL, so that form
    -- hands the first stop a phantom 120 s hop and moves every departure two
    -- minutes later — which is precisely what this migration must not do.
    SELECT id, trip_id, stop_sequence,
           CASE WHEN secs IS NULL THEN 0 ELSE GREATEST(secs, 120) END AS secs
    FROM gap
),
rebuilt AS (
    SELECT id,
           trip_id,
           SUM(secs) OVER (PARTITION BY trip_id ORDER BY stop_sequence
                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS offset_s
    FROM floored
),
departure AS (
    SELECT trip_id, MIN(arrival_seconds) AS first_s
    FROM scheduled_stops
    GROUP BY trip_id
)
UPDATE scheduled_stops s
   SET arrival_seconds = d.first_s + r.offset_s
  FROM rebuilt r
  JOIN departure d ON d.trip_id = r.trip_id
 WHERE s.id = r.id
   AND s.arrival_seconds <> d.first_s + r.offset_s;
