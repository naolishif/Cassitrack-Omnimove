package it.unicas.cassitrack.model;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scheduled_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Questa è l'UNICA relazione che deve legare la fermata al suo viaggio.
    // Hibernate cercherà la colonna "trip_id" (che esiste nel DB) anziché "route_id".
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    // stop_id non c'è più (V27). Quale fermata occupi questa posizione lo dice
    // route_stops, tramite la linea della corsa: usare RoutePatternService.
    // Tenerlo qui significava 1884 copie di un'informazione che ne richiede 142,
    // e nessun vincolo che impedisse a due corse della stessa linea di divergere.

    @Column(name = "stop_sequence", nullable = false)
    private Integer stopSequence;

    @Column(name = "arrival_seconds", nullable = false)
    private Integer arrivalSeconds;

    // ⚠️ CONTROLLA QUI SOTTO:
    // Non devono esserci variabili come "private Route route" o "private String serviceType".
}