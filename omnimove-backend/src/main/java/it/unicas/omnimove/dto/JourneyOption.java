package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data @Builder
public class JourneyOption {
    private String mode;
    @JsonProperty("mode_label") private String modeLabel;
    @JsonProperty("duration_minutes") private Integer durationMinutes;
    @JsonProperty("distance_metres") private Double distanceMetres;
    @JsonProperty("cost_euros") private Double costEuros;
    @JsonProperty("green_index") private Integer greenIndex;
    @JsonProperty("co2_grams") private Double co2Grams;
    @JsonProperty("eta_minutes") private Integer etaMinutes;
    private String summary;
    @JsonProperty("weather_warning") private String weatherWarning;
    @JsonProperty("weather_suggestion") private String weatherSuggestion;
    private List<JourneyLeg> legs;
    @JsonProperty("delay_minutes")   private Integer delayMinutes;
    @JsonProperty("delay_status")    private String  delayStatus;    // ON_TIME, SLIGHTLY_LATE
    @JsonProperty("delay_real_time") private Boolean delayRealTime;
    @JsonProperty("delay_at_stop")   private String  delayAtStop;
    @JsonProperty("delay_label")     private String  delayLabel;     // frase pronta per la scheda
    @JsonProperty("score_fast")   private Double scoreFast;
    @JsonProperty("score_budget") private Double scoreBudget;
    @JsonProperty("score_eco")    private Double scoreEco;

}
