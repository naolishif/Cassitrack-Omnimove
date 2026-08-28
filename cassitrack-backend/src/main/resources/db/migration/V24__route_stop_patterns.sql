-- ═══════════════════════════════════════════════════════════════════════════
-- V24 — Il pattern di fermate sale dalla corsa alla linea
--
-- PROBLEMA
-- Fino a qui "le fermate di una linea" non esistevano come dato. La sequenza
-- viveva solo dentro scheduled_stops, ripetuta identica in ogni corsa: 125
-- copie della stessa informazione per 14 linee. Niente impediva alla corsa
-- delle 07:00 di fermarsi altrove rispetto a quella delle 08:00, e modificare
-- il percorso di una linea significava riscrivere tutte le copie sperando di
-- non sbagliarne una.
--
-- SOLUZIONE
-- Si separa QUALI fermate (della linea) da QUANDO (della corsa) — la stessa
-- distinzione che Transmodel/NeTEx fanno fra JourneyPattern e ServiceJourney:
--
--   route_stops      route_id, stop_sequence, stop_id     ← il pattern
--   scheduled_stops  trip_id,  stop_sequence, arrival_seconds  ← i tempi
--
-- scheduled_stops perde stop_id, che ora ricava dal pattern via stop_sequence.
-- Ogni corsa conserva i propri orari, modificabili singolarmente: la corsa
-- del rientro da scuola può impiegare 22 minuti dove quella serale ne usa 18.
-- default_offset_seconds serve SOLO a precompilare una corsa nuova o una
-- fermata appena inserita; non è autoritativo e non ri-tempifica nulla.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Il pattern ──────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS route_stops (

    route_id      VARCHAR(50) NOT NULL
        REFERENCES routes(id)
        ON DELETE CASCADE,

    -- 1-based, come già in scheduled_stops. Insieme a route_id è la chiave
    -- naturale: una posizione della sequenza ospita una fermata sola.
    stop_sequence INTEGER     NOT NULL,

    -- Nessun ON DELETE CASCADE, al contrario di scheduled_stops.stop_id.
    -- Cancellare una fermata non deve svuotare silenziosamente il percorso
    -- di una linea: RESTRICT costringe a passare dal servizio, che sa
    -- ricucire la sequenza e ri-tempificare le corse.
    stop_id       VARCHAR(50) NOT NULL
        REFERENCES stops(id)
        ON DELETE RESTRICT,

    -- Scarto dalla partenza usato per PROPORRE gli orari. Vedi intestazione.
    default_offset_seconds INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (route_id, stop_sequence),

    CONSTRAINT chk_route_stops_sequence CHECK (stop_sequence >= 1),
    CONSTRAINT chk_route_stops_offset   CHECK (default_offset_seconds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_route_stops_stop ON route_stops (stop_id);


-- ── 2. Guardiano ───────────────────────────────────────────────────────────
--
-- Il backfill può promuovere una sola sequenza per linea, quindi deve poter
-- dimostrare che ce n'è una sola. Se due corse della stessa linea si fermano
-- in posti diversi non esiste un pattern da estrarre, e sceglierne uno a caso
-- perderebbe dati in silenzio. Meglio fermare la migrazione e far correggere
-- i dati: un deploy fallito si ripara, una fermata sparita no.

DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(DISTINCT route_id, ', ')
      INTO offending
      FROM (
          SELECT t.route_id, ss.stop_sequence
            FROM scheduled_stops ss
            JOIN trips t ON t.id = ss.trip_id
           GROUP BY t.route_id, ss.stop_sequence
          HAVING COUNT(DISTINCT ss.stop_id) > 1
      ) AS conflicts;

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION
            'V24 interrotta: queste linee hanno corse con fermate diverse nella stessa posizione (%). Allinearle prima di migrare.',
            offending;
    END IF;
END $$;


-- ── 3. Backfill del pattern ────────────────────────────────────────────────
--
-- DISTINCT basta proprio perché il guardiano sopra ha appena provato che per
-- ogni (route_id, stop_sequence) esiste un solo stop_id.

INSERT INTO route_stops (route_id, stop_sequence, stop_id)
SELECT DISTINCT t.route_id, ss.stop_sequence, ss.stop_id
  FROM scheduled_stops ss
  JOIN trips t ON t.id = ss.trip_id
ON CONFLICT (route_id, stop_sequence) DO NOTHING;


-- ── 4. Scarti di default ───────────────────────────────────────────────────
--
-- Mediana degli scarti osservati, non media: se una singola corsa ha un orario
-- battuto a mano fuori scala, la mediana la ignora invece di lasciarsi
-- trascinare. È solo un valore di proposta, ma una proposta sbagliata viene
-- accettata senza guardare, quindi conviene che sia buona.

WITH offsets AS (
    SELECT t.route_id,
           ss.stop_sequence,
           ss.arrival_seconds - MIN(ss.arrival_seconds) OVER (PARTITION BY ss.trip_id)
               AS offset_seconds
      FROM scheduled_stops ss
      JOIN trips t ON t.id = ss.trip_id
),
medians AS (
    SELECT route_id,
           stop_sequence,
           PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY offset_seconds) AS median_offset
      FROM offsets
     GROUP BY route_id, stop_sequence
)
UPDATE route_stops rs
   SET default_offset_seconds = GREATEST(0, ROUND(m.median_offset)::INTEGER)
  FROM medians m
 WHERE m.route_id      = rs.route_id
   AND m.stop_sequence = rs.stop_sequence;


-- ── 5. Verifica prima di distruggere ───────────────────────────────────────
--
-- Il passo 6 butta via stop_id. Prima di farlo bisogna dimostrare che ogni
-- riga di scheduled_stops si ritrova nel pattern: dopo il DROP la prova non
-- sarebbe più possibile, e una riga orfana diventerebbe una fermata muta.

DO $$
DECLARE
    orphans BIGINT;
BEGIN
    SELECT COUNT(*)
      INTO orphans
      FROM scheduled_stops ss
      JOIN trips t ON t.id = ss.trip_id
      LEFT JOIN route_stops rs
             ON rs.route_id      = t.route_id
            AND rs.stop_sequence = ss.stop_sequence
            AND rs.stop_id       = ss.stop_id
     WHERE rs.route_id IS NULL;

    IF orphans > 0 THEN
        RAISE EXCEPTION
            'V24 interrotta: % righe di scheduled_stops non trovano riscontro nel pattern appena creato.',
            orphans;
    END IF;
END $$;


-- ── 6. scheduled_stops diventa solo tempo ──────────────────────────────────

ALTER TABLE scheduled_stops DROP COLUMN stop_id;

-- La coppia (corsa, posizione) era già UNIQUE. Ora che la riga non porta
-- altro che un orario, quel vincolo è anche la sua identità completa.
COMMENT ON TABLE scheduled_stops IS
    'Orari di una corsa. Quale fermata occupi ogni posizione lo dice route_stops, via la linea della corsa.';

COMMENT ON TABLE route_stops IS
    'Sequenza di fermate di una linea (il JourneyPattern di NeTEx). Gli orari stanno in scheduled_stops, uno per corsa.';


-- ── 7. route_stops entra nel conteggio delle versioni ──────────────────────
--
-- OmniMove decide se ri-importare il NeTEx guardando data_version. Il pattern
-- di fermate è a tutti gli effetti definizione di rete: senza questo trigger,
-- spostare una fermata di linea non farebbe scattare il re-import e i due
-- sistemi resterebbero disallineati senza che nessuno se ne accorga.

INSERT INTO data_version (table_name) VALUES ('route_stops')
ON CONFLICT (table_name) DO NOTHING;

DROP TRIGGER IF EXISTS trg_version_route_stops ON route_stops;
CREATE TRIGGER trg_version_route_stops
    AFTER INSERT OR UPDATE OR DELETE ON route_stops
    FOR EACH STATEMENT EXECUTE FUNCTION bump_data_version();

-- Lo schema di scheduled_stops è cambiato sotto i piedi di chi la legge:
-- un bump esplicito forza il re-import anche se nessuna riga si è mossa.
UPDATE data_version
   SET version = version + 1, updated_at = now()
 WHERE table_name IN ('scheduled_stops', 'routes');
