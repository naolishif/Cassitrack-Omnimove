package it.unicas.omnimove.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.Instant;
@Data
public class VehicleDTO {
    @JsonProperty("vehicle_id") private String vehicleId;
    private Double lat;
    private Double lon;
    @JsonProperty("speed_kmh") private Double speedKmh;
    @JsonProperty("schedule_status") private String scheduleStatus;
    @JsonProperty("crowding_level") private String crowdingLevel;
    @JsonProperty("estimated_passengers") private Integer estimatedPassengers;
    /**
     * Riempimento in percentuale, 0-100. Null quando passeggeri o posti non
     * si conoscono.
     *
     * Serve accanto a crowding_level e non al posto suo: il livello e' una
     * classe (quattro valori, quattro colori), la percentuale e' una quantita'
     * — ed e' la quantita' a riempire l'icona sulla mappa. Con il solo livello
     * il riempimento avrebbe quattro posizioni possibili e si leggerebbe come
     * una tacca, non come "quanta gente c'e'".
     */
    @JsonProperty("occupancy_pct")  private Integer occupancyPct;
    // Fields needed for live bus map markers
    // Quale CORSA sta facendo, non solo su quale linea. E' la differenza fra
    // "un mezzo della linea 10" e "il mezzo che prenderai": senza, il client
    // puo' solo filtrare per linea e mostra un veicolo qualsiasi.
    @JsonProperty("trip_id")         private String tripId;
    @JsonProperty("route_id")        private String routeId;
    @JsonProperty("route_name")      private String routeName;
    @JsonProperty("delay_minutes")   private Integer delayMinutes;
    @JsonProperty("next_stop_name")  private String nextStopName;
    @JsonProperty("eta_seconds")     private Integer etaSeconds;
    @JsonProperty("last_seen")       private Instant lastSeen;
    @JsonProperty("is_active")       private Boolean isActive;
}
