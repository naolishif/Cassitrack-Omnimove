package it.unicas.cassitrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;

/**
 * One vertex of a route's road geometry (see V9__route_shapes.sql).
 *
 * A route used to be nothing but a sequence of stops, so the maps drew each
 * line as straight hops between them. This table holds the full polyline that
 * follows the real streets, so a bus tracked along the road stays on the line
 * that is drawn for it.
 *
 * Presentation only: timetable, adherence and ETA all continue to work off
 * {@link ScheduledStop}. A route with no shape rows simply falls back to the
 * old stop-to-stop rendering.
 *
 * Rows are keyed on (route_id, seq) — a point can only occupy one position in
 * the path — hence the {@link IdClass} below.
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

    /** TRUE when this vertex also happens to be a scheduled stop. */
    @Column(name = "is_stop", nullable = false)
    private Boolean isStop;

    /** Composite primary key holder. Must be public, no-args and serializable. */
    @Data
    public static class RouteShapeId implements Serializable {
        private String routeId;
        private Integer seq;
    }
}
