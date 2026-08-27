-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V24__half_hourly_timetable_with_returns.sql
--
-- Rebuilds the timetable so that every line runs both ways, every half hour,
-- from 06:00 to 23:00 — and gives the whole fleet one naming scheme.
--
-- WHY THE TIMETABLE IS REPLACED RATHER THAN EXTENDED
-- --------------------------------------------------
-- The old data had outbound runs only: LINEA_02 went PSB → Rocca d'Evandro and
-- never came back, so from 37 of the 45 stops no bus could carry you towards
-- Cassino at all. Worse, the frequencies assumed a vehicle could be in two
-- places at once — bus 5 arrived at PSB at 09:04 and was booked to leave LIC,
-- nineteen minutes away, at 09:10. Adding returns on top of that would have
-- made the contradiction real instead of removing it, so the schedule is
-- regenerated from scratch around a fleet that can actually run it.
--
-- Only scheduled_stops references trips (ON DELETE CASCADE) and nothing stores
-- a trip id beyond it, so replacing the runs loses no history.
--
-- THE SHAPE OF THE NEW SERVICE
-- ----------------------------
-- Departures on the half hour, 06:00 to 23:00, in both directions. A bus drives
-- to the terminus, turns round and comes straight back with the same times
-- mirrored — no layover, which is what makes 2·ceil(duration/30min) vehicles
-- per line exactly enough and not one more. Loop lines (PSB → … → PSB) already
-- return by construction, so they keep their shape and only gain the new
-- frequency. The return of a late-evening run departs after 23:00: the bus has
-- to get home, and that stretch is real service.
--
-- Stop sequences and inter-stop times are not written out here — they are read
-- from the existing data, one representative run per line (verified: every line
-- has exactly one stop pattern and one timing profile). The network therefore
-- cannot drift from what it is today; only the frequency and the directions do.
--
-- WHY EVERY VEHICLE IS RENAMED
-- ----------------------------
-- current_vehicle_id is the name a vehicle answers to on MQTT. The registry
-- carried two conventions at once: BUS1/BUS2/BUS2L/BUS3, set by V11 because the
-- physical ESP32 units broadcast those exact strings, and BUS-101..BUS-111 for
-- the simulated ones (V22, which deliberately left the first four alone). One
-- vehicle carried no name at all and published to cassitrack/None/position.
--
-- Everything now follows BUS<n>. Three of the four antennas keep their exact
-- string and gain the right vehicle; only BUS2L cannot be expressed and needs
-- reflashing to BUS4:
--
--     BUS1 stays BUS1 (row 1)      BUS2 stays BUS2 (moves to row 2)
--     BUS3 stays BUS3 (row 3)      BUS2L becomes BUS4 (row 4) — reflash needed
--
-- ────────────────────────────────────────────────────────────────

-- ── 1. One naming scheme, in the shape the hardware speaks ─────────────────
-- The ESP32 units broadcast BUS1, BUS2, BUS3 — "BUS" then a number, no
-- separator and no padding. A registry that writes BUS-001 is using a name the
-- hardware will never say, so the fleet adopts the hardware's own shape:
-- BUS<n>, with n the row's bus_id.
--
-- The rows have to be re-seated first. The antennas sit on mismatched rows
-- today — BUS2 on row 3, BUS3 on row 4 — so naming purely by bus_id would hand
-- each antenna's identity to a different vehicle, complete with the wrong plate
-- and capacity: worse than not matching at all, because it would look right.
-- Re-seating puts the antenna-bearing vehicles on rows 1..4, so BUS1, BUS2 and
-- BUS3 keep both their string and the coach they are bolted to.
--
-- BUS2L is the one name the scheme cannot express. It takes row 4 and becomes
-- BUS4: that single unit has to be reflashed to keep its link.
CREATE TEMP TABLE bus_reseat ON COMMIT DROP AS
SELECT row_number() OVER (
           ORDER BY CASE current_vehicle_id
                        WHEN 'BUS1'  THEN 1
                        WHEN 'BUS2'  THEN 2
                        WHEN 'BUS3'  THEN 3
                        WHEN 'BUS2L' THEN 4
                        ELSE 5
                    END,
                    bus_id
       )::int AS seat,
       targa, numero_posti, wheelchair_accessible, disponibile, status, map_visible
FROM buses;

-- targa and current_vehicle_id are both UNIQUE, and a permutation walks through
-- states where a value is briefly held twice. Park them somewhere harmless, then
-- deal them out in the new order.
UPDATE buses SET targa = targa || '~', current_vehicle_id = NULL;

UPDATE buses b
   SET targa                 = r.targa,
       numero_posti          = r.numero_posti,
       wheelchair_accessible = r.wheelchair_accessible,
       disponibile           = r.disponibile,
       status                = r.status,
       map_visible           = r.map_visible,
       current_vehicle_id    = 'BUS' || b.bus_id
  FROM bus_reseat r
 WHERE r.seat = b.bus_id;

-- ── 2. The vehicles the new frequency needs ─────────────────────────────────
-- Seventeen to thirty-seven. Plates continue the FR###MG series the simulated
-- fleet already uses; capacity and accessibility match the existing coaches.
-- bus_id is GENERATED ALWAYS, so the explicit numbering has to be declared:
-- the ids are not decoration here, the schedule below allocates vehicles by
-- contiguous ranges and needs to know which number each line gets.
INSERT INTO buses (bus_id, targa, numero_posti, wheelchair_accessible,
                   disponibile, current_vehicle_id, status, map_visible)
OVERRIDING SYSTEM VALUE
SELECT n,
       'FR' || (194 + n)::text || 'MG',
       52,
       TRUE,
       TRUE,
       'BUS' || n,
       'ACTIVE',
       TRUE
FROM generate_series(17, 37) AS n
WHERE NOT EXISTS (SELECT 1 FROM buses b WHERE b.bus_id = n);

-- Hand-picked ids leave the identity sequence behind; move it past them or the
-- next bus added from the admin console collides with one of these.
SELECT setval(pg_get_serial_sequence('buses', 'bus_id'),
              (SELECT max(bus_id) FROM buses));

-- ── 3. The pattern of every line, taken from the data itself ────────────────
CREATE TEMP TABLE route_plan ON COMMIT DROP AS
WITH rep AS (
    SELECT route_id, min(id) AS trip_id
    FROM trips
    GROUP BY route_id
),
pattern AS (
    SELECT rep.route_id,
           array_agg(s.stop_id        ORDER BY s.stop_sequence) AS stop_ids,
           array_agg(s.arrival_seconds ORDER BY s.stop_sequence) AS abs_times,
           count(*)::int AS n_stops
    FROM rep
    JOIN scheduled_stops s ON s.trip_id = rep.trip_id
    GROUP BY rep.route_id
)
SELECT route_id,
       stop_ids,
       -- seconds from this run's own departure, so the pattern can be replayed
       -- at any hour of the day
       (SELECT array_agg(t - abs_times[1] ORDER BY ord)
          FROM unnest(abs_times) WITH ORDINALITY AS u(t, ord))          AS offsets,
       n_stops,
       (abs_times[n_stops] - abs_times[1])                              AS duration_s,
       (stop_ids[1] = stop_ids[n_stops])                                AS is_loop
FROM pattern;

-- How many vehicles each line needs to hold a departure every half hour, and
-- which of them it gets. A one-way line occupies a vehicle for two legs, a loop
-- for one, and with no layover the count is exact.
ALTER TABLE route_plan ADD COLUMN slots      int;
ALTER TABLE route_plan ADD COLUMN bus_needed int;
ALTER TABLE route_plan ADD COLUMN bus_first  int;

UPDATE route_plan
   SET slots      = ceil(duration_s / 1800.0)::int,
       bus_needed = CASE WHEN is_loop THEN ceil(duration_s / 1800.0)::int
                                      ELSE 2 * ceil(duration_s / 1800.0)::int END;

UPDATE route_plan p
   SET bus_first = q.first_bus
  FROM (SELECT route_id,
               1 + coalesce(sum(bus_needed) OVER (ORDER BY route_id
                                                  ROWS BETWEEN UNBOUNDED PRECEDING
                                                           AND 1 PRECEDING), 0) AS first_bus
        FROM route_plan) q
 WHERE q.route_id = p.route_id;

-- ── 4. Out with the old runs ────────────────────────────────────────────────
DELETE FROM trips;

-- ── 5. Generate ─────────────────────────────────────────────────────────────
DO $$
DECLARE
    FIRST_DEP CONSTANT int := 6 * 3600;    -- 06:00
    LAST_DEP  CONSTANT int := 23 * 3600;   -- 23:00
    HEADWAY   CONSTANT int := 30 * 60;

    r        record;
    dep      int;
    k        int;
    i        int;
    v_bus    int;
    v_trip   text;
    ret_dep  int;
BEGIN
    FOR r IN SELECT * FROM route_plan ORDER BY route_id LOOP

        k := 0;
        dep := FIRST_DEP;
        WHILE dep <= LAST_DEP LOOP

            -- the vehicles of this line take the departures in turn
            v_bus := r.bus_first + (k % r.bus_needed);

            -- ── outbound ────────────────────────────────────────────────────
            v_trip := r.route_id || '_A_' || dep;
            INSERT INTO trips (id, route_id, bus_id) VALUES (v_trip, r.route_id, v_bus);
            FOR i IN 1 .. r.n_stops LOOP
                INSERT INTO scheduled_stops (trip_id, stop_id, stop_sequence, arrival_seconds)
                VALUES (v_trip, r.stop_ids[i], i, dep + r.offsets[i]);
            END LOOP;

            -- ── the way back, same vehicle, times mirrored ──────────────────
            -- Skipped for loops: they are already back where they started.
            -- arrival_seconds counts from midnight and everything that reads it —
            -- the simulator's clock, the planner's wait times — works inside a
            -- single day. A run that would cross 24:00 is never selectable, so
            -- the evening's last return is simply not scheduled.
            IF NOT r.is_loop AND dep + r.slots * HEADWAY + r.duration_s <= 86400 THEN
                ret_dep := dep + r.slots * HEADWAY;
                v_trip  := r.route_id || '_R_' || ret_dep;
                INSERT INTO trips (id, route_id, bus_id) VALUES (v_trip, r.route_id, v_bus);
                FOR i IN 1 .. r.n_stops LOOP
                    INSERT INTO scheduled_stops (trip_id, stop_id, stop_sequence, arrival_seconds)
                    VALUES (v_trip,
                            r.stop_ids[r.n_stops + 1 - i],
                            i,
                            ret_dep + (r.duration_s - r.offsets[r.n_stops + 1 - i]));
                END LOOP;
            END IF;

            k   := k + 1;
            dep := dep + HEADWAY;
        END LOOP;

        -- the line's own vehicles, so the registry agrees with the schedule
        UPDATE buses
           SET route_id = r.route_id
         WHERE bus_id BETWEEN r.bus_first AND r.bus_first + r.bus_needed - 1;

    END LOOP;
END $$;
