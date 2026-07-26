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

    // FK guard for stop deletion: scheduled_stops.stop_id references stops(id)
    // ON DELETE CASCADE — a delete would silently wipe timetable rows, so the
    // controller counts references and refuses instead of relying on the DB.
    long countByStopId(String stopId);

}