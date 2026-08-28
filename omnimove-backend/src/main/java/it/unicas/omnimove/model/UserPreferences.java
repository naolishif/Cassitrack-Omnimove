package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_preferences")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserPreferences {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "default_journey_mode")
    private String defaultJourneyMode;

    @Column(name = "avoid_high_occupancy")
    private Boolean avoidHighOccupancy;

    @Column(name = "show_walking")
    private Boolean showWalking;

    @Column(name = "prefer_bike_over_bus")
    private Boolean preferBikeOverBus;

    @Column(name = "notify_delays")
    private Boolean notifyDelays;

    /**
     * When it rains, bus options are sorted first and the reason is shown.
     * It never hides walking, bike or scooter — see V22.
     */
    @Column(name = "rain_prefers_bus", nullable = false)
    private Boolean rainPrefersBus = true;

    @Column(name = "max_bike_walk_metres", nullable = false)
    private Integer maxBikeWalkMetres = 500;

    // ── The onboarding answers, 0..5 ────────────────────────────────
    // Stored raw; the weights are derived from them so the normalisation
    // lives in exactly one place (PreferenceWeights).

    @Column(name = "answer_time", nullable = false)
    private Integer answerTime = 3;

    @Column(name = "answer_cost", nullable = false)
    private Integer answerCost = 3;

    @Column(name = "answer_eco", nullable = false)
    private Integer answerEco = 3;

    /** 0 = wants wide transfer margins, 5 = accepts tight connections. */
    @Column(name = "answer_reliability", nullable = false)
    private Integer answerReliability = 3;

    /** Threshold the crowding filter uses, as a percentage of seats. */
    @Column(name = "occupancy_threshold_pct", nullable = false)
    private Integer occupancyThresholdPct = 80;

    @Column(name = "onboarding_done", nullable = false)
    private Boolean onboardingDone = false;

    /**
     * Whether the five behavioural preferences reach beyond the Custom ranking.
     * They always shape Custom — that is the traveller's own profile. Fast,
     * Budget and Eco each answer one question, and letting a profile filter
     * their results makes them answer a different one than their name promises.
     */
    @Column(name = "apply_prefs_to_presets", nullable = false)
    private Boolean applyPrefsToPresets = true;
}