-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V9__route_shapes.sql
--
-- Adds the road geometry ("shape") of each route.
--
-- WHY THIS EXISTS
-- ---------------
-- Until now a route was only a *sequence of stops*. The maps therefore drew
-- each line as straight segments hopping from one stop to the next, which is
-- fine as a schematic but wrong as geography: a bus that follows the real
-- streets visibly leaves the drawn line, cuts corners and swings wide on
-- bends. This table stores the full ordered polyline that follows the actual
-- roads, so the map can draw what the bus really does.
--
-- This is the same idea as GTFS `shapes.txt` (shape_pt_lat / shape_pt_lon /
-- shape_pt_sequence), kept deliberately close to that vocabulary because the
-- project already exposes GTFS-RT and SIRI feeds.
--
-- IMPORTANT: this is presentation/geometry only. Scheduling, adherence and
-- ETA continue to be driven by `scheduled_stops` — nothing in this table
-- affects timetable logic. Routes with no shape rows simply keep the old
-- stop-to-stop rendering, so the change is backwards compatible.
-- ────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS route_shapes (

    route_id  VARCHAR(50)      NOT NULL
        REFERENCES routes(id)
        ON DELETE CASCADE,

    -- Position along the path, 0-based, ascending. Together with route_id this
    -- is the natural key: one point can only sit at one place in the sequence.
    seq       INTEGER          NOT NULL,

    lat       DOUBLE PRECISION NOT NULL,
    lon       DOUBLE PRECISION NOT NULL,

    -- TRUE when this vertex coincides with a scheduled stop. The simulator and
    -- the editor both mark these; keeping the flag lets the UI render stop
    -- markers straight from the shape without re-matching coordinates.
    is_stop   BOOLEAN          NOT NULL DEFAULT FALSE,

    PRIMARY KEY (route_id, seq),

    CONSTRAINT chk_route_shapes_lat CHECK (lat BETWEEN -90  AND 90),
    CONSTRAINT chk_route_shapes_lon CHECK (lon BETWEEN -180 AND 180)
);

-- Primary access pattern is "give me the whole path of route X in order".
-- The PK already covers (route_id, seq); this index is redundant on Postgres,
-- so we deliberately do NOT create a second one.

COMMENT ON TABLE  route_shapes IS
    'Ordered polyline following the real roads for each route (GTFS shapes.txt equivalent). Presentation only: timetable logic uses scheduled_stops.';
COMMENT ON COLUMN route_shapes.seq IS
    'Ascending 0-based index of the point along the path.';
COMMENT ON COLUMN route_shapes.is_stop IS
    'TRUE when this vertex is also a scheduled stop.';
