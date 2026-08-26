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

    @Column(name = "notify_ticket_expiry")
    private Boolean notifyTicketExpiry;

    @Column(name = "notify_eco_tip")
    private Boolean notifyEcoTip;

    @Column(name = "only_bus_when_raining", nullable = false)
    private Boolean onlyBusWhenRaining = true;

    @Column(name = "max_bike_walk_metres", nullable = false)
    private Integer maxBikeWalkMetres = 500;
}