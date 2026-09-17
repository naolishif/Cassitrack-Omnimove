package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An emergency a partner system has told us about: a flood, an accident,
 * a building site, a street event — anything that makes an area not
 * passable.
 *
 * <p>Described as a centre and a radius — the least a partner can supply
 * without a GIS tool. The partner names the report with its own
 * {@link #externalId} (its {@code eventId}), unique per partner: sending
 * it again updates the row, and {@link #status} {@code false} is how it
 * says the emergency is over. Nothing is deleted, so the history of what
 * was reported stays readable.
 */
@Entity
@Table(name = "road_closures")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RoadClosure {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The partner's {@code eventId}. */
    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    /** The partner's numeric {@code emergencyId}, when it sends one. */
    @Column(name = "emergency_id")
    private Long emergencyId;

    /** The partner's {@code eventType} — FLOOD, ACCIDENT, ROADWORKS… — upper-cased. */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    /** LOW, MEDIUM, HIGH, or whatever the partner sends, upper-cased. */
    @Column(nullable = false, length = 20)
    private String severity;

    @Column(length = 40)
    private String category;

    @Column(length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Radius of the affected area around the centre, in metres. */
    @Column(name = "radius_m", nullable = false)
    private Integer radiusMetres;

    @Column(name = "meeting_lat")
    private Double meetingLat;

    @Column(name = "meeting_lon")
    private Double meetingLon;

    @Column(name = "meeting_address", length = 300)
    private String meetingAddress;

    /** {@code true} = in force, {@code false} = over. */
    @Column(nullable = false)
    private Boolean status;

    /** When the partner said it was over; {@code null} while in force. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Label of the API key that sent the report. */
    @Column(name = "reported_by", nullable = false, length = 100)
    private String reportedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
