package it.unicas.omnimove.service;

import it.unicas.omnimove.client.CassitrackClient;
import it.unicas.omnimove.dto.ChatRequest;
import it.unicas.omnimove.dto.ChatResponse;
import it.unicas.omnimove.dto.StopArrivalDTO;
import it.unicas.omnimove.dto.VehicleDTO;
import it.unicas.omnimove.model.JourneyLog;
import it.unicas.omnimove.model.Stop;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.StopRepository;
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
    private final JourneyLogRepository journeyLogRepository;
    private final WeatherService weatherService;
    private final GreenIndexService greenIndexService;

    @Value("${ai.api.key:}")
    private String apiKey;
    @Value("${ai.api.url}")
    private String apiUrl;
    @Value("${ai.api.model}")
    private String model;

    public AiOrchestrationService(CassitrackClient cassitrackClient,
                                  StopRepository stopRepository,
                                  JourneyLogRepository journeyLogRepository,
                                  WeatherService weatherService,
                                  GreenIndexService greenIndexService) {
        this.cassitrackClient = cassitrackClient;
        this.stopRepository = stopRepository;
        this.journeyLogRepository = journeyLogRepository;
        this.weatherService = weatherService;
        this.greenIndexService = greenIndexService;
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
            String context = buildContext(userId);
            String system = buildSystem(lang, context);
            String answer = callModel(system, req.getHistory(), question);

            return ChatResponse.builder()
                    .answer(answer)
                    .success(true)
                    .detectedLanguage(lang)
                    .suggestions(buildSuggestions(lang))
                    .build();

        } catch (Exception e) {
            log.error("AI failed: {}", e.getMessage());
            // Graceful fallback so the chat never shows a hard error in a demo
            return ChatResponse.builder()
                    .answer(getFallbackResponse(question, lang))
                    .success(true)              // still "success" so UI renders it nicely
                    .detectedLanguage(lang)
                    .suggestions(buildSuggestions(lang))
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

    private String buildContext(Long userId) {
        StringBuilder sb = new StringBuilder();
        String now = LocalDateTime.now(ZoneId.of("Europe/Rome"))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss 'on' EEEE dd MMMM yyyy"));
        sb.append("=== CASSITRACK LIVE DATA ===\nTime in Cassino: ").append(now).append("\n\n");

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
                        sb.append("    - Bus ").append(a.getVehicleId())
                                .append(": arrives in ").append(etaMin > 0 ? etaMin + " min" : "<1 min")
                                .append(" (").append(a.getScheduleStatus()).append(")\n");
                    });
                }
            } catch (Exception e) {
                sb.append("    ETA unavailable.\n");
            }
        }

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

        // ── 2. Personalisation (logged-in traveller history) ────────────
        if (userId != null) {
            try {
                List<JourneyLog> trips = journeyLogRepository.findByUserId(userId);
                if (!trips.isEmpty()) {
                    trips.sort(Comparator.comparing(JourneyLog::getCreatedAt).reversed());
                    List<JourneyLog> recent = trips.stream().limit(5).toList();

                    sb.append("\n=== THIS TRAVELLER'S RECENT JOURNEYS ===\n");
                    recent.forEach(j -> sb
                            .append("  - ").append(j.getMode())
                            .append(" ").append(j.getOriginName())
                            .append(" \u2192 ").append(j.getDestName())
                            .append(" (Green ").append(j.getGreenIndex())
                            .append(", \u20ac").append(String.format("%.2f", j.getCostEuros()))
                            .append(")\n"));

                    // Most-used mode — lets the AI personalise suggestions
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
                            .append(String.format("%.1f", totalCo2Saved / 1000.0)).append(" kg\n");
                }
            } catch (Exception e) {
                log.warn("Could not load traveller history: {}", e.getMessage());
            }
        }

        sb.append("\nALL STOPS: ");
        sb.append(stopRepository.findAll().stream()
                .map(s -> s.getId() + "=" + s.getName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none"));
        sb.append("\n=== END ===\n");
        return sb.toString();
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
                between Cassino city centre and the UNICAS Engineering Campus (Folcara).

                You have live real-time data from the CASSITRACK fleet system below.
                Guidelines:
                - Be concise, friendly and practical. Two or three sentences is usually enough.
                - When you have live ETA or crowding data, quote it specifically.
                - If the weather is bad, proactively warn about bike/scooter/walk modes.
                - If you know the traveller's recent journeys, personalise your suggestions
                  (e.g. reference their preferred mode), but never invent data you weren't given.
                - If asked about something outside Cassino transport, politely steer back.
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
                ? "OMNIMOVE monitora l'autobus 16 in tempo reale tra Cassino e il Campus di Ingegneria UNICAS. Usa la scheda Flotta per le posizioni, ETA per gli arrivi e il Pianificatore per i percorsi."
                : "OMNIMOVE monitors Bus 16 in real time between Cassino and the UNICAS Engineering Campus. Use the Fleet tab for live positions, ETA tab for arrival times, and Journey Planner for routes.";
    }

    // ════════════════════════════════════════════════════════════════════
    //  FOLLOW-UP SUGGESTIONS
    // ════════════════════════════════════════════════════════════════════

    private List<String> buildSuggestions(String lang) {
        if ("it".equals(lang)) {
            return List.of(
                    "Quando arriva il prossimo autobus?",
                    "L'autobus è affollato?",
                    "Come arrivo al Campus?"
            );
        }
        return List.of(
                "When is the next bus?",
                "Is the bus crowded?",
                "How do I get to the Campus?"
        );
    }
}
