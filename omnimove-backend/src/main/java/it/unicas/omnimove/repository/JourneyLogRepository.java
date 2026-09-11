package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.JourneyLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
public interface JourneyLogRepository extends JpaRepository<JourneyLog, Long> {
    List<JourneyLog> findByUserId(Long userId);
    List<JourneyLog> findByUserIdAndCreatedAtAfter(Long userId, ZonedDateTime since);

    /**
     * La corsa ancora aperta di questo utente, la piu' recente.
     *
     * completed IS NULL significa "in corso": TRUE e FALSE sono due modi di
     * essere chiusa. Si prende la piu' recente perche' una corsa dimenticata
     * aperta ieri non deve intercettare la chiusura del viaggio di oggi.
     */
    java.util.Optional<JourneyLog>
        findFirstByUserIdAndCompletedIsNullOrderByCreatedAtDesc(Long userId);

    /** Returns [originName, destName, count, avgGreenIndex] for the top N routes. */
    @Query("""
        SELECT j.originName, j.destName,
               COUNT(j)            AS uses,
               AVG(j.greenIndex)   AS avgGi
        FROM   JourneyLog j
        WHERE  j.createdAt > :since
          AND  j.createdAt <= :until
          AND  j.originName IS NOT NULL
          AND  j.destName   IS NOT NULL
        GROUP BY j.originName, j.destName
        ORDER BY uses DESC
        """)
    /**
     * Busiest routes inside a window.
     *
     * <p>An upper bound as well as a lower one: with only {@code since} the list
     * always ran to now, so a custom period picked in the dashboard would show
     * its start honoured and its end ignored — the one table on the page that
     * quietly disagreed with the others.
     */
    List<Object[]> findTopRoutes(@Param("since") ZonedDateTime since,
                                 @Param("until") ZonedDateTime until,
                                 Pageable pageable);
}