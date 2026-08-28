package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.ZonedDateTime;

@Entity
@Table(name = "journey_log")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class JourneyLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String mode;

    @Column(name = "distance_km")
    private Double distanceKm;

    @Column(name = "cost_euros")
    private Double costEuros;

    @Column(name = "co2_grams")
    private Double co2Grams;

    @Column(name = "green_index")
    private Integer greenIndex;

    @Column(name = "origin_name")
    private String originName;

    @Column(name = "dest_name")
    private String destName;

    // Where the journey actually started and ended. The names above are what the
    // traveller reads; these are what lets a trip be replayed when an end was a
    // point on the map rather than a stop. Null on everything recorded before.
    @Column(name = "origin_lat") private Double originLat;
    @Column(name = "origin_lon") private Double originLon;
    @Column(name = "dest_lat")   private Double destLat;
    @Column(name = "dest_lon")   private Double destLon;

    @Column(name = "created_at")
    private ZonedDateTime createdAt;
}