-- ═══════════════════════════════════════════════════════════════════════════
-- V32 — Uso delle chiavi API partner
--
-- api_clients.last_used_at dice solo QUANDO una chiave è stata usata l'ultima
-- volta. Il pannello admin vuole anche QUANTE volte e SU QUALI endpoint:
-- una riga per chiamata autenticata, con metodo e path. Il volume è basso
-- (pochi partner, poche chiamate al minuto) e la storia serve per il
-- grafico, quindi righe e non contatori.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS api_client_usage (
    id          BIGSERIAL    PRIMARY KEY,
    client_id   BIGINT       NOT NULL REFERENCES api_clients(id) ON DELETE CASCADE,
    method      VARCHAR(8)   NOT NULL,
    endpoint    VARCHAR(200) NOT NULL,
    called_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_api_client_usage_client_time
    ON api_client_usage (client_id, called_at);

COMMENT ON TABLE api_client_usage IS
    'Una riga per chiamata autenticata con una chiave partner: per il grafico d''uso nel pannello admin.';
