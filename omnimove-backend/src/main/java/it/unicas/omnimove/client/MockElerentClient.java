package it.unicas.omnimove.client;

import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simulated Elerent fleet for Cassino, active while no RideAtom
 * App-Public-Key is available (elerent.api.mock=true, the default).
 *
 * Positions are spread around real Cassino landmarks with a fixed
 * random seed, so the fleet is stable across calls and restarts —
 * demos look consistent and screenshots are reproducible.
 */
@Component
@ConditionalOnProperty(name = "elerent.api.mock", havingValue = "true", matchIfMissing = true)
public class MockElerentClient implements BikeSharingClient {

    private static final Logger log = LoggerFactory.getLogger(MockElerentClient.class);

    // [lat, lon, bikes to place nearby]
    private static final double[][] SPOTS = {
        {41.4901, 13.8303, 4},   // centro (Piazza De Gasperi / Corso della Repubblica)
        {41.4874, 13.8317, 3},   // stazione FS Cassino
        {41.4756, 13.8100, 4},   // campus Folcara (Università)
        {41.4920, 13.8250, 2},   // Via Ausonia
        {41.4855, 13.8365, 2},   // ospedale S. Scolastica
    };

    private final List<BikeVehicleDTO> fleet;
    private final List<BikeZoneDTO> zones;

    public MockElerentClient() {
        this.fleet = buildFleet();
        this.zones = buildZones();
        log.info("MockElerentClient active — {} simulated vehicles in Cassino "
                + "(set elerent.api.mock=false + ELERENT_PUBLIC_KEY for the real API)",
                fleet.size());
    }

    @Override
    public List<BikeVehicleDTO> getVehicles(double lat, double lon, int radiusKm) {
        return fleet;
    }

    @Override
    public List<BikeZoneDTO> getZones() {
        return zones;
    }

    private static List<BikeVehicleDTO> buildFleet() {
        Random rnd = new Random(42);   // fixed seed → stable fleet
        List<BikeVehicleDTO> bikes = new ArrayList<>();
        int n = 1;
        for (double[] spot : SPOTS) {
            for (int i = 0; i < (int) spot[2]; i++) {
                // ~±250 m jitter around the landmark
                double lat = spot[0] + (rnd.nextDouble() - 0.5) * 0.0045;
                double lon = spot[1] + (rnd.nextDouble() - 0.5) * 0.0045;
                boolean scooter = n % 5 == 0;   // 1 scooter every 5 vehicles
                bikes.add(BikeVehicleDTO.builder()
                        .bikeId("ELR-" + String.format("%03d", n))
                        .plate((scooter ? "S" : "B") + String.format("%03d", n))
                        .lat(round6(lat))
                        .lon(round6(lon))
                        .batteryPct(35 + rnd.nextInt(61))   // 35–95 %
                        .vehicleType(scooter ? "SCOOTER" : "BIKE")
                        .isAvailable(true)
                        .lastUpdated(Instant.now())
                        .build());
                n++;
            }
        }
        return bikes;
    }

    private static List<BikeZoneDTO> buildZones() {
        List<BikeZoneDTO> zones = new ArrayList<>();
        // Operating area: rough polygon around Cassino urban core + campus
        zones.add(BikeZoneDTO.builder()
                .zoneId("Z-CASSINO")
                .title("Elerent Cassino — operating area")
                .zoneType("OPERATING")
                .color("#3b82f6")
                .polygon(List.of(
                        new double[]{41.4990, 13.8180},
                        new double[]{41.4975, 13.8420},
                        new double[]{41.4830, 13.8440},
                        new double[]{41.4700, 13.8210},
                        new double[]{41.4720, 13.8020},
                        new double[]{41.4880, 13.8050}))
                .build());
        // No-parking example: pedestrian core
        zones.add(BikeZoneDTO.builder()
                .zoneId("Z-CENTRO-NP")
                .title("Centro storico — no parking")
                .zoneType("NO_PARKING")
                .color("#ef4444")
                .center(new double[]{41.4903, 13.8308})
                .radiusM(120)
                .build());
        return zones;
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
