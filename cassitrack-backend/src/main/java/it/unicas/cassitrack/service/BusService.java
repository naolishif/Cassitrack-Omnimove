package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.BusDTO;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.TripRepository;
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
    private final TripRepository tripRepository;
    private final ScheduledStopRepository scheduledStopRepository;

    private static final java.time.ZoneId ITALY_TZ = java.time.ZoneId.of("Europe/Rome");

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
        // Resolved once for the whole fleet (two queries), not per row.
        Map<Integer, DerivedRoute> derived = derivedRoutes();

        return busRepository.findAll().stream()
                .map(bus -> toDto(bus, routeLabels, derived))
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
        // No route validation: the route is derived, never submitted.

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
        // No route validation: the route is derived, never submitted.

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

    /**
     * Delete a bus, refusing if it would break referential integrity.
     *
     * trips.bus_id is a NOT NULL FK to buses(bus_id), so deleting a bus that
     * still has trips would fail at the database and surface as an opaque 500.
     * We count first and return a readable 409 instead, telling the fleet
     * manager exactly how many trips need reassigning.
     */
    @Transactional
    public void delete(Integer id) {
        if (!busRepository.existsById(id)) throw notFound(id);

        long trips = tripRepository.countByBusBusId(id);
        if (trips > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete: this bus is assigned to " + trips + " trip(s). "
                            + "Reassign or remove those trips first.");

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

    // ensureRouteExists() removed: the route is no longer submitted by the
    // client, so there is nothing to validate. routeRepository is still used
    // by routeLabelMap() / getRouteOptions() for the filter dropdown.

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

        // routeId is deliberately NOT written: the route shown in the registry
        // is derived from the timetable (see derivedRoutes()), so storing a
        // manual value here could only contradict it. The column still exists
        // in the schema, it is simply no longer maintained from the UI.

        String vehicleId = dto.getCurrentVehicleId();
        bus.setCurrentVehicleId(vehicleId == null || vehicleId.isBlank() ? null : vehicleId.trim());

        // Keep the legacy boolean consistent with the new status
        bus.setDisponibile("ACTIVE".equals(dto.getStatus()));
    }

    private BusDTO toDto(Bus bus, Map<String, String> routeLabels) {
        return toDto(bus, routeLabels, derivedRoutes());
    }

    private BusDTO toDto(Bus bus, Map<String, String> routeLabels,
                         Map<Integer, DerivedRoute> derived) {
        BusDTO dto = new BusDTO();
        dto.setBusId(bus.getBusId());
        dto.setTarga(bus.getTarga());
        dto.setNumeroPosti(bus.getNumeroPosti());
        dto.setWheelchairAccessible(bus.getWheelchairAccessible());
        dto.setStatus(bus.getStatus());
        dto.setMapVisible(bus.getMapVisible());
        dto.setCurrentVehicleId(bus.getCurrentVehicleId());

        // Route is derived from the timetable (antenna → bus → trips → route),
        // never from the manual buses.route_id column: a bus can serve several
        // lines a day, so a single stored value could not represent reality.
        DerivedRoute dr = derived.get(bus.getBusId());
        if (dr != null) {
            dto.setRouteId(dr.routeId());
            dto.setRouteName(dr.label());
            dto.setRouteLive(dr.live());
        }
        return dto;
    }

    // ── Route derivation ────────────────────────────────────────────

    /**
     * What the registry shows in the "route" column for one bus.
     *
     * @param routeId the line in service now, or the first scheduled one
     * @param label   display text ("2 — Anello Liceo", or "1, 2" when idle)
     * @param live    true when the bus is inside a trip's service window
     */
    private record DerivedRoute(String routeId, String label, boolean live) {}

    /**
     * Resolve the route of EVERY bus in two queries (no N+1):
     *   · the trip whose service window covers now  → live line
     *   · every route the bus serves in the timetable → fallback when idle
     */
    private Map<Integer, DerivedRoute> derivedRoutes() {
        int now = java.time.LocalTime.now(ITALY_TZ).toSecondOfDay();
        Map<Integer, DerivedRoute> out = new HashMap<>();

        // Fallback first: all lines this bus serves today, de-duplicated by
        // short name (LINEA_2 and LINEA_2_LIC are both line "2").
        Map<Integer, LinkedHashSet<String>> scheduled = new LinkedHashMap<>();
        Map<Integer, String> anyRouteId = new HashMap<>();
        for (Object[] r : scheduledStopRepository.findScheduledRoutesPerBus()) {
            Integer busId = ((Number) r[0]).intValue();
            String  rid   = (String) r[1];
            String  shrt  = (String) r[2];
            String  lng   = (String) r[3];
            scheduled.computeIfAbsent(busId, k -> new LinkedHashSet<>())
                     .add(shortLabel(shrt, lng, rid));
            anyRouteId.putIfAbsent(busId, rid);
        }
        scheduled.forEach((busId, lines) -> out.put(busId,
                new DerivedRoute(anyRouteId.get(busId), String.join(", ", lines), false)));

        // Live wins: the query is ordered by latest departure first, so the
        // first row seen for a bus is its current trip.
        for (Object[] r : scheduledStopRepository.findActiveTripsForAllBuses(now)) {
            Integer busId = ((Number) r[0]).intValue();
            if (out.containsKey(busId) && out.get(busId).live()) continue;   // already set
            String rid  = (String) r[1];
            String shrt = (String) r[2];
            String lng  = (String) r[3];
            out.put(busId, new DerivedRoute(rid, fullLabel(shrt, lng, rid), true));
        }
        return out;
    }

    /** Compact form for the idle list: just the line number. */
    private String shortLabel(String shortName, String longName, String routeId) {
        if (shortName != null && !shortName.isBlank()) return shortName;
        return longName == null || longName.isBlank() ? routeId : longName;
    }

    /** Full form for the line currently in service. */
    private String fullLabel(String shortName, String longName, String routeId) {
        if (shortName == null || shortName.isBlank())
            return longName == null || longName.isBlank() ? routeId : longName;
        return longName == null || longName.isBlank()
                ? shortName : shortName + " — " + longName;
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
