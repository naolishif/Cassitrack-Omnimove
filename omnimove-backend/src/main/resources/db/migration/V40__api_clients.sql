-- ═══════════════════════════════════════════════════════════════════════════
-- V40 — Chiavi API per i sistemi partner
--
-- Fino a qui OmniMove non aveva nessun endpoint invocabile da un altro
-- sistema: tutto passava da un utente loggato. Le API partner
-- (/api/partner/v1/**) si autenticano invece con una chiave, e questa tabella
-- è il registro di quelle chiavi.
--
-- PERCHÉ UNA TABELLA E NON UNA VARIABILE D'AMBIENTE
-- Il canale OmniMove↔CassiTrack usa una sola chiave condivisa
-- (CASSITRACK_API_TOKEN). Funziona fra due sistemi nostri; con quattro team
-- esterni significherebbe dare a tutti lo stesso segreto, e revocarlo a uno
-- vorrebbe dire cambiarlo a tutti. Qui ogni partner ha la sua, con la sua
-- scadenza e la sua revoca.
--
-- LA CHIAVE NON È SALVATA
-- Si conserva solo lo SHA-256: chi legge la tabella non può usare le chiavi.
-- SHA-256 e non bcrypt perché la chiave è casuale a 256 bit, non una password
-- scelta da una persona: non c'è niente da indovinare per dizionario, e un
-- hash lento costerebbe a ogni richiesta senza proteggere nulla in più.
-- key_prefix è il pezzo iniziale in chiaro, perché nel pannello si possa
-- riconoscere QUALE chiave è quale senza poterla ricostruire.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS api_clients (
    id            BIGSERIAL     PRIMARY KEY,
    label         VARCHAR(100)  NOT NULL,
    key_prefix    VARCHAR(16)   NOT NULL,
    -- VARCHAR e non CHAR: Hibernate valida lo schema all'avvio e per una String
    -- si aspetta varchar; con bpchar l'applicazione rifiuta di partire.
    key_hash      VARCHAR(64)   NOT NULL UNIQUE,
    created_by    VARCHAR(100),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- NULL = non scade. La scadenza si sceglie alla creazione e non si sposta:
    -- per allungarla si emette una chiave nuova e si revoca la vecchia.
    expires_at    TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ
);

COMMENT ON TABLE api_clients IS
    'Chiavi API dei sistemi partner (X-Api-Key su /api/partner/**). '
    'Solo l''hash è conservato; la chiave in chiaro si vede una volta sola.';
COMMENT ON COLUMN api_clients.key_prefix IS
    'Inizio della chiave in chiaro, per riconoscerla nel pannello.';
COMMENT ON COLUMN api_clients.expires_at IS
    'NULL = senza scadenza.';
COMMENT ON COLUMN api_clients.last_used_at IS
    'Ultima richiesta accettata con questa chiave, aggiornata al più una '
    'volta al minuto.';
