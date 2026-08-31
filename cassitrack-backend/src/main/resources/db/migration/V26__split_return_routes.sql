-- ────────────────────────────────────────────────────────────────
-- CASSITRACK
-- V26__split_return_routes.sql
-- Il ritorno di una linea diventa una linea a sé.
--
-- PERCHÉ
-- ------
-- V24 ha dato a ogni linea andata e ritorno sotto lo STESSO route_id: la
-- corsa di ritorno è lo specchio dell'andata, stop_ids[n+1-i] in posizione i.
-- Serviva: prima, da 37 delle 45 fermate nessun autobus portava verso Cassino.
--
-- V27 (i pattern di fermate) assume invece che una linea abbia UNA sola
-- sequenza — PRIMARY KEY (route_id, stop_sequence) — e arriva a eliminare
-- scheduled_stops.stop_id, facendo dipendere "quale fermata è la posizione 3"
-- dal solo pattern della linea. Le due cose non possono valere insieme: nella
-- posizione 1 l'andata ha un capolinea e il ritorno l'altro.
--
-- Questa migrazione scioglie il nodo spostando le corse di ritorno su una
-- linea propria, <route_id>_R. Ogni linea torna ad avere una sequenza sola,
-- il guardiano di V27 passa, e nessuna corsa viene persa.
--
-- COSA NON CAMBIA
-- ---------------
-- Gli orari, i capolinea, i veicoli e le fermate restano identici: cambia
-- solo a quale riga della tabella routes appartiene una corsa. Il traveller
-- raggruppa gli orari per CAPOLINEA, non per linea, quindi continua a
-- mostrare le due direzioni come prima (vedi JourneyController.timetable,
-- che da qui in poi legge anche la linea _R quando gli si chiede l'andata).
--
-- IDENTIFICARE I RITORNI
-- ----------------------
-- V24 nomina le corse <route_id>_A_<partenza> e <route_id>_R_<partenza>.
-- Il confronto è ancorato al route_id della corsa stessa e usa left(), non
-- LIKE: in LIKE l'underscore è un jolly e '_R_' aggancerebbe anche 'XRY'.
-- Le corse più vecchie (V17) non hanno quel prefisso e restano dove sono,
-- correttamente: erano di sola andata.
-- ────────────────────────────────────────────────────────────────

-- ── 1. Una linea di ritorno per ogni linea che ne ha bisogno ───────────────
-- Eredita numero e colori dall'andata: sulla mappa e nei badge le due
-- direzioni devono leggersi come la stessa linea, perché lo sono.
INSERT INTO routes (id, short_name, long_name, description, color, text_color, active)
SELECT r.id || '_R',
       r.short_name,
       COALESCE(r.long_name, r.short_name) || ' (ritorno)',
       r.description,
       r.color,
       r.text_color,
       r.active
  FROM routes r
 WHERE EXISTS (
         SELECT 1 FROM trips t
          WHERE t.route_id = r.id
            AND left(t.id, length(t.route_id) + 3) = t.route_id || '_R_')
   AND NOT EXISTS (SELECT 1 FROM routes x WHERE x.id = r.id || '_R');


-- ── 2. Le corse di ritorno passano alla loro linea ─────────────────────────
-- scheduled_stops segue le corse: referenzia trip_id, non route_id, quindi
-- non va toccata. Le posizioni 1..n del ritorno erano già nel suo ordine di
-- marcia, e su una linea propria sono la sua sequenza naturale.
UPDATE trips t
   SET route_id = t.route_id || '_R'
 WHERE left(t.id, length(t.route_id) + 3) = t.route_id || '_R_';


-- ── 3. Il tracciato è lo stesso percorso ───────────────────────────────────
-- Stessa geometria: il bus fa la medesima strada al contrario. Copiarla evita
-- che la linea di ritorno sparisca dalla mappa, e non inventa un percorso.
INSERT INTO route_shapes (route_id, seq, lat, lon)
SELECT rs.route_id || '_R', rs.seq, rs.lat, rs.lon
  FROM route_shapes rs
 WHERE EXISTS (SELECT 1 FROM routes x WHERE x.id = rs.route_id || '_R')
   AND NOT EXISTS (SELECT 1 FROM route_shapes y WHERE y.route_id = rs.route_id || '_R');


-- ── 4. Verifica ────────────────────────────────────────────────────────────
-- La stessa condizione che V27 pretende. Fallire qui, con un messaggio che
-- dice cosa è rimasto storto, è meglio che fallire nella migrazione dopo.
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
            'V26: dopo lo split queste linee hanno ancora fermate diverse nella stessa posizione (%). Corse che non seguono la nomenclatura _A_/_R_ di V24?',
            offending;
    END IF;
END $$;
