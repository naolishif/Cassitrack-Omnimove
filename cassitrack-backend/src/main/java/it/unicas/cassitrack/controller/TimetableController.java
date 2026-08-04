package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.service.TimetableService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Timetable management: the runs (trips) of each line and their stop times.
 *
 * Base path: /api/v1/timetable — consumed by the fleet manager's Data
 * Management panel, so the schedule can be edited without hand-written SQL
 * migrations.
 *
 * Authorisation: restricted to FLEET_MANAGER both here and in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/timetable")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('FLEET_MANAGER', 'ROLE_FLEET_MANAGER')")
@Tag(name = "Timetable", description = "Manage trips and their scheduled stops")
public class TimetableController {

    private final TimetableService timetableService;

    @GetMapping(produces = "application/json")
    @Operation(summary = "List runs, optionally filtered by line, bus or free text")
    public List<TimetableService.TripSummary> list(
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) Integer busId,
            @RequestParam(required = false) String search) {
        return timetableService.list(routeId, busId, search);
    }

    @GetMapping(value = "/stop-times", produces = "application/json")
    @Operation(summary = "Exploded timetable: one row per run and stop",
            description = "Same filters as the run list. Feeds the detailed CSV export.")
    public List<TimetableService.StopTimeRow> stopTimes(
            @RequestParam(required = false) String routeId,
            @RequestParam(required = false) Integer busId,
            @RequestParam(required = false) String search) {
        return timetableService.stopTimes(routeId, busId, search);
    }

    @GetMapping(value = "/{tripId}", produces = "application/json")
    @Operation(summary = "Stop-by-stop detail of one run")
    public TimetableService.TripDetail detail(@PathVariable String tripId) {
        return timetableService.detail(tripId);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(summary = "Create a run by copying the line's stop pattern")
    public ResponseEntity<TimetableService.TripDetail> create(
            @RequestBody TimetableService.CreateTripRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(timetableService.create(req));
    }

    @PutMapping(value = "/{tripId}/times", consumes = "application/json", produces = "application/json")
    @Operation(summary = "Update the arrival times of a run")
    public TimetableService.TripDetail updateTimes(
            @PathVariable String tripId,
            @RequestBody TimetableService.UpdateTimesRequest req) {
        return timetableService.updateTimes(tripId, req);
    }

    @DeleteMapping("/{tripId}")
    @Operation(summary = "Delete a run and its stop times")
    public ResponseEntity<Void> delete(@PathVariable String tripId) {
        timetableService.delete(tripId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Turns the service's 400/404/409 into the {status, error, message} shape
     * the Data Management UI already knows how to display.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        String msg = ex.getReason() == null ? "Request failed" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("status", ex.getStatusCode().value(), "error", msg, "message", msg));
    }
}
