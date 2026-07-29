package it.unicas.omnimove.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;

/**
 * One vertex of a route's road geometry (see V14__route_shapes.sql).
 *
 * OmniMove's local copy of CassiTrack's shape data, seeded by V15 and
 * refreshed by {@code NetexImportService} on each import.
 *
 * Used by the journey planner to draw a bus leg along the streets actually
 * travelled instead of a straight line between boarding and alighting stop.
 * Presentation only — timings still come from {@link ScheduledStop}.
 */
@Entity
@Table(name = "route_shapes")
@IdClass(RouteShape.RouteShapeId.class)
@Data
public class RouteShape {

    @Id
    @Column(name = "route_id", length = 50, nullable = false)
    private String routeId;

    /** Ascending 0-based index of this point along the path. */
    @Id
    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "lat", nullable = false)
    private Double lat;

    @Column(name = "lon", nullable = false)
    private Double lon;

    /** TRUE when this vertex is also a scheduled stop — a leg cut point. */
    @Column(name = "is_stop", nullable = false)
    private Boolean isStop;

    /** Composite primary key holder. Must be public, no-args and serializable. */
    @Data
    public static class RouteShapeId implements Serializable {
        private String routeId;
        private Integer seq;
    }
}
