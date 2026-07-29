package it.unicas.omnimove.controller;

import it.unicas.omnimove.dto.TravellerUpdateRequest;
import it.unicas.omnimove.model.FavoriteRoute;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.model.UserPreferences;
import it.unicas.omnimove.repository.FavoriteRouteRepository;
import it.unicas.omnimove.repository.JourneyLogRepository;
import it.unicas.omnimove.repository.UserPreferencesRepository;
import it.unicas.omnimove.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.service.GreenIndexService;
import it.unicas.omnimove.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import it.unicas.omnimove.model.JourneyLog;

import java.time.ZonedDateTime;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/traveller")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyAuthority('TRAVELLER', 'ROLE_TRAVELLER')")
@Tag(name = "Traveller", description = "Traveller self-service profile management")
public class TravellerController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JourneyLogRepository journeyLogRepository;
    private final GreenIndexService greenIndexService;
    private final FavoriteRouteRepository favoriteRouteRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final SecurityAuditService securityAuditService;

    @PutMapping("/me")
    @Operation(summary = "Update own profile")
    public ResponseEntity<?> updateMe(
            @RequestBody TravellerUpdateRequest req,
            @AuthenticationPrincipal UserDetails principal) {

        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElse(null);
        if (user == null)
            return ResponseEntity.notFound().build();

        boolean nameChanged     = false;
        boolean emailChanged    = false;
        boolean passwordChanged = false;
        String  oldEmail        = user.getEmail();

        if (req.getName() != null && !req.getName().isBlank()) {
            user.setName(req.getName());
            nameChanged = true;
        }

        if (req.getEmail() != null && !req.getEmail().isBlank()
                && !req.getEmail().equalsIgnoreCase(user.getEmail())) {
            if (userRepo.existsByEmail(req.getEmail()))
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Email already in use"));
            user.setEmail(req.getEmail());
            emailChanged = true;
        }

        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            if (req.getCurrentPassword() == null || req.getCurrentPassword().isBlank()
                    || !passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Current password is incorrect"));
            user.setPassword(passwordEncoder.encode(req.getPassword()));
            passwordChanged = true;
        }

        userRepo.save(user);

        // Audit — emit the most specific event for each change
        if (emailChanged)    securityAuditService.profileEmailChanged(oldEmail, user.getEmail());
        if (passwordChanged) securityAuditService.passwordChanged(oldEmail);
        if (nameChanged && !emailChanged && !passwordChanged)
                             securityAuditService.profileUpdated(oldEmail);

        log.info("Traveller {} updated their profile", principal.getUsername());
        return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
    }

    @GetMapping("/preferences")
    @Operation(summary = "Get preferences for the logged-in traveller")
    public ResponseEntity<?> getPreferences(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserPreferences prefs = preferencesRepository.findByUserId(user.getId())
                .orElse(UserPreferences.builder()
                        .userId(user.getId())
                        .defaultJourneyMode("FAST")
                        .avoidHighOccupancy(false)
                        .showWalking(true)
                        .preferBikeOverBus(false)
                        .onlyBusWhenRaining(true)
                        .notifyDelays(true)
                        .notifyTicketExpiry(true)
                        .notifyEcoTip(false)
                        .build());

        return ResponseEntity.ok(prefs);
    }

    @PutMapping("/preferences")
    @Operation(summary = "Save preferences for the logged-in traveller")
    public ResponseEntity<?> savePreferences(@RequestBody UserPreferences body,
                                             @AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        body.setUserId(user.getId());
        preferencesRepository.save(body);

        return ResponseEntity.ok(Map.of("message", "Preferences saved"));
    }

    @GetMapping("/stats")
    @Operation(summary = "Get aggregated eco/usage stats for the logged-in traveller")
    public ResponseEntity<?> getStats(@AuthenticationPrincipal UserDetails principal) {

        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<JourneyLog> all = journeyLogRepository.findByUserId(user.getId());

        ZonedDateTime since30d = ZonedDateTime.now().minusDays(30);
        double spent30d = all.stream()
                .filter(j -> j.getCreatedAt() != null && j.getCreatedAt().isAfter(since30d))
                .mapToDouble(JourneyLog::getCostEuros).sum();

        double co2SavedGrams = all.stream()
                .mapToDouble(j -> {
                    double carCo2 = greenIndexService.computeCo2Grams("CAR", j.getDistanceKm());
                    return Math.max(0, carCo2 - j.getCo2Grams());
                }).sum();

        long ecoPoints = all.stream().mapToInt(JourneyLog::getGreenIndex).sum();

        return ResponseEntity.ok(Map.of(
                "ecoPoints",   ecoPoints,
                "co2SavedKg",  Math.round((co2SavedGrams / 1000.0) * 10) / 10.0,
                "trips",       all.size(),
                "spent30d",    Math.round(spent30d * 100) / 100.0
        ));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<JourneyLog> logs = journeyLogRepository.findByUserId(user.getId());
        logs.sort(Comparator.comparing(JourneyLog::getCreatedAt).reversed());

        List<JourneyLog> limited = logs.stream().limit(20).toList();

        Set<String> favKeys = favoriteRouteRepository.findByUserId(user.getId()).stream()
                .map(f -> f.getMode() + "|" + f.getOriginName() + "|" + f.getDestName())
                .collect(Collectors.toSet());

        List<Map<String, Object>> result = limited.stream().map(j -> {
            String key = j.getMode() + "|" + j.getOriginName() + "|" + j.getDestName();
            Map<String, Object> m = new HashMap<>();
            m.put("id", j.getId());
            m.put("mode", j.getMode());
            m.put("distanceKm", j.getDistanceKm());
            m.put("costEuros", j.getCostEuros());
            m.put("greenIndex", j.getGreenIndex());
            m.put("originName", j.getOriginName());
            m.put("destName", j.getDestName());
            m.put("createdAt", j.getCreatedAt());
            m.put("isFavorite", favKeys.contains(key));
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/favorites")
    @Operation(summary = "Get favourite routes for the logged-in traveller")
    public ResponseEntity<?> getFavorites(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<FavoriteRoute> favs = favoriteRouteRepository.findByUserId(user.getId());
        List<JourneyLog> allTrips = journeyLogRepository.findByUserId(user.getId());

        List<Map<String, Object>> result = favs.stream().map(f -> {
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
                    "id", f.getId(),
                    "mode", f.getMode(),
                    "originName", f.getOriginName(),
                    "destName", f.getDestName(),
                    "usedCount", usedCount,
                    "greenIndex", lastGreenIndex,
                    "avgCost", Math.round(avgCost * 100) / 100.0
            );
        }).toList();

        return ResponseEntity.ok(result);
    }

    @PostMapping("/favorites/toggle")
    @Operation(summary = "Toggle a favourite route by mode + origin + destination")
    public ResponseEntity<?> toggleFavoriteRoute(@RequestBody Map<String, Object> body,
                                                 @AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        String mode   = (String) body.get("mode");
        String origin = (String) body.get("originName");
        String dest   = (String) body.get("destName");

        if (mode == null || origin == null || dest == null)
            return ResponseEntity.badRequest().body(Map.of("message", "mode, originName and destName are required"));

        if (!java.util.Set.of("BUS", "WALK", "BIKE", "SCOOTER").contains(mode.toUpperCase()))
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid transport mode"));

        if (origin.length() > 200 || dest.length() > 200)
            return ResponseEntity.badRequest().body(Map.of("message", "Origin and destination names must be 200 characters or less"));

        var existing = favoriteRouteRepository
                .findByUserIdAndModeAndOriginNameAndDestName(user.getId(), mode, origin, dest);

        if (existing.isPresent()) {
            favoriteRouteRepository.delete(existing.get());
            return ResponseEntity.ok(Map.of("favorited", false));
        } else {
            favoriteRouteRepository.save(FavoriteRoute.builder()
                    .userId(user.getId())
                    .mode(mode)
                    .originName(origin)
                    .destName(dest)
                    .createdAt(ZonedDateTime.now())
                    .build());
            return ResponseEntity.ok(Map.of("favorited", true));
        }
    }

}
