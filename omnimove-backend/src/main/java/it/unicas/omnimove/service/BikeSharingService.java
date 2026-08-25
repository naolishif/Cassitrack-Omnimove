package it.unicas.omnimove.service;

import it.unicas.omnimove.client.BikeSharingClient;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

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
}
