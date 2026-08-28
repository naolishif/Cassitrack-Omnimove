package it.unicas.omnimove.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client for the Google Maps Distance Matrix and Directions APIs.
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

    // Distance Matrix answers with numbers only. Drawing the path on the map
    // needs geometry, and geometry only comes from Directions.
    private static final String DIRECTIONS_URL =
            "https://maps.googleapis.com/maps/api/directions/json";

    private static final String GEOCODE_URL =
            "https://maps.googleapis.com/maps/api/geocode/json";

    @Value("${google.maps.api-key:}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    /** The administrator's switches: every call here can be turned off. */
    private final GoogleApiSettingsService settings;

    public GoogleMapsService(WebClient.Builder webClientBuilder,
                             ObjectMapper objectMapper,
                             GoogleApiSettingsService settings) {
        this.webClient    = webClientBuilder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
        this.settings     = settings;
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
    /**
     * A routed path: the same numbers Distance Matrix gives, plus the shape to
     * draw it with.
     *
     * @param points [lat, lon] pairs following the road, decoded from Google's
     *               overview polyline. Never null; empty only if Google sent a
     *               route without geometry.
     */
    public record RouteResult(
            long durationSeconds,
            long distanceMetres,
            List<double[]> points
    ) {}

    /**
     * Fastest route between two points, with its geometry.
     *
     * @param mode "walking" or "bicycling". Google has no scooter profile —
     *             bicycling is the closest fit and is what the planner already
     *             asks for when timing a scooter ride.
     */
    /**
     * The street a point sits on, for a place the traveller tapped on the map.
     *
     * Returns the road name alone — "Via Santa Scolastica" — not the postal
     * address: a house number is precision the tap never had, and printing one
     * would claim the traveller chose a doorway rather than a spot on a street.
     *
     * Empty on any failure, including a missing key. The caller is expected to
     * fall back to the coordinates: a label is a convenience, and losing it must
     * never cost the journey.
     */
    public Optional<String> reverseGeocodeStreet(double lat, double lon) {
        if (!settings.isGeocodingEnabled()) {
            log.debug("Geocoding disabled by the administrator");
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Google Maps API key non configurata — nessun geocoding");
            return Optional.empty();
        }
        try {
            // result_type=route asks Google for the road itself rather than the
            // building, the postcode and the province it would otherwise return
            String url = GEOCODE_URL
                    + "?latlng="      + lat + "," + lon
                    + "&result_type=route"
                    + "&language=it"
                    + "&key="         + apiKey;

            String response = webClient.get()
                    .uri(URI.create(url))
                    .retrieve().bodyToMono(String.class).block();
            if (response == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText();
            if (!"OK".equals(status)) {
                // ZERO_RESULTS is ordinary out in the countryside, not a fault
                if (!"ZERO_RESULTS".equals(status))
                    log.warn("Geocoding returned {}: {}", status, root.path("error_message").asText(""));
                return Optional.empty();
            }

            for (JsonNode result : root.path("results")) {
                for (JsonNode comp : result.path("address_components")) {
                    for (JsonNode type : comp.path("types")) {
                        if ("route".equals(type.asText())) {
                            String name = comp.path("long_name").asText("");
                            if (!name.isBlank()) return Optional.of(name);
                        }
                    }
                }
            }
            return Optional.empty();

        } catch (Exception e) {
            log.warn("Geocoding failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<RouteResult> getRoute(double originLat, double originLon,
                                          double destLat,   double destLon,
                                          String mode) {
        // The one Google call that had no switch. Off, the legs are still
        // planned — only their drawn shape falls back to a straight line
        // between the stops.
        if (!settings.isRouteShapeEnabled()) {
            log.debug("Route shapes disabled by the administrator");
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Google Maps API key non configurata — nessuna geometria");
            return Optional.empty();
        }
        try {
            // Coordinates are plain numbers and mode is a fixed keyword, so the
            // URL needs no escaping beyond what the values already are.
            String url = DIRECTIONS_URL
                    + "?origin="      + originLat + "," + originLon
                    + "&destination=" + destLat   + "," + destLon
                    + "&mode="        + mode
                    + "&key="         + apiKey;

            String response = webClient.get()
                    .uri(URI.create(url))
                    .retrieve().bodyToMono(String.class).block();

            return parseDirections(response);
        } catch (Exception e) {
            log.warn("Google Directions fallita ({}): {}", mode, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<RouteResult> parseDirections(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            String status = root.path("status").asText();
            if (!"OK".equals(status)) {
                log.warn("Google Directions returned status: {}", status);
                return Optional.empty();
            }

            JsonNode route = root.path("routes").get(0);
            JsonNode leg   = route.path("legs").get(0);

            long duration = leg.path("duration").path("value").asLong();
            long distance = leg.path("distance").path("value").asLong();
            List<double[]> points =
                    decodePolyline(route.path("overview_polyline").path("points").asText());

            log.debug("Google Directions: dist={}m, {}s, {} punti",
                    distance, duration, points.size());

            return Optional.of(new RouteResult(duration, distance, points));
        } catch (Exception e) {
            log.warn("Parsing Google Directions fallito: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Google's encoded polyline format. Each coordinate travels as a delta from
     * the previous one: zig-zag encoded so the sign rides in the low bit, then
     * split into 5-bit chunks with the high bit meaning "another chunk follows",
     * each chunk offset by 63 to land in printable ASCII. Values are fixed point
     * with five decimal places.
     */
    static List<double[]> decodePolyline(String encoded) {
        List<double[]> points = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return points;

        int index = 0, lat = 0, lon = 0;
        while (index < encoded.length()) {
            int shift = 0, result = 0, b;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            shift = 0; result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lon += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);

            points.add(new double[]{ lat / 1e5, lon / 1e5 });
        }
        return points;
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
