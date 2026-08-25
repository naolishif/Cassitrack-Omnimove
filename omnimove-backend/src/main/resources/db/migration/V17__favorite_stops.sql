-- ────────────────────────────────────────────────────────────────
-- OMNIMOVE
-- Stops a traveller has starred, to drop into the search without typing.
--
-- WHY stop_id AND NOT THE NAME
-- favorite_route stores origin_name / dest_name, and that choice has a cost we
-- have already seen: rename a stop and the saved trip still shows the old label
-- while the search quietly refuses to resolve it. An id survives a rename, and
-- the name is looked up fresh on every read.
--
-- WHY NO FOREIGN KEY TO stops
-- NetexImportService rebuilds the whole network on every import — it calls
-- stopRepository.deleteAll() and re-inserts. A FK with ON DELETE CASCADE would
-- therefore wipe every traveller's favourites each time CassiTrack publishes a
-- change, and a restricting FK would break the import outright. The reference is
-- deliberately loose: the API resolves each id against the current stops and
-- leaves out any that no longer exist, so a retired stop disappears from the
-- list instead of taking the import down with it.
-- ────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS favorite_stop (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stop_id    VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_favorite_stop UNIQUE (user_id, stop_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_stop_user ON favorite_stop(user_id);
