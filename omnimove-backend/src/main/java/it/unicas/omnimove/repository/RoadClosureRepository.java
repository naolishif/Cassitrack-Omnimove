package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.RoadClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoadClosureRepository extends JpaRepository<RoadClosure, Long> {

    Optional<RoadClosure> findByReportedByAndExternalId(String reportedBy, String externalId);

    List<RoadClosure> findByStatusTrue();

    List<RoadClosure> findAllByOrderByCreatedAtDesc();
}
