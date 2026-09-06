package it.unicas.omnimove.client;

import com.fasterxml.jackson.databind.JsonNode;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.BikeZoneDTO;
import it.unicas.omnimove.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for the ATOM Mobility (RideAtom) Sharing API used by Elerent.
 *
 * Read-only integration: only /get-zones and /get-vehicles are called.
 * Both are POST and carry the operator's App-Public-Key in a header, which
 * is what selects Elerent's installation out of the shared platform.
 *
 * Only /get-zones is reachable with the public key alone. /get-vehicles
 * additionally requires a per-user bearer token (the API declares
 * `App-Public-Key + Authorization`, and answers 401 "Unauthorized Access"
 * without it); {@code elerent.api.user-token} carries one if Elerent ever
 * issues a service account, otherwise the simulated fleet keeps the map
 * populated — see {@code elerent.api.vehicles-fallback-mock}.
 *
 * Same contract as CassitrackClient: never throws. On any failure it logs
 * and returns an empty list, so OMNIMOVE keeps working without the layer.
 */
@Component
@Primary
@ConditionalOnProperty(name = "elerent.api.mock", havingValue = "false")
public class RideAtomClient implements BikeSharingClient {

    private static final Logger log = LoggerFactory.getLogger(RideAtomClient.class);

    /** Elerent operates several Italian cities: the nationwide zone list is ~670 polygons. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** ATOM zone titles look like {@code ID: 21675 "Cassino" (Cassino)}. */
    private static final Pattern ZONE_TITLE = Pattern.compile(
            "^ID:\\s*\\d+\\s*(?:\"([^\"]*)\")?\\s*(?:\\(([^)]*)\\))?\\s*$");

    private static final Map<String, String> ZONE_LABELS = Map.of(
            "PARKING_ZONE",      "parking area",
            "PAID_PARKING_ZONE", "paid parking area",
            "PARK_PLACE_ZONE",   "parking place",
            "NO_PARKING_ZONE",   "no parking",
            "NO_GO_ZONE",        "no-go area",
            "BONUS_ZONE",        "bonus area",
            "SPEED_LIMIT_ZONE",  "speed limited area");

    private final WebClient webClient;
    private final String publicKey;
    private final String userToken;
    private final double centreLat;
    private final double centreLon;
    private final int radiusKm;
    private final boolean vehiclesFallbackMock;
    private final ObjectProvider<MockElerentClient> mockProvider;

    /** The 401 on /get-vehicles is a standing condition, not an incident: warn once. */
    private boolean vehiclesUnauthorisedLogged = false;

    public RideAtomClient(
            @Value("${elerent.api.base-url}") String baseUrl,
            @Value("${elerent.api.public-key:}") String publicKey,
            @Value("${elerent.api.user-token:}") String userToken,
            @Value("${elerent.api.centre-lat:41.4901}") double centreLat,
            @Value("${elerent.api.centre-lon:13.8303}") double centreLon,
            @Value("${elerent.api.radius-km:5}") int radiusKm,
            @Value("${elerent.api.vehicles-fallback-mock:true}") boolean vehiclesFallbackMock,
            ObjectProvider<MockElerentClient> mockProvider) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
        this.publicKey = publicKey;
        this.userToken = userToken;
        this.centreLat = centreLat;
        this.centreLon = centreLon;
        this.radiusKm = radiusKm;
        this.vehiclesFallbackMock = vehiclesFallbackMock;
        this.mockProvider = mockProvider;
        log.info("RideAtomClient → {} (key {}, user token {}, vehicle fallback {})", baseUrl,
                publicKey.isBlank() ? "MISSING" : "configured",
                userToken.isBlank() ? "absent" : "configured",
                vehiclesFallbackMock ? "on" : "off");
    }

    @Override
    public List<BikeVehicleDTO> getVehicles(double lat, double lon, int radiusKm) {
        if (publicKey.isBlank()) {
            log.warn("RideAtom: App-Public-Key not configured, returning no vehicles");
            return Collections.emptyList();
        }
        try {
            WebClient.RequestBodySpec request = webClient.post()
                    .uri("/get-vehicles")
                    .header("App-Public-Key", publicKey);
            if (!userToken.isBlank()) {
                request = request.header("Authorization", "Bearer " + userToken);
            }
            JsonNode root = request
                    .bodyValue(Map.of(
                            "user_latitude", lat,
                            "user_longitude", lon,
                            "radius_in_km", radiusKm))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);
            if (root == null) return Collections.emptyList();

            List<BikeVehicleDTO> result = new ArrayList<>();
            for (JsonNode v : root.path("vehicles")) {
                JsonNode coords = v.path("coordinates");
                Double vLat = firstDouble(coords, "latitude", "lat");
                Double vLon = firstDouble(coords, "longitude", "lng", "lon");
                if (vLat == null || vLon == null) {
                    // Older installations flatten the coordinates onto the vehicle
                    vLat = firstDouble(v, "latitude", "lat");
                    vLon = firstDouble(v, "longitude", "lng", "lon");
                }
                if (vLat == null || vLon == null) continue;

                String type = normaliseType(firstText(v, "type", "vehicle_type"));
                if (type == null) continue;   // cars, vans, boats: not a bike-layer marker

                result.add(BikeVehicleDTO.builder()
                        .bikeId(firstText(v, "id", "vehicle_id"))
                        .plate(firstText(v, "nr", "plate", "number"))
                        .lat(vLat)
                        .lon(vLon)
                        .batteryPct(firstInt(v, "battery_level", "battery"))
                        .vehicleType(type)
                        .isAvailable(!v.path("is_active_ride").asBoolean(false))
                        .lastUpdated(Instant.now())
                        .build());
            }
            log.debug("RideAtom: {} vehicles within {} km", result.size(), radiusKm);
            return result;
        } catch (WebClientResponseException.Unauthorized e) {
            return vehiclesUnauthorised();
        } catch (Exception e) {
            log.warn("RideAtom /get-vehicles unreachable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * /get-vehicles is the one endpoint we need that the App-Public-Key does not
     * open on its own. Rather than emptying the map — which would also remove the
     * bike and scooter options from the planner — fall back to the simulated
     * fleet, while the zones around it stay real.
     */
    private List<BikeVehicleDTO> vehiclesUnauthorised() {
        MockElerentClient fallback = vehiclesFallbackMock ? mockProvider.getIfAvailable() : null;
        if (!vehiclesUnauthorisedLogged) {
            vehiclesUnauthorisedLogged = true;
            log.warn("RideAtom /get-vehicles refused the App-Public-Key alone (401): the endpoint "
                    + "also needs a user token. Set elerent.api.user-token once Elerent issues one. "
                    + "{}", fallback != null
                            ? "Serving the simulated fleet meanwhile (zones stay real)."
                            : "No vehicles will be shown.");
        }
        return fallback != null
                ? fallback.getVehicles(centreLat, centreLon, radiusKm)
                : Collections.emptyList();
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
                    .block(TIMEOUT);
            if (root == null) return Collections.emptyList();

            // The live API answers with a bare array; keep the wrapped shape working too
            JsonNode zones = root.isArray() ? root : root.path("zones");
            List<BikeZoneDTO> result = new ArrayList<>();
            int received = 0;
            for (JsonNode z : zones) {
                received++;
                BikeZoneDTO zone = parseZone(z);
                if (zone != null && nearCentre(zone)) result.add(zone);
            }
            log.debug("RideAtom: {} zones within {} km of the service centre (out of {} nationwide)",
                    result.size(), radiusKm, received);
            return result;
        } catch (Exception e) {
            log.warn("RideAtom /get-zones unreachable: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * zone_area is a GeoJSON polygon — coordinates are [longitude, latitude] and
     * come nested one level per ring, the opposite order and shape of the
     * [lat, lon] pairs the frontend and GeoUtils work with. Circular station
     * zones carry zone_point + zone_radius instead and leave zone_area null.
     */
    private static BikeZoneDTO parseZone(JsonNode z) {
        String type = upper(firstText(z, "zone_type", "type"));
        BikeZoneDTO.BikeZoneDTOBuilder b = BikeZoneDTO.builder()
                .zoneId(firstText(z, "zone_id", "id"))
                .title(zoneTitle(firstText(z, "zone_title", "title"), type))
                .zoneType(type)
                .color(colour(firstText(z, "zone_color", "color")));

        List<double[]> polygon = new ArrayList<>();
        for (JsonNode ring : z.path("zone_area").path("coordinates")) {
            for (JsonNode p : ring) {
                if (p.size() >= 2) polygon.add(new double[]{p.get(1).asDouble(), p.get(0).asDouble()});
            }
            break;   // outer ring only: BikeZoneDTO has no notion of holes
        }
        if (!polygon.isEmpty()) b.polygon(polygon);

        JsonNode point = z.path("zone_point");
        Double cLat = firstDouble(point, "latitude", "lat");
        Double cLon = firstDouble(point, "longitude", "lng", "lon");
        Integer radius = firstInt(z, "zone_radius", "radius");
        if (cLat != null && cLon != null) b.center(new double[]{cLat, cLon}).radiusM(radius);

        BikeZoneDTO zone = b.build();
        return zone.getPolygon() == null && zone.getCenter() == null ? null : zone;
    }

    /** Keeps the zones of the served city: the account also covers other Elerent cities. */
    private boolean nearCentre(BikeZoneDTO zone) {
        double limit = radiusKm * 1000.0;
        List<double[]> polygon = zone.getPolygon();
        if (polygon != null) {
            // A zone large enough to hold the centre counts even if every vertex is far away
            if (GeoUtils.pointInPolygon(centreLat, centreLon, polygon)) return true;
            for (double[] p : polygon) {
                if (GeoUtils.haversineMetres(centreLat, centreLon, p[0], p[1]) <= limit) return true;
            }
        }
        double[] c = zone.getCenter();
        if (c != null) {
            double radius = zone.getRadiusM() != null ? zone.getRadiusM() : 0;
            return GeoUtils.haversineMetres(centreLat, centreLon, c[0], c[1]) <= limit + radius;
        }
        return false;
    }

    /** "ID: 21675 \"Cassino\" (Cassino)" → "Elerent — parking area (Cassino)". */
    private static String zoneTitle(String raw, String type) {
        String label = ZONE_LABELS.getOrDefault(type, "zone");
        if (raw == null) return "Elerent — " + label;
        Matcher m = ZONE_TITLE.matcher(raw.trim());
        if (!m.matches()) return raw;
        String name = m.group(1) != null && !m.group(1).isBlank() ? m.group(1) : m.group(2);
        return name != null && !name.isBlank()
                ? "Elerent — " + label + " (" + name + ")"
                : "Elerent — " + label;
    }

    /** RideAtom sends bare hex ("21C378"); Leaflet needs the CSS form. */
    private static String colour(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        return v.startsWith("#") ? v : "#" + v;
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

    private static String upper(String raw) {
        return raw == null ? null : raw.trim().toUpperCase();
    }

    /**
     * The platform enumerates 21 vehicle types; the map layer and the planner
     * only know two. Null for anything that is neither, so it is left out
     * rather than drawn as a bike.
     */
    private static String normaliseType(String raw) {
        String upper = upper(raw);
        if (upper == null) return "BIKE";
        if (upper.contains("SCOOT") || upper.contains("MOPED")) return "SCOOTER";
        if (upper.contains("BIKE")) return "BIKE";
        return null;
    }
}
