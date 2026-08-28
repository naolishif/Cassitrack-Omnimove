package it.unicas.cassitrack.repository;

import org.springframework.data.repository.query.Param;
import it.unicas.cassitrack.model.ScheduledStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface ScheduledStopRepository extends JpaRepository<ScheduledStop, Long> {

    List<ScheduledStop> findByTripId(String tripId);
    List<ScheduledStop> findByTripRouteIdOrderByStopSequenceAsc(String routeId);
    // JOIN FETCH su trip e route: da V24 la fermata di una riga di orario si
    // ricava dal pattern della LINEA, quindi chi legge queste righe deve poter
    // risalire alla linea. Trip.route e ScheduledStop.trip sono entrambi LAZY,
    // e i chiamanti principali — RouteMatchingService, ETAService,
    // TripResolutionService — girano nel gestore MQTT, cioè FUORI da una
    // sessione Hibernate. Senza il fetch esplicito la navigazione fallisce con
    // LazyInitializationException a ogni messaggio GPS.
    @Query("SELECT ss FROM ScheduledStop ss " +
            "JOIN FETCH ss.trip t JOIN FETCH t.route " +
            "WHERE ss.trip.id = (SELECT MIN(x.id) FROM Trip x WHERE x.route.id = :routeId) " +
            "ORDER BY ss.stopSequence")
    List<ScheduledStop> findRepresentativeSequence(@Param("routeId") String routeId);

    @Query("SELECT ss FROM ScheduledStop ss " +
            "JOIN FETCH ss.trip t JOIN FETCH t.route " +
            "WHERE ss.trip.id = :tripId " +
            "ORDER BY ss.stopSequence")
    List<ScheduledStop> findByTripIdOrderByStopSequenceAsc(@Param("tripId") String tripId);

    /**
     * Prossimi passaggi a una fermata.
     *
     * Da V24 la fermata non è più una colonna di ScheduledStop: si arriva a
     * lei attraverso la linea della corsa, in RouteStop, confrontando la
     * posizione nella sequenza.
     */
    // Join esplicita con ON, non una join implicita per virgola: mescolare
    // JOIN FETCH e prodotto cartesiano nella stessa query è ambiguo e alcuni
    // provider lo rifiutano. Qui il FETCH serve al chiamante (StopController
    // legge trip.route per etichettare il passaggio), la join su RouteStop
    // serve solo a filtrare per fermata.
    @Query("""
        SELECT ss FROM ScheduledStop ss
        JOIN FETCH ss.trip t
        JOIN FETCH t.route r
        JOIN RouteStop rs
          ON rs.routeId = r.id AND rs.stopSequence = ss.stopSequence
        WHERE rs.stopId = :stopId
          AND ss.arrivalSeconds >= :fromSeconds
        ORDER BY ss.arrivalSeconds ASC
        """)
    List<ScheduledStop> findUpcomingByStopId(
            @Param("stopId")      String stopId,
            @Param("fromSeconds") int    fromSeconds);

    @Query("""
    SELECT ss.trip.id FROM ScheduledStop ss
    WHERE ss.trip.route.id = :routeId
    GROUP BY ss.trip.id
    HAVING MIN(ss.arrivalSeconds) <= :now AND MAX(ss.arrivalSeconds) >= :now
    ORDER BY MIN(ss.arrivalSeconds) DESC
""")
    List<String> findActiveTripIds(@Param("routeId") String routeId, @Param("now") int now);
    @Query("SELECT s.trip.route.id, MIN(s.arrivalSeconds), MAX(s.arrivalSeconds) " +
            "FROM ScheduledStop s GROUP BY s.trip.route.id")
    List<Object[]> findOperatingHoursByRoute();

    /**
     * Le fermate di ogni linea attiva, in ordine di percorso.
     *
     * Da V24 legge direttamente il pattern invece di dedurlo dalle corse. Il
     * GROUP BY che serviva a collassare le 1884 righe duplicate in 142 non ha
     * più ragione di esistere: la tabella contiene già una riga per posizione.
     * Ne segue anche che una linea senza corse programmate compare comunque,
     * col suo percorso — prima spariva, il che era sbagliato.
     */
    @Query("""
        SELECT r.id, r.shortName, r.longName,
               s.id, s.name, s.lat, s.lon, rs.stopSequence
        FROM RouteStop rs, Stop s, Route r
        WHERE rs.stopId  = s.id
          AND rs.routeId = r.id
          AND s.active   = true
          AND r.active   = true
        ORDER BY r.id, rs.stopSequence
        """)
    List<Object[]> findStopsGroupedByRoute();

    /**
     * Trips assigned to this bus whose service window covers {@code now}.
     *
     * Mirrors exactly the query the GPS simulator used to run before it stopped
     * publishing trip_id. The schedule lives in Postgres — the bus never needs
     * to tell us which trip it is running.
     *
     * Columns: [0] tripId, [1] routeId, [2] routeShortName, [3] routeLongName,
     *          [4] startSeconds, [5] endSeconds
     * Ordered by latest departure first (the trip that started most recently).
     */
    @Query("""
        SELECT ss.trip.id, ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName,
               MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds)
        FROM ScheduledStop ss
        WHERE ss.trip.bus.busId = :busId
        GROUP BY ss.trip.id, ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName
        HAVING MIN(ss.arrivalSeconds) <= :now AND MAX(ss.arrivalSeconds) >= :now
        ORDER BY MIN(ss.arrivalSeconds) DESC
        """)
    List<Object[]> findActiveTripsForBus(@Param("busId") Integer busId, @Param("now") int now);

    /**
     * Every trip in service right now, across the whole fleet — the same window
     * as findActiveTripsForBus but not filtered to one vehicle.
     *
     * A trip counts as running when the current time of day falls between its
     * first and last scheduled arrival. Note this is the TIMETABLE's view: it
     * lists what is supposed to be running, which is exactly what an operations
     * view needs — a scheduled trip with no bus reporting is itself the
     * interesting case, and would be invisible if this were driven by telemetry.
     *
     * Columns: [0] tripId, [1] routeId, [2] routeShortName, [3] routeLongName,
     *          [4] startSeconds, [5] endSeconds, [6] busId, [7] totalStops
     * Ordered by departure time, earliest first.
     *
     * The joins are spelled out as LEFT JOINs on purpose. Writing the path
     * inline (ss.trip.bus.busId) would make Hibernate emit an INNER JOIN, which
     * would silently drop exactly the rows this view exists to surface: trips
     * with no bus assigned. [6] is therefore null for an unassigned trip.
     */
    @Query("""
        SELECT t.id, r.id, r.shortName, r.longName,
               MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds),
               b.busId, COUNT(ss)
        FROM ScheduledStop ss
             JOIN ss.trip t
             LEFT JOIN t.route r
             LEFT JOIN t.bus b
        GROUP BY t.id, r.id, r.shortName, r.longName, b.busId
        HAVING MIN(ss.arrivalSeconds) <= :now AND MAX(ss.arrivalSeconds) >= :now
        ORDER BY MIN(ss.arrivalSeconds) ASC
        """)
    List<Object[]> findActiveTrips(@Param("now") int now);

    /**
     * The same summary rows as {@link #findActiveTrips}, for specific trips.
     *
     * Needed because a trip that finished early has already fallen outside the
     * "now" window, yet the Active Trips view keeps showing it for a few minutes
     * and still needs its route, bus and scheduled times to render the row.
     *
     * Columns: identical to findActiveTrips.
     */
    @Query("""
        SELECT t.id, r.id, r.shortName, r.longName,
               MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds),
               b.busId, COUNT(ss)
        FROM ScheduledStop ss
             JOIN ss.trip t
             LEFT JOIN t.route r
             LEFT JOIN t.bus b
        WHERE t.id IN :tripIds
        GROUP BY t.id, r.id, r.shortName, r.longName, b.busId
        ORDER BY MIN(ss.arrivalSeconds) ASC
        """)
    List<Object[]> findTripSummaries(@Param("tripIds") Collection<String> tripIds);

    /**
     * Every trip in the timetable, in departure order.
     *
     * The timetable has no service date — arrival_seconds is seconds since
     * midnight — so "every trip" IS the service day, the same one every day.
     * That is what the Trips tab lists. It is a bounded set (tens of rows for
     * this network), so it is fetched whole and filtered in the browser rather
     * than pushing status, route, bus and free-text filters into SQL.
     *
     * Columns: identical to findActiveTrips.
     */
    @Query("""
        SELECT t.id, r.id, r.shortName, r.longName,
               MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds),
               b.busId, COUNT(ss)
        FROM ScheduledStop ss
             JOIN ss.trip t
             LEFT JOIN t.route r
             LEFT JOIN t.bus b
        GROUP BY t.id, r.id, r.shortName, r.longName, b.busId
        ORDER BY MIN(ss.arrivalSeconds) ASC
        """)
    List<Object[]> findAllTripSummaries();

    /**
     * Buses already working a trip that overlaps the window [from, to].
     *
     * Two trips overlap when each starts before the other ends — the standard
     * interval test. Used to decide which vehicles are genuinely free to take
     * over a trip, rather than merely idle at this instant.
     *
     * Returns one row per overlapping trip, so a bus with several appears
     * several times; the caller deduplicates.
     */
    @Query("""
        SELECT b.busId
        FROM ScheduledStop ss
             JOIN ss.trip t
             JOIN t.bus b
        WHERE t.id <> :excludeTripId
        GROUP BY t.id, b.busId
        HAVING MIN(ss.arrivalSeconds) <= :to AND MAX(ss.arrivalSeconds) >= :from
        """)
    List<Integer> findBusIdsBusyBetween(@Param("from") int from,
                                        @Param("to") int to,
                                        @Param("excludeTripId") String excludeTripId);

    /**
     * Trips of one bus that would clash with the window [from, to].
     *
     * Used before moving a departure. LINEA_3 is the cautionary tale: its
     * timetable asked one vehicle to start a run four minutes before the
     * previous one ended, and the resulting confusion took a long time to
     * trace. Rescheduling by hand should not be able to recreate that.
     *
     * Columns: [0] tripId, [1] startSeconds, [2] endSeconds
     */
    @Query("""
        SELECT t.id, MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds)
        FROM ScheduledStop ss
             JOIN ss.trip t
             JOIN t.bus b
        WHERE b.busId = :busId AND t.id <> :excludeTripId
        GROUP BY t.id
        HAVING MIN(ss.arrivalSeconds) <= :to AND MAX(ss.arrivalSeconds) >= :from
        ORDER BY MIN(ss.arrivalSeconds) ASC
        """)
    List<Object[]> findConflictingTrips(@Param("busId") Integer busId,
                                        @Param("from") int from,
                                        @Param("to") int to,
                                        @Param("excludeTripId") String excludeTripId);

    // Il guardiano sulla cancellazione di una fermata è passato a
    // RouteStopRepository.countByStopId: da V24 è route_stops a referenziare
    // stops, ed è quella FK (ON DELETE RESTRICT) che ora protegge la rete.
    // Qui non resta nulla da contare: scheduled_stops non conosce le fermate.

    /**
     * Trips in service RIGHT NOW, for every bus at once.
     *
     * Same rule as findActiveTripsForBus (the service window must contain
     * {@code now}) but aggregated over the whole fleet, so the registry table
     * can answer "which line is this bus running?" for every row with one
     * query instead of one per bus.
     *
     * Columns: [0] busId, [1] routeId, [2] routeShortName, [3] routeLongName
     * Ordered by latest departure first, so the first row per bus wins.
     */
    @Query("""
        SELECT ss.trip.bus.busId, ss.trip.route.id,
               ss.trip.route.shortName, ss.trip.route.longName
        FROM ScheduledStop ss
        GROUP BY ss.trip.id, ss.trip.bus.busId, ss.trip.route.id,
                 ss.trip.route.shortName, ss.trip.route.longName
        HAVING MIN(ss.arrivalSeconds) <= :now AND MAX(ss.arrivalSeconds) >= :now
        ORDER BY MIN(ss.arrivalSeconds) DESC
        """)
    List<Object[]> findActiveTripsForAllBuses(@Param("now") int now);

    /**
     * Every route each bus serves according to the timetable (the whole day,
     * not just now). Used as the fallback label when a bus is outside service
     * hours — a bus can serve several lines, hence a list per bus.
     *
     * Columns: [0] busId, [1] routeId, [2] routeShortName, [3] routeLongName
     */
    @Query("""
        SELECT DISTINCT t.bus.busId, t.route.id, t.route.shortName, t.route.longName
        FROM Trip t
        """)
    List<Object[]> findScheduledRoutesPerBus();

    // ── Timetable management ────────────────────────────────────────────────

    /**
     * Summary of every trip for the timetable table: one row per trip with its
     * service window and stop count, so the list needs a single query.
     *
     * Columns: [0] tripId, [1] routeId, [2] routeShortName, [3] routeLongName,
     *          [4] busId, [5] targa, [6] startSeconds, [7] endSeconds, [8] stopCount
     */
    @Query("""
        SELECT ss.trip.id, ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName,
               ss.trip.bus.busId, ss.trip.bus.targa,
               MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds), COUNT(ss)
        FROM ScheduledStop ss
        GROUP BY ss.trip.id, ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName,
                 ss.trip.bus.busId, ss.trip.bus.targa
        ORDER BY MIN(ss.arrivalSeconds)
        """)
    List<Object[]> findTripSummaries();

    /**
     * Service window of every trip already assigned to a bus — used to reject
     * a new trip that would overlap one the same bus is already running.
     *
     * Columns: [0] tripId, [1] startSeconds, [2] endSeconds
     */
    @Query("""
        SELECT ss.trip.id, MIN(ss.arrivalSeconds), MAX(ss.arrivalSeconds)
        FROM ScheduledStop ss
        WHERE ss.trip.bus.busId = :busId
        GROUP BY ss.trip.id
        """)
    List<Object[]> findServiceWindowsForBus(@Param("busId") Integer busId);

    /** Delete every scheduled stop of a trip (used when rebuilding its times). */
    void deleteByTripId(String tripId);

    /**
     * Coordinates of a trip's LAST call — its terminus.
     *
     * TripResolutionService uses it to tell "this bus is late but still running"
     * from "this bus has arrived": a trip is only released once the vehicle is
     * physically at its terminus, not merely because the clock passed its
     * scheduled end.
     *
     * Columns: [0] stopId, [1] lat, [2] lon
     */
    @Query("""
        SELECT rs.stopId, s.lat, s.lon
        FROM ScheduledStop ss, RouteStop rs, Stop s
        WHERE ss.trip.id      = :tripId
          AND rs.routeId      = ss.trip.route.id
          AND rs.stopSequence = ss.stopSequence
          AND rs.stopId       = s.id
          AND ss.stopSequence = (SELECT MAX(x.stopSequence) FROM ScheduledStop x
                                 WHERE x.trip.id = :tripId)
        """)
    List<Object[]> findTerminusOf(@Param("tripId") String tripId);

    /**
     * Every call of every trip, joined with route, bus and stop — the exploded
     * timetable (one row per trip+stop), as exported to CSV.
     *
     * Da V24 la fermata arriva dal pattern della linea (RouteStop), non dalla
     * riga di orario: è la join che ricostruisce la vista che scheduled_stops
     * offriva da sola prima della normalizzazione.
     *
     * Columns: [0] tripId, [1] routeId, [2] routeShortName, [3] routeLongName,
     *          [4] busId, [5] targa, [6] stopSequence, [7] stopId,
     *          [8] stopName, [9] arrivalSeconds
     */
    @Query("""
        SELECT ss.trip.id, ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName,
               ss.trip.bus.busId, ss.trip.bus.targa,
               ss.stopSequence, rs.stopId, s.name, ss.arrivalSeconds
        FROM ScheduledStop ss, RouteStop rs, Stop s
        WHERE rs.routeId      = ss.trip.route.id
          AND rs.stopSequence = ss.stopSequence
          AND rs.stopId       = s.id
        ORDER BY ss.trip.route.id, ss.trip.id, ss.stopSequence
        """)
    List<Object[]> findAllStopTimes();
}
