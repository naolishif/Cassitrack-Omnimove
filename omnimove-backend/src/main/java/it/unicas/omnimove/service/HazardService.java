package it.unicas.omnimove.service;

import it.unicas.omnimove.dto.HazardDTO;
import it.unicas.omnimove.dto.JourneyLeg;
import it.unicas.omnimove.dto.JourneyOption;
import it.unicas.omnimove.model.RoadClosure;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.repository.RoadClosureRepository;
import it.unicas.omnimove.repository.StopRepository;
import it.unicas.omnimove.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * What the emergencies reported by the partners mean for a journey.
 *
 * <p>An emergency is a circle. A leg "crosses" it when its drawn geometry
 * — the Google polyline of a walk or ride, the CassiTrack shape slice of a
 * bus leg — comes within the radius of the centre: the same point-in-circle
 * test the bike zones use, applied along the path rather than to one point.
 *
 * <p>Nothing is cached: a journey is planned afresh on every request, so the
 * check reads the reports in force at that very moment.
 */
@Service
@RequiredArgsConstructor
public class HazardService {

    private final RoadClosureRepository closures;
    private final StopRepository stops;

    /** An emergency this close to the origin or the destination is worth a line even when every option avoids it. */
    private static final int NOTICE_DISTANCE_M = 1000;

    /** The reports in force right now. */
    @Transactional(readOnly = true)
    public List<RoadClosure> active() {
        return closures.findByStatusTrue();
    }

    /** True when the path passes through any of the given emergencies. */
    public static boolean crossesAny(List<double[]> path, List<RoadClosure> hazards) {
        if (path == null || path.size() < 1 || hazards.isEmpty()) return false;
        for (RoadClosure h : hazards) if (crosses(path, h)) return true;
        return false;
    }

    /**
     * Marks every option that still passes through an emergency: the map
     * gets the circles, the card gets the note. Options whose legs were
     * rerouted around the reports are left untouched — the traveller has
     * nothing to be told there.
     */
    @Transactional(readOnly = true)
    public void mark(List<JourneyOption> options, boolean italian) {
        List<RoadClosure> hazards = active();
        if (hazards.isEmpty()) return;
        for (JourneyOption o : options) {
            if (o.getLegs() == null) continue;
            Map<Long, RoadClosure> hit = new LinkedHashMap<>();
            for (JourneyLeg leg : o.getLegs())
                for (RoadClosure h : hazards)
                    if (crosses(leg.getStopCoords(), h)) hit.put(h.getId(), h);
            if (hit.isEmpty()) continue;
            o.setHazards(hit.values().stream().map(HazardService::dto).toList());
            o.setHazardWarning(italian
                    ? "⚠ Questo percorso passa vicino a un'emergenza in corso"
                    : "⚠ This route passes near an ongoing emergency");
        }
    }

    /**
     * One line per emergency that concerns this journey — crossed by an
     * option, or within {@link #NOTICE_DISTANCE_M} of where it starts or
     * ends — for the notices under the weather. Placed by the nearest stop,
     * which is how a traveller in Cassino names a part of town.
     */
    @Transactional(readOnly = true)
    public List<String> notices(List<JourneyOption> options,
                                double originLat, double originLon,
                                double destLat, double destLon, boolean italian) {
        List<RoadClosure> hazards = active();
        if (hazards.isEmpty()) return List.of();

        Set<Long> crossed = new HashSet<>();
        for (JourneyOption o : options)
            if (o.getHazards() != null) o.getHazards().forEach(h -> crossed.add(h.getId()));

        List<String> out = new ArrayList<>();
        for (RoadClosure h : hazards) {
            double[] c = {h.getLatitude(), h.getLongitude()};
            int reach = h.getRadiusMetres() + NOTICE_DISTANCE_M;
            boolean near = GeoUtils.inCircle(originLat, originLon, c, reach)
                        || GeoUtils.inCircle(destLat, destLon, c, reach);
            if (!crossed.contains(h.getId()) && !near) continue;

            String what = h.getTitle() != null ? h.getTitle() : typeLabel(h.getEventType());
            String where = nearestStopName(h.getLatitude(), h.getLongitude());
            String sev = h.getSeverity() == null ? "" : h.getSeverity().toLowerCase();
            out.add(italian
                    ? "⚠ Emergenza in corso: " + what + (sev.isEmpty() ? "" : " (gravità " + sev + ")")
                      + (where != null ? " nella zona di " + where : "")
                      + ", raggio " + h.getRadiusMetres() + " m. I percorsi la evitano dove possibile."
                    : "⚠ Ongoing emergency: " + what + (sev.isEmpty() ? "" : " (" + sev + " severity)")
                      + (where != null ? " around " + where : "")
                      + ", " + h.getRadiusMetres() + " m radius. Routes avoid it where possible.");
        }
        return out;
    }

    private String nearestStopName(double lat, double lon) {
        Stop best = null; double bestD = Double.MAX_VALUE;
        for (Stop s : stops.findAll()) {
            if (s.getLat() == null || s.getLon() == null) continue;
            double d = GeoUtils.haversineMetres(lat, lon, s.getLat(), s.getLon());
            if (d < bestD) { bestD = d; best = s; }
        }
        return best == null || best.getName() == null ? null : best.getName();
    }

    /** FLOOD → Flood, ROAD_WORKS → Road works. */
    private static String typeLabel(String type) {
        if (type == null || type.isBlank()) return "emergency";
        String t = type.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ── Geometry ──────────────────────────────────────────────────────────

    static boolean crosses(List<double[]> path, RoadClosure h) {
        if (path == null || path.isEmpty()) return false;
        double[] centre = {h.getLatitude(), h.getLongitude()};
        int r = h.getRadiusMetres();
        double[] prev = null;
        for (double[] p : path) {
            if (GeoUtils.inCircle(p[0], p[1], centre, r)) return true;
            // A long straight stretch can cut through a small circle with
            // both ends outside it: check the segment, not only its ends.
            if (prev != null && segmentDistanceM(prev, p, centre) <= r) return true;
            prev = p;
        }
        return false;
    }

    private static final double M_PER_DEG_LAT = 111_320.0;

    /** Distance in metres from {@code c} to the segment a–b, in a flat frame around c. */
    private static double segmentDistanceM(double[] a, double[] b, double[] c) {
        double kx = M_PER_DEG_LAT * Math.cos(Math.toRadians(c[0]));
        double ax = (a[1] - c[1]) * kx, ay = (a[0] - c[0]) * M_PER_DEG_LAT;
        double bx = (b[1] - c[1]) * kx, by = (b[0] - c[0]) * M_PER_DEG_LAT;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        return Math.hypot(ax + t * dx, ay + t * dy);
    }

    private static HazardDTO dto(RoadClosure h) {
        return HazardDTO.builder()
                .id(h.getId())
                .eventType(h.getEventType())
                .severity(h.getSeverity())
                .title(h.getTitle())
                .latitude(h.getLatitude())
                .longitude(h.getLongitude())
                .radiusMetres(h.getRadiusMetres())
                .build();
    }
}
