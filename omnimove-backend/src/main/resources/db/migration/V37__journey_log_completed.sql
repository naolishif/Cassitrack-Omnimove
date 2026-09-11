-- ═══════════════════════════════════════════════════════════════════════════
-- V37 — Una corsa vale eco points solo se e' stata davvero fatta
--
-- PROBLEMA
-- La riga di journey_log nasce quando il viaggio COMINCIA, e gli eco points
-- sono la somma dei green_index di tutte le righe dell'utente. Il risultato e'
-- che i punti si prendono premendo "Inizia percorso" e subito "Termina
-- percorso": il viaggio non lo si e' fatto, ma il punteggio lo si e' preso.
--
-- SOLUZIONE
-- La riga dice anche com'e' finito il viaggio. Il conteggio dei punti guarda
-- solo quelle concluse; tutto il resto — storico, statistiche, ricerca —
-- continua a vedere ogni riga, perche' un viaggio interrotto e' comunque un
-- viaggio scelto, ed e' un dato buono su cosa la gente sceglie.
--
-- TRE STATI, NON DUE, e la colonna e' percio' NULLABLE:
--   NULL   viaggio in corso: cominciato, non ancora chiuso
--   TRUE   concluso: conta per gli eco points
--   FALSE  chiuso troppo presto: non conta, ma e' CHIUSO
--
-- Il terzo stato non e' un lusso. Con due soli valori una corsa interrotta
-- resterebbe indistinguibile da una in corso, e la chiusura successiva
-- ripescherebbe quella vecchia attribuendole l'esito di un altro viaggio.
--
-- LE RIGHE GIA' ESISTENTI NASCONO CONCLUSE
-- Precedono il controllo: nessuno poteva sapere che ci sarebbe stato, e
-- azzerare punti gia' guadagnati sarebbe una punizione retroattiva per viaggi
-- con ogni probabilita' fatti sul serio.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE journey_log
    ADD COLUMN IF NOT EXISTS completed BOOLEAN;

UPDATE journey_log SET completed = TRUE WHERE completed IS NULL;

COMMENT ON COLUMN journey_log.completed IS
    'NULL = in corso, TRUE = concluso (vale eco points), FALSE = chiuso troppo presto.';

-- Due letture frequenti: la somma dei punti di un utente (user_id, completed)
-- e la corsa ancora aperta da chiudere (user_id, completed IS NULL). Senza
-- indice sono due scansioni dell'intera tabella, una a ogni apertura della
-- pagina e una a ogni fine viaggio.
CREATE INDEX IF NOT EXISTS idx_journey_log_user_completed
    ON journey_log (user_id, completed);
