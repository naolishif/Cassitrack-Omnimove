-- =================================================================
-- V32: messaggi dal traveller all'amministrazione
--
-- Un canale di feedback dentro l'app: finora l'unico modo per
-- segnalare un problema era scrivere a qualcuno che si conosceva.
--
-- Il testo lo scrive l'utente e puo' contenere qualunque cosa,
-- compresi dati personali propri o altrui: e' legato all'account con
-- ON DELETE CASCADE come tutto il resto, quindi la cancellazione
-- dell'account se lo porta via senza bisogno di un job dedicato.
--
-- read_at, non un flag booleano: l'operatore deve poter dire QUANDO
-- un messaggio e' stato letto, non solo che lo e' stato, ed e' la
-- stessa informazione che serve per l'avviso nella lista utenti.
-- =================================================================

CREATE TABLE IF NOT EXISTS user_messages (
    id         BIGSERIAL   PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Nessun limite stretto: un feedback utile e' spesso lungo. Il
    -- controllo sulla lunghezza massima sta nel controller, dove puo'
    -- restituire un errore comprensibile invece di far fallire l'insert.
    body       TEXT        NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ
);

-- La lista utenti chiede "quanti non letti per ciascuno", il dettaglio
-- chiede "tutti i messaggi di questo, dal piu' recente".
CREATE INDEX IF NOT EXISTS idx_user_messages_user
    ON user_messages (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_messages_unread
    ON user_messages (user_id) WHERE read_at IS NULL;

COMMENT ON TABLE user_messages IS
    'Messaggi scritti dal traveller e letti dall''amministrazione.';
