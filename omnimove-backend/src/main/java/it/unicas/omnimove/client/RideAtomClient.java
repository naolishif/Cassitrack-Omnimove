package it.unicas.omnimove.client;

import com.fasterxml.jackson.databind.JsonNode;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the ATOM Mobility (RideAtom) Sharing API used by Elerent.
 *
 * Read-only integration: only /get-vehicles and /get-zones are called.
 * All Sharing endpoints are POST with an App-Public-Key header; a public
 * key alone is enough for these two (no user token needed).
 *
 * Same contract as CassitrackClient: if the provider is unreachable or
 * the key is missing, log a warning and return empty lists — OMNIMOVE
 * keeps working without the bike layer.
 */
@Component
@ConditionalOnProperty(name = "elerent.api.mock", havingValue = "false")
public class RideAtomClient implements BikeSharingClient {

    private static final Logger log = LoggerFactory.getLogger(RideAtomClient.class);

    private final WebClient webClient;
    private final String publicKey;

    public RideAtomClient(
            @Value("${elerent.api.base-url}") String baseUrl,
            @Value("${elerent.api.public-key:}") String publicKey) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.publicKey = publicKey;
        log.info("RideAtomClient → {} (key {})", baseUrl,
                publicKey.isBlank() ? "MISSING" : "configured");
    }

    @Override
    public List<BikeVehicleDTO> getVehicles(double lat, double lon, int radiusKm) {
        if (publicKey.isBlank()) {
            log.warn("RideAtom: App-Public-Key not configured, returning no vehicles");
            return Collections.emptyList();
        }
        try {
            JsonNode root = webClient.post()
                    .uri("/get-vehicles")
                    .header("App-Public-Key", publicKey)
                    .bodyValue(Map.of(
                            "user_latitude", lat,
                            "user_longitude", lon,
                            "radius_in_km", radiusKm))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (root == null) return Collections.emptyList();

            List<BikeVehicleDTO> result = new ArrayList<>();
            for (JsonNode v : root.path("vehicles")) {
                Double vLat = firstDouble(v, "latitude", "lat");
                Double vLon = firstDouble(v, "longitude", "lng", "lon");
                if (vLat == null || vLon == null) continue;
                result.add(BikeVehicleDTO.builder()
                        .bikeId(firstText(v, "id", "vehicle_id"))
                        .plate(firstText(v, "nr", "plate", "number"))
                        .lat(vLat)
                        .lon(vLon)
                        .batteryPct(firstInt(v, "battery_level", "battery"))
                        .vehicleType(normaliseType(firstText(v, "type", "vehicle_type")))
                        .isAvailable(true)
                        .lastUpdated(Instant.now())
                        .build());
            }
            log.debug("RideAtom: {} vehicles within {} km", result.size(), radiusKm);
            return result;
        } catch (Exception e) {
            log.warn("RideAtom /get-vehicles unreachable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<BikeZoneDTO> getZones() {
        if (publicKey.isBlank()) return Collections.emptyList();
        try {
            JsonNode root = webClient.post()
                    .uri("/get-zones")
                    .header("App-Public-Key", publicKey)
                    .bodyValue(Map.of())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (root == null) return Collections.emptyList();

            List<BikeZoneDTO> result = new ArrayList<>();
            JsonNode zones = root.has("zones") ? root.path("zones") : root;
            for (JsonNode z : zones) {
                BikeZoneDTO.BikeZoneDTOBuilder b = BikeZoneDTO.builder()
                        .zoneId(firstText(z, "zone_id", "id"))
                        .title(firstText(z, "zone_title", "title"))
                        .zoneType(firstText(z, "zone_type", "type"))
                        .color(firstText(z, "zone_color", "color"));

                // zone_area = polygon points; zone_point + zone_radius = circle
                List<double[]> polygon = new ArrayList<>();
                for (JsonNode p : z.path("zone_area")) {
                    Double pLat = firstDouble(p, "latitude", "lat");
                    Double pLon = firstDouble(p, "longitude", "lng", "lon");
                    if (pLat != null && pLon != null) polygon.add(new double[]{pLat, pLon});
                }
                if (!polygon.isEmpty()) b.polygon(polygon);

                JsonNode point = z.path("zone_point");
                Double cLat = firstDouble(point, "latitude", "lat");
                Double cLon = firstDouble(point, "longitude", "lng", "lon");
                Integer radius = firstInt(z, "zone_radius", "radius");
                if (cLat != null && cLon != null) b.center(new double[]{cLat, cLon}).radiusM(radius);

                result.add(b.build());
            }
            return result;
        } catch (Exception e) {
            log.warn("RideAtom /get-zones unreachable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Lenient JSON helpers (field names vary across ATOM deployments) ──

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode n = node.path(k);
            if (!n.isMissingNode() && !n.isNull()) return n.asText();
        }
        return null;
    }

    private static Double firstDouble(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode n = node.path(k);
            if (n.isNumber() || (n.isTextual() && !n.asText().isBlank())) return n.asDouble();
        }
        return null;
    }

    private static Integer firstInt(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode n = node.path(k);
            if (n.isNumber() || (n.isTextual() && !n.asText().isBlank())) return n.asInt();
        }
        return null;
    }

    private static String normaliseType(String raw) {
        if (raw == null) return "BIKE";
        String upper = raw.toUpperCase();
        return upper.contains("SCOOT") ? "SCOOTER" : "BIKE";
    }
}
