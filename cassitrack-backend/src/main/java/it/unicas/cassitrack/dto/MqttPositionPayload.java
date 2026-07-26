package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * The JSON payload published by each on-board ESP32 unit to:
 *   MQTT topic: cassitrack/{vehicle_id}/position
 *
 * This DTO contains ONLY what a physical unit can actually measure:
 * a GNSS fix, a BLE device count, and hardware telemetry.
 *
 * Everything else — trip, route, current stop, next stop, delay,
 * schedule adherence — is DERIVED server-side. The bus does not know
 * which trip it is running, and must never be trusted to tell us.
 *
 * Example JSON:
 * {
 *   "vehicle_id": "MAGNI-001",
 *   "timestamp": "2026-04-04T08:30:00Z",
 *   "lat": 41.4917,
 *   "lon": 13.8314,
 *   "speed_kmh": 32.5,
 *   "heading_deg": 270.0,
 *   "ble_device_count": 12,
 *   "passengers": 7,
 *   "capacity": 85,
 *   "battery_voltage": 12.4,
 *   "firmware_version": "1.0.0"
 * }
 */
@Data
public class MqttPositionPayload {

    // ── Identity ────────────────────────────────────────────────
    @JsonProperty("vehicle_id")
    private String vehicleId;

    @JsonProperty("timestamp")
    private Instant timestamp;

    // ── Position: raw GNSS output (NMEA RMC) ────────────────────
    @JsonProperty("lat")
    private Double lat;

    @JsonProperty("lon")
    private Double lon;

    /** Speed over ground, km/h */
    @JsonProperty("speed_kmh")
    private Double speedKmh;

    /** Course over ground, degrees (0=N, 90=E) */
    @JsonProperty("heading_deg")
    private Double headingDeg;

    // ── On-board sensors ────────────────────────────────────────
    /** Bluetooth devices seen by the on-board scanner. Nullable: not all units have BLE. */
    @JsonProperty("ble_device_count")
    private Integer bleDeviceCount;

    /** Direct passenger count, if the unit has a counter. Nullable. */
    @JsonProperty("passengers")
    private Integer passengers;

    /** Seat capacity reported by the unit. Nullable — buses.numero_posti is authoritative. */
    @JsonProperty("capacity")
    private Integer capacity;

    @JsonProperty("battery_voltage")
    private Double batteryVoltage;

    @JsonProperty("firmware_version")
    private String firmwareVersion;
}
