package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Il pattern di fermate di una linea (V27). Vedi {@link RouteStop}.
 */
public interface RouteStopRepository extends JpaRepository<RouteStop, RouteStop.Key> {

    List<RouteStop> findByRouteIdOrderByStopSequenceAsc(String routeId);

    /**
     * Il pattern di più linee in una sola query.
     *
     * Esiste per le viste che elencano molte linee — l'export CSV, il NeTEx,
     * la tab Trips. Chiamare findByRouteId in un ciclo darebbe una query per
     * linea, che su 18 linee è il classico N+1 che non si nota in sviluppo e
     * si nota in aula.
     */
    List<RouteStop> findByRouteIdInOrderByRouteIdAscStopSequenceAsc(Collection<String> routeIds);

    /**
     * Il pattern completo, unito ai dati della fermata.
     *
     * Colonne: [0] routeId, [1] stopSequence, [2] stopId, [3] stopName,
     *          [4] lat, [5] lon, [6] defaultOffsetSeconds
     */
    @Query("""
        SELECT rs.routeId, rs.stopSequence, rs.stopId, s.name, s.lat, s.lon,
               rs.defaultOffsetSeconds
        FROM RouteStop rs, Stop s
        WHERE rs.stopId = s.id
          AND (:routeIds IS NULL OR rs.routeId IN :routeIds)
        ORDER BY rs.routeId, rs.stopSequence
        """)
    List<Object[]> findPatternWithStops(@Param("routeIds") Collection<String> routeIds);

    /**
     * Quante linee passano da questa fermata.
     *
     * La FK di route_stops è ON DELETE RESTRICT, quindi il database rifiuterebbe
     * comunque la cancellazione. Questo conteggio serve a dirlo PRIMA, con un
     * messaggio comprensibile, invece di lasciar arrivare un vincolo violato.
     */
    long countByStopId(String stopId);

    @Query("SELECT DISTINCT rs.routeId FROM RouteStop rs WHERE rs.stopId = :stopId")
    List<String> findRouteIdsServing(@Param("stopId") String stopId);

    /** Ultima posizione della sequenza, cioè il capolinea. */
    @Query("SELECT MAX(rs.stopSequence) FROM RouteStop rs WHERE rs.routeId = :routeId")
    Integer findLastSequence(@Param("routeId") String routeId);

    // Niente @Modifying: quell'annotazione serve alle query dichiarate con
    // @Query, non ai metodi derivati dal nome — come deleteByTripId qui
    // accanto in ScheduledStopRepository, che infatti non ce l'ha. Deve solo
    // girare in transazione, e il chiamante (RoutePatternEditService) lo è.
    void deleteByRouteId(String routeId);
}
