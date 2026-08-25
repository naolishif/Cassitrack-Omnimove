package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.dto.VehicleStatusDTO;
import it.unicas.cassitrack.service.VehicleService;
import it.unicas.cassitrack.service.VehicleStateCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import it.unicas.cassitrack.repository.BusRepository;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing real-time vehicle data.
 *
 * Base path: /api/v1/vehicles
 *
 * These endpoints are PUBLIC (no authentication required).
 * They are the primary integration point consumed by OMNIMOVE.
 *
 * Swagger UI: http://localhost:8280/api/swagger-ui
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
@Tag(name = "Vehicles", description = "Real-time bus positions and status")
public class VehicleController {

    private final VehicleService vehicleService;
    private final VehicleStateCache vehicleStateCache;
    private final BusRepository busRepository;

    /**
     * GET /api/v1/vehicles
     *
     * Returns the current position and status of ALL active buses.
     * "Active" = received a GPS message within the last 5 minutes.
     *
     * This is the endpoint OMNIMOVE polls every 30 seconds to
     * update the map with live bus positions.
     */
    @GetMapping(produces = "application/json")
    @Operation(
        summary = "Get all active vehicles",
        description = "Returns real-time position and status of all active buses in Cassino.",
        responses = {
            @ApiResponse(responseCode = "200", description = "List of active vehicles",
                content = @Content(schema = @Schema(implementation = VehicleStatusDTO.class)))
        }
    )
    public ResponseEntity<List<VehicleStatusDTO>> getAllVehicles() {
        List<VehicleStatusDTO> vehicles = vehicleService.getAllActiveVehicles();
        return ResponseEntity.ok(vehicles);
    }

    @GetMapping(value = "/fleet-size", produces = "application/json")
    public Map<String, Long> fleetSize() {
        return Map.of("total", busRepository.count());
    }

    /**
     * GET /api/v1/vehicles/{id}
     *
     * Returns the current status of a single vehicle.
     * Returns 404 if the vehicle is not in the cache (never seen or too old).
     */
    @GetMapping(value="/{id}",  produces = "application/json")
    @Operation(
        summary = "Get a single vehicle",
        description = "Returns real-time status of a specific vehicle by its ID (e.g. BUS-101).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Vehicle found"),
            @ApiResponse(responseCode = "404", description = "Vehicle not found or not active")
        }
    )
    public ResponseEntity<VehicleStatusDTO> getVehicleById(
        @Parameter(description = "Vehicle ID, e.g. BUS-101")
        @PathVariable String id
    ) {
        return vehicleService.getVehicleById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/vehicles/count
     *
     * Quick health check: how many vehicles are currently tracked?
     * Useful for the fleet dashboard header.
     */
    @GetMapping(value = "/count", produces = "application/json")
    @Operation(summary = "Get count of tracked vehicles")
    public ResponseEntity<Map<String, Integer>> getVehicleCount() {
        return ResponseEntity.ok(Map.of(
            "active", vehicleStateCache.getActive().size(),
            "total", vehicleStateCache.size()
        ));
    }
}
