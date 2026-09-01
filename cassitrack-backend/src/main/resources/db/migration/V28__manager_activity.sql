-- =================================================================
-- V28: what the fleet manager did — accesses and downloads
--
-- WHY NOT security_audit_events
-- -----------------------------
-- Logins are already recorded there, and V8 makes that table
-- write-only for the application on purpose: a forensic register the
-- app can re-read is a register a compromised app can also erase.
-- That stays as it is.
--
-- These two tables answer a different question, and it is an
-- operational one: who has been in the system, and what has left it.
-- The audit row remains the proof; this is its visible echo, the part
-- the people who run the service can actually look at.
--
-- Same shape as OMNIMOVE's login_events and admin_exports, so the two
-- dashboards read alike and neither has to be learned twice.
-- =================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

-- ── One successful access ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS login_events (
    id           BIGSERIAL PRIMARY KEY,

    -- CASCADE like everything else: deleting an account takes its
    -- history with it, which is what erasure has to mean.
    user_id      BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    logged_in_at TIMESTAMP NOT NULL DEFAULT NOW(),
    ip_address   VARCHAR(50),
    user_agent   VARCHAR(255)
);

-- The card asks "this manager's accesses, newest first". The list view
-- reads users.last_login_at instead, so this index serves only the detail.
CREATE INDEX IF NOT EXISTS idx_login_events_user
    ON login_events (user_id, logged_in_at DESC);

-- ── One file taken out of the system ─────────────────────────────
-- No content is stored, only what was downloaded and how much of it:
-- the dataset, the format, the filters in effect and the row count.
CREATE TABLE IF NOT EXISTS manager_exports (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Buses | Stops | Routes | Timetable | Fleet analytics …
    dataset     VARCHAR(60) NOT NULL,
    -- csv | xlsx | pdf
    format      VARCHAR(10) NOT NULL,
    -- How many rows left with it, so a bulk download stands out from a peek.
    row_count   INTEGER     NOT NULL DEFAULT 0,
    -- The filters, as the screen worded them.
    detail      VARCHAR(255),

    exported_at TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_manager_exports_user
    ON manager_exports (user_id, exported_at DESC);

COMMENT ON TABLE login_events IS
    'Accessi riusciti, mostrati nella scheda utente. La prova resta in security_audit_events.';
COMMENT ON TABLE manager_exports IS
    'File scaricati dagli operatori, mostrati nella loro scheda. Nessun contenuto, solo ambito e dimensione.';
