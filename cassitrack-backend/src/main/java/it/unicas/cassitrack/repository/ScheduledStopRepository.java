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

    // FK guard for stop deletion: scheduled_stops.stop_id references stops(id)
    // ON DELETE CASCADE — a delete would silently wipe timetable rows, so the
    // controller counts references and refuses instead of relying on the DB.
    long countByStopId(String stopId);
}
