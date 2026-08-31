package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.RouteStop;
import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.VehiclePosition.ScheduleStatus;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.model.Trip;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.RouteStopRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import it.unicas.cassitrack.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Cambiare le fermate di una linea, propagandolo alle sue corse.
 *
 * PRINCIPIO
 * Le fermate appartengono alla linea, gli orari alla corsa (V26). Cambiare il
 * percorso significa quindi riscrivere una tabella sola — route_stops — e poi
 * riconciliare gli orari di ogni corsa con la nuova sequenza. La regola è che
 * <b>una fermata sopravvissuta non cambia orario</b>: chi resta resta anche
 * nell'orario pubblicato, e si inventano tempi solo per le fermate nuove.
 *
 * COME SI CAPISCE CHI E' SOPRAVVISSUTO
 * Non basta confrontare gli id: le linee di Cassino sono anelli, e lo stesso
 * id ricorre due volte nello stesso percorso. Confrontando insiemi, spostare
 * il capolinea in mezzo al giro sembrerebbe "nessun cambiamento". Serve un
 * allineamento posizionale, e la sottosequenza comune più lunga (LCS) è
 * esattamente questo: dice quali occorrenze sono le stesse occorrenze.
 *
 * I TEMPI DELLE FERMATE NUOVE
 * Interpolati fra le due fermate sopravvissute che le circondano, in
 * proporzione alla distanza geografica — e con i tempi <i>di quella corsa</i>,
 * non con gli scarti di default della linea. È la ragione per cui la corsa del
 * rientro da scuola resta più lenta di quella serale anche nel tratto nuovo:
 * se fra due ancore quella corsa impiega 8 minuti, la fermata inserita cade
 * dentro quegli 8 minuti.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoutePatternEditService {

    private final RouteStopRepository     routeStopRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final TripRepository          tripRepository;
    private final StopRepository          stopRepository;
    private final RoutePatternService     routePatternService;
    private final VehicleStateCache       vehicleStateCache;

    /** Distanza minima fra due arrivi consecutivi: un orario deve progredire. */
    private static final int MIN_GAP_SECONDS = 1;

    public record Result(boolean changed, int stopsBefore, int stopsAfter,
                         int tripsRetimed, int callsInserted, int callsRemoved,
                         int busesReanchored) {}

    /**
     * Cosa succederebbe, senza che succeda.
     *
     * Esiste perché ri-tempificare le corse di LINEA_16 ne tocca 26 in un
     * colpo solo, e un gestore che sposta una fermata non ha modo di saperlo
     * prima di averlo fatto. Questo metodo risponde alla stessa domanda del
     * salvataggio usando lo stesso allineamento, ma è {@code readOnly} e non
     * chiama un solo metodo di scrittura: non è un salvataggio "quasi", è un
     * conteggio.
     */
    @Transactional(readOnly = true)
    public Preview preview(String routeId, List<String> newStopIds) {

        if (newStopIds == null || newStopIds.size() < 2)
            throw bad("A line needs at least two stops.");
        rejectConsecutiveDuplicates(newStopIds);

        List<String> oldStopIds = routePatternService.stopIds(routeId);
        if (oldStopIds.equals(newStopIds))
            return new Preview(false, oldStopIds.size(), newStopIds.size(),
                    List.of(), List.of(), 0, 0);

        int[] anchorOf = alignByLcs(oldStopIds, newStopIds);

        // Nuove: le posizioni nuove senza ancora. Tolte: le posizioni vecchie
        // che nessuna posizione nuova rivendica.
        boolean[] survived = new boolean[oldStopIds.size()];
        List<String> addedIds = new ArrayList<>();
        for (int j = 0; j < anchorOf.length; j++) {
            if (anchorOf[j] >= 0) survived[anchorOf[j]] = true;
            else addedIds.add(newStopIds.get(j));
        }
        List<String> removedIds = new ArrayList<>();
        for (int i = 0; i < oldStopIds.size(); i++)
            if (!survived[i]) removedIds.add(oldStopIds.get(i));

        Map<String, String> names = new HashMap<>();
        Set<String> wanted = new LinkedHashSet<>(addedIds);
        wanted.addAll(removedIds);
        for (Stop s : stopRepository.findAllById(wanted)) names.put(s.getId(), s.getName());

        int trips = (int) tripRepository.countByRouteId(routeId);
        int buses = 0;
        for (VehiclePosition pos : vehicleStateCache.getAll())
            if (routeId.equals(pos.getRouteId())) buses++;

        return new Preview(true, oldStopIds.size(), newStopIds.size(),
                label(addedIds, names), label(removedIds, names), trips, buses);
    }

    public record Preview(boolean changed, int stopsBefore, int stopsAfter,
                          List<String> added, List<String> removed,
                          int tripsAffected, int busesReanchored) {}

    /** "Piazza San Benedetto (PSB)", o il solo id se la fermata è appena stata disegnata. */
    private static List<String> label(List<String> ids, Map<String, String> names) {
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            String n = names.get(id);
            out.add(n != null ? n + " (" + id + ")" : id);
        }
        return out;
    }

    /**
     * Sostituisce la sequenza di fermate di una linea e ri-tempifica le corse.
     *
     * @param routeId    la linea
     * @param newStopIds le fermate nel nuovo ordine di percorso
     */
    @Transactional
    public Result replacePattern(String routeId, List<String> newStopIds) {

        if (newStopIds == null || newStopIds.size() < 2)
            throw bad("A line needs at least two stops.");
        rejectConsecutiveDuplicates(newStopIds);

        // Le fermate devono esistere: un id inventato passerebbe il salvataggio
        // e riapparirebbe come percorso rotto molto più tardi.
        Map<String, Stop> stops = new HashMap<>();
        for (Stop s : stopRepository.findAllById(new LinkedHashSet<>(newStopIds)))
            stops.put(s.getId(), s);
        for (String id : newStopIds)
            if (!stops.containsKey(id)) throw bad("Unknown stop '" + id + "'.");

        List<String> oldStopIds = routePatternService.stopIds(routeId);
        if (oldStopIds.equals(newStopIds))
            return new Result(false, oldStopIds.size(), newStopIds.size(), 0, 0, 0, 0);

        // newIndex -> oldIndex per le fermate sopravvissute, -1 per le nuove.
        int[] anchorOf = alignByLcs(oldStopIds, newStopIds);

        // ── 1. Il pattern ────────────────────────────────────────────────
        routeStopRepository.deleteByRouteId(routeId);
        routeStopRepository.flush();          // il DELETE deve precedere l'INSERT: PK (route_id, seq)

        List<RouteStop> pattern = new ArrayList<>();
        for (int i = 0; i < newStopIds.size(); i++) {
            pattern.add(RouteStop.builder()
                    .routeId(routeId)
                    .stopSequence(i + 1)
                    .stopId(newStopIds.get(i))
                    .defaultOffsetSeconds(0)   // ricalcolato in fondo, dai tempi veri
                    .build());
        }
        routeStopRepository.saveAll(pattern);

        // ── 2. Le corse ──────────────────────────────────────────────────
        double[] gaps = legDistances(newStopIds, stops);
        int retimed = 0, inserted = 0, removed = 0;
        List<List<Integer>> allTimes = new ArrayList<>();

        for (Trip trip : tripRepository.findAllByRouteId(routeId)) {
            List<ScheduledStop> calls =
                    scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(trip.getId());
            if (calls.isEmpty()) continue;

            // posizione (1-based) -> orario, così com'era prima
            Map<Integer, Integer> oldTimes = new HashMap<>();
            for (ScheduledStop ss : calls) oldTimes.put(ss.getStopSequence(), ss.getArrivalSeconds());

            List<Integer> times = retime(anchorOf, oldTimes, gaps);
            allTimes.add(times);

            scheduledStopRepository.deleteByTripId(trip.getId());
            scheduledStopRepository.flush();  // UNIQUE (trip_id, stop_sequence)

            List<ScheduledStop> rewritten = new ArrayList<>();
            for (int i = 0; i < times.size(); i++) {
                rewritten.add(ScheduledStop.builder()
                        .trip(trip)
                        .stopSequence(i + 1)
                        .arrivalSeconds(times.get(i))
                        .build());
            }
            scheduledStopRepository.saveAll(rewritten);

            retimed++;
            inserted += Math.max(0, times.size() - calls.size());
            removed  += Math.max(0, calls.size() - times.size());
        }

        // ── 3. Scarti di default, dai tempi appena scritti ───────────────
        // Mediana come nella migrazione V26: una corsa con un orario battuto a
        // mano fuori scala non deve trascinare la proposta per le corse future.
        if (!allTimes.isEmpty()) {
            for (int i = 0; i < pattern.size(); i++) {
                List<Integer> offsets = new ArrayList<>();
                for (List<Integer> t : allTimes)
                    if (i < t.size()) offsets.add(t.get(i) - t.get(0));
                if (offsets.isEmpty()) continue;
                Collections.sort(offsets);
                pattern.get(i).setDefaultOffsetSeconds(Math.max(0, offsets.get(offsets.size() / 2)));
            }
            routeStopRepository.saveAll(pattern);
        }

        routePatternService.invalidate(routeId);
        int reanchored = reanchorVehicles(routeId);

        log.info("Percorso di {} ridefinito: {} -> {} fermate, {} corse ri-tempificate, {} bus riagganciati",
                routeId, oldStopIds.size(), newStopIds.size(), retimed, reanchored);

        return new Result(true, oldStopIds.size(), newStopIds.size(),
                retimed, inserted, removed, reanchored);
    }

    /**
     * Rifiuta la stessa fermata due volte di seguito.
     *
     * Un anello ripete legittimamente il capolinea in testa e in coda, quindi
     * i duplicati in sé vanno permessi. Due occorrenze CONSECUTIVE no: un bus
     * non si ferma due volte di fila nello stesso posto, e l'orario che ne
     * risulta ha due arrivi diversi per la stessa fermata.
     *
     * Il caso reale che ha motivato questo controllo: l'editor mappa aggancia
     * un vertice alla fermata esistente più vicina entro 80 m, e in centro
     * città più vertici consecutivi finiscono sulla stessa fermata senza che
     * chi disegna se ne accorga.
     */
    private void rejectConsecutiveDuplicates(List<String> stopIds) {
        for (int i = 1; i < stopIds.size(); i++) {
            if (stopIds.get(i).equals(stopIds.get(i - 1))) {
                throw bad("Stop '" + stopIds.get(i) + "' appears twice in a row (positions "
                        + i + " and " + (i + 1) + "). Two vertices snapped to the same stop: "
                        + "mark only one of them as a stop, or move the other one further away.");
            }
        }
    }

    // ── Allineamento ─────────────────────────────────────────────────────

    /**
     * Allinea la vecchia e la nuova sequenza con la sottosequenza comune più
     * lunga, restituendo per ogni posizione nuova la posizione vecchia
     * corrispondente, o -1 se la fermata è nuova.
     */
    static int[] alignByLcs(List<String> oldIds, List<String> newIds) {
        int n = oldIds.size(), m = newIds.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--)
                dp[i][j] = oldIds.get(i).equals(newIds.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);

        int[] anchor = new int[m];
        Arrays.fill(anchor, -1);
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (oldIds.get(i).equals(newIds.get(j))) { anchor[j] = i; i++; j++; }
            else if (dp[i + 1][j] >= dp[i][j + 1])   { i++; }
            else                                     { j++; }
        }
        return anchor;
    }

    /** Distanze in metri fra fermate consecutive della nuova sequenza. */
    private static double[] legDistances(List<String> stopIds, Map<String, Stop> stops) {
        double[] d = new double[Math.max(0, stopIds.size() - 1)];
        for (int i = 0; i < d.length; i++) {
            Stop a = stops.get(stopIds.get(i)), b = stops.get(stopIds.get(i + 1));
            d[i] = (a == null || b == null || a.getLat() == null || b.getLat() == null)
                    ? 0.0 : haversine(a.getLat(), a.getLon(), b.getLat(), b.getLon());
        }
        return d;
    }

    // ── Ri-tempificazione di una singola corsa ───────────────────────────

    /**
     * I nuovi orari di una corsa: invariati dove la fermata è sopravvissuta,
     * interpolati dove è nuova.
     */
    static List<Integer> retime(int[] anchorOf, Map<Integer, Integer> oldTimes, double[] gaps) {
        int m = anchorOf.length;
        Integer[] t = new Integer[m];

        List<Integer> anchors = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            if (anchorOf[j] < 0) continue;
            Integer was = oldTimes.get(anchorOf[j] + 1);   // oldTimes è 1-based
            if (was == null) continue;
            t[j] = was;
            anchors.add(j);
        }

        // Nessuna ancora: la corsa non ha più nulla in comune con il percorso.
        // Si conserva almeno la partenza e si distribuisce sulle distanze.
        if (anchors.isEmpty()) {
            int start = oldTimes.values().stream().min(Integer::compareTo).orElse(0);
            int end   = oldTimes.values().stream().max(Integer::compareTo).orElse(start + 60 * m);
            return spread(start, end, gaps, m);
        }

        // Fra due ancore: interpolazione proporzionale alla distanza.
        for (int k = 0; k + 1 < anchors.size(); k++) {
            int a = anchors.get(k), b = anchors.get(k + 1);
            if (b - a < 2) continue;
            fillBetween(t, a, b, gaps);
        }

        // Prima della prima ancora e dopo l'ultima non c'è un estremo su cui
        // interpolare: si estende con il passo medio della corsa stessa.
        double pace = paceSecondsPerMetre(t, anchors, gaps);
        for (int j = anchors.get(0) - 1; j >= 0; j--)
            t[j] = t[j + 1] - Math.max(MIN_GAP_SECONDS, (int) Math.round(gaps[j] * pace));
        for (int j = anchors.get(anchors.size() - 1) + 1; j < m; j++)
            t[j] = t[j - 1] + Math.max(MIN_GAP_SECONDS, (int) Math.round(gaps[j - 1] * pace));

        return monotonic(t);
    }

    private static void fillBetween(Integer[] t, int a, int b, double[] gaps) {
        double total = 0;
        for (int i = a; i < b; i++) total += gaps[i];
        int span = t[b] - t[a];

        double run = 0;
        for (int j = a + 1; j < b; j++) {
            run += gaps[j - 1];
            // Distanze tutte nulle (fermate sovrapposte, o coordinate mancanti):
            // ripiego sulla spaziatura uniforme, che almeno è ordinata.
            double frac = total > 0 ? run / total : (double) (j - a) / (b - a);
            t[j] = t[a] + (int) Math.round(span * frac);
        }
    }

    /** Secondi per metro della corsa, dedotti dai tratti già ancorati. */
    private static double paceSecondsPerMetre(Integer[] t, List<Integer> anchors, double[] gaps) {
        int first = anchors.get(0), last = anchors.get(anchors.size() - 1);
        double metres = 0;
        for (int i = first; i < last; i++) metres += gaps[i];
        int seconds = t[last] - t[first];
        if (metres > 0 && seconds > 0) return seconds / metres;
        return 1.0 / (20_000.0 / 3600.0);   // 20 km/h, il passo urbano tipico
    }

    private static List<Integer> spread(int start, int end, double[] gaps, int m) {
        double total = 0;
        for (double g : gaps) total += g;
        List<Integer> out = new ArrayList<>(m);
        double run = 0;
        for (int j = 0; j < m; j++) {
            if (j > 0) run += gaps[j - 1];
            double frac = total > 0 ? run / total : (double) j / Math.max(1, m - 1);
            out.add(start + (int) Math.round((end - start) * frac));
        }
        return monotonic(out.toArray(new Integer[0]));
    }

    /**
     * Forza la progressione stretta degli orari.
     *
     * L'interpolazione può produrre due arrivi allo stesso secondo quando due
     * fermate quasi coincidono. Il database lo accetterebbe, ma una corsa che
     * arriva due volte nello stesso istante rompe ogni calcolo di ritardo a
     * valle, quindi si corregge qui piuttosto che scoprirlo lì.
     */
    private static List<Integer> monotonic(Integer[] t) {
        List<Integer> out = new ArrayList<>(t.length);
        int prev = Integer.MIN_VALUE;
        for (Integer v : t) {
            int x = (v == null ? prev + MIN_GAP_SECONDS : v);
            if (x <= prev) x = prev + MIN_GAP_SECONDS;
            out.add(x);
            prev = x;
        }
        return out;
    }

    // ── Bus in strada ────────────────────────────────────────────────────

    /**
     * Azzera l'ancora di aderenza dei bus sulla linea appena modificata.
     *
     * {@code lastStopSequence} è una posizione nella sequenza, e la sequenza è
     * appena cambiata sotto i piedi del bus: quella posizione ora indica una
     * fermata diversa, e il prossimo ritardo verrebbe misurato contro l'orario
     * sbagliato. Azzerare fa ripartire la misura dalla prossima fermata
     * raggiunta — un buco di una fermata, invece di un dato falso.
     *
     * Lo stato diventa UNKNOWN, che l'interfaccia mostra già come "LIVE": il
     * bus trasmette ma non ha ancora nulla da confrontare con l'orario. È
     * esattamente la sua condizione.
     */
    private int reanchorVehicles(String routeId) {
        int touched = 0;
        for (VehiclePosition pos : vehicleStateCache.getAll()) {
            if (!routeId.equals(pos.getRouteId())) continue;

            pos.setLastStopSequence(null);
            pos.setLastStopRegisteredId(null);
            pos.setApproachStopSequence(null);
            pos.setApproachMinDistanceMetres(null);
            pos.setApproachMinTimestamp(null);
            pos.setDelayMinutes(null);
            pos.setDelayStopId(null);
            pos.setDelayStopName(null);
            pos.setDelayStopSequence(null);
            pos.setScheduleStatus(ScheduleStatus.UNKNOWN);

            vehicleStateCache.update(pos.getVehicleId(), pos);
            touched++;
        }
        if (touched > 0)
            log.info("{} bus sulla linea {} riagganciati: la misura del ritardo riparte dalla prossima fermata",
                    touched, routeId);
        return touched;
    }

    // ── Utilità ──────────────────────────────────────────────────────────

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
