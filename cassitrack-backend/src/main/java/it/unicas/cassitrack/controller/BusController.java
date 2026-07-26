package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.TripRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRUD management of the bus fleet (Postgres `buses` table), consumed by the
 * fleet manager's Data Management panel.
 *
 * All endpoints require FLEET_MANAGER (defence in depth: also gated in
 * SecurityConfig). CSRF is disabled application-wide (JWT, stateless), so no
 * token handling is needed on the client.
 */
@RestController
@RequestMapping("/api/v1/buses")
@PreAuthorize("hasAnyAuthority('FLEET_MANAGER', 'ROLE_FLEET_MANAGER')")
public class BusController {

    private final BusRepository busRepository;
    private final TripRepository tripRepository;

    public BusController(BusRepository busRepository, TripRepository tripRepository) {
        this.busRepository = busRepository;
        this.tripRepository = tripRepository;
    }

    /** Editable fields accepted from the client. busId is server-assigned. */
    public record BusRequest(String targa,
                             Integer numeroPosti,
                             Boolean wheelchairAccessible,
                             Boolean disponibile,
                             String currentVehicleId) {}

    private static ResponseEntity<?> error(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }

    @GetMapping
    public List<Bus> list() {
        return busRepository.findAll(Sort.by(Sort.Direction.ASC, "busId"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Integer id) {
        Bus b = busRepository.findById(id).orElse(null);
        if (b == null) return error(HttpStatus.NOT_FOUND, "Bus not found.");
        return ResponseEntity.ok(b);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BusRequest req) {
        String targa = normalizeTarga(req.targa());
        String cvid  = blankToNull(req.currentVehicleId());
        String bad = validate(targa, req.numeroPosti(), cvid);
        if (bad != null) return error(HttpStatus.BAD_REQUEST, bad);
        if (busRepository.existsByTarga(targa))
            return error(HttpStatus.CONFLICT, "A bus with plate '" + targa + "' already exists.");
        if (cvid != null && busRepository.existsByCurrentVehicleId(cvid))
            return error(HttpStatus.CONFLICT, "Vehicle id '" + cvid + "' is already assigned to another bus.");

        Bus b = new Bus();
        apply(b, targa, req, cvid);
        return ResponseEntity.status(HttpStatus.CREATED).body(busRepository.save(b));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Integer id, @RequestBody BusRequest req) {
        Bus b = busRepository.findById(id).orElse(null);
        if (b == null) return error(HttpStatus.NOT_FOUND, "Bus not found.");
        String targa = normalizeTarga(req.targa());
        String cvid  = blankToNull(req.currentVehicleId());
        String bad = validate(targa, req.numeroPosti(), cvid);
        if (bad != null) return error(HttpStatus.BAD_REQUEST, bad);
        if (busRepository.existsByTargaAndBusIdNot(targa, id))
            return error(HttpStatus.CONFLICT, "A bus with plate '" + targa + "' already exists.");
        if (cvid != null && busRepository.existsByCurrentVehicleIdAndBusIdNot(cvid, id))
            return error(HttpStatus.CONFLICT, "Vehicle id '" + cvid + "' is already assigned to another bus.");

        apply(b, targa, req, cvid);
        return ResponseEntity.ok(busRepository.save(b));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Integer id) {
        Bus b = busRepository.findById(id).orElse(null);
        if (b == null) return error(HttpStatus.NOT_FOUND, "Bus not found.");
        long trips = tripRepository.countByBusBusId(id);
        if (trips > 0)
            return error(HttpStatus.CONFLICT,
                    "Cannot delete: this bus is assigned to " + trips + " trip(s). "
                            + "Reassign or remove those trips first.");
        busRepository.delete(b);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private static void apply(Bus b, String targa, BusRequest req, String cvid) {
        b.setTarga(targa);
        b.setNumeroPosti(req.numeroPosti());
        b.setWheelchairAccessible(Boolean.TRUE.equals(req.wheelchairAccessible()));
        b.setDisponibile(req.disponibile() == null ? Boolean.TRUE : req.disponibile());
        b.setCurrentVehicleId(cvid);
    }

    private static String normalizeTarga(String t) {
        return t == null ? null : t.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String validate(String targa, Integer posti, String cvid) {
        if (targa == null || targa.isEmpty()) return "Plate (targa) is required.";
        if (targa.length() > 20)             return "Plate is too long (max 20 characters).";
        if (posti == null)                   return "Number of seats is required.";
        if (posti < 1 || posti > 300)        return "Number of seats must be between 1 and 300.";
        if (cvid != null && cvid.length() > 50) return "Vehicle id is too long (max 50 characters).";
        return null;
    }
}
