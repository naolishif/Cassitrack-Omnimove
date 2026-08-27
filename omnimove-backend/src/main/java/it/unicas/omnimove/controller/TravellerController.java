package it.unicas.omnimove.controller;

import it.unicas.omnimove.dto.TravellerUpdateRequest;
import it.unicas.omnimove.model.FavoriteRoute;
import it.unicas.omnimove.model.FavoriteStop;
import it.unicas.omnimove.model.User;
import it.unicas.omnimove.model.UserPreferences;
import it.unicas.omnimove.repository.FavoriteRouteRepository;
import it.unicas.omnimove.repository.FavoriteStopRepository;
import it.unicas.omnimove.repository.StopRepository;
import it.unicas.omnimove.repository.UserPreferencesRepository;
import it.unicas.omnimove.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.unicas.omnimove.security.PasswordPolicy;
import it.unicas.omnimove.util.RequestLang;
import it.unicas.omnimove.service.PasswordResetService;
import it.unicas.omnimove.service.RateLimiterService;
import it.unicas.omnimove.service.SecurityAuditService;
import it.unicas.omnimove.service.SessionService;
import it.unicas.omnimove.service.TravellerProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;


import java.time.ZonedDateTime;

@RestController
@RequestMapping("/api/v1/traveller")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAnyAuthority('TRAVELLER', 'ROLE_TRAVELLER')")
@Tag(name = "Traveller", description = "Traveller self-service profile management")
public class TravellerController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final FavoriteRouteRepository favoriteRouteRepository;
    private final FavoriteStopRepository  favoriteStopRepository;
    private final StopRepository          stopRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final SecurityAuditService securityAuditService;
    private final TravellerProfileService travellerProfileService;
    private final PasswordResetService    passwordResetService;
    private final RateLimiterService      rateLimiter;
    private final SessionService         sessionService;

    @GetMapping("/me")
    @Operation(summary = "Own profile, including how this account signs in")
    public ResponseEntity<?> getMe(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // hasPassword decides whether the account page offers "change password"
        // or "set a password": a Google account has none to confirm against.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name",         user.getName());
        body.put("email",        user.getEmail());
        body.put("role",         user.getRole());
        body.put("authProvider", user.getAuthProvider());
        body.put("hasPassword",  user.hasPassword());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/me")
    @Operation(summary = "Update own profile")
    public ResponseEntity<?> updateMe(
            @RequestBody TravellerUpdateRequest req,
            @AuthenticationPrincipal UserDetails principal,
            HttpServletRequest request) {

        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElse(null);
        if (user == null)
            return ResponseEntity.notFound().build();

        boolean nameChanged     = false;
        boolean emailChanged    = false;
        boolean passwordChanged = false;
        boolean passwordAdded   = false;
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

            // A Google account has no current password to confirm against. The
            // live session is the proof here — the same reasoning every "you are
            // already signed in" flow uses — so the first password is set
            // without one, and every later change asks for it as usual.
            if (user.hasPassword()) {
                if (req.getCurrentPassword() == null || req.getCurrentPassword().isBlank()
                        || !passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
                    return ResponseEntity.badRequest()
                            .body(Map.of("message", "Current password is incorrect"));
            }

            // Was missing entirely: sign-up and reset both enforce the policy,
            // so a password set from this page could be weaker than one the
            // same account was refused at registration.
            if (!PasswordPolicy.isValid(req.getPassword()))
                return ResponseEntity.badRequest()
                        .body(Map.of("message", PasswordPolicy.message(RequestLang.of(request))));

            passwordAdded   = !user.hasPassword();
            passwordChanged = true;
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }

        userRepo.save(user);

        // Audit — emit the most specific event for each change
        if (emailChanged)    securityAuditService.profileEmailChanged(oldEmail, user.getEmail());
        if (passwordChanged) securityAuditService.passwordChanged(oldEmail);
        // Worth its own line: the account gained a second way in
        if (passwordAdded)   log.info("Traveller {} set a first password on a {} account",
                                      oldEmail, user.getAuthProvider());
        if (nameChanged && !emailChanged && !passwordChanged)
                             securityAuditService.profileUpdated(oldEmail);

        log.info("Traveller {} updated their profile", principal.getUsername());
        return ResponseEntity.ok(Map.of(
                "message",     passwordAdded ? "Password set successfully" : "Profile updated successfully",
                "hasPassword", user.hasPassword()));
    }

    @PostMapping("/me/password-reset")
    @Operation(summary = "Email myself a password-reset link",
               description = "For a signed-in user who no longer remembers their "
                           + "current password. The link always goes to the address "
                           + "on the account — it is never taken from the request.")
    public ResponseEntity<?> requestOwnPasswordReset(@AuthenticationPrincipal UserDetails principal,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Same bucket as the public form: being signed in is no reason to let
        // someone use the account as a mail relay
        if (!rateLimiter.allowForgotPassword(user.getEmail()))
            return ResponseEntity.status(429)
                    .body(Map.of("message", "Too many requests. Please wait before asking for another link."));

        passwordResetService.sendResetLink(user, RequestLang.of(request));

        // Someone who cannot remember their password should not be left holding a
        // live session: whoever is at the keyboard has not proved they are the
        // account owner, and the link that just went out is the proof. The old
        // token is revoked here so the reset is a real re-authentication rather
        // than a formality performed from inside an already-open door.
        sessionService.terminate(request, response);
        securityAuditService.logout(user.getEmail());

        return ResponseEntity.ok(Map.of(
                "message",        "We have emailed you a link to set a new password.",
                "email",          user.getEmail(),
                "expiresInHours", PasswordResetService.RESET_EXPIRY_HOURS,
                "sessionEnded",   true));
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
                        .maxBikeWalkMetres(500)
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

        // Same figures the admin dashboard reads back — see TravellerProfileService
        return ResponseEntity.ok(travellerProfileService.stats(user.getId()));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(travellerProfileService.history(
                user.getId(), TravellerProfileService.HISTORY_LIMIT));
    }

    // ── Favourite stops ───────────────────────────────────────────
    // Separate from favourite routes on purpose: a route is a pair the traveller
    // has already travelled, a stop is one end they keep reusing. Starring a stop
    // must not require having made the trip first.

    private static final java.util.regex.Pattern STOP_ID_RE =
            java.util.regex.Pattern.compile("^[A-Za-z0-9\\-_]{1,50}$");

    @GetMapping("/favorite-stops")
    @Operation(summary = "Stops the logged-in traveller has starred")
    public ResponseEntity<?> getFavoriteStops(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(travellerProfileService.favoriteStops(user.getId()));
    }

    @PostMapping("/favorite-stops")
    @Operation(summary = "Star a stop")
    public ResponseEntity<?> addFavoriteStop(@RequestBody Map<String, String> body,
                                             @AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        String stopId = body == null ? null : body.get("stop_id");
        if (stopId == null || !STOP_ID_RE.matcher(stopId).matches())
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid stop id"));
        if (stopRepository.findById(stopId).isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Unknown stop"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Idempotent: starring twice is the same as starring once, and the unique
        // constraint would otherwise turn a double tap into a 500.
        favoriteStopRepository.findByUserIdAndStopId(user.getId(), stopId)
                .orElseGet(() -> favoriteStopRepository.save(FavoriteStop.builder()
                        .userId(user.getId()).stopId(stopId)
                        .createdAt(ZonedDateTime.now()).build()));

        return ResponseEntity.ok(Map.of("stop_id", stopId, "starred", true));
    }

    @DeleteMapping("/favorite-stops/{stopId}")
    @Operation(summary = "Un-star a stop")
    public ResponseEntity<?> removeFavoriteStop(@PathVariable String stopId,
                                                @AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        if (!STOP_ID_RE.matcher(stopId).matches())
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid stop id"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Scoped to the caller: the id in the path is the stop, never the row, so
        // one traveller cannot delete another's favourite by guessing a number.
        favoriteStopRepository.findByUserIdAndStopId(user.getId(), stopId)
                .ifPresent(favoriteStopRepository::delete);

        return ResponseEntity.ok(Map.of("stop_id", stopId, "starred", false));
    }

    @GetMapping("/favorites")
    @Operation(summary = "Get favourite routes for the logged-in traveller")
    public ResponseEntity<?> getFavorites(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null)
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));

        User user = userRepo.findByEmail(principal.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(travellerProfileService.favoriteRoutes(user.getId()));
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

        // Normalised before both the lookup and the insert, so starring and
        // un-starring the same card always land on the same row
        mode = mode.trim().toUpperCase();

        if (!isFavouritableMode(mode))
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

    private static final Set<String> SIMPLE_MODES = Set.of("BUS", "WALK", "BIKE", "SCOOTER");

    /** favorite_route.mode is VARCHAR(20) — a longer chain is rejected here
     *  rather than blowing up on the insert. */
    private static final int MODE_MAX = 20;

    /**
     * A combined option does not arrive as a single word: JourneyPlannerService
     * names it after the chain it stitched — BUS_BIKE, SCOOTER_BUS — so each
     * link has to be checked, not the whole name. Matching the full string
     * against the simple modes is what used to make every combined route
     * impossible to star.
     */
    private boolean isFavouritableMode(String mode) {
        if (mode.isEmpty() || mode.length() > MODE_MAX) return false;
        // -1 keeps trailing empties, so "BUS_" is rejected instead of read as "BUS"
        for (String leg : mode.split("_", -1)) {
            if (!SIMPLE_MODES.contains(leg)) return false;
        }
        return true;
    }

}
