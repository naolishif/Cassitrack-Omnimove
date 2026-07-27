package it.unicas.cassitrack.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "buses")
@Data
public class Bus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bus_id")
    private Integer busId;

    @Column(nullable = false, unique = true)
    private String targa;

    @Column(name = "numero_posti", nullable = false)
    private Integer numeroPosti;

    @Column(name = "wheelchair_accessible", nullable = false)
    private Boolean wheelchairAccessible;

    /**
     * Legacy two-state availability flag.
     * Kept for backwards compatibility with existing code (NeTEx export etc.).
     * BusService keeps it in sync with {@link #status}: ACTIVE -> true, otherwise false.
     */
    @Column(nullable = false)
    private Boolean disponibile;

    @Column(name = "current_vehicle_id", unique = true)
    private String currentVehicleId;

    // ── US-01 additions ────────────────────────────────────────────

    /** Route this bus is assigned to (FK to routes.id). Null = unassigned. */
    @Column(name = "route_id", length = 50)
    private String routeId;

    /** ACTIVE | INACTIVE | MAINTENANCE — enforced by a CHECK constraint in V7. */
    @Column(nullable = false, length = 20)
    private String status;

    /** Whether this bus is drawn on the fleet map (US-05 toggle). */
    @Column(name = "map_visible", nullable = false)
    private Boolean mapVisible;
}
