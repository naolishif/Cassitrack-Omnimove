package it.unicas.omnimove.util;

import java.util.List;

/**
 * Shared geo helpers. Points are (lat, lon) in degrees; polygon vertices are
 * double[]{lat, lon} — the same shape used by BikeZoneDTO and JourneyLeg
 * stop_coords.
 */
public final class GeoUtils {

    private GeoUtils() {}

    /** Great-circle distance in metres. */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    /**
     * Ray-casting point-in-polygon. Adequate for city-scale zones, where
     * treating lat/lon as planar coordinates introduces negligible error.
     */
    public static boolean pointInPolygon(double lat, double lon, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double latI = polygon.get(i)[0], lonI = polygon.get(i)[1];
            double latJ = polygon.get(j)[0], lonJ = polygon.get(j)[1];
            if ((lonI > lon) != (lonJ > lon)
                    && lat < (latJ - latI) * (lon - lonI) / (lonJ - lonI) + latI) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** True if the point lies within radiusM metres of center ({lat, lon}). */
    public static boolean inCircle(double lat, double lon, double[] center, int radiusM) {
        if (center == null || center.length < 2) return false;
        return haversineMetres(lat, lon, center[0], center[1]) <= radiusM;
    }

    // ── Local planar frame ────────────────────────────────────────────────
    // Over a few kilometres a degree of latitude and a degree of longitude are
    // near enough constant that the zone maths can be done in plain metres.
    // Everything below centres that frame on the query point, so the error
    // never accumulates over distance.

    private static final double M_PER_DEG_LAT = 110540.0;

    private static double mPerDegLon(double lat) {
        return 111320.0 * Math.cos(Math.toRadians(lat));
    }

    /**
     * Closest point on a polygon's outline to (lat, lon), as {lat, lon}.
     * Returns null for a degenerate polygon. Note this is the outline, not the
     * area: a point already inside still gets its nearest edge back, which is
     * what "leave this zone by the shortest way" needs.
     */
    public static double[] closestPointOnOutline(double lat, double lon, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 2) return null;
        double mLon = mPerDegLon(lat);
        double bestX = 0, bestY = 0, bestD2 = Double.MAX_VALUE;

        for (int i = 0; i < polygon.size(); i++) {
            double[] a = polygon.get(i);
            double[] b = polygon.get((i + 1) % polygon.size());
            double ax = (a[1] - lon) * mLon, ay = (a[0] - lat) * M_PER_DEG_LAT;
            double bx = (b[1] - lon) * mLon, by = (b[0] - lat) * M_PER_DEG_LAT;

            // Project the origin onto segment AB, clamped to its ends
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx, py = ay + t * dy;

            double d2 = px * px + py * py;
            if (d2 < bestD2) { bestD2 = d2; bestX = px; bestY = py; }
        }
        return new double[]{ lat + bestY / M_PER_DEG_LAT, lon + bestX / mLon };
    }

    /** Average of the vertices — good enough to tell "inwards" from "outwards". */
    public static double[] centroid(List<double[]> polygon) {
        if (polygon == null || polygon.isEmpty()) return null;
        double lat = 0, lon = 0;
        for (double[] v : polygon) { lat += v[0]; lon += v[1]; }
        return new double[]{ lat / polygon.size(), lon / polygon.size() };
    }

    /** (lat, lon) moved `metres` towards (towardLat, towardLon), never overshooting it. */
    public static double[] nudgeToward(double lat, double lon,
                                       double towardLat, double towardLon, double metres) {
        double mLon = mPerDegLon(lat);
        double dx = (towardLon - lon) * mLon, dy = (towardLat - lat) * M_PER_DEG_LAT;
        double d = Math.hypot(dx, dy);
        if (d < 1e-6) return new double[]{ lat, lon };
        double f = Math.min(1.0, metres / d);
        return new double[]{ lat + (dy * f) / M_PER_DEG_LAT, lon + (dx * f) / mLon };
    }

    /**
     * The point at `radiusM` from `center`, on the ray towards (lat, lon).
     * Used to step just outside a circular zone, or just inside one.
     */
    public static double[] pointOnCircle(double[] center, double lat, double lon, double radiusM) {
        if (center == null || center.length < 2) return null;
        double mLon = mPerDegLon(center[0]);
        double dx = (lon - center[1]) * mLon, dy = (lat - center[0]) * M_PER_DEG_LAT;
        double d = Math.hypot(dx, dy);
        // Destination sitting exactly on the centre: any bearing will do, take north
        if (d < 1e-6) return new double[]{ center[0] + radiusM / M_PER_DEG_LAT, center[1] };
        double f = radiusM / d;
        return new double[]{ center[0] + (dy * f) / M_PER_DEG_LAT, center[1] + (dx * f) / mLon };
    }
}
