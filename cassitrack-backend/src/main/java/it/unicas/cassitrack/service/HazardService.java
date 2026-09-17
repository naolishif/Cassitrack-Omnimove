package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.RoadClosure;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.RoadClosureRepository;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.RouteShapeRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * What the road closures reported by the partners mean for the network:
 * which lines run through them, and which ones the fleet manager still has
 * to be told about.
 *
 * <p>A closure is a circle. A line is hit when its drawn geometry
 * ({@code route_shapes}, or the stop-to-stop polyline for a line that has
 * no shape) comes within the radius of the centre — the same test the map
 * does visually, done here so the answer is one for every screen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HazardService {

    /** A resolved closure stays listed this long, so the "all clear" is seen. */
    static final Duration RESOLVED_VISIBLE_FOR = Duration.ofHours(24);

    private final RoadClosureRepository closures;
    private final RouteRepository routeRepository;
    private final RouteShapeRepository routeShapeRepository;
    private final StopRepository stopRepository;
    private final RoutePatternService routePatternService;

    public record AffectedRoute(String id, String shortName, String longName) {}

    /**
     * @param noticePending the fleet manager has not yet been shown this
     *                      closure in its current status
     */
    public record Hazard(RoadClosure closure, List<AffectedRoute> affectedRoutes,
                         boolean noticePending) {}

    public record Board(List<Hazard> active, List<Hazard> resolved) {}

    @Transactional(readOnly = true)
    public Board board() {
        Instant since = Instant.now().minus(RESOLVED_VISIBLE_FOR);
        Map<String, List<double[]>> geometries = geometries();
        return new Board(
                closures.findByStatusTrueOrderByUpdatedAtDesc().stream()
                        .map(c -> hazard(c, geometries)).toList(),
                closures.findByStatusFalseAndUpdatedAtAfterOrderByUpdatedAtDesc(since).stream()
                        .map(c -> hazard(c, geometries)).toList());
    }

    /** Records that the fleet manager has seen the closure in its current status. */
    @Transactional
    public Optional<RoadClosure> acknowledge(long id) {
        return closures.findById(id).map(c -> {
            c.setAcknowledgedStatus(c.getStatus());
            return closures.save(c);
        });
    }

    private Hazard hazard(RoadClosure c, Map<String, List<double[]>> geometries) {
        List<AffectedRoute> hit = new ArrayList<>();
        for (Route r : routeRepository.findAll()) {
            if (!r.isActive()) continue;
            List<double[]> pts = geometries.get(r.getId());
            if (pts != null && polylineWithin(pts, c.getLatitude(), c.getLongitude(), c.getRadiusMetres()))
                hit.add(new AffectedRoute(r.getId(), r.getShortName(), r.getLongName()));
        }
        hit.sort(Comparator.comparing(AffectedRoute::id));
        boolean pending = !Objects.equals(c.getAcknowledgedStatus(), c.getStatus());
        return new Hazard(c, hit, pending);
    }

    /**
     * Every line's polyline as [lat, lon] points: the road shape when one is
     * stored, the sequence of its stops otherwise — the same fallback the
     * fleet map draws.
     */
    private Map<String, List<double[]>> geometries() {
        Map<String, List<double[]>> out = new HashMap<>();
        for (var sh : routeShapeRepository.findAllByOrderByRouteIdAscSeqAsc())
            out.computeIfAbsent(sh.getRouteId(), k -> new ArrayList<>())
               .add(new double[]{sh.getLat(), sh.getLon()});

        Map<String, Stop> stops = new HashMap<>();
        for (Route r : routeRepository.findAll()) {
            if (out.containsKey(r.getId())) continue;
            if (stops.isEmpty()) for (Stop s : stopRepository.findAll()) stops.put(s.getId(), s);
            List<double[]> pts = new ArrayList<>();
            for (var ps : routePatternService.pattern(r.getId())) {
                Stop s = stops.get(ps.stopId());
                if (s != null && s.getLat() != null && s.getLon() != null)
                    pts.add(new double[]{s.getLat(), s.getLon()});
            }
            if (pts.size() >= 2) out.put(r.getId(), pts);
        }
        return out;
    }

    // ── Geometry ──────────────────────────────────────────────────────────

    /**
     * True when any segment of the polyline passes within {@code radiusM} of
     * the centre. Distances in a local flat frame around the centre: at a few
     * kilometres the error is centimetres, and a closure is never larger.
     */
    static boolean polylineWithin(List<double[]> pts, double cLat, double cLon, int radiusM) {
        if (pts.size() == 1) return distanceM(pts.get(0), cLat, cLon) <= radiusM;
        for (int i = 1; i < pts.size(); i++)
            if (segmentDistanceM(pts.get(i - 1), pts.get(i), cLat, cLon) <= radiusM) return true;
        return false;
    }

    private static final double M_PER_DEG_LAT = 111_320.0;

    private static double[] local(double[] p, double cLat, double cLon) {
        double kx = M_PER_DEG_LAT * Math.cos(Math.toRadians(cLat));
        return new double[]{(p[1] - cLon) * kx, (p[0] - cLat) * M_PER_DEG_LAT};
    }

    private static double distanceM(double[] p, double cLat, double cLon) {
        double[] l = local(p, cLat, cLon);
        return Math.hypot(l[0], l[1]);
    }

    /** Distance from the centre (the origin of the local frame) to segment a–b. */
    private static double segmentDistanceM(double[] a, double[] b, double cLat, double cLon) {
        double[] p = local(a, cLat, cLon), q = local(b, cLat, cLon);
        double dx = q[0] - p[0], dy = q[1] - p[1];
        double len2 = dx * dx + dy * dy;
        // Projection of the origin onto the segment, clamped to its ends
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(p[0] * dx + p[1] * dy) / len2));
        return Math.hypot(p[0] + t * dx, p[1] + t * dy);
    }
}
