package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.DataVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Read access to the static-data change counters (see V15__data_version.sql).
 *
 * findAll() returns one row per watched table — five rows — so the version
 * endpoint costs a single tiny query no matter how large routes, trips or
 * scheduled_stops become. That is the whole point of the table.
 */
@Repository
public interface DataVersionRepository extends JpaRepository<DataVersion, String> {
}
