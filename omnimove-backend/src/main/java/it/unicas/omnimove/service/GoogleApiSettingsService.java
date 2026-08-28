package it.unicas.omnimove.service;

import it.unicas.omnimove.model.AppSetting;
import it.unicas.omnimove.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Governs whether OmniMove may call Google Maps, per feature.
 *
 * Two independent switches, persisted in Postgres (app_settings) and
 * cached in memory so the hot path (every search, every stop popup)
 * does not hit the DB:
 *
 *   google.search   -> traffic-aware travel time in the journey planner
 *   google.stop_eta -> real-time delay recalculation in the stop popup
 *
 * Walk / bike / scooter always use Google and are deliberately NOT here:
 * their travel time has no meaning without a real routing engine.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleApiSettingsService {

    public static final String KEY_SEARCH      = "google.search";
    public static final String KEY_STOP_ETA    = "google.stop_eta";
    /** Reverse geocoding: the street name behind a point tapped on the map. */
    public static final String KEY_GEOCODING   = "google.geocoding";
    /** Directions: the drawn shape of a walking or riding leg. */
    public static final String KEY_ROUTE_SHAPE = "google.route_shape";

    private static final java.util.Set<String> KEYS = java.util.Set.of(
            KEY_SEARCH, KEY_STOP_ETA, KEY_GEOCODING, KEY_ROUTE_SHAPE);

    private final AppSettingRepository repo;

    /** key -> boolean, kept in sync with Postgres. Loaded once at startup. */
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        // Default ON: if the row is missing (fresh DB, migration not yet run),
        // the feature stays enabled rather than silently disabling Google.
        KEYS.forEach(k -> cache.put(k, readOrDefault(k, true)));
        log.info("Google API flags loaded: {}", cache);
    }

    private boolean readOrDefault(String key, boolean dflt) {
        return repo.findById(key)
                .map(s -> Boolean.parseBoolean(s.getValue()))
                .orElse(dflt);
    }

    public boolean isSearchEnabled()     { return cache.getOrDefault(KEY_SEARCH,      true); }
    public boolean isStopEtaEnabled()    { return cache.getOrDefault(KEY_STOP_ETA,    true); }
    public boolean isGeocodingEnabled()  { return cache.getOrDefault(KEY_GEOCODING,   true); }
    public boolean isRouteShapeEnabled() { return cache.getOrDefault(KEY_ROUTE_SHAPE, true); }

    public boolean isEnabled(String key) { return cache.getOrDefault(key, true); }

    /** Update one flag, write-through to Postgres and refresh the cache. */
    public void set(String key, boolean enabled) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("Unknown setting: " + key);
        }
        AppSetting s = repo.findById(key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setKey(key);
            return n;
        });
        s.setValue(Boolean.toString(enabled));
        s.setUpdatedAt(Instant.now());
        repo.save(s);
        cache.put(key, enabled);
        log.info("Google API flag '{}' set to {}", key, enabled);
    }

    /** Snapshot for the admin UI. */
    public Map<String, Boolean> snapshot() {
        return Map.of(
                KEY_SEARCH,      isSearchEnabled(),
                KEY_STOP_ETA,    isStopEtaEnabled(),
                KEY_GEOCODING,   isGeocodingEnabled(),
                KEY_ROUTE_SHAPE, isRouteShapeEnabled());
    }
}
