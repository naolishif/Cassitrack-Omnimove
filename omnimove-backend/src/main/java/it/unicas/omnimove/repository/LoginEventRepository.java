package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.LoginEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoginEventRepository extends JpaRepository<LoginEvent, Long> {

    /** Newest first — the order the history modal displays. */
    List<LoginEvent> findByUserIdOrderByLoggedInAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /**
     * Access count per user, as {@code [userId, count]} pairs.
     * One query for the whole registry instead of a count per row.
     */
    @Query("SELECT e.userId, COUNT(e) FROM LoginEvent e GROUP BY e.userId")
    List<Object[]> countGroupedByUser();
}
