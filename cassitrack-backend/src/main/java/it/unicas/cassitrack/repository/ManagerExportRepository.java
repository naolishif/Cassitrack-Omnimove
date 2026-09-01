package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.ManagerExport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ManagerExportRepository extends JpaRepository<ManagerExport, Long> {

    List<ManagerExport> findByUserIdOrderByExportedAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /** [userId, downloads] for every account that has ever exported anything. */
    @Query("SELECT x.userId, COUNT(x) FROM ManagerExport x GROUP BY x.userId")
    List<Object[]> countGroupedByUser();
}
