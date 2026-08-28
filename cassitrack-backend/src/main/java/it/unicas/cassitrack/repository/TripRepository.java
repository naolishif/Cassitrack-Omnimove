package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TripRepository extends JpaRepository<Trip, String> {

    @Query("SELECT t FROM Trip t JOIN FETCH t.route JOIN FETCH t.bus WHERE t.id IN :ids")
    List<Trip> findAllByIdInWithRouteAndBus(@Param("ids") List<String> ids);

    // FK guard for bus deletion: trips.bus_id references buses(bus_id) NOT NULL.
    long countByBusBusId(Integer busId);

    // FK guard for route deletion: trips.route_id references routes(id) ON DELETE
    // CASCADE — deleting a route would silently wipe its trips (and their
    // scheduled_stops), so the controller counts and refuses instead.
    long countByRouteId(String routeId);

    /** Tutte le corse di una linea, per la cascata sul cambio di percorso (V24). */
    java.util.List<Trip> findAllByRouteId(String routeId);
}