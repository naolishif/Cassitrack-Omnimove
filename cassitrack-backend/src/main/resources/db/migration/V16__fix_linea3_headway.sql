-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V16__fix_linea3_headway.sql
--
-- Widens LINEA_3 from a 30-minute to a 40-minute headway.
--
-- WHY
-- ---
-- LINEA_3 was the last line whose timetable asked one bus to be in two places
-- at once. V13 fixed exactly this for LINEA_2 and LINEA_2_LIC and stated the
-- rule: the headway must exceed the round-trip time, because ONE bus works
-- the line. LINEA_3 was left behind.
--
--     legs 1440 s + 10 dwells (60 s from the third stop on) = 2040 s = 34 min
--     headway in V5                                                  = 30 min
--                                                                 -> 4 min short
--
-- WHAT IT ACTUALLY BROKE
-- ----------------------
-- Not trip resolution — findActiveTripsForBus returns both overlapping trips
-- and bestByGps picks one. The damage was to the SIMULATION and to adherence:
--
--   * simulate_bus_scheduled.py advances to the next trip only once the
--     current one has ended, so BUS3 began every run about 6 minutes after
--     its scheduled departure, from the origin.
--   * Its pacing then hit `remaining_s <= 1 -> need_kmh = MAX_KMH`, so BUS3
--     drove the whole day pinned at the 50 km/h ceiling while LINEA_3's legs
--     only need 15-32 km/h — a bus permanently sprinting to catch a timetable
--     it could never meet.
--   * ScheduleAdherenceService anchors a new trip from the CLOCK, so it placed
--     BUS3 two stops ahead of where the bus actually was, at the start of
--     every one of its 23 daily trips. Its punctuality never settled.
--
-- The other three duties all had slack and behaved correctly, which is why
-- BUS3 alone never acquired a status.
--
-- LINEA_1 looks worse on paper (44 min run, 30 min headway) but gen_line
-- alternates its departures between two buses, so each vehicle gets 60
-- minutes. LINEA_3 passes the same bus twice — gen_line('LINEA_3', bus4,
-- bus4, …) — and therefore has no such relief.
--
-- EFFECT
-- ------
--     before   08:00 → 19:24, every 30 min, 23 trips, 4 min overlap
--     after    08:00 → 19:14, every 40 min, 17 trips, 6 min recovery
--
-- Service is thinner, which is the honest trade: the old timetable was not
-- deliverable by one vehicle. Restoring 30-minute service means giving
-- LINEA_3 a second bus (MAGNI-002 is the only spare, and V14 cleared its
-- antenna, so it would need one fitted before it could transmit).
-- ────────────────────────────────────────────────────────────────

-- Same helper as V5/V13. Redefined here because pg_temp functions do not
-- outlive the session that created them.
--
-- Timing rule, unchanged:
--   • first stop: departure time, no dwell
--   • second stop: + leg time
--   • later stops: + 60 s dwell + leg time
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
    bus3 INT;
BEGIN
    -- Buses are identified by the id their on-board unit transmits (V11).
    SELECT bus_id INTO bus3 FROM buses WHERE current_vehicle_id = 'BUS3' LIMIT 1;

    -- Fall back to the pre-V11 naming, so this still works on a database where
    -- the OBU remap was never applied.
    IF bus3 IS NULL THEN
        SELECT bus_id INTO bus3 FROM buses WHERE current_vehicle_id = 'MAGNI-004' LIMIT 1;
    END IF;

    IF bus3 IS NULL THEN
        RAISE NOTICE 'No bus for LINEA_3 found — timetable left unchanged.';
        RETURN;
    END IF;

    -- Replace, do not append: the old 30-minute departures must go, or the
    -- overlap survives alongside the new ones. scheduled_stops follows by
    -- ON DELETE CASCADE.
    DELETE FROM trips WHERE route_id = 'LINEA_3';

    -- LINEA 3 — full ring from Piazza San Benedetto, 08:00..18:40 every 40 min.
    -- Stop sequence and leg times are exactly V5's; only the departures change.
    PERFORM pg_temp.gen_line('LINEA_3', bus3, bus3,
        ARRAY['PSB','ING','OSR','SFF','VGA','IMA','EDN','XXS','OSS','XXS','GIA','PSB'],
        ARRAY[0,120,60,120,60,60,120,120,300,300,60,120],
        ARRAY(SELECT generate_series(28800, 68400, 2400)));   -- 08:00, every 40 min
END $$;
