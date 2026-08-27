package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.ScheduledStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduledStopRepository extends JpaRepository<ScheduledStop, Long> {

    List<ScheduledStop> findByTripId(String tripId);

    /**
     * Every stop a bus can carry you to, leaving from `origin`. One query instead
     * of asking pair by pair: the combined planner needs the whole reachable set
     * to pick an interchange, and forty round trips to answer that would cost more
     * than the plan itself.
     */
    @Query("""
    SELECT DISTINCT sd.stopId
    FROM ScheduledStop so
         JOIN so.trip t
         JOIN t.route r,
         ScheduledStop sd
    WHERE sd.trip = t
      AND so.stopId = :origin
      AND so.stopSequence < sd.stopSequence
      AND r.active = true
    """)
    List<String> findStopsReachableFrom(@Param("origin") String origin);

    /** The mirror image: every stop from which a bus reaches `dest`. */
    @Query("""
    SELECT DISTINCT so.stopId
    FROM ScheduledStop so
         JOIN so.trip t
         JOIN t.route r,
         ScheduledStop sd
    WHERE sd.trip = t
      AND sd.stopId = :dest
      AND so.stopSequence < sd.stopSequence
      AND r.active = true
    """)
    List<String> findStopsConnectingTo(@Param("dest") String dest);

    // ── Linea diretta origine → dest ───────────────────────────────
    @Query("""
    SELECT r.shortName AS shortName, r.longName AS longName,
           r.id AS routeId,
           so.arrivalSeconds AS originSec, sd.arrivalSeconds AS destSec, t.id AS tripId
    FROM ScheduledStop so
         JOIN so.trip t
         JOIN t.route r,
         ScheduledStop sd
    WHERE sd.trip = t
      AND so.stopId = :origin
      AND sd.stopId = :dest
      AND so.stopSequence < sd.stopSequence
      AND r.active = true
    ORDER BY (sd.arrivalSeconds - so.arrivalSeconds)
    """)
    List<ConnectingLine> findLinesConnecting(@Param("origin") String origin,
                                             @Param("dest") String dest);

    interface ConnectingLine {
        String getShortName();
        String getLongName();
        Integer getOriginSec();
        Integer getDestSec();
        String getTripId();
        String getRouteId();
    }

    // ── Soluzione con un cambio: origine → X → dest ────────────────
    @Query(value = """
        SELECT
            ss_x1.stop_id AS "transferStop",
            r1.short_name AS "l1Short",
            r1.long_name  AS "l1Long",
            r1.id         AS "l1RouteId",
            ss_o.trip_id  AS "l1TripId",
            (ss_x1.arrival_seconds - ss_o.arrival_seconds) AS "l1Sec",
            r2.short_name AS "l2Short",
            r2.long_name  AS "l2Long",
            r2.id         AS "l2RouteId",
            ss_x2.trip_id AS "l2TripId",
            (ss_d.arrival_seconds - ss_x2.arrival_seconds) AS "l2Sec"
        FROM scheduled_stops ss_o
        JOIN trips  t1 ON t1.id = ss_o.trip_id
        JOIN routes r1 ON r1.id = t1.route_id
        JOIN scheduled_stops ss_x1 ON ss_x1.trip_id = ss_o.trip_id
                                  AND ss_x1.stop_sequence > ss_o.stop_sequence
        JOIN scheduled_stops ss_x2 ON ss_x2.stop_id = ss_x1.stop_id
        JOIN trips  t2 ON t2.id = ss_x2.trip_id
        JOIN routes r2 ON r2.id = t2.route_id
        JOIN scheduled_stops ss_d ON ss_d.trip_id = ss_x2.trip_id
                                 AND ss_d.stop_sequence > ss_x2.stop_sequence
        WHERE ss_o.stop_id = :origin
          AND ss_d.stop_id = :dest
          AND ss_x1.stop_id <> :origin
          AND ss_x1.stop_id <> :dest
          AND t1.route_id <> t2.route_id
        ORDER BY (ss_x1.arrival_seconds - ss_o.arrival_seconds)
               + (ss_d.arrival_seconds - ss_x2.arrival_seconds)
        LIMIT 1
        """, nativeQuery = true)
    List<TransferRoute> findBestTransfer(@Param("origin") String origin,
                                         @Param("dest")   String dest);

    interface TransferRoute {
        String  getTransferStop();
        String  getL1Short();
        String  getL1Long();
        Integer getL1Sec();
        String  getL1RouteId();
        String  getL2Short();
        String  getL2Long();
        Integer getL2Sec();
        String  getL2RouteId();
        String  getL1TripId();
        String  getL2TripId();
    }

    /**
     * Tutte le fermate schedulate a uno stop specifico per una linea (routeShort),
     * ordinate per ora di arrivo. Usato come fallback DB in waitMinutesFromSchedule.
     */
    @Query("""
    SELECT ss FROM ScheduledStop ss
    JOIN ss.trip t
    JOIN t.route r
    WHERE ss.stopId    = :stopId
      AND r.shortName  = :routeShort
    ORDER BY ss.arrivalSeconds
    """)
    List<ScheduledStop> findByStopIdAndRouteShort(@Param("stopId")     String stopId,
                                                  @Param("routeShort") String routeShort);

    /**
     * Tutte le fermate schedulate a uno stop, ordinate per ora di arrivo.
     * Usato come fallback DB quando routeShort non è disponibile.
     */
    @Query("""
    SELECT ss FROM ScheduledStop ss
    WHERE ss.stopId = :stopId
    ORDER BY ss.arrivalSeconds
    """)
    List<ScheduledStop> findByStopId(@Param("stopId") String stopId);

    /**
     * Distinct route short-names that serve a given stop — used to show
     * which lines call at each stop in the route stop-list panel.
     */
    @Query("""
    SELECT DISTINCT t.route.shortName
    FROM ScheduledStop ss
    JOIN ss.trip t
    WHERE ss.stopId = :stopId
      AND t.route.active = true
    """)
    List<String> findRouteShortNamesByStopId(@Param("stopId") String stopId);

    /**
     * One representative trip for a route — used to get the ordered stop list.
     */
    @Query("""
    SELECT ss FROM ScheduledStop ss
    JOIN ss.trip t
    WHERE t.route.id = :routeId
    ORDER BY ss.stopSequence
    """)
    List<ScheduledStop> findStopsForRoute(@Param("routeId") String routeId);
}