package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.RouteShape;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to OmniMove's local copy of the route road geometry
 * (see V14__route_shapes.sql).
 */
@Repository
public interface RouteShapeRepository extends JpaRepository<RouteShape, RouteShape.RouteShapeId> {

    /** Full path of one route, in drawing order. */
    List<RouteShape> findByRouteIdOrderBySeqAsc(String routeId);

    /**
     * Clear one route's path before re-importing it. The NeTEx import replaces
     * a shape wholesale rather than diffing it: paths are reshaped in the route
     * editor as a unit, so point-by-point merging would add complexity for no
     * benefit.
     */
    @Modifying
    @Query("DELETE FROM RouteShape rs WHERE rs.routeId = :routeId")
    void deleteByRouteId(String routeId);
}
