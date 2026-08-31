package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.RouteStop;
import it.unicas.cassitrack.repository.RouteStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accesso in memoria al pattern di fermate delle linee (V26).
 *
 * Perché una cache. Dopo la normalizzazione la fermata di una riga di orario
 * si ottiene da route_stops, e i chiamanti che ne hanno bisogno sono i due
 * percorsi più caldi del sistema: RouteMatchingService ed ETAService, invocati
 * a ogni messaggio GPS di ogni bus. Risolverla con una query per riga sarebbe
 * il classico N+1 in un punto dove non ce lo si può permettere.
 *
 * Tenerla in memoria è praticabile perché il pattern è minuscolo e quasi
 * immutabile: 142 righe per l'intera rete, che cambiano solo quando un gestore
 * modifica il percorso di una linea. Quel momento passa da
 * {@link #invalidate(String)}.
 *
 * Nota: questo servizio sostituisce anche una query che c'era già e costava —
 * stopAtSequence() ricaricava l'intera sequenza della corsa dal database per
 * ogni singola chiamata.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoutePatternService {

    private final RouteStopRepository routeStopRepository;

    /** Una posizione del pattern, senza il peso dell'entità JPA. */
    public record PatternStop(int stopSequence, String stopId, int defaultOffsetSeconds) {}

    /**
     * routeId → sequenza ordinata. ConcurrentHashMap perché il gestore può
     * modificare una linea mentre il gestore MQTT sta leggendo il pattern di
     * un'altra; le liste dentro sono immutabili, quindi chi ne ha già una in
     * mano continua a leggere una fotografia coerente anche durante un
     * invalidate.
     */
    private final Map<String, List<PatternStop>> cache = new ConcurrentHashMap<>();

    /** La sequenza di fermate di una linea, in ordine. Mai null. */
    public List<PatternStop> pattern(String routeId) {
        if (routeId == null) return List.of();
        return cache.computeIfAbsent(routeId, id ->
                routeStopRepository.findByRouteIdOrderByStopSequenceAsc(id).stream()
                        .map(rs -> new PatternStop(rs.getStopSequence(), rs.getStopId(),
                                rs.getDefaultOffsetSeconds() != null ? rs.getDefaultOffsetSeconds() : 0))
                        .toList());
    }

    /** La fermata in una data posizione, o null se la posizione non esiste. */
    public String stopIdAt(String routeId, int stopSequence) {
        for (PatternStop ps : pattern(routeId)) {
            if (ps.stopSequence() == stopSequence) return ps.stopId();
        }
        return null;
    }

    /** Gli id di fermata in ordine di percorso. */
    public List<String> stopIds(String routeId) {
        return pattern(routeId).stream().map(PatternStop::stopId).toList();
    }

    /** La posizione del capolinea, o 0 se la linea non ha pattern. */
    public int lastSequence(String routeId) {
        List<PatternStop> p = pattern(routeId);
        return p.isEmpty() ? 0 : p.get(p.size() - 1).stopSequence();
    }

    /**
     * Il pattern di più linee in una sola query.
     *
     * Per le viste che ne elencano molte — export CSV, NeTEx, tab Trips —
     * dove chiamare pattern() in un ciclo su una cache fredda darebbe una
     * query per linea.
     */
    public Map<String, List<PatternStop>> patterns(Collection<String> routeIds) {
        if (routeIds == null || routeIds.isEmpty()) return Map.of();

        Map<String, List<PatternStop>> out = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String id : routeIds) {
            List<PatternStop> cached = cache.get(id);
            if (cached != null) out.put(id, cached); else missing.add(id);
        }
        if (missing.isEmpty()) return out;

        Map<String, List<PatternStop>> loaded = new HashMap<>();
        for (RouteStop rs : routeStopRepository
                .findByRouteIdInOrderByRouteIdAscStopSequenceAsc(missing)) {
            loaded.computeIfAbsent(rs.getRouteId(), k -> new ArrayList<>())
                  .add(new PatternStop(rs.getStopSequence(), rs.getStopId(),
                          rs.getDefaultOffsetSeconds() != null ? rs.getDefaultOffsetSeconds() : 0));
        }
        loaded.forEach((id, list) -> {
            List<PatternStop> frozen = List.copyOf(list);
            cache.put(id, frozen);
            out.put(id, frozen);
        });
        // Una linea senza pattern va messa in cache come vuota, altrimenti la
        // si riinterroga a ogni giro sperando che sia comparso qualcosa.
        for (String id : missing) {
            if (!out.containsKey(id)) { cache.put(id, List.of()); out.put(id, List.of()); }
        }
        return out;
    }

    /** Da chiamare dopo ogni modifica al percorso di una linea. */
    public void invalidate(String routeId) {
        if (routeId == null) return;
        cache.remove(routeId);
        log.debug("Pattern della linea {} invalidato", routeId);
    }

    public void invalidateAll() {
        cache.clear();
        log.debug("Cache dei pattern svuotata");
    }
}
