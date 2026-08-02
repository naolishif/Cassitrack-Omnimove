package it.unicas.cassitrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Change counter for one static-data table (see V15__data_version.sql).
 *
 * Maintained entirely by database triggers, never written from Java — so it
 * also reflects changes made by Flyway migrations or by hand in psql.
 * OmniMove polls these rows to decide whether a NeTEx re-import is needed.
 */
@Entity
@Table(name = "data_version")
@Data
public class DataVersion {

    @Id
    @Column(name = "table_name", length = 40, nullable = false)
    private String tableName;

    /** Incremented once per statement that changes the watched table. */
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
