-- ═══════════════════════════════════════════════════════════════════════════
-- V30 — Chiavi API per i sistemi partner
--
-- I feed che oggi chiedono X-Api-Key (/api/static/netex, /api/static/version,
-- /api/v1/telemetry/stream) confrontano l'header con l'unica stringa
-- SSE_API_TOKEN, che è il segreto del canale con OmniMove. Darla agli altri
-- team significherebbe dare a tutti lo stesso segreto, e revocarlo a uno
-- vorrebbe dire cambiarlo a tutti — OmniMove compreso.
--
-- Questa tabella è il registro delle chiavi dei partner: ognuno ha la sua,
-- con la sua scadenza e la sua revoca. SSE_API_TOKEN resta valido, per
-- OmniMove e per nient'altro di nuovo.
--
-- LA CHIAVE NON È SALVATA
-- Si conserva solo lo SHA-256: chi legge la tabella non può usare le chiavi.
-- SHA-256 e non bcrypt perché la chiave è casuale a 256 bit, non una password
-- scelta da una persona: non c'è niente da indovinare per dizionario, e un
-- hash lento costerebbe a ogni richiesta senza proteggere nulla in più.
-- key_prefix è il pezzo iniziale in chiaro, perché nel pannello si possa
-- riconoscere QUALE chiave è quale senza poterla ricostruire.
--
-- Stesso schema della tabella omonima di OmniMove (V40): i due pannelli
-- fanno la stessa cosa e chi li usa trova le stesse colonne.
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
    'Chiavi API dei sistemi partner (X-Api-Key sui feed NeTEx, version e SSE). '
    'Solo l''hash è conservato; la chiave in chiaro si vede una volta sola.';
COMMENT ON COLUMN api_clients.key_prefix IS
    'Inizio della chiave in chiaro, per riconoscerla nel pannello.';
COMMENT ON COLUMN api_clients.expires_at IS
    'NULL = senza scadenza.';
COMMENT ON COLUMN api_clients.last_used_at IS
    'Ultima richiesta accettata con questa chiave, aggiornata al più una '
    'volta al minuto.';
