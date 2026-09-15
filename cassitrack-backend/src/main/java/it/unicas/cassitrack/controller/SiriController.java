package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.dto.siri.Siri;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.service.SiriService;
import it.unicas.cassitrack.service.VehicleStateCache;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SIRI Lite: the three SIRI services as plain GETs returning the SIRI XML.
 *
 * <p>Each filter accepts the SIRI name ({@code LineRef}, {@code VehicleRef},
 * {@code MonitoringRef}, {@code MaximumStopVisits}) and the snake_case name
 * the rest of this API uses ({@code route_id}, {@code vehicle_id},
 * {@code stop_id}, {@code max}). Public, like the other live feeds.
 */
@RestController
@RequestMapping("/api/v1/siri")
@Tag(name = "SIRI", description = "CEN SIRI 2.1 — VehicleMonitoring, StopMonitoring, CheckStatus")
public class SiriController {

    private static final Pattern STOP_ID_RE = Pattern.compile("^[A-Za-z0-9\\-_]{1,50}$");
    private static final int MAX_STOP_VISITS = 50;

    private final VehicleStateCache vehicleStateCache;
    private final SiriService siriService;

    public SiriController(VehicleStateCache vehicleStateCache, SiriService siriService) {
        this.vehicleStateCache = vehicleStateCache;
        this.siriService = siriService;
    }

    @GetMapping(value = "/vehicle-monitoring", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "VehicleMonitoringDelivery — every active vehicle, or one line, or one vehicle")
    public Siri vehicleMonitoring(
            @Parameter(description = "Line, e.g. LINEA_11I") @RequestParam(value = "LineRef", required = false) String lineRef,
            @RequestParam(value = "route_id", required = false) String routeId,
            @Parameter(description = "Vehicle, e.g. BUS1") @RequestParam(value = "VehicleRef", required = false) String vehicleRef,
            @RequestParam(value = "vehicle_id", required = false) String vehicleId) {

        String line    = firstNonBlank(lineRef, routeId);
        String vehicle = firstNonBlank(vehicleRef, vehicleId);

        Collection<VehiclePosition> vehicles = vehicleStateCache.getActive();
        if (line != null)
            vehicles = vehicles.stream().filter(v -> line.equals(v.getRouteId())).collect(Collectors.toList());
        if (vehicle != null)
            vehicles = vehicles.stream().filter(v -> vehicle.equals(v.getVehicleId())).collect(Collectors.toList());

        return siriService.vehicleMonitoring(vehicles);
    }

    @GetMapping(value = "/stop-monitoring", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "StopMonitoringDelivery — the next arrivals at one stop")
    public ResponseEntity<Siri> stopMonitoring(
            @Parameter(description = "Stop, e.g. PSB") @RequestParam(value = "MonitoringRef", required = false) String monitoringRef,
            @RequestParam(value = "stop_id", required = false) String stopId,
            @RequestParam(value = "MaximumStopVisits", required = false) Integer maximumStopVisits,
            @RequestParam(value = "max", required = false) Integer max) {

        String stop = firstNonBlank(monitoringRef, stopId);
        if (stop == null || !STOP_ID_RE.matcher(stop).matches()) return ResponseEntity.badRequest().build();

        Integer wanted = maximumStopVisits != null ? maximumStopVisits : max;
        int limit = wanted == null ? 10 : Math.max(1, Math.min(wanted, MAX_STOP_VISITS));

        return siriService.stopMonitoring(stop, limit)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/check-status", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "CheckStatusResponse — is the SIRI producer up, and since when")
    public Siri checkStatus() {
        return siriService.checkStatus();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
