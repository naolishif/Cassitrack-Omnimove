package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.RouteShape;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Read access to the road geometry of routes (see V9__route_shapes.sql).
 *
 * The map needs whole paths in drawing order, so both queries sort by seq.
 * findAll() is used to load every route's shape in one round-trip when the
 * map boots — a municipal network is a few hundred rows in total, so this is
 * cheaper than one query per route.
 */
@Repository
public interface RouteShapeRepository extends JpaRepository<RouteShape, RouteShape.RouteShapeId> {

    /** Full path of one route, in drawing order. */
    List<RouteShape> findByRouteIdOrderBySeqAsc(String routeId);

    /** Every path, ordered so callers can group by route id without re-sorting. */
    List<RouteShape> findAllByOrderByRouteIdAscSeqAsc();
}
