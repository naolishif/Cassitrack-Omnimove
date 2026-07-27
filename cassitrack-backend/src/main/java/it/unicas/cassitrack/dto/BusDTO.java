package it.unicas.cassitrack.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Data Transfer Object for the bus registry (the {@code buses} table).
 *
 * Used for create, update and read. Kept separate from VehicleStatusDTO,
 * which carries live telemetry (position, speed, crowding). This one is the
 * static registry record the fleet manager edits.
 */
@Data
public class BusDTO {

    /** Auto-generated primary key. Null when creating a new bus. */
    private Integer busId;

    @NotBlank(message = "Plate (targa) is required")
    private String targa;

    @NotNull(message = "Capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer numeroPosti;

    @NotNull(message = "Wheelchair accessibility must be specified")
    private Boolean wheelchairAccessible;

    /** ACTIVE | INACTIVE | MAINTENANCE */
    @NotBlank(message = "Status is required")
    @Pattern(regexp = "ACTIVE|INACTIVE|MAINTENANCE",
            message = "Status must be ACTIVE, INACTIVE or MAINTENANCE")
    private String status;

    /** Assigned route id, or null when the bus is unassigned. */
    private String routeId;

    /**
     * Human-readable route label, filled in by the service for display.
     * Read-only: ignored on create/update.
     */
    private String routeName;

    /** Whether the bus is shown on the fleet map. */
    @NotNull(message = "Map visibility must be specified")
    private Boolean mapVisible;

    /**
     * MQTT antenna / vehicle id this bus is linked to (e.g. "MAGNI-001").
     * Optional — a bus in the depot may have none.
     */
    private String currentVehicleId;
}
