package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.ZonedDateTime;

/**
 * A stop the traveller starred, so it can be dropped into origin or destination
 * without typing it out.
 *
 * Holds the stop id rather than its name: names change, and a favourite that
 * points at a label the network no longer uses is a favourite that silently
 * stops working. The name is resolved on read, from the current stops.
 */
@Entity
@Table(name = "favorite_stop")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FavoriteStop {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stop_id", nullable = false, length = 50)
    private String stopId;

    @Column(name = "created_at")
    private ZonedDateTime createdAt;
}
