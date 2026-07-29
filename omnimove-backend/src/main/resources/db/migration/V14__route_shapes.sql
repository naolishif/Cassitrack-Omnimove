-- ────────────────────────────────────────────────────────────────
-- OMNIMOVE
-- V14__route_shapes.sql
--
-- Road geometry for each route — OmniMove's copy of CassiTrack's V9 table.
--
-- WHY OMNIMOVE NEEDS ITS OWN
-- --------------------------
-- OmniMove runs against its own database (omnimovedb) and holds its own copy
-- of routes / stops / trips / scheduled_stops, kept in step with CassiTrack by
-- the NeTEx import. Geometry follows the same rule: this table is the local
-- copy, seeded by V15 and refreshed by NetexImportService on each import.
--
-- WHY IT MATTERS HERE
-- -------------------
-- The traveller map draws a journey's bus legs from the stops of the trip, so
-- a leg was a straight line between boarding and alighting. With the real path
-- available, JourneyPlannerService can slice the portion of road actually
-- travelled, and the drawn leg follows the streets like the bus does.
--
-- Presentation only — journey planning, timing and ETA still run off
-- scheduled_stops. Routes with no shape keep the old stop-to-stop rendering.
--
-- Deliberately identical in shape to CassiTrack's route_shapes so the same
-- generated data file loads into either database unchanged.
-- ────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS route_shapes (

    route_id  VARCHAR(50)      NOT NULL
        REFERENCES routes(id)
        ON DELETE CASCADE,

    -- Position along the path, 0-based, ascending.
    seq       INTEGER          NOT NULL,

    lat       DOUBLE PRECISION NOT NULL,
    lon       DOUBLE PRECISION NOT NULL,

    -- TRUE when this vertex coincides with a scheduled stop. Used when slicing
    -- a leg out of the path: stop vertices are the cut points.
    is_stop   BOOLEAN          NOT NULL DEFAULT FALSE,

    PRIMARY KEY (route_id, seq),

    CONSTRAINT chk_route_shapes_lat CHECK (lat BETWEEN -90  AND 90),
    CONSTRAINT chk_route_shapes_lon CHECK (lon BETWEEN -180 AND 180)
);

COMMENT ON TABLE  route_shapes IS
    'Ordered polyline following the real roads for each route (GTFS shapes.txt equivalent). Local copy of CassiTrack''s table, refreshed by the NeTEx import.';
COMMENT ON COLUMN route_shapes.seq IS
    'Ascending 0-based index of the point along the path.';
COMMENT ON COLUMN route_shapes.is_stop IS
    'TRUE when this vertex is also a scheduled stop — the cut points when slicing a journey leg.';
