package it.unicas.omnimove.client;

import com.fasterxml.jackson.databind.JsonNode;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Vehicle positions from Elerent's operator dashboard API.
 *
 * The consumer Sharing API cannot serve them: /get-vehicles answers only on
 * behalf of a signed-in rider, and Elerent has no rider account to lend us.
 * Their dashboard exposes the fleet without any user identity, so that is
 * where the positions come from, while the zones keep coming from the public
 * Sharing API through {@link RideAtomClient}.
 *
 * Scope is deliberate and narrow. The dashboard credentials also open customer
 * records, identity-document review and ride operations; this client calls
 * exactly two endpoints — the login and the vehicle list — and nothing else
 * in the codebase holds the token.
 */
@Component
public class ElerentDashboardClient {

    private static final Logger log = LoggerFactory.getLogger(ElerentDashboardClient.class);

    private static final String LOGIN_PATH = "/api/v2/admin/login/openapi";
    private static final String VEHICLES_PATH = "/api/v2/admin/vehicles";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int PAGE_LENGTH = 100;
    private static final int MAX_PAGES = 10;        // 240 vehicles today; a guard, not a limit
    /** Renew this long before the token actually expires. */
    private static final long EXPIRY_MARGIN_MS = 60 * 60 * 1000;
    /** Which icon is a bike and which a scooter changes only when the fleet does. */
    private static final long ICON_MAP_TTL_MS = 6 * 60 * 60 * 1000;

    private final WebClient webClient;
    private final String email;
    private final String password;

    private String token;
    private long tokenExpiresAt = 0;

    private Map<Integer, String> typeByIcon = Map.of();
    private long typeByIconAt = 0;

    public ElerentDashboardClient(
            @Value("${elerent.api.dashboard-url:https://app.rideatom.com}") String dashboardUrl,
            @Value("${elerent.api.dashboard-email:}") String email,
            @Value("${elerent.api.dashboard-password:}") String password) {
        this.webClient = WebClient.builder()
                .baseUrl(dashboardUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
        this.email = email;
        this.password = password;
        log.info("ElerentDashboardClient → {} ({})", dashboardUrl,
                isConfigured() ? "credentials configured" : "no credentials, disabled");
    }

    public boolean isConfigured() {
        return !email.isBlank() && !password.isBlank();
    }

    /**
     * Vehicles available for hire within radiusKm of (lat, lon).
     *
     * Two calls: the paginated list carries the exact battery level and the
     * plate, the map view carries the icon, which is the only thing in this
     * API that tells a bike from a scooter. They are joined on the vehicle id.
     *
     * Never throws. An empty Optional means the provider could not be reached,
     * which is not the same as an empty list: the list is Elerent answering that
     * nothing is available right now, and the caller must not paper over that
     * with simulated vehicles.
     */
    public synchronized Optional<List<BikeVehicleDTO>> getVehicles(double lat, double lon, int radiusKm) {
        if (!isConfigured()) return Optional.empty();
        try {
            Map<Integer, String> types = typeByIcon();
            Map<Long, Integer> iconByVehicle = iconByVehicle();

            List<BikeVehicleDTO> result = new ArrayList<>();
            double limitMetres = radiusKm * 1000.0;

            for (JsonNode v : availableVehicles()) {
                JsonNode coords = v.path("coordinates");
                if (!coords.hasNonNull("latitude") || !coords.hasNonNull("longitude")) continue;
                double vLat = coords.path("latitude").asDouble();
                double vLon = coords.path("longitude").asDouble();
                // The account covers several Elerent towns; keep the served one
                if (GeoUtils.haversineMetres(lat, lon, vLat, vLon) > limitMetres) continue;

                long id = v.path("id").asLong();
                Integer icon = iconByVehicle.get(id);
                String type = icon != null ? types.get(icon) : null;
                if (type == null) type = "BIKE";   // unknown icon: still worth showing

                result.add(BikeVehicleDTO.builder()
                        .bikeId(String.valueOf(id))
                        .plate(v.path("vehicle_number").asText(null))
                        .lat(vLat)
                        .lon(vLon)
                        .batteryPct(v.hasNonNull("vehicle_battery")
                                ? (int) Math.round(v.path("vehicle_battery").asDouble()) : null)
                        .vehicleType(type)
                        .isAvailable(true)
                        .lastUpdated(Instant.now())
                        .build());
            }
            log.debug("Elerent dashboard: {} vehicles available within {} km", result.size(), radiusKm);
            return Optional.of(result);
        } catch (Exception e) {
            log.warn("Elerent dashboard vehicles unavailable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Every vehicle ready for hire, following pagination. */
    private List<JsonNode> availableVehicles() {
        List<JsonNode> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode body = postVehicles(Map.of(
                    "page_length", PAGE_LENGTH,
                    "page", page,
                    "filter", List.of("ACTIVE")));
            if (body == null) break;
            body.path("data").forEach(all::add);
            if (!body.path("has_next_page").asBoolean(false)) break;
        }
        return all;
    }

    /** Vehicle id → icon id, from the lighter map view. */
    private Map<Long, Integer> iconByVehicle() {
        Map<Long, Integer> icons = new HashMap<>();
        JsonNode body = postVehicles(Map.of("map_view", true, "filter", List.of("ACTIVE")));
        if (body != null) {
            for (JsonNode v : body.path("data")) {
                if (v.hasNonNull("icon")) icons.put(v.path("id").asLong(), v.path("icon").asInt());
            }
        }
        return icons;
    }

    /**
     * Icon id → BIKE or SCOOTER, worked out from the fleet's vehicle models:
     * the model carries a readable name ("OMNI Dyna bike", "Segway MAX"),
     * the vehicle in the list carries only an icon, so the two are matched by
     * asking which icons each model uses. Cached — models change rarely.
     */
    private Map<Integer, String> typeByIcon() {
        long now = System.currentTimeMillis();
        if (!typeByIcon.isEmpty() && now - typeByIconAt < ICON_MAP_TTL_MS) return typeByIcon;

        Map<Integer, String> map = new HashMap<>();
        JsonNode options = postVehicles(Map.of("page_length", 1, "filter", List.of("ALL")));
        if (options != null) {
            for (JsonNode model : options.path("select_options").path("vehicle_models")) {
                int modelId = model.path("id").asInt();
                String name = model.path("name").asText("");
                String type = name.toLowerCase().contains("bike") ? "BIKE" : "SCOOTER";
                JsonNode byModel = postVehicles(Map.of(
                        "map_view", true, "filter", List.of("ALL"), "models", List.of(modelId)));
                if (byModel == null) continue;
                for (JsonNode v : byModel.path("data")) {
                    if (!v.hasNonNull("icon")) continue;
                    // A shared icon means a shared kind of vehicle; scooters win a
                    // tie, since only the bike models are named as bikes
                    map.merge(v.path("icon").asInt(), type,
                            (a, b) -> a.equals(b) ? a : "SCOOTER");
                }
            }
        }
        if (!map.isEmpty()) {
            typeByIcon = map;
            typeByIconAt = now;
            log.info("Elerent dashboard: vehicle types resolved by icon {}", map);
        }
        return typeByIcon;
    }

    // ── Transport ───────────────────────────────────────────────────────────

    /** One vehicles call, re-authenticating once if the token is no longer accepted. */
    private JsonNode postVehicles(Map<String, Object> body) {
        try {
            return vehiclesRequest(body, token());
        } catch (WebClientResponseException.Unauthorized e) {
            token = null;
            return vehiclesRequest(body, token());
        }
    }

    private JsonNode vehiclesRequest(Map<String, Object> body, String bearer) {
        return webClient.post()
                .uri(VEHICLES_PATH)
                .header("Authorization", "Bearer " + bearer)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
    }

    /**
     * A dashboard token lasts days, so it is kept until shortly before it
     * expires rather than re-requested per call — one login, not one per minute.
     */
    private String token() {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpiresAt - EXPIRY_MARGIN_MS) return token;

        JsonNode auth = webClient.post()
                .uri(LOGIN_PATH)
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        if (auth == null || !auth.hasNonNull("access_token")) {
            throw new IllegalStateException("dashboard login returned no access token");
        }
        token = auth.path("access_token").asText();
        long ttlSeconds = auth.path("expires_in").asLong(3600);
        tokenExpiresAt = now + ttlSeconds * 1000;
        log.info("Elerent dashboard: signed in, token valid for {} h", ttlSeconds / 3600);
        return token;
    }
}
