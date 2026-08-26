package it.unicas.omnimove.service;

import it.unicas.omnimove.client.BikeSharingClient;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;
import it.unicas.omnimove.util.GeoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Bike-sharing availability with a short in-process TTL cache
 * (same manual pattern as WeatherService), so map polling from many
 * browsers never hammers the provider API.
 */
@Service
public class BikeSharingService {

    private static final long CACHE_TTL_MS = 60_000;   // 60 s

    // Cassino city centre — search centre for the whole service area
    private static final double CASSINO_LAT = 41.4901;
    private static final double CASSINO_LON = 13.8303;

    private final BikeSharingClient client;
    private final int radiusKm;

    private List<BikeVehicleDTO> cachedVehicles;
    private long vehiclesTimestamp = 0;

    private List<BikeZoneDTO> cachedZones;
    private long zonesTimestamp = 0;

    public BikeSharingService(BikeSharingClient client,
                              @Value("${elerent.api.radius-km:5}") int radiusKm) {
        this.client = client;
        this.radiusKm = radiusKm;
    }

    public synchronized List<BikeVehicleDTO> getAvailableBikes() {
        long now = System.currentTimeMillis();
        if (cachedVehicles == null || now - vehiclesTimestamp > CACHE_TTL_MS) {
            cachedVehicles = client.getVehicles(CASSINO_LAT, CASSINO_LON, radiusKm);
            vehiclesTimestamp = now;
        }
        return cachedVehicles;
    }

    public synchronized List<BikeZoneDTO> getZones() {
        long now = System.currentTimeMillis();
        if (cachedZones == null || now - zonesTimestamp > CACHE_TTL_MS * 10) {   // zones change rarely
            cachedZones = client.getZones();
            zonesTimestamp = now;
        }
        return cachedZones;
    }

    // ── Journey-planning support ──────────────────────────────────────

    /** A vehicle plus its straight-line distance from the query point. */
    public record NearestVehicle(BikeVehicleDTO vehicle, int walkMetres) {}

    /** Zone problems a destination can have. */
    public enum ZoneIssue { OUT_OF_OPERATING_AREA, NO_PARKING }

    /**
     * Nearest available vehicle of the given type ("BIKE" | "SCOOTER") within
     * maxMetres of (lat, lon). Uses the same cached fleet as the map — no
     * extra provider calls. Battery level is deliberately NOT a filter: a
     * low-battery bike is still shown, flagged by the frontend battery bars.
     */
    public Optional<NearestVehicle> findNearest(double lat, double lon,
                                                String vehicleType, int maxMetres) {
        NearestVehicle best = null;
        for (BikeVehicleDTO v : getAvailableBikes()) {
            if (v.getLat() == null || v.getLon() == null) continue;
            if (Boolean.FALSE.equals(v.getIsAvailable())) continue;
            if (vehicleType != null && !vehicleType.equalsIgnoreCase(v.getVehicleType())) continue;
            int d = (int) Math.round(GeoUtils.haversineMetres(lat, lon, v.getLat(), v.getLon()));
            if (d <= maxMetres && (best == null || d < best.walkMetres())) {
                best = new NearestVehicle(v, d);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Checks the ride destination against the provider zones:
     *  - outside every operating zone (when at least one is defined) → OUT_OF_OPERATING_AREA
     *  - inside a no-parking zone → NO_PARKING
     * Zone types come from the provider unnormalised, so matching is lenient.
     */
    public Optional<ZoneIssue> checkDestinationZones(double lat, double lon) {
        List<BikeZoneDTO> zones = getZones();
        if (zones == null || zones.isEmpty()) return Optional.empty();

        boolean hasOperating = false, inOperating = false;
        for (BikeZoneDTO z : zones) {
            boolean contains = zoneContains(z, lat, lon);
            if (isForbidden(z)) {
                if (contains) return Optional.of(ZoneIssue.NO_PARKING);
            } else if (isOperating(z)) {
                hasOperating = true;
                if (contains) inOperating = true;
            }
        }
        if (hasOperating && !inOperating) return Optional.of(ZoneIssue.OUT_OF_OPERATING_AREA);
        return Optional.empty();
    }

    // ── Where a ride has to end when the destination itself is off-limits ────

    /** A legal place to leave the vehicle, and why the destination was not one. */
    public record DropOff(double lat, double lon, ZoneIssue reason) {}

    /**
     * Kept clear of the boundary by this much, so a drop-off computed on the
     * line does not land marginally on the wrong side of it.
     */
    private static final double EDGE_MARGIN_M = 20;

    /**
     * The nearest point to the destination where the ride may legally end.
     * Empty when the destination is fine as it stands, or when the zones carry
     * no geometry to work from.
     *
     * Riding to the edge and walking the rest is what a traveller would do
     * anyway; the planner used to compute the whole ride to a destination it
     * then declared unreachable, which is a plan nobody can follow.
     */
    public Optional<DropOff> findLegalDropOff(double destLat, double destLon) {
        Optional<ZoneIssue> issue = checkDestinationZones(destLat, destLon);
        if (issue.isEmpty()) return Optional.empty();

        List<BikeZoneDTO> zones = getZones();
        if (zones == null || zones.isEmpty()) return Optional.empty();

        return issue.get() == ZoneIssue.NO_PARKING
                ? stepOutOfForbidden(zones, destLat, destLon)
                : stepInsideOperating(zones, destLat, destLon);
    }

    /** Just outside the forbidden zone the destination fell into. */
    private Optional<DropOff> stepOutOfForbidden(List<BikeZoneDTO> zones, double lat, double lon) {
        for (BikeZoneDTO z : zones) {
            if (!isForbidden(z) || !zoneContains(z, lat, lon)) continue;

            if (z.getRadiusM() != null && z.getCenter() != null) {
                double[] p = GeoUtils.pointOnCircle(z.getCenter(), lat, lon,
                        z.getRadiusM() + EDGE_MARGIN_M);
                if (p != null) return Optional.of(new DropOff(p[0], p[1], ZoneIssue.NO_PARKING));
            }
            double[] edge = GeoUtils.closestPointOnOutline(lat, lon, z.getPolygon());
            double[] mid  = GeoUtils.centroid(z.getPolygon());
            if (edge != null && mid != null) {
                // Away from the middle of the zone, i.e. outwards across the edge
                double[] p = GeoUtils.nudgeToward(edge[0], edge[1],
                        2 * edge[0] - mid[0], 2 * edge[1] - mid[1], EDGE_MARGIN_M);
                return Optional.of(new DropOff(p[0], p[1], ZoneIssue.NO_PARKING));
            }
        }
        return Optional.empty();
    }

    /** The closest point just inside an operating zone. */
    private Optional<DropOff> stepInsideOperating(List<BikeZoneDTO> zones, double lat, double lon) {
        double[] best = null;
        double bestDist = Double.MAX_VALUE;

        for (BikeZoneDTO z : zones) {
            if (!isOperating(z)) continue;
            double[] candidate = null;

            if (z.getPolygon() != null && z.getPolygon().size() >= 3) {
                double[] edge = GeoUtils.closestPointOnOutline(lat, lon, z.getPolygon());
                double[] mid  = GeoUtils.centroid(z.getPolygon());
                if (edge != null && mid != null) {
                    candidate = GeoUtils.nudgeToward(edge[0], edge[1], mid[0], mid[1], EDGE_MARGIN_M);
                }
            } else if (z.getRadiusM() != null && z.getCenter() != null) {
                candidate = GeoUtils.pointOnCircle(z.getCenter(), lat, lon,
                        Math.max(0, z.getRadiusM() - EDGE_MARGIN_M));
            }
            if (candidate == null) continue;

            double d = GeoUtils.haversineMetres(lat, lon, candidate[0], candidate[1]);
            if (d < bestDist) { bestDist = d; best = candidate; }
        }
        return best == null ? Optional.empty()
                : Optional.of(new DropOff(best[0], best[1], ZoneIssue.OUT_OF_OPERATING_AREA));
    }

    // ── Zone predicates ─────────────────────────────────────────────────────

    private static boolean zoneContains(BikeZoneDTO z, double lat, double lon) {
        return GeoUtils.pointInPolygon(lat, lon, z.getPolygon())
                || (z.getRadiusM() != null && GeoUtils.inCircle(lat, lon, z.getCenter(), z.getRadiusM()));
    }

    private static boolean isForbidden(BikeZoneDTO z) {
        String type = z.getZoneType() != null ? z.getZoneType().toUpperCase() : "";
        return type.contains("NO_PARK") || type.contains("NO_GO") || type.contains("FORBIDDEN");
    }

    private static boolean isOperating(BikeZoneDTO z) {
        String type = z.getZoneType() != null ? z.getZoneType().toUpperCase() : "";
        return type.contains("OPERAT") || type.contains("SERVICE") || type.contains("ALLOWED");
    }
}
