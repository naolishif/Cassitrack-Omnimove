-- ═══════════════════════════════════════════════════════════════════════════
-- V41 — Emergenze segnalate dai sistemi partner
--
-- Gli altri team della challenge ci comunicano via API un'emergenza in un
-- punto: alluvione, incidente, cantiere, evento. Questa tabella è il
-- registro di quelle segnalazioni, nel formato che i partner già producono
-- (eventId, eventType, severity, radiusMeters, meetingPoint…).
--
-- PERCHÉ PUNTO + RAGGIO E NON UNA GEOMETRIA
-- Ai partner si chiede il minimo che sappiano dare senza uno strumento GIS:
-- il centro dell'area e quanto è grande. Una polilinea o un poligono
-- potranno aggiungersi in seguito senza toccare queste colonne.
--
-- UN SOLO POST, L'ID È IL LORO
-- Il partner manda la segnalazione con il proprio eventId; lo stesso id
-- ripetuto aggiorna la riga (status = false per dire "rientrata"). L'id è
-- unico per partner, non globale: due team possono entrambi avere la "1".
-- Una segnalazione rientrata resta in tabella con status = false e
-- resolved_at valorizzato: il pannello admin elenca quando è stata
-- attivata e quando è rientrata.
--
-- COME LA USA IL PLANNER
-- Nessuna cache: a ogni ricerca di itinerario le tratte candidate vengono
-- confrontate con le segnalazioni in corso in quel momento.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS road_closures (
    id              BIGSERIAL        PRIMARY KEY,
    -- eventId del partner, unico per partner
    external_id     VARCHAR(100)     NOT NULL,
    -- emergencyId numerico del partner, se lo manda
    emergency_id    BIGINT,
    event_type      VARCHAR(40)      NOT NULL,
    severity        VARCHAR(20)      NOT NULL,
    category        VARCHAR(40),
    title           VARCHAR(200),
    description     VARCHAR(500),
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    radius_m        INTEGER          NOT NULL,
    -- punto di raccolta indicato dal partner, opzionale
    meeting_lat     DOUBLE PRECISION,
    meeting_lon     DOUBLE PRECISION,
    meeting_address VARCHAR(300),
    -- true = in corso, false = rientrata
    status          BOOLEAN          NOT NULL DEFAULT TRUE,
    -- quando il partner ha detto "rientrata"; NULL finché è in corso
    resolved_at     TIMESTAMPTZ,
    -- etichetta della chiave API che ha inviato la segnalazione
    reported_by     VARCHAR(100)     NOT NULL,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_road_closures_partner_id
    ON road_closures (reported_by, external_id);
CREATE INDEX IF NOT EXISTS idx_road_closures_status ON road_closures (status);

COMMENT ON TABLE road_closures IS
    'Emergenze segnalate dai partner via /api/partner/v1/road-closures. status=false = rientrata.';
COMMENT ON COLUMN road_closures.external_id IS
    'eventId nel sistema del partner; ripeterlo aggiorna la riga.';
COMMENT ON COLUMN road_closures.radius_m IS
    'Raggio in metri dell''area interessata attorno a latitude/longitude.';
