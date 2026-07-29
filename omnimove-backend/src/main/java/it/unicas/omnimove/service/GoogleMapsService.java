package it.unicas.omnimove.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

/**
 * Client for the Google Maps Distance Matrix API.
 *
 * Given an origin and a destination, returns the travel duration
 * accounting for live traffic. departure_time defaults to "now", but a
 * future Instant can be passed to estimate traffic at the moment the
 * vehicle actually travels each leg (used by the journey planner).
 *
 * If the API key is missing or the call fails, returns Optional.empty()
 * so the caller can fall back to haversine-based estimates.
 */
@Service
@Slf4j
public class GoogleMapsService {

    private static final String BASE_URL =
            "https://maps.googleapis.com/maps/api/distancematrix/json";

    @Value("${google.maps.api-key:}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GoogleMapsService(WebClient.Builder webClientBuilder,
                             ObjectMapper objectMapper) {
        this.webClient    = webClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    /**
     * Result from the Distance Matrix API.
     *
     * @param durationSeconds          travel time WITHOUT traffic (baseline)
     * @param durationInTrafficSeconds travel time WITH real-time traffic
     * @param distanceMetres           road distance in metres
     */
    public record TrafficResult(
            long durationSeconds,
            long durationInTrafficSeconds,
            long distanceMetres
    ) {}

    /**
     * Query Google Maps for travel time between two points with live traffic.
     *
     * @param originLat  origin latitude
     * @param originLon  origin longitude
     * @param destLat    destination latitude
     * @param destLon    destination longitude
     * @return Optional with traffic data, or empty if unavailable
     */
    public Optional<TrafficResult> getTravelTime(
            double originLat, double originLon,
            double destLat,   double destLon) {
        return getTravelTime(originLat, originLon, destLat, destLon, "driving");
    }
    public Optional<TrafficResult> getTravelTime(
            double originLat, double originLon,
            double destLat,   double destLon,
            String mode) {
        return getTravelTime(originLat, originLon, destLat, destLon, mode, null);
    }

    /**
     * @param departureTime istante di partenza per il traffico. null = "now".
     *        Deve essere nel presente o futuro: Google rifiuta il passato.
     *        Ignorato se non driving (il traffico serve solo in auto).
     */
    public Optional<TrafficResult> getTravelTime(
            double originLat, double originLon,
            double destLat,   double destLon,
            String mode, java.time.Instant departureTime) {

        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Google Maps API key non configurata — uso fallback");
            return Optional.empty();
        }
        boolean driving = "driving".equalsIgnoreCase(mode);

        // Google rifiuta un departure_time nel passato: sotto "now" ci ripieghiamo.
        final String departureParam;
        if (driving) {
            if (departureTime == null || !departureTime.isAfter(java.time.Instant.now())) {
                departureParam = "now";
            } else {
                departureParam = String.valueOf(departureTime.getEpochSecond());
            }
        } else {
            departureParam = null;
        }

        try {
            String origins      = originLat + "," + originLon;
            String destinations = destLat   + "," + destLon;

            String response = webClient.get()
                    .uri(b -> {
                        b.queryParam("origins",      origins)
                                .queryParam("destinations", destinations)
                                .queryParam("mode",         mode)
                                .queryParam("key",          apiKey);
                        if (driving) {
                            b.queryParam("departure_time", departureParam)
                                    .queryParam("traffic_model",  "best_guess");
                        }
                        return b.build();
                    })
                    .retrieve().bodyToMono(String.class).block();

            return parseResponse(response);
        } catch (Exception e) {
            log.warn("Google Maps API fallita ({}): {}", mode, e.getMessage());
            return Optional.empty();
        }
    }
    private Optional<TrafficResult> parseResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            String topStatus = root.path("status").asText();
            if (!"OK".equals(topStatus)) {
                log.warn("Google Maps API returned status: {}", topStatus);
                return Optional.empty();
            }

            JsonNode element = root
                    .path("rows").get(0)
                    .path("elements").get(0);

            String elementStatus = element.path("status").asText();
            if (!"OK".equals(elementStatus)) {
                log.warn("Distance Matrix element status: {}", elementStatus);
                return Optional.empty();
            }

            long duration       = element.path("duration").path("value").asLong();
            long distanceMetres = element.path("distance").path("value").asLong();

            JsonNode trafficNode = element.path("duration_in_traffic");
            long durationInTraffic = trafficNode.isMissingNode()
                    ? duration
                    : trafficNode.path("value").asLong();

            log.debug("Google Maps: dist={}m, base={}s, traffic={}s",
                    distanceMetres, duration, durationInTraffic);

            return Optional.of(new TrafficResult(
                    duration, durationInTraffic, distanceMetres));

        } catch (Exception e) {
            log.error("Failed to parse Google Maps response: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
