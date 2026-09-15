package it.unicas.cassitrack.controller;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import it.unicas.cassitrack.dto.BusTelemetryDTO;
import it.unicas.cassitrack.dto.siri.Siri;
import it.unicas.cassitrack.service.ApiClientService;
import it.unicas.cassitrack.service.InfluxService;
import it.unicas.cassitrack.service.SiriService;
import it.unicas.cassitrack.service.VehicleStateCache;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/v1/telemetry")
public class DataExportController {

    private final InfluxService influxService;
    private final VehicleStateCache vehicleStateCache;
    private final XmlMapper xmlMapper = new XmlMapper();

    private final ApiClientService apiClientService;
    private final SiriService siriService;

    // Lista speciale thread-safe per memorizzare i client connessi (es. Omnimove)
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public DataExportController(InfluxService influxService, VehicleStateCache vehicleStateCache,
                                ApiClientService apiClientService, SiriService siriService) {
        this.influxService = influxService;
        this.vehicleStateCache = vehicleStateCache;
        this.apiClientService = apiClientService;
        this.siriService = siriService;
    }


    /*---------- USE RESTAPI: DEPRECATED ----------*/
    @GetMapping("/latest")
    public ResponseEntity<List<BusTelemetryDTO>> getLatestTelemetry(@RequestParam(value = "route_id", required = false) String routeId) {

        List<BusTelemetryDTO> data = influxService.getLatestTelemetry(routeId);

        return ResponseEntity.ok(data);
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTelemetry(@RequestHeader(value = "X-Api-Key", required = false) String receivedToken,
                                      HttpServletResponse response) {

        // OmniMove's token or a partner key: the service tells them apart
        if (apiClientService.authorizeFeed(receivedToken).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }

        if (emitters.size() >= 50) {
            response.setStatus(429);
            return null;
        }

        // Creiamo un Emitter senza timeout (-1L) così la connessione resta aperta indefinitamente
        SseEmitter emitter = new SseEmitter(-1L);
        this.emitters.add(emitter);

        // Se Omnimove si disconnette o ci sono errori, facciamo pulizia rimuovendolo dalla lista
        emitter.onCompletion(() -> this.emitters.remove(emitter));
        emitter.onTimeout(() -> this.emitters.remove(emitter));
        emitter.onError((e) -> this.emitters.remove(emitter));

        return emitter;
    }

    // Push ogni 5 secondi: costruisce un pacchetto SIRI XML dalla cache Redis e lo invia via SSE
    @Scheduled(fixedRate = 5000)
    public void pushTelemetryData() {
        if (emitters.isEmpty()) return;

        Siri siri = siriService.vehicleMonitoring(vehicleStateCache.getActive());
        String siriXml;
        try {
            siriXml = xmlMapper.writeValueAsString(siri);
        } catch (Exception e) {
            System.err.println("[CASSITRACK] Errore serializzazione SIRI XML: " + e.getMessage());
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("telemetry-update")
                        .data(siriXml, MediaType.APPLICATION_XML));
            } catch (IOException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    // Heartbeat ogni 3 secondi: mantiene viva la connessione TCP tra un push e l'altro
    @Scheduled(fixedRate = 3000)
    public void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException e) {
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }
}