package it.unicas.cassitrack.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.cassitrack.dto.ChatResponse;
import it.unicas.cassitrack.dto.StopArrivalDTO;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the AI assistant.
 *
 * This service:
 *   1. Collects live data from the CASSITRACK system
 *   2. Builds a detailed context for the AI
 *   3. Sends the user's question + context to Claude
 *   4. Returns Claude's natural language answer
 *
 * The service also handles low-level errors and falls back to local processing
 * when the AI cloud service is unavailable or credits are insufficient.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiOrchestrationService {

    private final VehicleStateCache vehicleStateCache;
    private final ETAService etaService;
    private final ObjectMapper objectMapper;
    private final StopRepository stopRepository;

    @Value("${anthropic.api.key}")
    private String apiKey;

    @Value("${anthropic.api.url}")
    private String apiUrl;

    @Value("${anthropic.api.model}")
    private String model;

    @Value("${anthropic.api.version:2023-06-01}")
    private String apiVersion;

    // Keyword → stopId matching for natural-language questions.
    // This is NLP-specific (not geographic data), so it stays in code.
    // Keys are matched against the stop NAME from the DB, case-insensitively,
    // plus these extra synonyms.
    private static final Map<String, List<String>> STOP_KEYWORDS = Map.of(
            "PSB", List.of("san benedetto", "benedetto", "piazza san benedetto"),
            "SFF", List.of("stazione", "station", "train", "ferrovia", "ff.ss"),
            "UNI", List.of("campus", "unicas", "universit", "folcara", "università"),
            "OSP", List.of("ospedale", "hospital"),
            "LIC", List.of("liceo", "scientifico", "scuola", "school"),
            "P14", List.of("14 febbraio", "quattordici febbraio")
    );

    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");

    /**
     * Main entry point to answer a user's question.
     *
     * @param question The user's question.
     * @param language The language code (e.g., "it" for Italian).
     * @return A ChatResponse containing the AI's answer or an error message.
     */
    public ChatResponse answer(String question, String language) {
        try {
            String context = buildLiveContext();
            log.debug("Built context for AI:\n{}", context);

            String systemPrompt = buildSystemPrompt(language, context);
            String aiAnswer = callClaude(systemPrompt, question);

            return ChatResponse.builder()
                    .answer(aiAnswer)
                    .success(true)
                    .build();

        } catch (RuntimeException e) {
            if (isLowCreditError(e)) {
                log.warn("Anthropic low credit detected: {}", e.getMessage());
                return ChatResponse.builder()
                        .success(true)
                        .error("ANTHROPIC_LOW_CREDIT")
                        .answer(buildLocalFallbackAnswer(question, language))
                        .build();
            }

            log.error("AI orchestration failed: {}", e.getMessage(), e);
            return ChatResponse.builder()
                    .success(false)
                    .error("AI service temporarily unavailable.")
                    .answer("Sorry, I could not process your request right now. Please try again in a moment.")
                    .build();

        } catch (Exception e) {
            log.error("AI orchestration failed: {}", e.getMessage(), e);
            return ChatResponse.builder()
                    .success(false)
                    .error("AI service temporarily unavailable.")
                    .answer("Sorry, I could not process your request right now. Please try again in a moment.")
                    .build();
        }
    }

    private String buildLiveContext() {
        StringBuilder sb = new StringBuilder();

        String now = LocalDateTime.now(ITALY_TZ)
                .format(DateTimeFormatter.ofPattern("HH:mm:ss 'on' EEEE dd MMMM yyyy"));

        sb.append("=== LIVE CASSITRACK DATA ===\n");
        sb.append("Current time in Cassino, Italy: ").append(now).append("\n\n");

        Collection<VehiclePosition> active = vehicleStateCache.getActive();
        if (active.isEmpty()) {
            sb.append("ACTIVE BUSES: None currently tracked.\n");
            sb.append("(GPS simulator may not be running)\n\n");
        } else {
            sb.append("ACTIVE BUSES (").append(active.size()).append(" total):\n");
            active.forEach(v -> {
                sb.append("\n  Bus: ").append(v.getVehicleId())
                        .append("\n    Position: lat=").append(String.format("%.5f", v.getLat()))
                        .append(", lon=").append(String.format("%.5f", v.getLon()))
                        .append("\n    Speed: ").append(v.getSpeedKmh() != null ? String.format("%.1f", v.getSpeedKmh()) : "0.0")
                        .append(" km/h")
                        .append("\n    Schedule status: ").append(v.getScheduleStatus() != null ? v.getScheduleStatus().name() : "UNKNOWN")
                        .append("\n    Route: ").append(v.getMatchedRouteId() != null ? v.getMatchedRouteId() : "LINEA-16")
                        .append("\n    Estimated passengers: ");

                if (v.getBleDeviceCount() != null) {
                    int pax = (int) (v.getBleDeviceCount() * 0.6);
                    sb.append("approximately ").append(pax)
                            .append(" (crowding: ").append(crowdingLabel(pax)).append(")");
                } else {
                    sb.append("unknown");
                }

                sb.append("\n    Last seen: ").append(v.getReceivedAt()).append("\n");
            });
        }

        sb.append("\nETA AT EACH STOP:\n");
        stopIds().forEach(stopId -> {
            sb.append("\n  Stop: ").append(stopId).append("\n");
            try {
                List<StopArrivalDTO> arrivals = etaService.getArrivalsAtStop(stopId);
                if (arrivals.isEmpty()) {
                    sb.append("    No buses expected soon.\n");
                } else {
                    arrivals.forEach(a -> sb.append("    - Bus ").append(a.getVehicleId())
                            .append(": arrives in ")
                            .append(etaMinutesText(a.getEstimatedArrival(), false))
                            .append(" (").append(a.getScheduleStatus()).append(")\n"));
                }
            } catch (Exception e) {
                sb.append("    ETA data unavailable.\n");
            }
        });

        sb.append("\nKNOWN STOPS:\n");
        int idx = 1;
        for (Stop stop : stopRepository.findAll()) {
            sb.append("  ").append(idx++).append(". ")
              .append(stop.getId())
              .append(" = ").append(stop.getName())
              .append("\n");
        }
        sb.append("\n=== END OF LIVE DATA ===\n");

        return sb.toString();
    }

    private String buildSystemPrompt(String language, String liveContext) {
        String langInstruction = "it".equals(language)
                ? "Always respond in Italian."
                : "Always respond in English.";

        return """
            You are CASSITRACK Assistant, an intelligent
            transport information system for the city of
            Cassino, Italy. You help passengers and fleet
            managers get information about the urban bus
            fleet, specifically Linea 16.

            You have access to LIVE real-time data about
            the buses. Use this data to answer questions
            accurately. If the data shows no buses active,
            say so honestly.

            Be concise, friendly, and helpful.

            %s

            Here is the current live data from the
            CASSITRACK system:

            %s
            """.formatted(langInstruction, liveContext);
    }

    private String callClaude(String systemPrompt, String userMessage) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is missing. Set it in environment variables.");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", 1024);
        requestBody.put("system", systemPrompt);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", userMessage)));

        WebClient client = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", apiVersion)
                .build();

        try {
            log.debug("Anthropic request payload: {}", objectMapper.writeValueAsString(requestBody));
        } catch (Exception ignored) {
            log.debug("Failed to serialize Anthropic request payload for debug logs");
        }

        Map<String, Object> response = client.post()
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("<empty error body>")
                                .map(body -> new RuntimeException(
                                        "Anthropic API returned " + clientResponse.statusCode().value() + ": " + body
                                ))
                )
                .bodyToMono(Map.class)
                .timeout(java.time.Duration.ofSeconds(30))
                .block();

        if (response != null && response.containsKey("content")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            if (!content.isEmpty()) {
                return (String) content.get(0).get("text");
            }
        }

        throw new RuntimeException("Unexpected response format from Claude API");
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private boolean isLowCreditError(Throwable error) {
        String message = error != null ? error.getMessage() : null;
        return message != null
                && message.contains("Anthropic API returned 400")
                && message.toLowerCase(Locale.ROOT).contains("credit balance is too low");
    }

    private String buildLocalFallbackAnswer(String question, String language) {
        boolean italian = "it".equalsIgnoreCase(language);
        String mentionedVehicleId = findMentionedVehicleId(question);
        String requestedStopId = findRequestedStopId(question);

        if (isComparisonIntent(question)) {
            String fallbackStop = stopIds().stream().findFirst().orElse(null);
            String targetStop = StringUtils.hasText(requestedStopId)
                    ? requestedStopId : fallbackStop;
            return buildComparisonFallback(targetStop, italian);
        }

        if (isNearestStopIntent(question)) {
            return buildNearestStopFallback(mentionedVehicleId, italian);
        }

        if (isEtaIntent(question)) {
            if (StringUtils.hasText(mentionedVehicleId)) {
                return buildVehicleCombinedFallback(mentionedVehicleId, italian);
            }
            if (StringUtils.hasText(requestedStopId)) {
                return buildStopEtaFallback(requestedStopId, null, italian);
            }
            return buildAllStopsEtaFallback(italian);
        }

        if (StringUtils.hasText(mentionedVehicleId)) {
            return buildVehicleCombinedFallback(mentionedVehicleId, italian);
        }

        if (StringUtils.hasText(requestedStopId)) {
            return buildStopEtaFallback(requestedStopId, null, italian);
        }

        int activeCount = vehicleStateCache.getActive().size();
        return italian
                ? String.format(Locale.ROOT,
                "Il servizio AI cloud non e disponibile (crediti API insufficienti). Traccio %d autobus attivi. Prova: 'Dov'e BUS-101?', 'Quale bus arriva prima al Campus?', 'ETA al Centro'.",
                activeCount)
                : String.format(Locale.ROOT,
                "Cloud AI is unavailable (insufficient API credits). I currently track %d active buses. Try: 'Where is BUS-101?', 'Which bus reaches Campus first?', 'ETA to Centro'.",
                activeCount);
    }

    private boolean isEtaIntent(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("when") || q.contains("eta") || q.contains("arrive")
                || q.contains("next bus") || q.contains("quando")
                || q.contains("arriva") || q.contains("prossim") || q.contains("tempo");
    }

    private boolean isComparisonIntent(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        boolean comparisonWord = q.contains("which") || q.contains("first") || q.contains("faster")
                || q.contains("quale") || q.contains("primo") || q.contains("prima");
        return comparisonWord && isEtaIntent(question);
    }

    private boolean isNearestStopIntent(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("nearest stop") || q.contains("closest stop") || q.contains("which stop")
                || q.contains("fermata") || q.contains("piu vicina") || q.contains("piu vicino");
    }

    private String findRequestedStopId(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        String q = question.toLowerCase(Locale.ROOT);

        // 1. Try matching against the real stop names from the DB
        for (Stop stop : stopRepository.findAll()) {
            if (stop.getName() != null
                    && q.contains(stop.getName().toLowerCase(Locale.ROOT))) {
                return stop.getId();
            }
        }

        // 2. Fall back to the NLP keyword synonyms
        for (Map.Entry<String, List<String>> entry : STOP_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (q.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private String buildAllStopsEtaFallback(boolean italian) {
        List<String> lines = new ArrayList<>();
        try {
            for (String stopId : stopIds()) {
                List<StopArrivalDTO> arrivals = etaService.getArrivalsAtStop(stopId);
                if (!arrivals.isEmpty()) {
                    StopArrivalDTO next = arrivals.get(0);
                    lines.add("- " + stopLabel(stopId, italian)
                            + ": " + next.getVehicleId()
                            + " in " + etaMinutesText(next.getEstimatedArrival(), italian)
                            + " (" + scheduleExplanation(next, italian) + ")");
                }
            }

            if (lines.isEmpty()) {
                return italian
                        ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Al momento non ci sono ETA disponibili alle fermate principali."
                        : "Cloud AI is unavailable (insufficient API credits). There are currently no ETA predictions at the main stops.";
            }

            String header = italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). ETA live principali:\n"
                    : "Cloud AI is unavailable (insufficient API credits). Main live ETAs:\n";
            return header + String.join("\n", lines);

        } catch (Exception e) {
            log.warn("All-stops ETA fallback failed: {}", e.getMessage());
            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti) e non riesco a leggere gli ETA in questo momento."
                    : "Cloud AI is unavailable (insufficient API credits) and I cannot read ETAs right now.";
        }
    }

    private String buildStopEtaFallback(String stopId, String vehicleId, boolean italian) {
        try {
            List<StopArrivalDTO> arrivals = etaService.getArrivalsAtStop(stopId);
            if (StringUtils.hasText(vehicleId)) {
                arrivals = arrivals.stream()
                        .filter(a -> vehicleId.equalsIgnoreCase(a.getVehicleId()))
                        .toList();
            }

            if (arrivals.isEmpty()) {
                String label = stopLabel(stopId, italian);
                return italian
                        ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Nessun bus previsto a breve per " + label + "."
                        : "Cloud AI is unavailable (insufficient API credits). No bus is predicted soon for " + label + ".";
            }

            StopArrivalDTO next = arrivals.get(0);
            String label = stopLabel(stopId, italian);
            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti), ma con i dati live: prossimo bus per "
                    + label + " e " + next.getVehicleId() + " in "
                    + etaMinutesText(next.getEstimatedArrival(), true) + " ("
                    + scheduleExplanation(next, true) + ")."
                    : "Cloud AI is unavailable (insufficient API credits), but using live data: next bus for "
                    + label + " is " + next.getVehicleId() + " in "
                    + etaMinutesText(next.getEstimatedArrival(), false) + " ("
                    + scheduleExplanation(next, false) + ").";

        } catch (Exception e) {
            log.warn("Stop ETA fallback failed for {}: {}", stopId, e.getMessage());
            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti) e non riesco a leggere l'ETA richiesto ora."
                    : "Cloud AI is unavailable (insufficient API credits) and I cannot read the requested ETA right now.";
        }
    }

    private String buildVehicleCombinedFallback(String vehicleId, boolean italian) {
        Optional<VehiclePosition> vehicle = vehicleStateCache.getActive().stream()
                .filter(v -> vehicleId.equalsIgnoreCase(v.getVehicleId()))
                .findFirst();

        if (vehicle.isEmpty()) {
            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Non trovo " + vehicleId + " tra i bus attivi ora."
                    : "Cloud AI is unavailable (insufficient API credits). I cannot find " + vehicleId + " among active buses right now.";
        }

        VehiclePosition v = vehicle.get();
        int pax = v.getBleDeviceCount() != null ? (int) (v.getBleDeviceCount() * 0.6) : -1;
        String crowding = pax >= 0 ? crowdingLabel(pax) : "UNKNOWN";

        Optional<Map.Entry<String, StopArrivalDTO>> nextArrival = findNextArrivalForVehicle(vehicleId);
        if (nextArrival.isPresent()) {
            String stopId = nextArrival.get().getKey();
            StopArrivalDTO eta = nextArrival.get().getValue();
            return italian
                    ? String.format(Locale.ROOT,
                    "Il servizio AI cloud non e disponibile (crediti API insufficienti), ma ecco i dati live: %s e a lat=%.5f, lon=%.5f, velocita %.1f km/h, affollamento %s. Prossima fermata stimata: %s tra %s (%s).",
                    v.getVehicleId(), v.getLat(), v.getLon(),
                    v.getSpeedKmh() != null ? v.getSpeedKmh() : 0.0,
                    crowding,
                    stopLabel(stopId, true),
                    etaMinutesText(eta.getEstimatedArrival(), true),
                    scheduleExplanation(eta, true))
                    : String.format(Locale.ROOT,
                    "Cloud AI is unavailable (insufficient API credits), but here is live data: %s is at lat=%.5f, lon=%.5f, speed %.1f km/h, crowding %s. Next predicted stop: %s in %s (%s).",
                    v.getVehicleId(), v.getLat(), v.getLon(),
                    v.getSpeedKmh() != null ? v.getSpeedKmh() : 0.0,
                    crowding,
                    stopLabel(stopId, false),
                    etaMinutesText(eta.getEstimatedArrival(), false),
                    scheduleExplanation(eta, false));
        }

        return italian
                ? String.format(Locale.ROOT,
                "Il servizio AI cloud non e disponibile (crediti API insufficienti), ma ecco i dati live: %s e a lat=%.5f, lon=%.5f, velocita %.1f km/h, affollamento %s. ETA non disponibile al momento.",
                v.getVehicleId(), v.getLat(), v.getLon(),
                v.getSpeedKmh() != null ? v.getSpeedKmh() : 0.0,
                crowding)
                : String.format(Locale.ROOT,
                "Cloud AI is unavailable (insufficient API credits), but here is live data: %s is at lat=%.5f, lon=%.5f, speed %.1f km/h, crowding %s. ETA is currently unavailable.",
                v.getVehicleId(), v.getLat(), v.getLon(),
                v.getSpeedKmh() != null ? v.getSpeedKmh() : 0.0,
                crowding);
    }

    private String buildNearestStopFallback(String mentionedVehicleId, boolean italian) {
        Collection<VehiclePosition> active = vehicleStateCache.getActive();
        if (active.isEmpty()) {
            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Non vedo autobus attivi ora."
                    : "Cloud AI is unavailable (insufficient API credits). I do not see active buses right now.";
        }

        if (StringUtils.hasText(mentionedVehicleId)) {
            Optional<VehiclePosition> bus = active.stream()
                    .filter(v -> mentionedVehicleId.equalsIgnoreCase(v.getVehicleId()))
                    .findFirst();
            if (bus.isEmpty()) {
                return italian
                        ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Non trovo " + mentionedVehicleId + " tra i bus attivi."
                        : "Cloud AI is unavailable (insufficient API credits). I cannot find " + mentionedVehicleId + " among active buses.";
            }
            return formatNearestStopForVehicle(bus.get(), italian);
        }

        return active.stream()
                .map(v -> formatNearestStopForVehicle(v, italian))
                .limit(2)
                .reduce((a, b) -> a + "\n" + b)
                .orElseGet(() -> italian ? "Nessun dato disponibile." : "No data available.");
    }

    private String formatNearestStopForVehicle(VehiclePosition v, boolean italian) {
        String nearestStopId = findNearestStopId(v.getLat(), v.getLon());
        double distance = distanceToStopMetres(v.getLat(), v.getLon(), nearestStopId);
        Optional<StopArrivalDTO> eta = getEtaForVehicleToStop(v.getVehicleId(), nearestStopId);
        String etaText = eta.map(a -> etaMinutesText(a.getEstimatedArrival(), italian))
                .orElse(italian ? "ETA non disponibile" : "ETA unavailable");

        return italian
                ? String.format(Locale.ROOT,
                "Il servizio AI cloud non e disponibile (crediti API insufficienti). %s e piu vicino a %s (circa %dm), ETA %s.",
                v.getVehicleId(), stopLabel(nearestStopId, true), (int) Math.round(distance), etaText)
                : String.format(Locale.ROOT,
                "Cloud AI is unavailable (insufficient API credits). %s is nearest to %s (about %dm), ETA %s.",
                v.getVehicleId(), stopLabel(nearestStopId, false), (int) Math.round(distance), etaText);
    }

    private String buildComparisonFallback(String stopId, boolean italian) {
        try {
            List<StopArrivalDTO> arrivals = etaService.getArrivalsAtStop(stopId);
            String stopName = stopLabel(stopId, italian);

            if (arrivals.isEmpty()) {
                return italian
                        ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Nessun confronto disponibile per " + stopName + "."
                        : "Cloud AI is unavailable (insufficient API credits). No comparison is available for " + stopName + ".";
            }

            StopArrivalDTO first = arrivals.get(0);
            if (arrivals.size() == 1) {
                return italian
                        ? "Il servizio AI cloud non e disponibile (crediti API insufficienti). Verso " + stopName
                        + " c'e solo " + first.getVehicleId() + " in "
                        + etaMinutesText(first.getEstimatedArrival(), true) + "."
                        : "Cloud AI is unavailable (insufficient API credits). For " + stopName
                        + ", only " + first.getVehicleId() + " is predicted in "
                        + etaMinutesText(first.getEstimatedArrival(), false) + ".";
            }

            StopArrivalDTO second = arrivals.get(1);
            long gapSec = second.getEstimatedArrival().getEpochSecond() - first.getEstimatedArrival().getEpochSecond();
            long gapMin = Math.max(0, gapSec / 60);

            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti), ma con i dati live: "
                    + "arriva prima " + first.getVehicleId() + " a " + stopName + " in "
                    + etaMinutesText(first.getEstimatedArrival(), true) + ". "
                    + second.getVehicleId() + " segue dopo circa " + gapMin + " min."
                    : "Cloud AI is unavailable (insufficient API credits), but using live data: "
                    + first.getVehicleId() + " reaches " + stopName + " first in "
                    + etaMinutesText(first.getEstimatedArrival(), false) + ". "
                    + second.getVehicleId() + " follows about " + gapMin + " min later.";

        } catch (Exception e) {
            log.warn("Comparison fallback failed for {}: {}", stopId, e.getMessage());
            return italian
                    ? "Il servizio AI cloud non e disponibile (crediti API insufficienti) e non riesco a confrontare gli arrivi adesso."
                    : "Cloud AI is unavailable (insufficient API credits) and I cannot compare arrivals right now.";
        }
    }

    private Optional<Map.Entry<String, StopArrivalDTO>> findNextArrivalForVehicle(String vehicleId) {
        Map.Entry<String, StopArrivalDTO> best = null;
        for (String stopId : stopIds()) {
            try {
                for (StopArrivalDTO a : etaService.getArrivalsAtStop(stopId)) {
                    if (!vehicleId.equalsIgnoreCase(a.getVehicleId())) {
                        continue;
                    }
                    if (best == null || a.getEstimatedArrival().isBefore(best.getValue().getEstimatedArrival())) {
                        best = new AbstractMap.SimpleEntry<>(stopId, a);
                    }
                }
            } catch (Exception ignored) {
                // Ignore one stop failure and continue with others.
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<StopArrivalDTO> getEtaForVehicleToStop(String vehicleId, String stopId) {
        try {
            return etaService.getArrivalsAtStop(stopId).stream()
                    .filter(a -> vehicleId.equalsIgnoreCase(a.getVehicleId()))
                    .findFirst();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** All stop IDs currently in the DB. */
    private List<String> stopIds() {
        return stopRepository.findAll().stream()
                .map(Stop::getId)
                .toList();
    }

    /** Coordinates [lat, lon] of a stop from the DB, or null if unknown. */
    private double[] stopCoords(String stopId) {
        return stopRepository.findById(stopId)
                .filter(s -> s.getLat() != null && s.getLon() != null)
                .map(s -> new double[]{s.getLat(), s.getLon()})
                .orElse(null);
    }

    private String findNearestStopId(double lat, double lon) {
        String nearest = null;
        double best = Double.MAX_VALUE;
        for (Stop stop : stopRepository.findAll()) {
            if (stop.getLat() == null || stop.getLon() == null) continue;
            double d = haversineMetres(lat, lon, stop.getLat(), stop.getLon());
            if (d < best) {
                best = d;
                nearest = stop.getId();
            }
        }
        return nearest;
    }

    private double distanceToStopMetres(double lat, double lon, String stopId) {
        double[] coords = stopCoords(stopId);
        if (coords == null) return Double.MAX_VALUE;
        return haversineMetres(lat, lon, coords[0], coords[1]);
    }

    private double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        final double earth = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earth * c;
    }

    private String stopLabel(String stopId, boolean italian) {
        String name = stopRepository.findById(stopId)
                .map(Stop::getName)
                .orElse(stopId);
        return name;
    }

    private String etaMinutesText(Instant eta, boolean italian) {
        long etaSec = eta.getEpochSecond() - (System.currentTimeMillis() / 1000);
        long etaMin = Math.max(0, etaSec / 60);
        if (etaMin == 0) {
            return italian ? "meno di 1 minuto" : "less than 1 minute";
        }
        return etaMin + (italian ? " minuti" : " minutes");
    }

    private String scheduleExplanation(StopArrivalDTO arrival, boolean italian) {
        Integer delay = arrival.getDelayMinutes();
        if (delay != null) {
            if (delay > 0) return italian ? "in ritardo di " + delay + " min" : delay + " min late";
            if (delay < 0) return italian ? "in anticipo di " + Math.abs(delay) + " min" : Math.abs(delay) + " min early";
            return italian ? "in orario" : "on time";
        }
        String status = arrival.getScheduleStatus();
        return StringUtils.hasText(status) ? status : (italian ? "stato non disponibile" : "status unavailable");
    }

    private String findMentionedVehicleId(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        String upper = question.toUpperCase(Locale.ROOT);
        return vehicleStateCache.getActive().stream()
                .map(VehiclePosition::getVehicleId)
                .filter(StringUtils::hasText)
                .filter(id -> upper.contains(id.toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private String crowdingLabel(int passengers) {
        if (passengers < 10) return "LOW";
        if (passengers < 25) return "MEDIUM";
        if (passengers < 40) return "HIGH";
        return "VERY HIGH";
    }
}

