-- =================================================================
-- V33: registro degli export, leggibile dall'applicazione
--
-- PERCHÉ NON BASTA L'AUDIT
-- ------------------------
-- Gli export sono già registrati in security_audit_events, ma quella
-- tabella l'applicazione può solo scriverla: V13 concede INSERT e
-- nient'altro, e la lettura è riservata al ruolo security_auditor.
-- È una scelta giusta e non la tocchiamo — un registro forense che
-- l'applicazione può rileggere è un registro che un'applicazione
-- compromessa può anche cancellare.
--
-- Questa tabella serve a un'altra cosa: mostrare nella scheda di un
-- amministratore che cosa ha scaricato e quando. Trasparenza operativa,
-- non forense. Le due convivono: la riga di audit resta la prova, questa
-- è la sua eco visibile.
--
-- Nessun contenuto: solo che tipo di export, quando, e una descrizione
-- dell'ambito (formato e periodo, oppure filtri e numero di righe).
-- =================================================================

CREATE TABLE IF NOT EXISTS admin_exports (
    id          BIGSERIAL   PRIMARY KEY,

    -- Chi ha scaricato. CASCADE come tutto il resto: cancellare l'account
    -- di un operatore porta via anche questo elenco.
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- ANALYTICS | USER_LIST
    kind        VARCHAR(20) NOT NULL,

    -- Ambito in chiaro: "pdf, Last month" oppure "role ADMIN; 12 righe".
    detail      VARCHAR(255),

    exported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- La scheda chiede "gli export di questo, dal più recente".
CREATE INDEX IF NOT EXISTS idx_admin_exports_user
    ON admin_exports (user_id, exported_at DESC);

COMMENT ON TABLE admin_exports IS
    'Export effettuati dagli operatori, mostrati nella loro scheda. La prova resta in security_audit_events.';
