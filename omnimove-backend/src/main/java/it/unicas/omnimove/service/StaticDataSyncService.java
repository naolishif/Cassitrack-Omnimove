package it.unicas.omnimove.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.Map;

/**
 * Keeps OmniMove's copy of the static network in step with CassiTrack.
 *
 * THE PROBLEM
 * -----------
 * OmniMove mirrors routes / stops / trips / scheduled_stops / route_shapes,
 * rebuilt by {@link NetexImportService}. That import runs once, at startup, so
 * anything the fleet manager changes afterwards stays invisible to travellers
 * until someone restarts the container.
 *
 * WHY NOT SIMPLY RE-IMPORT ON A TIMER
 * -----------------------------------
 * CassiTrack's /api/static/netex rebuilds the whole document from its database
 * on every call. Polling it once a minute would mean reading every stop, route,
 * trip and scheduled stop 1,440 times a day just to discover nothing changed.
 *
 * So this polls a much cheaper endpoint — /api/static/version, five rows of
 * change counters maintained by database triggers — and pulls the full document
 * only when one of those numbers moves.
 *
 * WHY THIS IS A SEPARATE BEAN
 * ---------------------------
 * importDataFromCassitrack() is @Transactional. Spring applies that through a
 * proxy, so calling it from another method of the SAME class would bypass the
 * proxy entirely and run the wipe-and-rebuild with no transaction — leaving
 * travellers able to observe a half-empty database. Injecting the service from
 * outside keeps the proxy, and therefore the transaction, intact.
 */
@Service
public class StaticDataSyncService {

    private final NetexImportService netexImportService;
    private final RestClient restClient = RestClient.create();

    /**
     * Normally blank. The URL is derived from the NeTEx one below, so the two
     * cannot drift apart across environments; set this only to override.
     */
    @Value("${cassitrack.static.version-url:}")
    private String versionUrlOverride;

    /** Already configured for the import; the version endpoint is its sibling. */
    @Value("${cassitrack.netex.url}")
    private String netexUrl;

    @Value("${cassitrack.api.token}")
    private String cassitrackApiToken;

    /**
     * Last version set seen from CassiTrack. Held only in memory: after a
     * restart the boot-time import has already loaded everything, so the first
     * poll simply records the current numbers rather than re-importing.
     */
    private Map<String, Long> lastSeen = null;

    public StaticDataSyncService(NetexImportService netexImportService) {
        this.netexImportService = netexImportService;
    }

    /**
     * .../api/static/netex -> .../api/static/version
     *
     * Derived rather than configured separately: on the server the NeTEx URL is
     * already set correctly, and a second URL would be one more thing to get
     * wrong in a .env file.
     */
    private String versionUrl() {
        if (versionUrlOverride != null && !versionUrlOverride.isBlank()) {
            return versionUrlOverride;
        }
        return netexUrl.endsWith("/netex")
                ? netexUrl.substring(0, netexUrl.length() - "/netex".length()) + "/version"
                : netexUrl + "/../version";   // unusual shape: let the server 404 loudly
    }

    /**
     * Poll for changes and re-import when there are any.
     *
     * Never throws: CassiTrack being briefly unreachable is normal (a restart,
     * a deploy) and must not kill the scheduler or leave OmniMove's data
     * half-written. A failed poll simply logs and waits for the next tick, and
     * because lastSeen is only updated on success, the change is picked up as
     * soon as CassiTrack returns.
     */
    @Scheduled(fixedDelayString = "${cassitrack.static.poll-interval-ms:60000}")
    public void pollForChanges() {
        Map<String, Long> current;
        try {
            current = restClient.get()
                    .uri(versionUrl())
                    .header("X-Api-Key", cassitrackApiToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() {});
        } catch (Exception e) {
            System.out.println("[OMNIMOVE] Static-data version check failed ("
                    + e.getMessage() + "); retrying next tick.");
            return;
        }

        if (current == null || current.isEmpty()) {
            System.out.println("[OMNIMOVE] Static-data version endpoint returned nothing; skipping.");
            return;
        }

        // First poll after startup: the boot import already loaded this data,
        // so just remember where we are.
        if (lastSeen == null) {
            lastSeen = Collections.unmodifiableMap(current);
            System.out.println("[OMNIMOVE] Static-data baseline recorded: " + current);
            return;
        }

        if (current.equals(lastSeen)) {
            return;                       // nothing changed — the common case
        }

        System.out.println("[OMNIMOVE] Static data changed in CassiTrack "
                + describeChange(lastSeen, current) + " — re-importing NeTEx...");
        try {
            netexImportService.importDataFromCassitrack();
            // Only advance the baseline once the import has actually committed,
            // so a failure part-way is retried rather than silently skipped.
            lastSeen = Collections.unmodifiableMap(current);
            System.out.println("[OMNIMOVE] Re-import complete.");
        } catch (Exception e) {
            System.err.println("[OMNIMOVE] Re-import failed (" + e.getMessage()
                    + "); will retry on the next change check.");
        }
    }

    /** Which tables moved — useful in the log when diagnosing sync problems. */
    private String describeChange(Map<String, Long> before, Map<String, Long> after) {
        StringBuilder sb = new StringBuilder("[");
        after.forEach((table, v) -> {
            if (!v.equals(before.get(table))) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(table);
            }
        });
        return sb.append(']').toString();
    }
}
