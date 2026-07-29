package it.unicas.cassitrack.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * Compact JSON payload published by the real ESP32 / OBU units to:
 *   MQTT topic: cassitrack/obu/{ID}/pos   (broker devaidalab.unicas.it:8883)
 *
 * Example JSON (as observed on the wire):
 * {
 *   "id": "BUS2", "ts": 1784794295, "lat": 41.493771, "lon": 13.822153,
 *   "spd": 22.6, "hdg": 204.3, "occ": 27, "sat": 10, "bat": 3.91,
 *   "tech": "sim", "rsrp": -89
 * }
 *
 * This is a DIFFERENT schema from {@link MqttPositionPayload} (the verbose
 * internal format). {@link #toMqttPositionPayload()} adapts one into the other
 * so the rest of the ingestion pipeline stays unchanged.
 *
 * Unknown fields (sat / tech / rsrp have no target field) are ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObuPositionPayload {

    @JsonProperty("id")
    private String id;

    /** Unix epoch SECONDS. 0 (or missing) means "no GPS fix / no RTC" → use now(). */
    @JsonProperty("ts")
    private Long ts;

    @JsonProperty("lat")
    private Double lat;

    @JsonProperty("lon")
    private Double lon;

    /** Speed in km/h. */
    @JsonProperty("spd")
    private Double spd;

    /** Heading in degrees. */
    @JsonProperty("hdg")
    private Double hdg;

    /** Occupancy = number of passengers on board. */
    @JsonProperty("occ")
    private Integer occ;

    /** Satellites in view — diagnostic only, not persisted. */
    @JsonProperty("sat")
    private Integer sat;

    /** Battery voltage (LiPo cell), e.g. 3.91. */
    @JsonProperty("bat")
    private Double bat;

    /** Radio technology tag (e.g. "sim", "lte") — diagnostic only. */
    @JsonProperty("tech")
    private String tech;

    /** Reference signal received power — diagnostic only. */
    @JsonProperty("rsrp")
    private Integer rsrp;

    /**
     * Translate the compact OBU payload into the verbose internal payload
     * consumed by the MQTT handler / Influx / state cache.
     *
     * Fields not carried by the OBU feed (trip_id, route, capacity, next stop…)
     * are left null on purpose — cassitrack enriches them from its own DB.
     */
    public MqttPositionPayload toMqttPositionPayload() {
        MqttPositionPayload out = new MqttPositionPayload();
        out.setVehicleId(id);
        out.setTimestamp((ts != null && ts > 0) ? Instant.ofEpochSecond(ts) : Instant.now());
        out.setLat(lat);
        out.setLon(lon);
        out.setSpeedKmh(spd);
        out.setHeadingDeg(hdg);
        out.setPassengers(occ);
        out.setBatteryVoltage(bat);
        return out;
    }
}
