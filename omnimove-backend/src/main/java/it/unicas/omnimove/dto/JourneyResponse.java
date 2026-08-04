package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data @Builder
public class JourneyResponse {
    private List<JourneyOption> options;
    @JsonProperty("messages") private List<String> messages;   // ← aggiunto
    private String origin;
    private String destination;
    @JsonProperty("total_options") private Integer totalOptions;
    @JsonProperty("realtime_available") private boolean realtimeAvailable;
    @JsonProperty("weather_summary") private String weatherSummary;
    @JsonProperty("weather_condition") private String weatherCondition;
    @JsonProperty("temperature_celsius") private Double temperatureCelsius;
    public void addMessage(String msg) { this.messages.add(msg); }
}