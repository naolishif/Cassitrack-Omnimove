package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.BusDTO;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Business logic for the bus registry (US-01 — Manage buses).
 *
 * Provides create / read / update / delete plus the search-and-filter the
 * fleet manager's Data Management tab needs, so the registry stays accurate
 * without anyone touching PostgreSQL directly.
 *
 * Filtering is done in memory on purpose: a municipal fleet is tens of rows,
 * not millions, so a single findAll() plus stream filtering is simpler and
 * faster to maintain than dynamic Specifications — and it keeps "search by
 * any field" trivial to extend.
 */
@Service
@RequiredArgsConstructor
public class BusService {

    private final BusRepository busRepository;
    private final RouteRepository routeRepository;

    private static final Set<String> VALID_STATUSES =
            Set.of("ACTIVE", "INACTIVE", "MAINTENANCE");

    // ── READ ────────────────────────────────────────────────────────

    /**
     * All buses, optionally narrowed down.
     *
     * @param search  free text matched against plate, antenna id, status,
     *                route and capacity (case-insensitive); null/blank = no filter
     * @param status  ACTIVE / INACTIVE / MAINTENANCE; null/blank = all
     * @param routeId restrict to one route; "UNASSIGNED" = buses with no route;
     *                null/blank = all
     */
    @Transactional(readOnly = true)
    public List<BusDTO> getAll(String search, String status, String routeId) {
        Map<String, String> routeLabels = routeLabelMap();

        return busRepository.findAll().stream()
                .map(bus -> toDto(bus, routeLabels))
                .filter(dto -> matchesStatus(dto, status))
                .filter(dto -> matchesRoute(dto, routeId))
                .filter(dto -> matchesSearch(dto, search))
                .sorted(Comparator.comparing(BusDTO::getTarga, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public BusDTO getById(Integer id) {
        Bus bus = busRepository.findById(id).orElseThrow(() -> notFound(id));
        return toDto(bus, routeLabelMap());
    }

    /** Id + label for every route, for the assignment and filter dropdowns. */
    @Transactional(readOnly = true)
    public List<Map<String, String>> getRouteOptions() {
        return routeRepository.findAll().stream()
                .sorted(Comparator.comparing(r -> label(r).toLowerCase()))
                .map(r -> Map.of("id", r.getId(), "label", label(r)))
                .toList();
    }

    // ── CREATE ──────────────────────────────────────────────────────

    @Transactional
    public BusDTO create(BusDTO dto) {
        validateStatus(dto.getStatus());
        ensurePlateFree(dto.getTarga(), null);
        ensureVehicleIdFree(dto.getCurrentVehicleId(), null);
        ensureRouteExists(dto.getRouteId());

        Bus bus = new Bus();
        applyDto(bus, dto);
        return toDto(busRepository.save(bus), routeLabelMap());
    }

    // ── UPDATE ──────────────────────────────────────────────────────

    @Transactional
    public BusDTO update(Integer id, BusDTO dto) {
        Bus bus = busRepository.findById(id).orElseThrow(() -> notFound(id));

        validateStatus(dto.getStatus());
        ensurePlateFree(dto.getTarga(), id);
        ensureVehicleIdFree(dto.getCurrentVehicleId(), id);
        ensureRouteExists(dto.getRouteId());

        applyDto(bus, dto);
        return toDto(busRepository.save(bus), routeLabelMap());
    }

    /** Flip only the map-visibility flag (US-05 toggle on the table row). */
    @Transactional
    public BusDTO setMapVisible(Integer id, boolean visible) {
        Bus bus = busRepository.findById(id).orElseThrow(() -> notFound(id));
        bus.setMapVisible(visible);
        return toDto(busRepository.save(bus), routeLabelMap());
    }

    // ── DELETE ──────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id) {
        if (!busRepository.existsById(id)) throw notFound(id);
        busRepository.deleteById(id);
    }

    // ── Validation helpers ──────────────────────────────────────────

    private void validateStatus(String status) {
        if (status == null || !VALID_STATUSES.contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be ACTIVE, INACTIVE or MAINTENANCE");
    }

    /** @param selfId id of the bus being edited, or null when creating */
    private void ensurePlateFree(String targa, Integer selfId) {
        if (targa == null || targa.isBlank()) return;   // @NotBlank already reports this
        busRepository.findAll().stream()
                .filter(b -> targa.equalsIgnoreCase(b.getTarga()))
                .filter(b -> selfId == null || !selfId.equals(b.getBusId()))
                .findAny()
                .ifPresent(b -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Plate '" + targa + "' is already used by another bus");
                });
    }

    private void ensureVehicleIdFree(String vehicleId, Integer selfId) {
        if (vehicleId == null || vehicleId.isBlank()) return;
        busRepository.findByCurrentVehicleId(vehicleId)
                .filter(b -> selfId == null || !selfId.equals(b.getBusId()))
                .ifPresent(b -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vehicle id '" + vehicleId + "' is already linked to another bus");
                });
    }

    private void ensureRouteExists(String routeId) {
        if (routeId == null || routeId.isBlank()) return;   // unassigned is allowed
        if (!routeRepository.existsById(routeId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No route found with id '" + routeId + "'");
    }

    private ResponseStatusException notFound(Integer id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No bus found with id " + id);
    }

    // ── Filter helpers ──────────────────────────────────────────────

    private boolean matchesStatus(BusDTO dto, String status) {
        return status == null || status.isBlank() || status.equalsIgnoreCase(dto.getStatus());
    }

    private boolean matchesRoute(BusDTO dto, String routeId) {
        if (routeId == null || routeId.isBlank()) return true;
        if ("UNASSIGNED".equalsIgnoreCase(routeId)) return dto.getRouteId() == null;
        return routeId.equals(dto.getRouteId());
    }

    /** "Search by any field" — one lowercase haystack per row. */
    private boolean matchesSearch(BusDTO dto, String search) {
        if (search == null || search.isBlank()) return true;
        String needle = search.trim().toLowerCase();
        String haystack = String.join(" ",
                String.valueOf(dto.getBusId()),
                nullSafe(dto.getTarga()),
                String.valueOf(dto.getNumeroPosti()),
                nullSafe(dto.getStatus()),
                nullSafe(dto.getRouteId()),
                nullSafe(dto.getRouteName()),
                nullSafe(dto.getCurrentVehicleId())
        ).toLowerCase();
        return haystack.contains(needle);
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    // ── Mapping ─────────────────────────────────────────────────────

    /** Copy editable fields from DTO onto the entity. */
    private void applyDto(Bus bus, BusDTO dto) {
        bus.setTarga(dto.getTarga().trim());
        bus.setNumeroPosti(dto.getNumeroPosti());
        bus.setWheelchairAccessible(Boolean.TRUE.equals(dto.getWheelchairAccessible()));
        bus.setStatus(dto.getStatus());
        bus.setMapVisible(dto.getMapVisible() == null || dto.getMapVisible());

        // Normalise blanks to null so the UNIQUE / FK constraints behave
        String routeId = dto.getRouteId();
        bus.setRouteId(routeId == null || routeId.isBlank() ? null : routeId);

        String vehicleId = dto.getCurrentVehicleId();
        bus.setCurrentVehicleId(vehicleId == null || vehicleId.isBlank() ? null : vehicleId.trim());

        // Keep the legacy boolean consistent with the new status
        bus.setDisponibile("ACTIVE".equals(dto.getStatus()));
    }

    private BusDTO toDto(Bus bus, Map<String, String> routeLabels) {
        BusDTO dto = new BusDTO();
        dto.setBusId(bus.getBusId());
        dto.setTarga(bus.getTarga());
        dto.setNumeroPosti(bus.getNumeroPosti());
        dto.setWheelchairAccessible(bus.getWheelchairAccessible());
        dto.setStatus(bus.getStatus());
        dto.setRouteId(bus.getRouteId());
        dto.setRouteName(bus.getRouteId() == null ? null : routeLabels.get(bus.getRouteId()));
        dto.setMapVisible(bus.getMapVisible());
        dto.setCurrentVehicleId(bus.getCurrentVehicleId());
        return dto;
    }

    private Map<String, String> routeLabelMap() {
        Map<String, String> map = new HashMap<>();
        for (Route r : routeRepository.findAll()) map.put(r.getId(), label(r));
        return map;
    }

    private String label(Route r) {
        if (r.getShortName() != null && !r.getShortName().isBlank()) {
            return r.getLongName() == null || r.getLongName().isBlank()
                    ? r.getShortName()
                    : r.getShortName() + " — " + r.getLongName();
        }
        return r.getLongName() == null || r.getLongName().isBlank() ? r.getId() : r.getLongName();
    }
}
