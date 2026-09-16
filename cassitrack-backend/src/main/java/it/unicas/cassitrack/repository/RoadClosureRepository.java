package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.RoadClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoadClosureRepository extends JpaRepository<RoadClosure, Long> {

    Optional<RoadClosure> findByReportedByAndExternalId(String reportedBy, String externalId);

    List<RoadClosure> findByStatusTrueOrderByUpdatedAtDesc();

    /** Closures resolved after {@code since}, newest first. */
    List<RoadClosure> findByStatusFalseAndUpdatedAtAfterOrderByUpdatedAtDesc(Instant since);
}
