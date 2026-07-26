package it.unicas.cassitrack.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import it.unicas.cassitrack.dto.MqttPositionPayload;
import it.unicas.cassitrack.model.Bus;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.BusRepository;
import it.unicas.cassitrack.service.RouteMatchingService;
import it.unicas.cassitrack.service.ScheduleAdherenceService;
import it.unicas.cassitrack.service.SecurityAuditService;
import it.unicas.cassitrack.service.TripResolutionService;
import it.unicas.cassitrack.service.VehicleStateCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Entry point for on-board telemetry.
 *
 * The unit reports only what it can measure: a GNSS fix, a BLE count and
 * hardware telemetry. Everything operational — trip, route, current stop,
 * next stop, delay — is derived here, in this order:
 *
 *   1. vehicle_id  → bus_id            (buses.current_vehicle_id)
 *   2. bus_id + clock → trip, route    (TripResolutionService)
 *   3. trip + GPS → stop arrival, delay (ScheduleAdherenceService)
 *   4. trip + current stop → next stop  (RouteMatchingService)
 *
 * Each step depends on the previous one, so the order is load-bearing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MqttMessageHandler implements MessageHandler {

    private final ObjectMapper objectMapper;
    private final WriteApiBlocking influxWriteApi;
    private final VehicleStateCache vehicleStateCache;
    private final BusRepository busRepository;
    private final ScheduleAdherenceService scheduleAdherenceService;
    private final SecurityAuditService securityAuditService;
    private final RouteMatchingService routeMatchingService;
    private final TripResolutionService tripResolutionService;

    private static final double LAT_MIN = 41.40;
    private static final double LAT_MAX = 41.60;
    private static final double LON_MIN = 13.70;
    private static final double LON_MAX = 14.00;
    private static final long MAX_AGE_SECONDS = 300;

    @Override
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) throws MessagingException {
        String topic = (String) message.getHeaders().get("mqtt_receivedTopic");
        String payload = (String) message.getPayload();

        log.debug("MQTT message received on topic [{}]: {}", topic, payload);

        try {
            // ── Step 1: Parse ─────────────────────────────────────
            MqttPositionPayload pos = objectMapper.readValue(payload, MqttPositionPayload.class);

            // ── Step 2: Validate ──────────────────────────────────
            if (!isValid(pos)) {
                securityAuditService.mqttInvalidPayload(topic);
                return;
            }

            // ── Step 3: vehicle_id → bus anagraphics from Postgres ──
            Optional<Bus> associatedBus = busRepository.findByCurrentVehicleId(pos.getVehicleId());

            Integer busId                = associatedBus.map(Bus::getBusId).orElse(null);
            Boolean wheelchairAccessible = associatedBus.map(Bus::getWheelchairAccessible).orElse(null);
            Integer numeroPosti          = associatedBus.map(Bus::getNumeroPosti).orElse(null);

            if (busId == null) {
                log.warn("Vehicle {} is not assigned to any bus in the fleet", pos.getVehicleId());
            }

            // ── Step 4: bus + clock → the trip it is running now ───
            var activeTrip = tripResolutionService.resolve(
                    busId, pos.getVehicleId(), pos.getLat(), pos.getLon());

            VehiclePosition entity = toEntity(pos, busId, wheelchairAccessible, numeroPosti);
            activeTrip.ifPresent(t -> {
                entity.setTripId(t.tripId());
                entity.setRouteId(t.routeId());
                entity.setRouteName(t.routeName());
            });

            // ── Step 5: trip + GPS → stop arrival, delay, adherence ──
            // Sets lastStopRegisteredId when the bus is inside a stop radius.
            scheduleAdherenceService.processBusAdherence(entity);

            // ── Step 6: resolve stop identities ───────────────────
            // The bus never told us where it is. If adherence found no arrival
            // and we have no carried-over anchor, fall back to the nearest stop
            // on the trip so the UI still has something to show.
            entity.setLastStopRegistered(
                    routeMatchingService.stopName(entity.getLastStopRegisteredId()));

            var next = routeMatchingService.nextStopAfterSequence(
                    entity.getTripId(), entity.getLastStopSequence());
            if (next != null) {
                entity.setNextStopId(next.id());
                entity.setNextStop(next.name());
            }

            vehicleStateCache.update(pos.getVehicleId(), entity);

            // ── Step 7: time series ───────────────────────────────
            try {
                writeToInflux(entity);
            } catch (Exception influxError) {
                log.warn("InfluxDB unavailable, skipping time-series write for vehicle {}: {}",
                        pos.getVehicleId(), influxError.getMessage());
            }

            log.info("Processed [{}] -> bus {} | trip {} | route {} | lat={} lon={} | last={} next={} | delay={}",
                    entity.getVehicleId(), busId, entity.getTripId(), entity.getRouteId(),
                    entity.getLat(), entity.getLon(),
                    entity.getLastStopRegistered(), entity.getNextStop(), entity.getDelayMinutes());

        } catch (Exception e) {
            log.error("Failed to process MQTT message from topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    private boolean isValid(MqttPositionPayload pos) {
        if (pos.getVehicleId() == null || pos.getVehicleId().isBlank()) return false;
        if (!pos.getVehicleId().matches("[A-Za-z0-9_\\-]{1,50}")) return false;
        if (pos.getLat() == null || pos.getLon() == null) return false;
        if (pos.getLat() < LAT_MIN || pos.getLat() > LAT_MAX
                || pos.getLon() < LON_MIN || pos.getLon() > LON_MAX) return false;
        if (pos.getTimestamp() == null) return false;

        long ageSeconds = Instant.now().getEpochSecond() - pos.getTimestamp().getEpochSecond();
        return ageSeconds <= MAX_AGE_SECONDS;
    }

    /**
     * Writes the FULLY RESOLVED state, not the raw payload. trip_id and route_id
     * are now real values rather than the "UNKNOWN" placeholder they used to be
     * whenever the bus did not send them.
     */
    private void writeToInflux(VehiclePosition v) {
        Point point = Point
                .measurement("vehicle_position")
                .addTag("vehicle_id", v.getVehicleId())
                .addTag("bus_id",   v.getBusId()   != null ? v.getBusId().toString() : "UNKNOWN")
                .addTag("trip_id",  v.getTripId()  != null ? v.getTripId()  : "UNKNOWN")
                .addTag("route_id", v.getRouteId() != null ? v.getRouteId() : "UNKNOWN")
                .addField("lat", v.getLat())
                .addField("lon", v.getLon())
                .addField("speed_kmh",   v.getSpeedKmh()   != null ? v.getSpeedKmh()   : 0.0)
                .addField("heading_deg", v.getHeadingDeg() != null ? v.getHeadingDeg() : 0.0)
                .addField("wheelchair_accessible",
                        v.getWheelchairAccessible() != null ? v.getWheelchairAccessible() : false)
                .time(v.getTimestamp(), WritePrecision.S);

        if (v.getBleDeviceCount() != null) point.addField("ble_device_count", v.getBleDeviceCount());
        if (v.getBatteryVoltage() != null) point.addField("battery_voltage",  v.getBatteryVoltage());
        if (v.getPassengers()     != null) point.addField("passengers",       v.getPassengers());
        if (v.getCapacity()       != null) point.addField("capacity",         v.getCapacity());
        if (v.getDelayMinutes()   != null) point.addField("delay",            v.getDelayMinutes());
        if (v.getLastStopRegistered() != null) point.addField("last_stop_registered", v.getLastStopRegistered());
        if (v.getNextStop()           != null) point.addField("next_stop",            v.getNextStop());

        influxWriteApi.writePoint(point);
    }

    /**
     * Raw sensor readings only. Trip, route and stops are filled in afterwards
     * by the resolution pipeline — never by the bus.
     */
    private VehiclePosition toEntity(MqttPositionPayload pos, Integer busId,
                                     Boolean wheelchairAccessible, Integer numeroPosti) {
        return VehiclePosition.builder()
                .vehicleId(pos.getVehicleId())
                .busId(busId)
                .numeroPosti(numeroPosti)
                .wheelchairAccessible(wheelchairAccessible)
                .timestamp(pos.getTimestamp())
                .lat(pos.getLat())
                .lon(pos.getLon())
                .speedKmh(pos.getSpeedKmh())
                .headingDeg(pos.getHeadingDeg())
                .bleDeviceCount(pos.getBleDeviceCount())
                .batteryVoltage(pos.getBatteryVoltage())
                .firmwareVersion(pos.getFirmwareVersion())
                .passengers(pos.getPassengers())
                // buses.numero_posti is authoritative; the unit's value is a fallback
                .capacity(numeroPosti != null ? numeroPosti : pos.getCapacity())
                .scheduleStatus(VehiclePosition.ScheduleStatus.UNKNOWN)
                .receivedAt(Instant.now())
                .build();
    }
}
