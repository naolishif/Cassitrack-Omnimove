-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V13__extend_linea2_timetable.sql
--
-- Gives LINEA_2 and LINEA_2_LIC a full day of service.
--
-- WHY
-- ---
-- V5 left these two lines deliberately thin — its own comment says
-- "LINEA 2: per ora solo la partenza 08:05". The result:
--
--     LINEA_1       23 departures   08:00 → 19:28
--     LINEA_3       23 departures   08:00 → 19:24
--     LINEA_2        1 departure    08:05 → 08:33   ← only one, all day
--     LINEA_2_LIC    5 departures   last run ends 16:45
--
-- TripResolutionService matches a bus to a trip with
--     trips.bus_id = <bus> AND now BETWEEN first and last arrival
-- so outside those windows BUS2 and BUS2L resolve nothing. On the fleet map
-- they show NO TRIP, carry no route, compute no delay — and because the map
-- only draws a route polyline when some active bus reports that route_id,
-- the LINEA_2 and LINEA_2_LIC lines disappear from the map entirely.
--
-- Nothing was broken: the timetable simply had no service to report. This
-- migration fills it in so all four lines run across the operating day.
--
-- HEADWAYS
-- --------
-- The headway must exceed the round-trip time, because ONE bus works each line:
-- two overlapping trips on the same vehicle would make trip resolution
-- ambiguous (findActiveTripsForBus would return both).
--
-- Careful with the run time — it is NOT just the sum of the leg times. gen_line
-- adds a 60 s dwell at every stop from the third onward, so:
--
--     LINEA_2      legs 1680 s + 9 dwells  = 2220 s = 37 min  → 40 min headway
--     LINEA_2_LIC  legs  900 s + 4 dwells  = 1140 s = 19 min  → 25 min headway
--
--     LINEA_2      08:05 → 19:22, every 40 min   (BUS2,  ex MAGNI-003)
--     LINEA_2_LIC  08:45 → 19:29, every 25 min   (BUS2L, added in V11)
--
-- That puts LINEA_2 in service 93% of 08:00–19:30 and the half-run 75%. The
-- remaining gaps are real layovers between runs: a bus showing NO TRIP inside
-- one is correct behaviour, not a fault.
--
-- The existing trips for these two routes are deleted and regenerated rather
-- than topped up: mixing a new grid into the old irregular departures would
-- have produced overlapping runs. scheduled_stops is ON DELETE CASCADE and
-- nothing else references trips, so the replacement is clean.
-- ────────────────────────────────────────────────────────────────

-- The same arrival maths as V5's generator. pg_temp functions live only for the
-- current session and Flyway runs each migration in its own, so it is redefined
-- here rather than reused.
--   • first stop: departure time, no dwell
--   • second stop: + leg time
--   • later stops: + 60 s dwell + leg time
--
-- ONE DIFFERENCE from V5: it does not write travel_seconds_from_prev. V5 added
-- that column and V6 dropped it again as redundant — the travel time is
-- recoverable from the arrival times. The leg value is still needed to compute
-- the arrivals, it is simply no longer stored.
CREATE OR REPLACE FUNCTION pg_temp.gen_line(
    p_route TEXT, p_bus1 INT, p_bus2 INT,
    p_stops TEXT[], p_legs INT[], p_deps INT[]
) RETURNS void AS $f$
DECLARE
    d INT; idx INT; i INT; tid TEXT; arr INT; tfp INT; chosen INT;
BEGIN
    FOR idx IN 1..array_length(p_deps,1) LOOP
        d := p_deps[idx];
        chosen := CASE WHEN idx % 2 = 1 THEN p_bus1 ELSE p_bus2 END;
        tid := p_route || '_' || d;

        INSERT INTO trips(id, route_id, bus_id) VALUES (tid, p_route, chosen);

        arr := d;
        FOR i IN 1..array_length(p_stops,1) LOOP
            IF i = 1 THEN
                tfp := 0;            arr := d;
            ELSIF i = 2 THEN
                tfp := p_legs[i];    arr := arr + tfp;
            ELSE
                tfp := p_legs[i];    arr := arr + 60 + tfp;
            END IF;

            INSERT INTO scheduled_stops(trip_id, stop_id, stop_sequence, arrival_seconds)
            VALUES (tid, p_stops[i], i, arr);
        END LOOP;
    END LOOP;
END;
$f$ LANGUAGE plpgsql;

DO $$
DECLARE
    bus2 INT; bus2l INT;
BEGIN
    -- Buses are identified by the id their on-board unit transmits (V11).
    SELECT bus_id INTO bus2  FROM buses WHERE current_vehicle_id = 'BUS2'  LIMIT 1;
    SELECT bus_id INTO bus2l FROM buses WHERE current_vehicle_id = 'BUS2L' LIMIT 1;

    -- Fall back to the pre-V11 naming, so this still works on a database where
    -- the OBU remap was never applied.
    IF bus2 IS NULL THEN
        SELECT bus_id INTO bus2 FROM buses WHERE current_vehicle_id = 'MAGNI-003' LIMIT 1;
    END IF;
    IF bus2l IS NULL THEN
        bus2l := bus2;      -- no separate half-run bus: one vehicle works both
    END IF;

    IF bus2 IS NULL THEN
        RAISE NOTICE 'No bus for LINEA_2 found — timetable left unchanged.';
        RETURN;
    END IF;

    -- Replace, do not append (see header).
    DELETE FROM trips WHERE route_id IN ('LINEA_2', 'LINEA_2_LIC');

    -- LINEA 2 — full ring from Piazza San Benedetto, 08:05..18:45 every 40 min.
    PERFORM pg_temp.gen_line('LINEA_2', bus2, bus2,
        ARRAY['PSB','CRS','VLE','VGA','SFF','LIC','COL','SFF','EDN','GIA','PSB'],
        ARRAY[0,240,60,60,60,360,360,120,180,120,120],
        ARRAY(SELECT generate_series(29100, 68700, 2400)));   -- 08:05, every 40 min

    -- LINEA 2 (mezza corsa) — Liceo → PSB, 08:45..19:10 every 25 min.
    PERFORM pg_temp.gen_line('LINEA_2_LIC', bus2l, bus2l,
        ARRAY['LIC','COL','SFF','EDN','GIA','PSB'],
        ARRAY[0,360,120,180,120,120],
        ARRAY(SELECT generate_series(31500, 69300, 1500)));   -- 08:45, every 25 min
END $$;
