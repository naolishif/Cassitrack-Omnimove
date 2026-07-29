package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.VehicleStatusDTO;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.BusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service layer for vehicle-related business logic.
 *
 * Translates raw VehiclePosition entities from the cache into
 * the rich VehicleStatusDTO format that the REST API returns.
 *
 * In future iterations, this service will also:
 *   - call ScheduleAdherenceService to compute delay_minutes
 *   - call ETAService to compute eta_seconds
 *   - call CrowdEstimationService to compute estimated_passengers
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleStateCache vehicleStateCache;
    private final BusRepository busRepository;

    /**
     * Returns current status of ALL active vehicles.
     * Used by: GET /api/v1/vehicles
     */
    public List<VehicleStatusDTO> getAllActiveVehicles() {
        // buses.map_visible is read here rather than carried on the cached
        // position, so toggling "On map" in Data Management takes effect on the
        // very next poll instead of waiting for the bus to transmit again —
        // which, on the one-message-per-minute OBU feed, could be a long wait.
        Map<String, Boolean> visibility = mapVisibilityByVehicleId();
        return vehicleStateCache.getActive().stream()
                .map(pos -> toStatusDTO(pos, visibility.get(pos.getVehicleId())))
                .toList();
    }

    /**
     * Returns current status of a single vehicle.
     * Used by: GET /api/v1/vehicles/{id}
     */
    public Optional<VehicleStatusDTO> getVehicleById(String vehicleId) {
        return vehicleStateCache.get(vehicleId)
            .map(pos -> toStatusDTO(pos,
                    busRepository.findByCurrentVehicleId(vehicleId)
                            .map(Bus::getMapVisible)
                            .orElse(null)));
    }

    /**
     * vehicle_id → map_visible for the whole fleet, in one query.
     *
     * Keyed on current_vehicle_id because that is what telemetry arrives under.
     * Buses with no unit assigned are simply absent, and a null result is
     * treated as visible by the caller.
     */
    private Map<String, Boolean> mapVisibilityByVehicleId() {
        Map<String, Boolean> out = new HashMap<>();
        for (Bus b : busRepository.findAll()) {
            if (b.getCurrentVehicleId() != null) {
                out.put(b.getCurrentVehicleId(), b.getMapVisible());
            }
        }
        return out;
    }

    /**
     * Converts a raw VehiclePosition entity to the API response DTO.
     * This is where we'll plug in schedule adherence and ETA
     * computation once those services are built.
     */
    private VehicleStatusDTO toStatusDTO(VehiclePosition pos, Boolean mapVisible) {
        boolean active = vehicleStateCache.isActive(pos.getVehicleId());

        Integer estimatedPassengers = CrowdingService.effectivePassengers(
                pos.getPassengers(), pos.getBleDeviceCount());
        String  crowdingLevel = CrowdingService.levelFromRatio(
                estimatedPassengers, pos.getCapacity());
        Integer occupancyPct  = CrowdingService.occupancyPct(
                estimatedPassengers, pos.getCapacity());

        VehiclePosition.ScheduleStatus status =
                pos.getScheduleStatus() != null
                        ? pos.getScheduleStatus()
                        : VehiclePosition.ScheduleStatus.UNKNOWN;

        return VehicleStatusDTO.builder()
                .vehicleId(pos.getVehicleId())
                // null = no bus row / never set → treated as visible, so a bus
                // is only hidden when someone has explicitly turned it off.
                .mapVisible(mapVisible == null || mapVisible)
                .busId(pos.getBusId())
                .numeroPosti(pos.getNumeroPosti())
                .wheelchairAccessible(pos.getWheelchairAccessible())
                .lat(pos.getLat())
                .lon(pos.getLon())
                .speedKmh(pos.getSpeedKmh())
                .headingDeg(pos.getHeadingDeg())
                .tripId(pos.getTripId())
                .routeId(pos.getRouteId())
                .routeName(pos.getRouteName())
                .tripId(pos.getTripId())
                .scheduleStatus(status)
                .delayMinutes(pos.getDelayMinutes())
                .delayStopName(pos.getDelayStopName())
                .delayStopSequence(pos.getDelayStopSequence())
                .delayMeasuredAt(pos.getDelayMeasuredAt())
                // Both are resolved once, in MqttMessageHandler. Recomputing the
                // next stop on every read (as before) meant a DB round-trip per
                // vehicle per API call, and could disagree with the cached state.
                .lastStopId(pos.getLastStopRegisteredId())
                .lastStopName(pos.getLastStopRegistered())
                .nextStopId(pos.getNextStopId())
                .nextStopName(pos.getNextStop())
                .etaSeconds(null)
                .estimatedPassengers(estimatedPassengers)
                .crowdingLevel(crowdingLevel)
                .timestamp(pos.getTimestamp())
                .lastSeen(pos.getReceivedAt())
                .occupancyPct(occupancyPct)
                .isActive(active)
                .build();
    }

}
