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
            String type = z.getZoneType() != null ? z.getZoneType().toUpperCase() : "";
            boolean contains = GeoUtils.pointInPolygon(lat, lon, z.getPolygon())
                    || (z.getRadiusM() != null && GeoUtils.inCircle(lat, lon, z.getCenter(), z.getRadiusM()));
            if (type.contains("NO_PARK") || type.contains("NO_GO") || type.contains("FORBIDDEN")) {
                if (contains) return Optional.of(ZoneIssue.NO_PARKING);
            } else if (type.contains("OPERAT") || type.contains("SERVICE") || type.contains("ALLOWED")) {
                hasOperating = true;
                if (contains) inOperating = true;
            }
        }
        if (hasOperating && !inOperating) return Optional.of(ZoneIssue.OUT_OF_OPERATING_AREA);
        return Optional.empty();
    }
}
