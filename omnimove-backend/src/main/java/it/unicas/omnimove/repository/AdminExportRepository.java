package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.AdminExport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminExportRepository extends JpaRepository<AdminExport, Long> {

    /** Newest first. Paged: a card shows the recent ones, not a career's worth. */
    List<AdminExport> findByUserIdOrderByExportedAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);
}
