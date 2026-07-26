package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.VehicleStatusDTO;
import it.unicas.cassitrack.model.VehiclePosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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
    private final RouteMatchingService routeMatchingService;

    /**
     * Returns current status of ALL active vehicles.
     * Used by: GET /api/v1/vehicles
     */
    public List<VehicleStatusDTO> getAllActiveVehicles() {
        List<VehicleStatusDTO> list = vehicleStateCache.getActive().stream()
                .map(this::toStatusDTO)
                .toList();
        return list;
    }

    /**
     * Returns current status of a single vehicle.
     * Used by: GET /api/v1/vehicles/{id}
     */
    public Optional<VehicleStatusDTO> getVehicleById(String vehicleId) {
        return vehicleStateCache.get(vehicleId)
            .map(this::toStatusDTO);
    }

    /**
     * Converts a raw VehiclePosition entity to the API response DTO.
     * This is where we'll plug in schedule adherence and ETA
     * computation once those services are built.
     */
    private VehicleStatusDTO toStatusDTO(VehiclePosition pos) {
        boolean active = vehicleStateCache.isActive(pos.getVehicleId());

        // Passengers: prefer direct count from simulator,
        // fall back to BLE estimate
        Integer estimatedPassengers = null;
        String  crowdingLevel       = null;
        if (pos.getPassengers() != null) {
            estimatedPassengers = pos.getPassengers();
            crowdingLevel = estimateCrowdingLevel(estimatedPassengers);
        } else if (pos.getBleDeviceCount() != null) {
            estimatedPassengers = (int)(pos.getBleDeviceCount() * 0.6);
            crowdingLevel = estimateCrowdingLevel(estimatedPassengers);
        }

        VehiclePosition.ScheduleStatus status =
                pos.getScheduleStatus() != null
                        ? pos.getScheduleStatus()
                        : VehiclePosition.ScheduleStatus.UNKNOWN;

        return VehicleStatusDTO.builder()
                .vehicleId(pos.getVehicleId())
                .busId(pos.getBusId())
                .numeroPosti(pos.getNumeroPosti())
                .wheelchairAccessible(pos.getWheelchairAccessible())
                .lat(pos.getLat())
                .lon(pos.getLon())
                .speedKmh(pos.getSpeedKmh())
                .headingDeg(pos.getHeadingDeg())
                .routeId(pos.getRouteId())
                .routeName(pos.getRouteName())
                .tripId(pos.getTripId())
                .scheduleStatus(status)
                .delayMinutes(pos.getDelayMinutes())
                .nextStopId(pos.getLastStopRegisteredId())
                .nextStopName(pos.getLastStopRegistered())
                .upcomingStopName(routeMatchingService.nextStopName(
                        pos.getTripId(), pos.getRouteId(), pos.getLastStopRegisteredId()))
                .etaSeconds(null)
                .estimatedPassengers(estimatedPassengers)
                .crowdingLevel(crowdingLevel)
                .timestamp(pos.getTimestamp())
                .lastSeen(pos.getReceivedAt())
                .isActive(active)
                .build();
    }


    /**
     * Rough crowding classification.
     * Will be replaced by the calibrated model from the university
     * measurement research group.
     */
    private String estimateCrowdingLevel(int passengers) {
        if (passengers < 10)  return "LOW";
        if (passengers < 25)  return "MEDIUM";
        if (passengers < 40)  return "HIGH";
        return "VERY_HIGH";
    }
}
