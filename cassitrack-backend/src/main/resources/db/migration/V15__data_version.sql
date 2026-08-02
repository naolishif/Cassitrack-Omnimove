-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V15__data_version.sql
--
-- A change counter per static-data table, so OmniMove can ask "has anything
-- changed?" cheaply instead of re-downloading the whole NeTEx document.
--
-- WHY
-- ---
-- OmniMove keeps its own copy of routes / stops / trips / scheduled_stops /
-- route_shapes, rebuilt by NetexImportService. That import runs once, at
-- startup, so anything a fleet manager changes afterwards stays invisible to
-- travellers until the container is restarted.
--
-- Polling GET /api/static/netex on a timer would fix the staleness but is
-- wasteful: that endpoint rebuilds the entire document from the database on
-- every call, so a one-minute poll means a full read of every static table
-- 1,440 times a day to discover that nothing changed.
--
-- Instead each watched table bumps a counter here whenever a row is inserted,
-- updated or deleted. OmniMove reads those few rows once a minute and only
-- downloads the document when a number has moved.
--
-- WHY TRIGGERS RATHER THAN pg_stat_user_tables
-- --------------------------------------------
-- Postgres already counts writes per table in pg_stat_user_tables, which would
-- need no schema change at all. But those are STATISTICS: they can be wiped by
-- pg_stat_reset(), they are updated asynchronously, and they are not
-- transactional — a rolled-back transaction still moves them. Good enough for a
-- dashboard, too loose to drive data synchronisation.
--
-- Triggers are exact, transactional (a rollback leaves the counter untouched),
-- and — because they live in the database rather than in the service layer —
-- they also catch changes made by a Flyway migration or by hand in psql, not
-- only writes that go through the Java code.
--
-- WHY buses IS NOT WATCHED
-- ------------------------
-- buses.map_visible changes every time a fleet manager toggles a bus on the
-- map, and OmniMove does not consume that column at all. Watching it would
-- trigger a full re-import for a change no traveller can see.
-- ────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS data_version (
    table_name VARCHAR(40)  PRIMARY KEY,
    version    BIGINT       NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE data_version IS
    'Change counter per static-data table. Bumped by triggers; read by OmniMove to decide whether a NeTEx re-import is needed.';

-- Seed a row per watched table so the endpoint always returns a complete set,
-- even before anything has been edited.
INSERT INTO data_version (table_name) VALUES
    ('routes'), ('stops'), ('trips'), ('scheduled_stops'), ('route_shapes')
ON CONFLICT (table_name) DO NOTHING;

-- ── The trigger function ─────────────────────────────────────────
-- Statement-level, not row-level: one bump per statement is enough to say
-- "this table changed", and it keeps a bulk insert of 527 route_shape rows
-- from doing 527 pointless updates to the same row. TG_TABLE_NAME lets a
-- single function serve every watched table.
--
-- Known trade-off: a statement-level trigger fires even when the statement
-- matched no rows, so a DELETE or UPDATE affecting nothing still bumps the
-- counter and costs OmniMove one unnecessary re-import. Harmless (the import
-- is idempotent) and rare, since these tables are only written when a fleet
-- manager edits the network. Avoiding it would mean transition tables and a
-- row-count check — more machinery than the problem deserves.
CREATE OR REPLACE FUNCTION bump_data_version() RETURNS trigger AS $$
BEGIN
    -- Upsert rather than a plain UPDATE: a plain UPDATE would silently affect
    -- zero rows if someone later attaches this trigger to a table without also
    -- seeding its row, and the change would then never be detected — the worst
    -- kind of failure, because everything looks fine.
    INSERT INTO data_version (table_name, version, updated_at)
         VALUES (TG_TABLE_NAME, 1, now())
    ON CONFLICT (table_name) DO UPDATE
        SET version    = data_version.version + 1,
            updated_at = now();

    RETURN NULL;   -- statement-level AFTER triggers ignore the return value
END;
$$ LANGUAGE plpgsql;

-- ── Attach it to each watched table ──────────────────────────────
DROP TRIGGER IF EXISTS trg_version_routes          ON routes;
DROP TRIGGER IF EXISTS trg_version_stops           ON stops;
DROP TRIGGER IF EXISTS trg_version_trips           ON trips;
DROP TRIGGER IF EXISTS trg_version_scheduled_stops ON scheduled_stops;
DROP TRIGGER IF EXISTS trg_version_route_shapes    ON route_shapes;

CREATE TRIGGER trg_version_routes
    AFTER INSERT OR UPDATE OR DELETE ON routes
    FOR EACH STATEMENT EXECUTE FUNCTION bump_data_version();

CREATE TRIGGER trg_version_stops
    AFTER INSERT OR UPDATE OR DELETE ON stops
    FOR EACH STATEMENT EXECUTE FUNCTION bump_data_version();

CREATE TRIGGER trg_version_trips
    AFTER INSERT OR UPDATE OR DELETE ON trips
    FOR EACH STATEMENT EXECUTE FUNCTION bump_data_version();

CREATE TRIGGER trg_version_scheduled_stops
    AFTER INSERT OR UPDATE OR DELETE ON scheduled_stops
    FOR EACH STATEMENT EXECUTE FUNCTION bump_data_version();

CREATE TRIGGER trg_version_route_shapes
    AFTER INSERT OR UPDATE OR DELETE ON route_shapes
    FOR EACH STATEMENT EXECUTE FUNCTION bump_data_version();
