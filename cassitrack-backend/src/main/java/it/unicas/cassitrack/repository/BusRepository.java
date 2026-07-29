package it.unicas.cassitrack.repository;

import it.unicas.cassitrack.model.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusRepository extends JpaRepository<Bus, Integer> {
    // 🔍 Questo metodo cercherà nel database Postgres il pullman associato al codice dell'antenna MQTT
    Optional<Bus> findByCurrentVehicleId(String currentVehicleId);

    // Uniqueness checks used by the CRUD management endpoints.
    boolean existsByTarga(String targa);
    boolean existsByTargaAndBusIdNot(String targa, Integer busId);
    boolean existsByCurrentVehicleId(String currentVehicleId);
    boolean existsByCurrentVehicleIdAndBusIdNot(String currentVehicleId, Integer busId);
}