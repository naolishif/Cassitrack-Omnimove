package it.unicas.omnimove.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One authenticated call made with a partner API key: which key, which endpoint, when. */
@Entity
@Table(name = "api_client_usage")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApiClientUsage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = 200)
    private String endpoint;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;
}
