package it.unicas.cassitrack.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.cassitrack.dto.ActiveTripDTO;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.Trip;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.TripRepository;
import it.unicas.cassitrack.service.ActiveTripService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Operational view of trips for the fleet manager.
 *
 * Distinct from the timetable itself: this answers "what is running now, and
 * how is it going", which is a live question, whereas routes/stops/buses are
 * registry data managed elsewhere in Data Management.
 *
 * Restricted to FLEET_MANAGER — it exposes which vehicle is on which duty and
 * allows reassigning it.
 */
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('FLEET_MANAGER', 'ROLE_FLEET_MANAGER')")
@Tag(name = "Trips", description = "Live trip monitoring and vehicle reassignment")
public class TripController {

    private final ActiveTripService activeTripService;
    private final TripRepository tripRepository;
    private final BusRepository busRepository;

    @GetMapping(value = "/active", produces = "application/json")
    @Operation(summary = "Trips scheduled to be running right now",
            description = "Timetable-driven: a scheduled trip whose bus is silent is "
                        + "returned with status NO_SIGNAL rather than omitted.")
    public List<ActiveTripDTO> getActiveTrips() {
        return activeTripService.getActiveTrips();
    }

    /**
     * Buses that could take this trip over.
     *
     * "Available" means no OTHER trip overlapping this one's window — not
     * "idle at this instant". A bus sitting still between two duties is not
     * free; a bus whose only other trip finished an hour ago is.
     *
     * The trip's current bus is always included and flagged, so the edit form
     * can show the existing value as the selected option rather than presenting
     * a list that mysteriously excludes it.
     */
    @GetMapping(value = "/{tripId}/available-buses", produces = "application/json")
    @Operation(summary = "Buses free for the whole of this trip's scheduled window")
    public List<Map<String, Object>> getAvailableBuses(@PathVariable String tripId) {
        return activeTripService.getAvailableBuses(tripId);
    }

    /**
     * Move a trip to a different vehicle.
     *
     * PUT rather than PATCH deliberately, matching the convention in
     * BusController: SecurityConfig declares FLEET_MANAGER rules for
     * POST/PUT/DELETE only, so a PATCH route would fall through to the generic
     * "authenticated" rule.
     *
     * No overlap checking yet — a fleet manager may legitimately need to
     * double-book a vehicle during a disruption, and warning them is a
     * decision for the disruption story rather than a hard block here.
     */
    @PutMapping(value = "/{tripId}/bus", produces = "application/json")
    @Operation(summary = "Reassign a trip to another bus (FLEET_MANAGER only)")
    public ResponseEntity<Map<String, Object>> reassignBus(@PathVariable String tripId,
                                                           @RequestParam Integer busId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No trip found with id " + tripId));

        Bus bus = busRepository.findById(busId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No bus found with id " + busId));

        trip.setBus(bus);
        tripRepository.save(trip);

        return ResponseEntity.ok(Map.of(
                "trip_id", tripId,
                "bus_id",  busId,
                "plate",   bus.getTarga() == null ? "" : bus.getTarga()));
    }

    /** Readable message instead of a bare 500 — same pattern as BusController. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of(
                        "status",  ex.getStatusCode().value(),
                        "message", ex.getReason() == null ? "Request failed" : ex.getReason()));
    }
}
