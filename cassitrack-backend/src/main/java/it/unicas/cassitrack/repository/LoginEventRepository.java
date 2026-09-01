package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.LoginEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

    List<LoginEvent> findByUserIdOrderByLoggedInAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /** [userId, accesses] for every account that has ever signed in. */
    @Query("SELECT e.userId, COUNT(e) FROM LoginEvent e GROUP BY e.userId")
    List<Object[]> countGroupedByUser();
}
