-- ═══════════════════════════════════════════════════════════════════════════
-- V39 — Nessun account si chiude senza averlo detto prima, e nella lingua giusta
--
-- Due colonne, un solo scopo: rendere possibili gli avvisi che accompagnano la
-- chiusura di un account. La prima serve a sapere SE abbiamo avvisato, la
-- seconda a sapere COME scrivere. Senza la prima la cancellazione arriverebbe
-- senza preavviso; senza la seconda arriverebbe in una lingua che l'utente
-- potrebbe non leggere.
--
-- Le regole che le usano stanno in DataRetentionService (UNVERIFIED_ACCOUNTS e
-- INACTIVE_ACCOUNTS) e nei due percorsi di cancellazione su richiesta:
-- AuthController.deleteAccount e AdminController.deleteUser.
-- ═══════════════════════════════════════════════════════════════════════════


-- ── 1. Il preavviso di chiusura per inattività ─────────────────────────────
--
-- privacy.html dichiara la cancellazione degli account rimasti inattivi per 24
-- mesi. La regola da sola non basta: va preceduta da un preavviso, e il
-- preavviso va SCRITTO da qualche parte, altrimenti il sistema non sa
-- distinguere chi è stato avvisato da chi no.
--
-- PERCHÉ UNA COLONNA E NON UN CALCOLO
-- Si potrebbe dedurre il momento dell'avviso dalla data di ultimo accesso
-- (inattivo da 24 mesi meno 7 giorni). Ma quel calcolo presume che il job
-- notturno sia girato quel giorno preciso. Se resta fermo un mese — server
-- spento, sessione di manutenzione, bug — alla ripartenza troverebbe persone
-- già oltre i 24 mesi e le cancellerebbe subito, senza che nessuna e-mail sia
-- mai partita. La data qui registrata è un FATTO: l'avviso è stato spedito,
-- quel giorno. La cancellazione la esige, e così i sette giorni sono sempre
-- sette giorni veri.
--
-- COME SI LEGGE, CONFRONTATA CON L'ULTIMO ACCESSO
--   warned_at IS NULL                  mai avvisato
--   warned_at > ultimo accesso         avvisato, e da allora non è più tornato
--   warned_at <= ultimo accesso        è tornato DOPO l'avviso: l'avviso è
--                                      decaduto, e ricomincia tutto da capo
--
-- L'ultima riga è la regola "se lo usa, non viene eliminato". Non serve
-- azzerare niente al login: il confronto fra le due date dice già tutto, e una
-- cancellazione dimenticata non può far sparire l'account di chi è tornato.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS inactivity_warned_at TIMESTAMP;

COMMENT ON COLUMN users.inactivity_warned_at IS
    'Quando è stato spedito il preavviso di cancellazione per inattività. '
    'NULL = mai avvisato. Se <= last_login_at l''utente è tornato dopo '
    'l''avviso, che quindi non vale più.';

-- Il job notturno cerca "inattivi da oltre N mesi": senza indice è una
-- scansione dell'intera tabella utenti ogni notte. COALESCE perché un account
-- che non ha mai fatto accesso si misura dalla data di creazione.
CREATE INDEX IF NOT EXISTS idx_users_inactivity
    ON users (COALESCE(last_login_at, created_at));


-- ── 2. La lingua in cui scrivergli ─────────────────────────────────────────
--
-- IL PROBLEMA
-- La lingua viveva solo in localStorage, cioè nel browser. Il server la
-- conosceva unicamente attraverso l'intestazione X-Omnimove-Lang della singola
-- richiesta, e quindi soltanto MENTRE l'utente era davanti allo schermo.
--
-- Le e-mail che contano di più partono proprio quando non c'è: l'operatore che
-- cancella un account dal pannello manda una richiesta che porta la lingua
-- DELL'OPERATORE, e lo spazzino notturno della retention non ha nessuna
-- richiesta da cui leggerla.
--
-- PERCHÉ SU users E NON SU user_preferences
-- Perché la riga di preferenze può non esistere ancora. Un'iscrizione mai
-- confermata viene cancellata dopo 24 ore senza aver mai aperto l'applicazione,
-- e anche a quella persona va scritto — nella lingua in cui si era iscritta. La
-- lingua sta quindi accanto all'indirizzo e-mail, che è l'unica altra cosa che
-- serve per scrivere a qualcuno.
--
-- Sta anche sulla stessa riga che le regole di retention cancellano, così
-- DELETE ... RETURNING email, name, language restituisce in un colpo solo tutto
-- ciò che serve per il messaggio di commiato.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS language VARCHAR(5);

COMMENT ON COLUMN users.language IS
    'Lingua scelta dall''utente (''it'' / ''en''), per le e-mail inviate quando '
    'non c''è una richiesta da cui dedurla. NULL = mai impostata, si usa il '
    'default del servizio.';

-- GLI ACCOUNT ESISTENTI RESTANO A NULL, DI PROPOSITO.
--
-- La tentazione è riempirli con 'it', visto che il servizio è di Cassino. Ma il
-- valore in colonna ha la precedenza sull'intestazione della richiesta — è una
-- scelta dichiarata, mentre l'intestazione è un indizio — quindi scriverci una
-- supposizione la farebbe vincere su un'informazione vera: chi naviga in
-- inglese si vedrebbe rispondere in italiano finché non rifà l'accesso, che è
-- esattamente il contrario di ciò che questa colonna serve a ottenere.
--
-- Lasciandolo a NULL, chi è davanti allo schermo viene servito nella lingua
-- della sua richiesta, e per chi non c'è vale il default del servizio. Il primo
-- accesso, o il primo tocco sul selettore, lo riempie con la scelta vera.
