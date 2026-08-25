package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
@Data
public class JourneyRequest {
    @JsonProperty("origin_lat") private Double originLat;
    @JsonProperty("origin_lon") private Double originLon;
    @JsonProperty("origin_name") private String originName;
    @JsonProperty("dest_lat") private Double destLat;
    @JsonProperty("dest_lon") private Double destLon;
    @JsonProperty("dest_name") private String destName;
    @JsonProperty("origin_is_gps") private Boolean originIsGps;
    /** True when the destination is the traveller's own position rather than a stop. */
    @JsonProperty("dest_is_gps") private Boolean destIsGps;
    @JsonProperty("user_id") private Long userId;
    @JsonProperty("origin_stop_id") private String originStopId;
    @JsonProperty("dest_stop_id")   private String destStopId;

    /**
     * Desired departure time as "HH:mm" (Europe/Rome). Optional:
     * null or blank -> now. If the time is already past today, tomorrow is assumed.
     */
    @JsonProperty("departure_time") private String departureTime;

    @JsonProperty("messages") private List<String> messages = new ArrayList<>();
    @JsonProperty("lang") private String lang;
    /** True when departure_time should be treated as the desired *arrival* time. */
    @JsonProperty("arrive_by") private Boolean arriveBy;

    public void addMessage(String msg) { this.messages.add(msg); }
    private List<String> modes;

    public boolean isItalian() { return "it".equalsIgnoreCase(lang); }
    public boolean isDestGps() { return Boolean.TRUE.equals(destIsGps); }
    public boolean isArriveBy() { return Boolean.TRUE.equals(arriveBy); }
}
