package it.unicas.omnimove.service;

import it.unicas.omnimove.client.CassitrackClient;
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
            String context = buildContext(userId, req.getContext()) + onScreenContext(req.getContext());
            String system = buildSystem(lang, context);
            String answer = callModel(system, req.getHistory(), question);

            return ChatResponse.builder()
                    .answer(answer)
                    .success(true)
                    .detectedLanguage(lang)
                    .suggestions(buildSuggestions(lang, req.getContext()))
                    .build();

        } catch (Exception e) {
            log.error("AI failed: {}", e.getMessage());
            // Graceful fallback so the chat never shows a hard error in a demo
            return ChatResponse.builder()
                    .answer(getFallbackResponse(question, lang))
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
            Stop near = null;
            double nearM = Double.MAX_VALUE;
            for (Stop st : stops) {
                double d = GeoUtils.haversineMetres(v.getLat(), v.getLon(), st.getLat(), st.getLon());
                if (d < nearM) { nearM = d; near = st; }
            }
            if (near != null)
                sb.append(", parked ").append(Math.round(nearM / 10.0) * 10)
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
                - If the weather is bad, warn about bike, scooter and walking.
                - If asked about something outside Cassino transport, steer back.
                """ + " " + lang + "\n\nLive data:\n" + context;
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

    private String getFallbackResponse(String message, String lang) {
        String msg = message == null ? "" : message.toLowerCase();
        boolean it = "it".equals(lang);

        if (msg.contains("bus") || msg.contains("autobus") || msg.contains("vehicle")) {
            return it
                    ? "L'autobus 16 è attivo sulla tratta Cassino\u2013UNICAS. Controlla la mappa per la posizione in tempo reale e gli orari di arrivo."
                    : "Bus 16 is active on the Cassino\u2013UNICAS route. Check the Live Map tab for real-time position and ETA.";
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
                ? "OMNIMOVE monitora l'autobus 16 in tempo reale tra Cassino e il Campus UNICAS di Folcara. Usa la scheda Flotta per le posizioni, ETA per gli arrivi e il Pianificatore per i percorsi."
                : "OMNIMOVE monitors Bus 16 in real time between Cassino and the UNICAS Folcara campus. Use the Fleet tab for live positions, ETA tab for arrival times, and Journey Planner for routes.";
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
