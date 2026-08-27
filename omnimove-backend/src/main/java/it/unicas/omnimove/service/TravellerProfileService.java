package it.unicas.omnimove.service;

import it.unicas.omnimove.model.FavoriteRoute;
import it.unicas.omnimove.model.FavoriteStop;
import it.unicas.omnimove.model.JourneyLog;
import it.unicas.omnimove.repository.FavoriteRouteRepository;
import it.unicas.omnimove.repository.FavoriteStopRepository;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything a user sees about their own travel: headline figures, journey
 * history, starred routes and starred stops.
 *
 * Shared by the traveller's own pages and by the admin dashboard: the operator
 * must read exactly what the user reads, so there is one implementation of the
 * arithmetic rather than two that can drift.
 */
@Service
@RequiredArgsConstructor
public class TravellerProfileService {

    /** Window behind the "spent" figure — 30 days, as shown in the app. */
    private static final int SPEND_WINDOW_DAYS = 30;

    /** Journeys listed in the history panel. */
    public static final int HISTORY_LIMIT = 20;

    private final JourneyLogRepository       journeyLogRepository;
    private final FavoriteRouteRepository    favoriteRouteRepository;
    private final FavoriteStopRepository     favoriteStopRepository;
    private final StopRepository             stopRepository;
    private final GreenIndexService          greenIndexService;

    /** Eco points, CO2 saved, trip count and 30-day spend. */
    public Map<String, Object> stats(Long userId) {

        List<JourneyLog> all = journeyLogRepository.findByUserId(userId);

        ZonedDateTime since = ZonedDateTime.now().minusDays(SPEND_WINDOW_DAYS);
        double spent30d = all.stream()
                .filter(j -> j.getCreatedAt() != null && j.getCreatedAt().isAfter(since))
                .mapToDouble(JourneyLog::getCostEuros).sum();

        // "Saved" is measured against making the same trip by car
        double co2SavedGrams = all.stream()
                .mapToDouble(j -> {
                    double carCo2 = greenIndexService.computeCo2Grams("CAR", j.getDistanceKm());
                    return Math.max(0, carCo2 - j.getCo2Grams());
                }).sum();

        long ecoPoints = all.stream().mapToInt(JourneyLog::getGreenIndex).sum();

        return Map.of(
                "ecoPoints",  ecoPoints,
                "co2SavedKg", Math.round((co2SavedGrams / 1000.0) * 10) / 10.0,
                "trips",      all.size(),
                "spent30d",   Math.round(spent30d * 100) / 100.0
        );
    }

    /** Most recent journeys, newest first, each flagged if it is a starred route. */
    public List<Map<String, Object>> history(Long userId, int limit) {

        List<JourneyLog> logs = journeyLogRepository.findByUserId(userId);
        logs.sort(Comparator.comparing(JourneyLog::getCreatedAt).reversed());

        List<JourneyLog> limited = logs.stream().limit(limit).toList();

        Set<String> favKeys = favoriteRouteRepository.findByUserId(userId).stream()
                .map(f -> f.getMode() + "|" + f.getOriginName() + "|" + f.getDestName())
                .collect(Collectors.toSet());

        return limited.stream().map(j -> {
            String key = j.getMode() + "|" + j.getOriginName() + "|" + j.getDestName();
            Map<String, Object> m = new HashMap<>();
            m.put("id",          j.getId());
            m.put("mode",        j.getMode());
            m.put("distanceKm",  j.getDistanceKm());
            m.put("costEuros",   j.getCostEuros());
            m.put("greenIndex",  j.getGreenIndex());
            m.put("originName",  j.getOriginName());
            m.put("destName",    j.getDestName());
            m.put("createdAt",   j.getCreatedAt());
            m.put("isFavorite",  favKeys.contains(key));
            return m;
        }).toList();
    }

    /**
     * Routes the user has starred, each summarised from the journeys that match
     * it: how often it was travelled, what it typically cost, how green the last
     * run was.
     */
    public List<Map<String, Object>> favoriteRoutes(Long userId) {

        List<FavoriteRoute> favs = favoriteRouteRepository.findByUserId(userId);
        List<JourneyLog> allTrips = journeyLogRepository.findByUserId(userId);

        return favs.stream().map(f -> {
            List<JourneyLog> matching = allTrips.stream()
                    .filter(j -> j.getMode().equals(f.getMode())
                            && f.getOriginName().equals(j.getOriginName())
                            && f.getDestName().equals(j.getDestName()))
                    .toList();

            int usedCount = matching.size();
            int lastGreenIndex = matching.stream()
                    .max(Comparator.comparing(JourneyLog::getCreatedAt))
                    .map(JourneyLog::getGreenIndex).orElse(0);
            double avgCost = matching.stream().mapToDouble(JourneyLog::getCostEuros).average().orElse(0);

            return Map.<String, Object>of(
                    "id",         f.getId(),
                    "mode",       f.getMode(),
                    "originName", f.getOriginName(),
                    "destName",   f.getDestName(),
                    "usedCount",  usedCount,
                    "greenIndex", lastGreenIndex,
                    "avgCost",    Math.round(avgCost * 100) / 100.0
            );
        }).toList();
    }

    /** Stops the user has starred, oldest first. */
    public List<Map<String, Object>> favoriteStops(Long userId) {

        // Names and coordinates come from the current stops, never from what was
        // stored: the id is the only thing that survives a rename. A favourite
        // whose stop the network no longer serves is left out rather than
        // returned half-empty — there is nothing the traveller could do with it.
        List<Map<String, Object>> result = new ArrayList<>();
        for (FavoriteStop f : favoriteStopRepository.findByUserIdOrderByCreatedAtAsc(userId)) {
            stopRepository.findById(f.getStopId())
                .filter(st -> st.getLat() != null && st.getLon() != null)
                .ifPresent(st -> result.add(Map.of(
                        "id",      f.getId(),
                        "stop_id", st.getId(),
                        "name",    st.getName() != null ? st.getName() : st.getId(),
                        "lat",     st.getLat(),
                        "lon",     st.getLon())));
        }
        return result;
    }

    /** Total journeys logged, so a capped history can say what it is a slice of. */
    public int tripCount(Long userId) {
        return journeyLogRepository.findByUserId(userId).size();
    }
}
