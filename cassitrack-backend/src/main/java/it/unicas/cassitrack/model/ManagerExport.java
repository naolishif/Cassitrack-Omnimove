package it.unicas.cassitrack.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A file taken out of the system by an operator.
 *
 * <p>Nothing of the file itself is kept — only what it covered. That is enough
 * to answer the question the card exists for ("what has left, and how much of
 * it") without the register becoming a second copy of the data it describes.
 */
@Entity
@Table(name = "manager_exports")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ManagerExport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Which table: Buses, Stops, Routes, Timetable, Fleet analytics… */
    @Column(nullable = false, length = 60)
    private String dataset;

    /** csv | xlsx | pdf */
    @Column(nullable = false, length = 10)
    private String format;

    /** How many rows left with it: a bulk download should not look like a peek. */
    @Column(name = "row_count", nullable = false)
    private Integer rowCount;

    /** The filters in effect, as the screen worded them. */
    @Column(length = 255)
    private String detail;

    @Column(name = "exported_at", nullable = false)
    private LocalDateTime exportedAt;
}
