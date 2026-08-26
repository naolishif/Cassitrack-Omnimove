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
}
