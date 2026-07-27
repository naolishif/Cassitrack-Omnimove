-- ============================================================
-- US-01 — Manage Buses
-- Adds the registry fields the fleet manager needs:
--   route_id     : which route the bus is assigned to
--   status       : ACTIVE / INACTIVE / MAINTENANCE (replaces the
--                  two-state "disponibile" boolean for UI purposes;
--                  "disponibile" is kept and auto-synced so existing
--                  code that reads it keeps working)
--   map_visible  : show/hide this bus on the fleet map (US-05)
-- ============================================================

ALTER TABLE buses
    ADD COLUMN IF NOT EXISTS route_id    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS map_visible BOOLEAN      NOT NULL DEFAULT TRUE;

-- Foreign key to routes. ON DELETE SET NULL: deleting a route must not
-- delete buses, it just leaves them unassigned.
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint WHERE conname = 'fk_buses_route'
        ) THEN
            ALTER TABLE buses
                ADD CONSTRAINT fk_buses_route
                    FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE SET NULL;
        END IF;
    END $$;

-- Only the three statuses the UI offers are allowed at DB level too.
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint WHERE conname = 'chk_buses_status'
        ) THEN
            ALTER TABLE buses
                ADD CONSTRAINT chk_buses_status
                    CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE'));
        END IF;
    END $$;

-- Backfill: existing rows get a status derived from the old boolean.
UPDATE buses
SET status = CASE WHEN disponibile THEN 'ACTIVE' ELSE 'INACTIVE' END;

-- Helpful index for the "filter by route" requirement.
CREATE INDEX IF NOT EXISTS idx_buses_route_id ON buses (route_id);
