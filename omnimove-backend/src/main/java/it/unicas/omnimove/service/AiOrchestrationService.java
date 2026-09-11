package it.unicas.omnimove.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.omnimove.client.CassitrackClient;
import it.unicas.omnimove.dto.AiAction;
import it.unicas.omnimove.dto.BikeVehicleDTO;
import it.unicas.omnimove.dto.ChatRequest;
import it.unicas.omnimove.dto.ChatResponse;
import it.unicas.omnimove.dto.StopArrivalDTO;
import it.unicas.omnimove.dto.VehicleDTO;
import it.unicas.omnimove.model.JourneyLog;
import it.unicas.omnimove.model.UserConsent;
import it.unicas.omnimove.model.Route;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.model.FavoriteStop;
import it.unicas.omnimove.model.UserPreferences;
import it.unicas.omnimove.repository.FavoriteStopRepository;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.RouteRepository;
import it.unicas.omnimove.repository.StopRepository;
import it.unicas.omnimove.repository.UserPreferencesRepository;
import it.unicas.omnimove.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Enhanced AI chatbot for OMNIMOVE.
 *
 * Capabilities:
 *   1. Multi-turn conversation — remembers earlier messages in the chat
 *   2. Personalised context — knows the logged-in traveller's journey history
 *   3. Weather awareness — proactively warns about rain/wind affecting modes
 *   4. Language auto-detection — replies in the language the user wrote in
 *   5. Graceful fallback — contextual canned answers if the model is unavailable
 */
@Service
public class AiOrchestrationService {

    private static final Logger log =
            LoggerFactory.getLogger(AiOrchestrationService.class);

    private final CassitrackClient cassitrackClient;
    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final JourneyLogRepository journeyLogRepository;
    private final FavoriteStopRepository favoriteStopRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final ConsentService consentService;
    private final WeatherService weatherService;
    private final GreenIndexService greenIndexService;
    private final BikeSharingService bikeSharingService;

    // The shared-mobility tariffs, quoted verbatim rather than guessed
    @Value("${elerent.bike.unlock:1.00}")      private double bikeUnlock;
    @Value("${elerent.bike.per-minute:0.29}")  private double bikePerMin;
    @Value("${elerent.scooter.unlock:1.00}")   private double scooterUnlock;
    @Value("${elerent.scooter.per-minute:0.25}") private double scooterPerMin;
    @Value("${elerent.scooter.deposit:5.00}")  private double scooterDeposit;

    @Value("${ai.api.key:}")
    private String apiKey;
    @Value("${ai.api.url}")
    private String apiUrl;
    @Value("${ai.api.model}")
    private String model;

    public AiOrchestrationService(CassitrackClient cassitrackClient,
                                  StopRepository stopRepository,
                                  RouteRepository routeRepository,
                                  JourneyLogRepository journeyLogRepository,
                                  FavoriteStopRepository favoriteStopRepository,
                                  UserPreferencesRepository preferencesRepository,
                                  ConsentService consentService,
                                  WeatherService weatherService,
                                  GreenIndexService greenIndexService,
                                  BikeSharingService bikeSharingService) {
        this.cassitrackClient = cassitrackClient;
        this.stopRepository = stopRepository;
        this.routeRepository = routeRepository;
        this.journeyLogRepository = journeyLogRepository;
        this.favoriteStopRepository = favoriteStopRepository;
        this.preferencesRepository = preferencesRepository;
        this.consentService = consentService;
        this.weatherService = weatherService;
        this.greenIndexService = greenIndexService;
        this.bikeSharingService = bikeSharingService;
    }

    // ════════════════════════════════════════════════════════════════════
    //  PUBLIC ENTRY POINTS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Backwards-compatible entry point (old signature).
     * Used if the frontend still sends just a message + language.
     */
    public ChatResponse answer(String question, String language) {
        ChatRequest req = new ChatRequest();
        req.setMessage(question);
        req.setLanguage(language);
        return answer(req, null);
    }

    /**
     * Main entry point. Accepts the full request (with history) and the
     * optional logged-in user's id for personalisation.
     *
     * @param req     the chat request (message + language + history)
     * @param userId  logged-in traveller id, or null for anonymous
     */
    public ChatResponse answer(ChatRequest req, Long userId) {
        String question = req.getMessage();

        // 1. Detect language from the message itself (overrides the toggle)
        String lang = detectLanguage(question, req.getLanguage());

        try {
            String context = buildContext(userId, req.getContext())
                    + onScreenContext(req.getContext())
                    // Last on purpose. Everything before it is the city; this is
                    // the one trip the traveller is on, and it has to be read as
                    // the subject of what they are asking.
                    + activeJourneyContext(req.getContext());
            String system = buildSystem(lang, context);
            String raw = callModel(system, req.getHistory(), question);

            // The reply may end with one instruction line. It is lifted out here
            // so the traveller never reads it, and so a model that writes a
            // malformed one simply gets no action rather than a broken chat.
            AiAction action = extractAction(raw);
            String answer = stripAction(raw);

            return ChatResponse.builder()
                    .answer(answer)
                    .success(true)
                    .detectedLanguage(lang)
                    .suggestions(buildSuggestions(lang, req.getContext()))
                    .action(action)
                    .build();

        } catch (Exception e) {
            log.error("AI failed: {}", e.getMessage());
            // Graceful fallback so the chat never shows a hard error in a demo
            return ChatResponse.builder()
                    .answer(getFallbackResponse(question, lang, req.getContext()))
                    .success(true)              // still "success" so UI renders it nicely
                    .detectedLanguage(lang)
                    .suggestions(buildSuggestions(lang, req.getContext()))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  1. LANGUAGE DETECTION
    // ════════════════════════════════════════════════════════════════════

    /**
     * Detects Italian vs English by scoring signals from BOTH languages,
     * rather than only looking for Italian and falling back to a hint.
     * This fixes English questions being answered in Italian.
     */
    private String detectLanguage(String text, String hint) {
        if (text == null || text.isBlank())
            return hint != null ? hint : "en";

        String t = text.toLowerCase();
        int itScore = 0;
        int enScore = 0;

        // ── Italian signals ──────────────────────────────────────────
        String[] itWords = {
                "dov'è", "dove", "quando", "quanto", "come", "autobus", "fermata",
                "prossimo", "arriva", "biglietto", "viaggio", "piedi", "bicicletta",
                "monopattino", "ciao", "grazie", "per favore", "qual è", "città",
                "stazione", "ospedale", "università", "affollato", "vado", "voglio",
                "mi", "il", "la", "che", "per", "sono", "verso"
        };
        for (String w : itWords)
            if (containsWord(t, w)) itScore++;

        // Accented Italian vowels are a very strong signal
        if (t.matches(".*[àèéìòùç].*")) itScore += 3;

        // ── English signals ──────────────────────────────────────────
        String[] enWords = {
                "how", "where", "when", "what", "which", "the", "is", "are", "to",
                "go", "get", "bus", "stop", "station", "hospital", "next", "arrive",
                "crowded", "ticket", "journey", "walk", "bike", "scooter", "campus",
                "i", "am", "you", "can", "near", "from", "does", "was", "were"
        };
        for (String w : enWords)
            if (containsWord(t, w)) enScore++;

        // ── Decide ───────────────────────────────────────────────────
        if (itScore > enScore) return "it";
        if (enScore > itScore) return "en";

        // Tie → trust the frontend hint, default English
        return hint != null ? hint : "en";
    }

    /** Whole-word match so "is" doesn't match inside "this", etc. */
    private boolean containsWord(String text, String word) {
        return text.matches(".*\\b" + java.util.regex.Pattern.quote(word) + "\\b.*");
    }

    // ════════════════════════════════════════════════════════════════════
    //  2. CONTEXT BUILDING (live data + personalisation + weather)
    // ════════════════════════════════════════════════════════════════════

    private String buildContext(Long userId, ChatRequest.ChatContext ctx) {
        StringBuilder sb = new StringBuilder();
        String now = LocalDateTime.now(ZoneId.of("Europe/Rome"))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss 'on' EEEE dd MMMM yyyy"));
        sb.append("=== CASSITRACK LIVE DATA ===\nTime in Cassino: ").append(now).append("\n\n");

        // ── The line catalogue ──────────────────────────────────────────
        // Asked "which lines run in Cassino?" the assistant had nothing to read:
        // line numbers reached it only through the ETA list, and only for a stop
        // with a bus due. So it answered from its training data and invented 59,
        // 60, 61, 68 and 100 — none of which exist here. Same filter and sort as
        // GET /journeys/timetable/routes, so the chat and the timetable screen
        // cannot disagree about what runs.
        List<Route> routes = routeRepository.findAll().stream()
                .filter(Route::isActive)
                .sorted(Comparator.comparing(
                        r -> r.getShortName() != null ? r.getShortName() : r.getId(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (routes.isEmpty()) {
            sb.append("BUS LINES: not loaded right now — say you cannot list them.\n\n");
        } else {
            sb.append("BUS LINES IN CASSINO (").append(routes.size())
              .append(") — this is the COMPLETE list, there are no others:\n");
            routes.forEach(r -> {
                String shortName = r.getShortName() != null ? r.getShortName() : r.getId();
                sb.append("  Line ").append(shortName);
                if (r.getLongName() != null && !r.getLongName().isBlank()
                        && !r.getLongName().equals(shortName))
                    sb.append(" — ").append(r.getLongName());
                sb.append("\n");
            });
            sb.append("\n");
        }

        // ── Live buses ──────────────────────────────────────────────────
        List<VehicleDTO> vehicles = cassitrackClient.getActiveVehicles();
        if (vehicles.isEmpty()) {
            sb.append("ACTIVE BUSES: None tracked right now.\n\n");
        } else {
            sb.append("ACTIVE BUSES (").append(vehicles.size()).append("):\n");
            vehicles.forEach(v -> sb
                    .append("\n  Bus: ").append(v.getVehicleId())
                    .append("\n    Position: lat=").append(String.format("%.5f", v.getLat()))
                    .append(", lon=").append(String.format("%.5f", v.getLon()))
                    .append("\n    Speed: ").append(v.getSpeedKmh() != null
                            ? String.format("%.1f", v.getSpeedKmh()) : "0.0").append(" km/h")
                    .append("\n    Schedule: ").append(v.getScheduleStatus())
                    .append("\n    Crowding: ").append(v.getCrowdingLevel()).append("\n"));
        }

        // ── ETA at every stop ───────────────────────────────────────────
        sb.append("\nETA AT STOPS:\n");
        for (Stop stop : stopRepository.findAll()) {
            String stopId = stop.getId();
            sb.append("  Stop: ").append(stopId).append(" (").append(stop.getName()).append(")\n");
            try {
                List<StopArrivalDTO> arrivals = cassitrackClient.getArrivalsAtStop(stopId);
                if (arrivals.isEmpty()) {
                    sb.append("    No buses expected soon.\n");
                } else {
                    arrivals.forEach(a -> {
                        long etaMin = Math.max(0,
                                (a.getEstimatedArrival().getEpochSecond()
                                        - System.currentTimeMillis() / 1000) / 60);
                        // The LINE is what a passenger waits for. This used to
                        // print the vehicle id — BUS29 is a coach in the depot,
                        // not something anyone can catch — and the assistant
                        // repeated it back as though it were a route number.
                        String line = a.getRouteShortName() != null ? a.getRouteShortName()
                                    : (a.getRouteName() != null ? a.getRouteName() : "?");
                        sb.append("    - Line ").append(line);
                        if (a.getRouteName() != null && !a.getRouteName().equals(line))
                            sb.append(" (").append(a.getRouteName()).append(")");
                        sb.append(": arrives in ").append(etaMin > 0 ? etaMin + " min" : "<1 min")
                                .append(", ").append(a.getScheduleStatus());
                        if (a.getCrowdingLevel() != null)
                            sb.append(", crowding ").append(a.getCrowdingLevel());
                        sb.append(" [vehicle ").append(a.getVehicleId()).append("]\n");
                    });
                }
            } catch (Exception e) {
                sb.append("    ETA unavailable.\n");
            }
        }

        // ── Shared mobility: who actually operates here ─────────────────
        // Without this the assistant had nothing to answer "where do I rent a
        // bike" with, and filled the gap from its training data — recommending
        // BikeMi, Lime, Bird and TIER, none of which exist in Cassino. A model
        // asked a question it has no data for will invent an answer; the fix is
        // to give it the data, and to say plainly that this is the only operator.
        sb.append("\n=== SHARED BIKES AND E-SCOOTERS ===\n");
        sb.append("  Operator: Elerent. It is the ONLY bike and e-scooter sharing service in Cassino.\n");
        // The division of labour, stated exactly. OMNIMOVE reads the Elerent
        // fleet and never writes to it: no unlock, no payment. Saying otherwise
        // sends the traveller looking for a button this app does not have.
        sb.append("  WHAT OMNIMOVE DOES: plans the journey and shows which Elerent vehicle is free\n")
          .append("    and where it is parked, how far it is on foot and its battery level.\n");
        sb.append("  WHAT OMNIMOVE DOES NOT DO: it cannot book, unlock or pay for a vehicle.\n")
          .append("    The rental itself is done with Elerent, through their own service.\n");
        sb.append(String.format(java.util.Locale.ROOT,
                "  Bike: %.2f EUR to unlock, then %.2f EUR per minute.%n", bikeUnlock, bikePerMin));
        sb.append(String.format(java.util.Locale.ROOT,
                "  E-scooter: %.2f EUR to unlock, then %.2f EUR per minute, plus a %.2f EUR hold%n"
              + "    that is returned at the end of the ride.%n",
                scooterUnlock, scooterPerMin, scooterDeposit));
        // The fleet used to reach the model as two numbers — "7 bikes, 4 scooters"
        // — while lat, lon, plate and battery were loaded and thrown away. The
        // prompt above promises the assistant can say WHERE a vehicle is parked,
        // so "where is the nearest bike?" was a question it was told to answer
        // with data it had never been given: exactly the hole that produced the
        // invented bus lines. Raw coordinates would not help either, so each
        // vehicle is placed against the nearest stop, which is a landmark the
        // traveller and the model both understand.
        try {
            List<BikeVehicleDTO> fleet = bikeSharingService.getAvailableBikes();
            long bikes    = fleet.stream().filter(v -> !"SCOOTER".equalsIgnoreCase(v.getVehicleType())).count();
            long scooters = fleet.size() - bikes;
            sb.append("  Available right now: ").append(bikes).append(" bikes, ")
              .append(scooters).append(" e-scooters.\n");
            if (fleet.isEmpty()) {
                sb.append("  None free at the moment — say so rather than suggesting another service.\n");
            } else {
                sb.append(describeFleet(fleet, ctx));
            }
        } catch (Exception e) {
            sb.append("  Live availability unavailable right now.\n");
        }
        sb.append("  Rides must end inside the Elerent operating area; outside it the app\n")
          .append("    routes the last stretch on foot.\n");

        // ── 3. Weather (proactive mode advice) ──────────────────────────
        try {
            WeatherService.WeatherData w = weatherService.getCurrentWeather();
            sb.append("\nWEATHER IN CASSINO: ").append(w.emoji).append(" ")
                    .append(w.description)
                    .append(", ").append(String.format("%.0f", w.tempCelsius)).append("°C")
                    .append(", wind ").append(String.format("%.0f", w.windSpeedMs)).append(" m/s\n");
            sb.append("  Mode advice: ").append(w.suggestion).append("\n");
            // Explicit per-mode warnings so the AI can quote them
            for (String mode : new String[]{"BIKE", "SCOOTER", "WALK"}) {
                String warn = weatherService.getModeWarning(w.condition, mode);
                if (warn != null && !warn.isBlank())
                    sb.append("    ").append(mode).append(": ").append(warn).append("\n");
            }
        } catch (Exception e) {
            sb.append("\nWEATHER: unavailable.\n");
        }

        // ── 2. Personalisation — only with the traveller's consent ──────
        //
        // Everything below is this person's own history, and it went into the
        // prompt for everyone: the PROFILING consent was collected at sign-up,
        // stored in the ledger, shown as a switch in Preferences, and then read
        // by nobody. A consent asked for and ignored is worse than one never
        // asked for, so the switch now decides.
        //
        // What is NOT gated: lines, stops, live buses, weather, tariffs. Those
        // are the service the traveller asked for, identical for everyone, and
        // the assistant is no use without them.
        if (userId != null && hasProfilingConsent(userId)) {
            appendTravellerProfile(sb, userId);
        }

        sb.append("\nALL STOPS: ");
        sb.append(stopRepository.findAll().stream()
                .map(s -> s.getId() + "=" + s.getName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none"));
        sb.append("\n=== END ===\n");
        return sb.toString();
    }

    /** How many vehicles get spelled out. The rest stay a count. */
    private static final int FLEET_DETAIL_LIMIT = 8;

    /**
     * Writes the free Elerent vehicles as places rather than coordinates.
     *
     * With an origin on screen the list is the nearest first, each with the walk
     * from that origin; without one the order is arbitrary and no distance is
     * claimed, because there is nothing to measure from. Distances are
     * straight-line times the 1.25 detour factor the journey planner already
     * uses when Google is unavailable, and are labelled approximate so the
     * assistant does not quote them as routed walking times.
     */
    private String describeFleet(List<BikeVehicleDTO> fleet, ChatRequest.ChatContext ctx) {
        Double oLat = ctx == null ? null : ctx.getOriginLat();
        Double oLon = ctx == null ? null : ctx.getOriginLon();
        boolean haveOrigin = oLat != null && oLon != null;

        List<BikeVehicleDTO> located = fleet.stream()
                .filter(v -> v.getLat() != null && v.getLon() != null)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (located.isEmpty())
            return "  Positions unavailable — do not say where any vehicle is.\n";

        if (haveOrigin)
            located.sort(Comparator.comparingDouble(
                    v -> GeoUtils.haversineMetres(oLat, oLon, v.getLat(), v.getLon())));

        List<Stop> stops = stopRepository.findAll().stream()
                .filter(st -> st.getLat() != null && st.getLon() != null)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(haveOrigin
                ? "  FREE VEHICLES, nearest to the traveller's origin first:\n"
                : "  FREE VEHICLES (no origin on screen, so these are in no particular\n"
                + "    order — ask where the traveller is before calling any of them near):\n");

        located.stream().limit(FLEET_DETAIL_LIMIT).forEach(v -> {
            boolean scooter = "SCOOTER".equalsIgnoreCase(v.getVehicleType());
            sb.append("    - ").append(scooter ? "E-scooter" : "Bike");
            String label = v.getPlate() != null && !v.getPlate().isBlank()
                    ? v.getPlate() : v.getBikeId();
            if (label != null) sb.append(" ").append(label);
            if (v.getBatteryPct() != null)
                sb.append(", battery ").append(v.getBatteryPct()).append("%");

            // Nearest stop as the human-readable anchor for the position
            Stop near = nearestStop(stops, v.getLat(), v.getLon());
            if (near != null)
                sb.append(", parked ")
                  .append(Math.round(GeoUtils.haversineMetres(
                          v.getLat(), v.getLon(), near.getLat(), near.getLon()) / 10.0) * 10)
                  .append(" m from the stop ").append(near.getName());

            if (haveOrigin) {
                long walk = Math.round(
                        GeoUtils.haversineMetres(oLat, oLon, v.getLat(), v.getLon()) * 1.25);
                sb.append(", about ").append(walk).append(" m on foot from the origin");
            }
            sb.append("\n");
        });

        if (located.size() > FLEET_DETAIL_LIMIT)
            sb.append("    (").append(located.size() - FLEET_DETAIL_LIMIT)
              .append(" more not listed — the map shows them all.)\n");
        return sb.toString();
    }

    /**
     * Whether this traveller has agreed to their history being used.
     *
     * <p>Absent means no: PROFILING is a real consent under art. 6(1)(a), so
     * silence is a refusal, and an acknowledgement given under a superseded
     * privacy notice is reported as false by the ledger and counts as one too.
     * Any failure reading it is also treated as a no — the assistant losing some
     * context is a far smaller problem than using data we were told not to.
     */
    private boolean hasProfilingConsent(Long userId) {
        try {
            return Boolean.TRUE.equals(
                    consentService.currentStateFor(userId).get(UserConsent.TYPE_PROFILING));
        } catch (Exception e) {
            log.warn("Could not read the profiling consent for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /** How many journeys are listed one by one before the rest are summarised. */
    private static final int JOURNEY_DETAIL_LIMIT = 150;

    /**
     * The traveller's own picture: every journey, their preferences, their
     * saved stops.
     *
     * <p>Every journey rather than the last five — a pattern is what makes a
     * suggestion personal, and five trips do not show one. Retention caps the
     * history at twelve months, so "every" is already a bounded number; the
     * limit below is only there so that one unusually heavy user cannot push
     * the request past what the model will read.
     */
    private void appendTravellerProfile(StringBuilder sb, Long userId) {
        try {
            List<JourneyLog> trips = journeyLogRepository.findByUserId(userId);
            if (!trips.isEmpty()) {
                trips.sort(Comparator.comparing(JourneyLog::getCreatedAt).reversed());

                sb.append("\n=== THIS TRAVELLER'S JOURNEYS (")
                  .append(trips.size()).append(", newest first) ===\n");
                trips.stream().limit(JOURNEY_DETAIL_LIMIT).forEach(j -> sb
                        .append("  - ").append(j.getCreatedAt().toLocalDate())
                        .append(" ").append(j.getMode())
                        .append(" ").append(j.getOriginName())
                        .append(" \u2192 ").append(j.getDestName())
                        .append(" (Green ").append(j.getGreenIndex())
                        .append(", \u20ac").append(String.format(java.util.Locale.ROOT, "%.2f", j.getCostEuros()))
                        .append(")\n"));
                if (trips.size() > JOURNEY_DETAIL_LIMIT)
                    sb.append("  (").append(trips.size() - JOURNEY_DETAIL_LIMIT)
                      .append(" older journeys not listed; the totals below count them all.)\n");

                Map<String, Long> modeCount = new HashMap<>();
                trips.forEach(j -> modeCount.merge(j.getMode(), 1L, Long::sum));
                String favMode = modeCount.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse(null);
                if (favMode != null)
                    sb.append("  Preferred mode: ").append(favMode)
                      .append(" (used ").append(modeCount.get(favMode)).append(" times)\n");

                double totalCo2Saved = trips.stream()
                        .mapToDouble(j -> Math.max(0,
                                greenIndexService.computeCo2Grams("CAR", j.getDistanceKm())
                                        - j.getCo2Grams()))
                        .sum();
                sb.append("  Total CO\u2082 saved vs car: ")
                  .append(String.format(java.util.Locale.ROOT, "%.1f", totalCo2Saved / 1000.0))
                  .append(" kg\n");
            }
        } catch (Exception e) {
            log.warn("Could not load traveller history: {}", e.getMessage());
        }

        // ── Saved stops. Names, not ids: "UNI" means nothing to the model. ──
        try {
            List<FavoriteStop> favourites = favoriteStopRepository.findByUserIdOrderByCreatedAtAsc(userId);
            if (!favourites.isEmpty()) {
                sb.append("\nTHIS TRAVELLER'S SAVED STOPS: ");
                sb.append(favourites.stream()
                        .map(f -> stopRepository.findById(f.getStopId())
                                .map(Stop::getName).orElse(f.getStopId()))
                        .reduce((a, b) -> a + ", " + b).orElse(""));
                sb.append("\n  These are the places they come back to — worth preferring when a\n")
                  .append("  question does not name one.\n");
            }
        } catch (Exception e) {
            log.warn("Could not load favourite stops: {}", e.getMessage());
        }

        // ── Settings they chose themselves, so answers do not contradict them ──
        try {
            preferencesRepository.findById(userId).ifPresent(p -> {
                sb.append("\nTHIS TRAVELLER'S PREFERENCES:\n");
                if (p.getDefaultJourneyMode() != null)
                    sb.append("  Default mode: ").append(p.getDefaultJourneyMode()).append("\n");
                appendFlag(sb, "Avoids crowded buses", p.getAvoidHighOccupancy());
                appendFlag(sb, "Wants walking legs shown", p.getShowWalking());
                appendFlag(sb, "Prefers a bike over the bus", p.getPreferBikeOverBus());
                appendFlag(sb, "Prefers the bus when it rains", p.getRainPrefersBus());
                if (p.getMaxBikeWalkMetres() != null)
                    sb.append("  Will walk at most ").append(p.getMaxBikeWalkMetres())
                      .append(" m to reach a bike or scooter\n");
                if (p.getOccupancyThresholdPct() != null)
                    sb.append("  Considers a bus crowded above ")
                      .append(p.getOccupancyThresholdPct()).append("% full\n");
                // The onboarding answers, 1-5. What the traveller says matters to
                // them, which is not always what their journeys suggest.
                sb.append("  Priorities out of 5 — speed ").append(p.getAnswerTime())
                  .append(", cost ").append(p.getAnswerCost())
                  .append(", environment ").append(p.getAnswerEco())
                  .append(", reliability ").append(p.getAnswerReliability()).append("\n");
            });
        } catch (Exception e) {
            log.warn("Could not load traveller preferences: {}", e.getMessage());
        }
    }

    private static void appendFlag(StringBuilder sb, String label, Boolean value) {
        if (value != null) sb.append("  ").append(label).append(": ")
                             .append(value ? "yes" : "no").append("\n");
    }

    // ════════════════════════════════════════════════════════════════════
    //  SYSTEM PROMPT
    // ════════════════════════════════════════════════════════════════════

    private String buildSystem(String language, String context) {
        String lang = "it".equals(language)
                ? "Always respond in Italian."
                : "Always respond in English.";
        return """
                You are the OMNIMOVE assistant for Cassino, Italy.
                You help passengers plan journeys using Bus, Bike, E-Scooter and Walk
                between Cassino city centre and the UNICAS campus at Folcara.

                Folcara is the university campus district, served by the stop
                UNI (Universita Folcara). It is NOT the Engineering faculty:
                those are two different places in Cassino, and calling Folcara
                "the Engineering campus" sends the traveller to the wrong stop.
                Name a faculty only if the data below names it.

                You have live real-time data from the CASSITRACK fleet system below.

                HOW TO WRITE
                - PLAIN TEXT ONLY. No Markdown: no asterisks for bold, no ###
                  headings, no bullet lists, no tables. The chat panel prints
                  your reply exactly as you write it, so any markup shows up as
                  the characters themselves.
                - Two or three sentences. This is a chat bubble on a phone, not
                  a document. If you must list two or three options, write them
                  as short separate sentences.
                - No emoji decoration beyond at most one.

                WHAT TO SAY
                - Name the LINE, never the vehicle. Lines are numbers such as 05
                  or 11; the "vehicle" in the data below is a coach in the depot
                  and means nothing to a passenger.
                - Quote live times and crowding exactly as given.
                - Never invent a line, a stop, a time or a distance. If the data
                  below does not contain the answer, say what you do not know
                  and ask which stop or which destination they mean.
                - The BUS LINES list below is the whole network. A line that is
                  not in it does not exist in Cassino — do not name it, do not
                  guess a plausible-looking number, and do not carry a line over
                  from anywhere else you have seen. Cassino's lines are short
                  numbers such as 01, 05, 11 and 16, not 60-something.
                - Shared bikes and e-scooters in Cassino are Elerent, and only
                  Elerent. Never name another operator — no BikeMi, Lime, Bird,
                  TIER or anything else.
                - Be exact about what this app does. OMNIMOVE PLANS the journey and
                  SHOWS which Elerent vehicle is free and where; it does NOT book,
                  unlock or pay for it. The rental is done with Elerent. Do not
                  promise a booking button that does not exist here, and do not
                  claim the traveller must go elsewhere to plan the trip.
                - Asked where a bike or e-scooter is, answer from the FREE
                  VEHICLES list below and nowhere else. Locate them the way the
                  list does — by the stop they are parked near — and never read
                  out raw coordinates. If the list says there is no origin on
                  screen, ask where the traveller is starting from instead of
                  calling any of them the nearest.
                - The origin and the destination do not have to be a stop or the
                  traveller's own GPS position: any point on the map can be
                  tapped and used as either end of the journey. If they want to
                  plan from or to somewhere with no stop of its own, tell them to
                  pick that point on the map.
                - The traveller's question may be missing its context: "the next
                  bus" from which stop, "the campus" from where. Ask rather than
                  assume, unless the context section below already says.
                - If the live data below has a section for a journey under way,
                  that journey is what the traveller is asking about. Answer
                  about ITS line, ITS stops and ITS run, and about no other: the
                  rest of the data is the whole city, and only that section is
                  their trip. Do not offer to plan or start it again — they are
                  on it — and if it is running late or its bus is untracked, say
                  that rather than reaching for another line.
                - If the weather is bad, warn about bike, scooter and walking.
                - If asked about something outside Cassino transport, steer back.

                DOING THINGS, NOT JUST SAYING THEM
                You can fill in the traveller's search fields, run the search,
                recommend one of the results, and — only when they have agreed —
                start the journey for them. To do any of it, end your reply with
                one line, exactly this shape and nothing else on it:

                <<OMNIMOVE {"from":"Colosseo","to":"Universita Folcara","pick":"FAST","worst":false,"mode":null,"start":false}>>

                - "from" and "to": a stop name spelled as it appears in the data
                  below, or "GPS" for the traveller's own position, or null to
                  leave that field as it is.
                - "pick": FAST, CHEAP, ECO or CUSTOM — the four rankings the app
                  offers. CUSTOM is the traveller's own profile. null if they
                  only asked you to search.
                - "worst": true when they asked for the opposite end — the
                  slowest, the dearest, the least green, the one their profile
                  ranks last. It flips whichever criterion is in "pick".
                - "mode": BUS, BIKE, SCOOTER or WALK to keep the recommendation
                  to one of them ("the fastest by bus"), otherwise null.
                - "start": true ONLY if they have said you may start the journey
                  for them. Offering to is not agreeing. Note what this does: the
                  app puts the chosen itinerary in front of them with a Start
                  Journey button already aimed at it, and waits for one tap. So
                  say you have it ready for them to confirm — never that you have
                  started it, because you have not.

                WHEN TO WRITE THAT LINE
                - Only after the traveller has confirmed. "How do I get to
                  Folcara?" is a question — answer it. "Yes, plan it" is
                  agreement — act on it.
                - Never on the same turn you propose something. Propose, let them
                  answer, then act.
                - You must know both ends. If either is missing and you cannot
                  read it from the context below, ask instead of guessing.
                - Say in your sentence what you are doing and, when you are
                  recommending an option, why that one — the traveller sees the
                  recommendation on the card and your reason in the chat.
                - Never mention the line itself, or the word OMNIMOVE in that
                  form. It is removed before your reply is shown, and describing
                  it makes you sound like you are talking about your own plumbing.
                """ + " " + lang + "\n\nLive data:\n" + context;
    }

    // ════════════════════════════════════════════════════════════════════
    //  ACTIONS: WHAT THE ASSISTANT DOES, NOT WHAT IT SAYS
    // ════════════════════════════════════════════════════════════════════

    /**
     * The one line of machine-readable text a reply may end with.
     *
     * <p>A marker rather than a JSON-only response format: the traveller still
     * gets a sentence in their own language, written by the same model in the
     * same breath as the decision, and the app gets the decision. Asking for
     * structured output instead would mean composing the prose ourselves, in
     * two languages, from fields.
     */
    private static final java.util.regex.Pattern ACTION_LINE =
            java.util.regex.Pattern.compile("<<OMNIMOVE\\s*(\\{.*?})\\s*>>", java.util.regex.Pattern.DOTALL);

    private static final Set<String> PICKS = Set.of("FAST", "CHEAP", "ECO", "CUSTOM");
    private static final Set<String> ACTION_MODES = Set.of("BUS", "BIKE", "SCOOTER", "WALK");

    private final ObjectMapper actionMapper = new ObjectMapper();

    /**
     * Reads the action out of a reply, or returns null.
     *
     * <p>Everything is checked rather than believed. The model is a text
     * generator: it can invent a criterion, a mode that does not exist, or set
     * start on a conversation where nobody agreed to anything. A field that does
     * not survive validation is dropped, and an action left asking for nothing
     * is dropped whole — the traveller keeps the sentence either way.
     */
    private AiAction extractAction(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = ACTION_LINE.matcher(raw);
        if (!m.find()) return null;

        try {
            AiAction a = actionMapper.readValue(m.group(1), AiAction.class);

            a.setFrom(cleanEndpoint(a.getFrom()));
            a.setTo(cleanEndpoint(a.getTo()));

            // Null-checked before the lookup: Set.of() throws on contains(null),
            // and an omitted "mode" is the ordinary case, not an error.
            String pick = upperOrNull(a.getPick());
            a.setPick(pick != null && PICKS.contains(pick) ? pick : null);

            String mode = upperOrNull(a.getMode());
            a.setMode(mode != null && ACTION_MODES.contains(mode) ? mode : null);

            // Starting a journey without a named option is not something anyone
            // can have agreed to — see AiAction.isStartable
            if (a.isStart() && a.getPick() == null) a.setStart(false);

            if (!a.isUseful()) return null;

            log.debug("AI action: from={} to={} pick={} worst={} mode={} start={}",
                    a.getFrom(), a.getTo(), a.getPick(), a.isWorst(), a.getMode(), a.isStart());
            return a;

        } catch (Exception e) {
            // A malformed marker is a model slip, not an outage: the answer is
            // still worth showing, it just does not drive anything.
            log.warn("Unreadable AI action '{}': {}", m.group(1), e.getMessage());
            return null;
        }
    }

    /** The reply as the traveller reads it: the marker, and the blank it leaves, removed. */
    private String stripAction(String raw) {
        if (raw == null) return "";
        return ACTION_LINE.matcher(raw).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String cleanEndpoint(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        // Long enough to be a stop name and short enough not to be a paragraph
        return s.length() > 80 ? null : s;
    }

    private static String upperOrNull(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase();
    }

    // ════════════════════════════════════════════════════════════════════
    //  1. MODEL CALL WITH MULTI-TURN HISTORY
    // ════════════════════════════════════════════════════════════════════

    /**
     * Speaks the OpenAI chat-completions dialect, which nearly every inference
     * provider now serves — Regolo among them. Changing provider is a base URL,
     * a key and a model name, with no code to rewrite; the previous version was
     * shaped around Anthropic's own /v1/messages and tied the assistant to one
     * vendor.
     *
     * Two differences from that shape, and they are the whole migration: the
     * system prompt is the first message rather than a top-level field, and the
     * reply is choices[0].message.content rather than content[0].text.
     */
    @SuppressWarnings("unchecked")
    private String callModel(String systemPrompt,
                             List<ChatRequest.ChatTurn> history,
                             String userMessage) {

        List<Map<String, Object>> messages = new ArrayList<>();
        // The system prompt leads the conversation instead of sitting beside it
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (history != null) {
            for (ChatRequest.ChatTurn turn : history) {
                if (turn.getRole() == null || turn.getContent() == null) continue;
                // Anything that is not the assistant is treated as the user: a
                // stray role would be rejected by the API for the whole request
                String role = turn.getRole().equalsIgnoreCase("assistant")
                        ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", turn.getContent()));
            }
        }
        // Current user message always goes last
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 1024);
        // Low but not zero: the answers quote live times and must not drift,
        // while a completely deterministic assistant repeats itself word for
        // word when asked the same thing twice.
        body.put("temperature", 0.3);

        WebClient client = WebClient.builder().baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();

        log.info("Calling model {} at {}", model, apiUrl);
        Map<String, Object> response = client.post().bodyValue(body).retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
                            log.error("AI provider error {}: {}", clientResponse.statusCode(), errorBody);
                            return reactor.core.publisher.Mono.error(
                                    new RuntimeException("AI provider error: " + errorBody));
                        }))
                .bodyToMono(Map.class).block();

        Object choices = response == null ? null : response.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first
                && first.get("message") instanceof Map<?, ?> msg) {
            Object content = msg.get("content");
            if (content instanceof String text && !text.isBlank()) return text;
        }
        throw new RuntimeException("Unexpected AI provider response");
    }

    // ════════════════════════════════════════════════════════════════════
    //  5. GRACEFUL FALLBACK (no credits / API down)
    // ════════════════════════════════════════════════════════════════════

    private String getFallbackResponse(String message, String lang,
                                       ChatRequest.ChatContext ctx) {
        String msg = message == null ? "" : message.toLowerCase();
        boolean it = "it".equals(lang);

        // A journey under way answers the question before any keyword is looked
        // at, and answers it with the traveller's own itinerary. This branch used
        // to reply "bus 16 is active on the Cassino-UNICAS route" to anything
        // containing the word bus — a line and a route invented here, in the
        // code, and read out to a traveller who had started a journey on a
        // different line entirely. A canned answer may say less than the model
        // would; it may not say something untrue.
        ChatRequest.ActiveJourney j = ctx == null ? null : ctx.getJourney();
        if (j != null) {
            String line = journeyLine(j);
            String where = nz(j.getOriginName(), "?") + " \u2192 " + nz(j.getDestName(), "?");
            StringBuilder sb = new StringBuilder();
            if (it) {
                sb.append("Ora non riesco a raggiungere l\u2019assistente. ")
                  .append("Il viaggio che hai avviato \u00e8 ").append(where);
                if (line != null) sb.append(", con la linea ").append(line);
                sb.append(". La posizione del bus in tempo reale \u00e8 sulla mappa, ")
                  .append("e la fermata di salita mostra gli arrivi.");
            } else {
                sb.append("I cannot reach the assistant right now. ")
                  .append("The journey you started is ").append(where);
                if (line != null) sb.append(", on line ").append(line);
                sb.append(". The live position of the bus is on the map, and your ")
                  .append("boarding stop lists its arrivals.");
            }
            return sb.toString();
        }

        if (msg.contains("bus") || msg.contains("autobus") || msg.contains("vehicle")) {
            return it
                    ? "Ora non riesco a raggiungere l\u2019assistente. La mappa mostra i bus in "
                    + "circolazione in tempo reale, e ogni fermata i suoi arrivi. "
                    + "Dimmi da quale fermata parti e dove vai, e cerco il percorso."
                    : "I cannot reach the assistant right now. The map shows the buses running "
                    + "live, and each stop lists its own arrivals. Tell me which stop you are at "
                    + "and where you are going, and I will plan the journey.";
        }
        if (msg.contains("eta") || msg.contains("arriv") || msg.contains("when") || msg.contains("quando")) {
            return it
                    ? "Apri la scheda ETA e seleziona la tua fermata per vedere gli orari di arrivo in tempo reale."
                    : "Open the ETA tab and select your stop to see live arrival times for all active buses.";
        }
        if (msg.contains("route") || msg.contains("journey") || msg.contains("viaggio") || msg.contains("come arrivo")) {
            return it
                    ? "Usa il Pianificatore di Viaggio per trovare il percorso migliore tra le fermate di Cassino: autobus, a piedi, bici o monopattino."
                    : "Use the Journey Planner tab to find the best route between Cassino stops \u2014 bus, walk, bike or scooter.";
        }
        if (msg.contains("crowd") || msg.contains("affoll") || msg.contains("passenger")) {
            return it
                    ? "Il livello di affollamento è mostrato su ogni scheda del veicolo nella sezione Flotta, aggiornato ogni 5 secondi."
                    : "Crowding levels are shown on each vehicle card in the Fleet tab, updated every 5 seconds.";
        }
        if (msg.contains("weather") || msg.contains("rain") || msg.contains("meteo") || msg.contains("pioggia")) {
            return it
                    ? "Controllo le condizioni meteo di Cassino per consigliarti il mezzo migliore. Con pioggia, l'autobus è la scelta più comoda."
                    : "I check Cassino's live weather to recommend the best mode. When it rains, the bus is the most comfortable choice.";
        }
        return it
                ? "OMNIMOVE segue in tempo reale i bus di Cassino, insieme a bici e monopattini "
                + "Elerent. Dimmi da dove parti e dove vuoi andare, e trovo il percorso."
                : "OMNIMOVE follows Cassino's buses in real time, along with the Elerent bikes "
                + "and e-scooters. Tell me where you are starting from and where you want to go, "
                + "and I will find the journey.";
    }

    /** The line of the started journey, as a passenger names it, or null. */
    private String journeyLine(ChatRequest.ActiveJourney j) {
        List<ChatRequest.BusLeg> legs = j.getBusLegs();
        if (legs == null || legs.isEmpty()) return null;
        String routeId = blankToNull(legs.get(0).getRouteId());
        return routeId == null ? null : lineNameOf(routeId);
    }

    // ════════════════════════════════════════════════════════════════════
    //  FOLLOW-UP SUGGESTIONS
    // ════════════════════════════════════════════════════════════════════

    /**
     * Tells the model what the traveller is looking at, so a question that omits
     * its subject still has one.
     */
    private String onScreenContext(ChatRequest.ChatContext ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        if (ctx.getStopName() != null && !ctx.getStopName().isBlank())
            sb.append("  The traveller is looking at the stop: ")
              .append(ctx.getStopName()).append("\n");
        if (ctx.getOriginName() != null && !ctx.getOriginName().isBlank())
            sb.append("  Origin currently chosen: ").append(ctx.getOriginName()).append("\n");
        if (ctx.getDestName() != null && !ctx.getDestName().isBlank())
            sb.append("  Destination currently chosen: ").append(ctx.getDestName()).append("\n");
        if (sb.length() == 0) return "";
        return "\n=== WHAT THE TRAVELLER HAS ON SCREEN ===\n" + sb;
    }

    // ════════════════════════════════════════════════════════════════════
    //  THE JOURNEY UNDER WAY
    // ════════════════════════════════════════════════════════════════════

    /**
     * The trip the traveller is actually travelling, and the runs it rides.
     *
     * <p>Everything above this in the prompt is the city: every line, every
     * stop, every bus on the road. Asked "where is the bus?" while waiting at a
     * stop, the assistant had all of it and nothing saying which part was this
     * traveller's — so it answered with a line their itinerary did not contain.
     * A started journey names its own line, its own boarding stop and its own
     * run, and while it is running that is what every question is about.
     *
     * <p>The live figures are looked up here rather than taken from the page:
     * the page's copy was written when the card was drawn, and a bus that has
     * since fallen five minutes behind is exactly what the traveller is asking
     * about.
     */
    private String activeJourneyContext(ChatRequest.ChatContext ctx) {
        ChatRequest.ActiveJourney j = ctx == null ? null : ctx.getJourney();
        if (j == null) return "";

        StringBuilder sb = new StringBuilder(
                "\n=== THE JOURNEY THIS TRAVELLER IS ON RIGHT NOW ===\n");
        sb.append("  They have pressed Start Journey: this trip is under way.\n");
        sb.append("  Itinerary: ").append(nz(j.getOriginName(), "?"))
          .append(" -> ").append(nz(j.getDestName(), "?"));
        if (j.getMode() != null && !j.getMode().isBlank())
            sb.append(", by ").append(j.getMode());
        if (j.getLabel() != null && !j.getLabel().isBlank())
            sb.append(" (the card they chose reads \"").append(j.getLabel()).append("\")");
        sb.append("\n");
        if (j.getDurationMinutes() != null)
            sb.append("  Planned duration: ").append(j.getDurationMinutes()).append(" min.\n");
        if (j.getMinutesLeft() != null)
            sb.append("  The counter on their screen reads ").append(j.getMinutesLeft())
              .append(" min left.\n");

        List<ChatRequest.BusLeg> busLegs =
                j.getBusLegs() == null ? List.of() : j.getBusLegs();

        if (busLegs.isEmpty()) {
            sb.append("  This journey has no bus leg — there is no bus of theirs to\n")
              .append("    report on, and no line to name.\n");
            sb.append("""
                      HOW TO USE THIS SECTION
                      - This is the trip they are on, and while it runs it is what
                        they are asking about. Asked where they are or how much
                        longer, answer about THIS trip. It has no bus, so do not
                        name a line at all: the lines further up belong to the city,
                        not to them.
                      - They are already travelling it: do not offer to plan it or
                        start it again.
                    """);
            return sb.toString();
        }

        List<VehicleDTO> vehicles = cassitrackClient.getActiveVehicles();
        List<Stop> stops = stopRepository.findAll().stream()
                .filter(st -> st.getLat() != null && st.getLon() != null)
                .toList();
        for (int i = 0; i < busLegs.size(); i++) {
            // Leg 1 is boarded at the origin stop, leg 2 at the interchange:
            // the option carries one pair of ids for each, and they are what
            // turn "a bus of line 16" into "the bus you are waiting for".
            boolean first = i == 0;
            String stopId = first ? j.getBoardingStopId() : j.getTransferStopId();
            String tripId = first ? j.getBoardingTripId() : j.getTransferTripId();
            sb.append("  Bus leg ").append(i + 1).append(" of ").append(busLegs.size())
              .append(":\n");
            appendJourneyBusLeg(sb, busLegs.get(i), stopId, tripId, vehicles, stops);
        }

        sb.append("""
                  HOW TO USE THIS SECTION
                  - While this journey is running it is what the traveller is
                    talking about. "The bus", "my bus", "where is it", "how much
                    longer", "am I late" all mean the line and the run named
                    here — never another line, and never another vehicle from the
                    lists further up.
                  - A line that is not named here has nothing to do with their
                    trip. Do not mention it, however much the live data above has
                    to say about it.
                  - If no live position is given for their line, say that this run
                    is not being tracked at the moment and quote the timetable
                    figure if one is given. Never answer with a different bus.
                  - They are already travelling this: do not offer to plan it or
                    start it again. Only a question about a DIFFERENT trip is
                    something to plan.
                """);
        return sb.toString();
    }

    /**
     * One bus leg of the started journey, with whatever is live about it.
     *
     * <p>The run is matched on its trip id first and only then on the line: a
     * traveller already on board no longer appears in the arrivals for their own
     * run, and the next bus of the same number is a useful second best as long
     * as it is labelled as one rather than passed off as theirs.
     */
    private void appendJourneyBusLeg(StringBuilder sb,
                                     ChatRequest.BusLeg leg,
                                     String stopId,
                                     String tripId,
                                     List<VehicleDTO> vehicles,
                                     List<Stop> stops) {
        String routeId = blankToNull(leg.getRouteId());
        String line = routeId == null ? null : lineNameOf(routeId);

        sb.append("    LINE ").append(line != null ? line : "not resolved");
        if (routeId != null) sb.append(" (route id ").append(routeId).append(")");
        sb.append(" — this is the line they are travelling on.\n");

        if (leg.getFromStopName() != null && !leg.getFromStopName().isBlank())
            sb.append("    Boards at ").append(leg.getFromStopName())
              .append(", gets off at ").append(nz(leg.getToStopName(), "?")).append("\n");

        // ── Their own run, at the stop they board it ──
        StopArrivalDTO mine = null;
        boolean isOwnRun = false;
        if (stopId != null) {
            try {
                List<StopArrivalDTO> arrivals = cassitrackClient.getArrivalsAtStop(stopId);
                if (tripId != null)
                    mine = arrivals.stream()
                            .filter(a -> tripId.equals(a.getTripId()))
                            .findFirst().orElse(null);
                isOwnRun = mine != null;
                if (mine == null && routeId != null)
                    mine = arrivals.stream()
                            .filter(a -> routeId.equals(a.getRouteId()))
                            .findFirst().orElse(null);
            } catch (Exception e) {
                sb.append("    Arrivals at their boarding stop are unavailable right now.\n");
            }
        }

        if (mine != null) {
            long etaMin = mine.getEstimatedArrival() == null ? -1
                    : Math.max(0, (mine.getEstimatedArrival().getEpochSecond()
                                   - System.currentTimeMillis() / 1000) / 60);
            sb.append(isOwnRun
                    ? "    THEIR OWN RUN (trip " + mine.getTripId() + "): "
                    : "    Their own run is no longer listed at that stop — they may already\n"
                    + "      be on board. Next run of the same line: ");
            if (etaMin >= 0)
                sb.append("due at the boarding stop in ")
                  .append(etaMin > 0 ? etaMin + " min" : "less than a minute");
            else
                sb.append("no arrival time given");
            if (mine.getScheduleStatus() != null)
                sb.append(", ").append(mine.getScheduleStatus());
            if (mine.getDelayMinutes() != null && mine.getDelayMinutes() != 0)
                sb.append(", ").append(Math.abs(mine.getDelayMinutes()))
                  .append(mine.getDelayMinutes() > 0 ? " min late" : " min early");
            if (mine.getCrowdingLevel() != null)
                sb.append(", crowding ").append(mine.getCrowdingLevel());
            sb.append("\n");
            if (!mine.isInTransit())
                sb.append("      That time is the timetable's, not the bus's: this run has not\n")
                  .append("        left the terminus yet.\n");
        } else if (stopId != null) {
            sb.append("    No arrival for this line at their boarding stop right now.\n");
        }

        // ── Where that bus actually is ──
        VehicleDTO bus = null;
        String wanted = mine == null ? null : mine.getVehicleId();
        if (wanted != null)
            bus = vehicles.stream()
                    .filter(v -> wanted.equals(v.getVehicleId()))
                    .findFirst().orElse(null);
        if (bus == null && routeId != null)
            bus = vehicles.stream()
                    .filter(v -> routeId.equals(v.getRouteId()))
                    .findFirst().orElse(null);

        if (bus == null || bus.getLat() == null || bus.getLon() == null) {
            sb.append("    Where that bus is now: not tracked at the moment. Say so rather\n")
              .append("      than naming any other bus.\n");
            return;
        }

        sb.append("    Where that bus is now:");
        Stop near = nearestStop(stops, bus.getLat(), bus.getLon());
        if (near != null)
            sb.append(" about ")
              .append(Math.round(GeoUtils.haversineMetres(
                      bus.getLat(), bus.getLon(), near.getLat(), near.getLon()) / 10.0) * 10)
              .append(" m from the stop ").append(near.getName()).append(";");
        if (bus.getNextStopName() != null && !bus.getNextStopName().isBlank())
            sb.append(" next stop ").append(bus.getNextStopName()).append(";");
        if (bus.getSpeedKmh() != null)
            sb.append(String.format(java.util.Locale.ROOT, " %.0f km/h;", bus.getSpeedKmh()));
        if (bus.getScheduleStatus() != null)
            sb.append(" ").append(bus.getScheduleStatus()).append(";");
        if (bus.getDelayMinutes() != null && bus.getDelayMinutes() != 0)
            sb.append(" ").append(Math.abs(bus.getDelayMinutes()))
              .append(bus.getDelayMinutes() > 0 ? " min late;" : " min early;");
        if (bus.getCrowdingLevel() != null)
            sb.append(" crowding ").append(bus.getCrowdingLevel()).append(";");
        sb.append(" [vehicle ").append(bus.getVehicleId())
          .append(" — the line is what the traveller cares about, not this id]\n");
    }

    /** The line as a passenger names it: its short name, or the id if it has none. */
    private String lineNameOf(String routeId) {
        try {
            return routeRepository.findById(routeId)
                    .map(r -> r.getShortName() != null && !r.getShortName().isBlank()
                            ? r.getShortName() : r.getId())
                    .orElse(routeId);
        } catch (Exception e) {
            return routeId;
        }
    }

    /**
     * The nearest stop to a coordinate: the landmark a position is described by,
     * because "41.49, 13.83" is not somewhere either the traveller or the model
     * can picture.
     */
    private static Stop nearestStop(List<Stop> stops, double lat, double lon) {
        Stop near = null;
        double nearM = Double.MAX_VALUE;
        for (Stop st : stops) {
            if (st.getLat() == null || st.getLon() == null) continue;
            double d = GeoUtils.haversineMetres(lat, lon, st.getLat(), st.getLon());
            if (d < nearM) { nearM = d; near = st; }
        }
        return near;
    }

    private static String nz(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    /**
     * Follow-up chips, written around what is actually on screen.
     *
     * They used to be three fixed sentences — "when is the next bus?", "is the
     * bus crowded?", "how do I get to the Campus?" — none of which names a
     * stop, a line or a starting point. Tapping one asked a question that could
     * not be answered, and the assistant either guessed or asked back. A
     * suggestion should be a question worth asking.
     */
    private List<String> buildSuggestions(String lang, ChatRequest.ChatContext ctx) {
        boolean it = "it".equals(lang);
        List<String> out = new ArrayList<>();

        // A journey under way takes the chips over, and the search ones go: "how
        // do I get from A to B" is not a question somebody already on their way
        // from A to B has.
        ChatRequest.ActiveJourney j = ctx == null ? null : ctx.getJourney();
        if (j != null) {
            String line  = journeyLine(j);
            String jDest = blankToNull(j.getDestName());
            if (line != null) {
                out.add(it ? "Dov'\u00e8 il bus della linea " + line + "?"
                           : "Where is the line " + line + " bus?");
                out.add(it ? "La linea " + line + " \u00e8 in ritardo?"
                           : "Is line " + line + " running late?");
            }
            if (jDest != null)
                out.add(it ? "Quanto manca per arrivare a " + jDest + "?"
                           : "How much longer to " + jDest + "?");
            if (out.isEmpty())
                out.add(it ? "Quanto manca all'arrivo?" : "How much longer to go?");
            return out.size() > 3 ? out.subList(0, 3) : out;
        }

        String stop   = ctx == null ? null : blankToNull(ctx.getStopName());
        String origin = ctx == null ? null : blankToNull(ctx.getOriginName());
        String dest   = ctx == null ? null : blankToNull(ctx.getDestName());

        if (stop != null) {
            out.add(it ? "Quali linee passano da " + stop + "?"
                       : "Which lines stop at " + stop + "?");
            out.add(it ? "Quando arriva il prossimo bus a " + stop + "?"
                       : "When is the next bus at " + stop + "?");
        }
        if (origin != null && dest != null) {
            out.add(it ? "Come arrivo da " + origin + " a " + dest + "?"
                       : "How do I get from " + origin + " to " + dest + "?");
            out.add(it ? "Qual è il modo più economico per " + dest + "?"
                       : "What is the cheapest way to " + dest + "?");
        } else if (dest != null) {
            out.add(it ? "Come arrivo a " + dest + "?" : "How do I get to " + dest + "?");
        }

        // Nothing on screen to hang a question on: ask about the network itself,
        // which is answerable without knowing where the traveller stands
        if (out.isEmpty()) {
            out.add(it ? "Quali linee ci sono a Cassino?"  : "Which lines run in Cassino?");
            out.add(it ? "Come arrivo al Campus Folcara dal centro?"
                       : "How do I get to the Folcara campus from the centre?");
            out.add(it ? "Conviene la bici o il bus con questo tempo?"
                       : "Bike or bus in this weather?");
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
