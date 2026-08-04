package it.unicas.cassitrack.repository;

import org.springframework.data.repository.query.Param;
import it.unicas.cassitrack.model.ScheduledStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ScheduledStopRepository extends JpaRepository<ScheduledStop, Long> {

    List<ScheduledStop> findByTripId(String tripId);
    List<ScheduledStop> findByTripRouteIdOrderByStopSequenceAsc(String routeId);
    @Query("SELECT ss FROM ScheduledStop ss " +
            "WHERE ss.trip.id = (SELECT MIN(t.id) FROM Trip t WHERE t.route.id = :routeId) " +
            "ORDER BY ss.stopSequence")
    List<ScheduledStop> findRepresentativeSequence(@Param("routeId") String routeId);

    List<ScheduledStop> findByTripIdOrderByStopSequenceAsc(String tripId);

    @Query("""
        SELECT ss FROM ScheduledStop ss
        JOIN FETCH ss.trip t
        JOIN FETCH t.route r
        WHERE ss.stopId = :stopId
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

    @Query("SELECT ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName, " +
            "s.id, s.name, s.lat, s.lon, MIN(ss.stopSequence) " +
            "FROM ScheduledStop ss, Stop s " +
            "WHERE ss.stopId = s.id AND s.active = true AND ss.trip.route.active = true " +
            "GROUP BY ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName, " +
            "s.id, s.name, s.lat, s.lon " +
            "ORDER BY ss.trip.route.id, MIN(ss.stopSequence)")
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

    // FK guard for stop deletion: scheduled_stops.stop_id references stops(id)
    // ON DELETE CASCADE — a delete would silently wipe timetable rows, so the
    // controller counts references and refuses instead of relying on the DB.
    long countByStopId(String stopId);

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
     * Every call of every trip, joined with route, bus and stop — the exploded
     * timetable (one row per trip+stop), as exported to CSV.
     *
     * Stop is joined on the id because ScheduledStop keeps stopId as a plain
     * column, not as a JPA relation (same approach as findStopsGroupedByRoute).
     *
     * Columns: [0] tripId, [1] routeId, [2] routeShortName, [3] routeLongName,
     *          [4] busId, [5] targa, [6] stopSequence, [7] stopId,
     *          [8] stopName, [9] arrivalSeconds
     */
    @Query("""
        SELECT ss.trip.id, ss.trip.route.id, ss.trip.route.shortName, ss.trip.route.longName,
               ss.trip.bus.busId, ss.trip.bus.targa,
               ss.stopSequence, ss.stopId, s.name, ss.arrivalSeconds
        FROM ScheduledStop ss, Stop s
        WHERE ss.stopId = s.id
        ORDER BY ss.trip.route.id, ss.trip.id, ss.stopSequence
        """)
    List<Object[]> findAllStopTimes();
}
