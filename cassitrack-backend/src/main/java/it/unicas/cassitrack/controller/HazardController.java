package it.unicas.cassitrack.controller;

import it.unicas.cassitrack.model.RoadClosure;
import it.unicas.cassitrack.service.HazardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The partners' emergencies as the fleet manager sees them: which are in
 * force, which were just resolved, which lines each one hits, and whether
 * the manager has been told yet. Gated to FLEET_MANAGER in SecurityConfig;
 * the partners write through {@link RoadClosureController}.
 */
@RestController
@RequestMapping("/api/v1/hazards")
@RequiredArgsConstructor
public class HazardController {

    private final HazardService hazardService;

    @GetMapping
    public Map<String, Object> board() {
        HazardService.Board b = hazardService.board();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active",   b.active().stream().map(HazardController::row).collect(Collectors.toList()));
        out.put("resolved", b.resolved().stream().map(HazardController::row).collect(Collectors.toList()));
        return out;
    }

    /** The manager has seen the notice for this closure's current status. */
    @PostMapping("/{id}/ack")
    public ResponseEntity<?> acknowledge(@PathVariable long id) {
        return hazardService.acknowledge(id)
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(Map.of("id", c.getId(), "status", c.getStatus())))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "error", "NOT_FOUND", "message", "No hazard with this id.")));
    }

    private static Map<String, Object> row(HazardService.Hazard h) {
        RoadClosure c = h.closure();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             c.getId());
        m.put("external_id",    c.getExternalId());
        m.put("emergency_id",   c.getEmergencyId());
        m.put("reported_by",    c.getReportedBy());
        m.put("type",           c.getEventType());
        m.put("severity",       c.getSeverity());
        m.put("category",       c.getCategory());
        m.put("title",          c.getTitle());
        m.put("description",    c.getDescription());
        m.put("latitude",       c.getLatitude());
        m.put("longitude",      c.getLongitude());
        m.put("radius_m",       c.getRadiusMetres());
        Map<String, Object> mp = null;
        if (c.getMeetingLat() != null || c.getMeetingAddress() != null) {
            mp = new LinkedHashMap<>();
            mp.put("latitude",  c.getMeetingLat());
            mp.put("longitude", c.getMeetingLon());
            mp.put("address",   c.getMeetingAddress());
        }
        m.put("meeting_point",  mp);
        m.put("status",         c.getStatus());
        m.put("created_at",     c.getCreatedAt());
        m.put("updated_at",     c.getUpdatedAt());
        m.put("notice_pending", h.noticePending());
        m.put("affected_routes", h.affectedRoutes().stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id",         r.id());
            rm.put("short_name", r.shortName());
            rm.put("long_name",  r.longName());
            return rm;
        }).collect(Collectors.toList()));
        return m;
    }
}
