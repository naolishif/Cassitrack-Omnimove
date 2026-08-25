package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.FavoriteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FavoriteStopRepository extends JpaRepository<FavoriteStop, Long> {

    List<FavoriteStop> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<FavoriteStop> findByUserIdAndStopId(Long userId, String stopId);
}
