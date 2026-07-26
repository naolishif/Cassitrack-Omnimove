package it.unicas.cassitrack.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import it.unicas.cassitrack.dto.VehicleStatusDTO;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.VehiclePositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import it.unicas.cassitrack.model.Trip;
import it.unicas.cassitrack.repository.TripRepository;

/**
 * Fleet analytics for the manager dashboard.
 * Hybrid: live data from Redis, historical aggregations from InfluxDB.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalyticsService {

    private final VehiclePositionRepository positionRepo;
    private final VehicleService            vehicleService;
    private final InfluxDBClient            influxDBClient;
    private final TripRepository            tripRepository;
    private final ScheduledStopRepository   scheduledStopRepo;
    private final RouteRepository           routeRepo;

    @Value("${spring.influx.bucket:vehicle_telemetry}")
    private String bucket;

    // CO2 emission factors from EEA (gCO2/passenger-km) — aligned with OmniMove GreenIndexService
    private static final double CO2_BUS_G_PER_KM      = 68.0;
    private static final double CO2_CAR_G_PER_KM      = 170.0;
    private static final double READING_INTERVAL_H     = 15.0 / 3600.0; // 15-second GPS reporting cycle
    private static final double AVG_SPEED_KMH_FALLBACK = 20.0;

    // ── Flux helpers ──────────────────────────────────────────────────────────

    private String buildFluxRange(String startTime, String endTime) {
        if (startTime == null || startTime.isBlank()) return "start: today()";
        if (endTime   == null || endTime.isBlank())   return "start: " + startTime;
        return "start: " + startTime + ", stop: " + endTime;
    }

    private String buildVehicleFilter(String busId) {
        if (busId == null || busId.isBlank()) return "";
        return String.format(" |> filter(fn: (r) => r[\"vehicle_id\"] == \"%s\")", busId);
    }

    // ── Summary (GET /api/v1/analytics/summary) ───────────────────────────────

    public Map<String, Object> getSummary() {
        List<VehicleStatusDTO> active = vehicleService.getAllActiveVehicles();
        int activeBuses = active.size();

        List<VehiclePosition> livePositions = positionRepo.findAll();

        long totalReports = 0L;
        String fluxCount = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: today()) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"delay\") " +
                        "|> count()", bucket);
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxCount);
            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Number val = (Number) tables.get(0).getRecords().get(0).getValue();
                if (val != null) totalReports = val.longValue();
            }
        } catch (Exception e) {
            log.error("Error querying report count from InfluxDB", e);
        }

        double globalAverageDelay = 0.0;
        String fluxGlobalDelay = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: -1h) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"delay\") " +
                        "|> mean()", bucket);
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxGlobalDelay);
            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Number val = (Number) tables.get(0).getRecords().get(0).getValue();
                if (val != null) globalAverageDelay = Math.round(val.doubleValue() * 10.0) / 10.0;
            }
        } catch (Exception e) {
            log.error("Error querying global average delay from InfluxDB", e);
        }

        long onTime = active.stream().filter(v ->
                v.getScheduleStatus() != null && "ON_TIME".equals(v.getScheduleStatus().name())).count();
        long late   = active.stream().filter(v ->
                v.getScheduleStatus() != null && v.getScheduleStatus().name().contains("LATE")).count();
        long early  = active.stream().filter(v ->
                v.getScheduleStatus() != null && "EARLY".equals(v.getScheduleStatus().name())).count();
        int  onTimePct = activeBuses > 0 ? (int)(onTime * 100 / activeBuses) : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active_buses_now",        activeBuses);
        out.put("buses_today",             livePositions.size());
        out.put("position_reports_today",  totalReports);
        out.put("average_delay_minutes",   globalAverageDelay);
        out.put("on_time_count",           onTime);
        out.put("late_count",              late);
        out.put("early_count",             early);
        out.put("on_time_percentage",      onTimePct);
        out.put("generated_at",            Instant.now().toString());
        return out;
    }

    // ── Adherence breakdown (GET /api/v1/analytics/adherence) ─────────────────

    public Map<String, Object> getAdherenceBreakdown() {
        List<VehicleStatusDTO> active = vehicleService.getAllActiveVehicles();

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ON_TIME",              0L);
        counts.put("SLIGHTLY_LATE",        0L);
        counts.put("SIGNIFICANTLY_LATE",   0L);
        counts.put("EARLY",                0L);
        counts.put("UNKNOWN",              0L);
        active.forEach(v -> {
            String s = v.getScheduleStatus() != null ? v.getScheduleStatus().name() : "UNKNOWN";
            counts.merge(s, 1L, Long::sum);
        });

        Map<String, Double> avgDelaysByBus = new HashMap<>();
        String fluxDelayMean = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: -1h) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"delay\") " +
                        "|> mean()", bucket);
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxDelayMean);
            for (FluxTable table : tables)
                for (FluxRecord record : table.getRecords()) {
                    String vId = (String) record.getValueByKey("vehicle_id");
                    Number val = (Number) record.getValue();
                    if (vId != null && val != null)
                        avgDelaysByBus.put(vId, Math.round(val.doubleValue() * 10.0) / 10.0);
                }
        } catch (Exception e) {
            log.error("Error querying individual delay averages from InfluxDB", e);
        }

        List<Map<String, Object>> vehicles = active.stream().map(v -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("vehicle_id",    v.getVehicleId());
            info.put("status",        v.getScheduleStatus() != null ? v.getScheduleStatus().name() : "UNKNOWN");
            info.put("speed_kmh",     v.getSpeedKmh());
            // NPE FIX: the second argument of getOrDefault is evaluated eagerly, so
            // (double) v.getDelayMinutes() threw whenever a bus had not yet reached
            // its first stop and delay_minutes was still null.
            Double liveDelay = v.getDelayMinutes() != null ? v.getDelayMinutes().doubleValue() : null;
            info.put("delay_minutes", avgDelaysByBus.getOrDefault(v.getVehicleId(), liveDelay));
            info.put("crowding",      v.getCrowdingLevel());
            return info;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status_counts", counts);
        out.put("vehicles",      vehicles);
        out.put("total_active",  active.size());
        return out;
    }

    // ── Busiest hours (GET /api/v1/analytics/busiest-hours) ───────────────────

    public Map<String, Object> getBusiestHours() {
        List<Map<String, Object>> hourlyData = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("hour",  String.format("%02d:00", h));
            p.put("count", 0);
            hourlyData.add(p);
        }

        String fluxBusiest = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(start: today()) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"ble_device_count\") " +
                        "|> aggregateWindow(every: 1h, fn: mean, createEmpty: false)", bucket);
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxBusiest);
            if (!tables.isEmpty())
                for (FluxRecord record : tables.get(0).getRecords()) {
                    Instant time = record.getTime();
                    Number  val  = (Number) record.getValue();
                    if (time != null && val != null) {
                        int hour = time.atZone(ZoneId.systemDefault()).getHour();
                        if (hour >= 0 && hour < 24) hourlyData.get(hour).put("count", val.intValue());
                    }
                }
        } catch (Exception e) {
            log.error("Error querying busiest hours from InfluxDB", e);
        }

        String peakHour = "N/A";
        int maxCount = -1;
        for (Map<String, Object> data : hourlyData) {
            int c = (int) data.get("count");
            if (c > maxCount && c > 0) { maxCount = c; peakHour = (String) data.get("hour"); }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hourly_activity", hourlyData);
        out.put("peak_hour",       peakHour);
        out.put("period_hours",    24);
        return out;
    }

    // ── Operating hours from schedule (GET /api/v1/analytics/operating-hours) ──

    public Map<String, Object> getOperatingHours() {
        List<Object[]> rows = scheduledStopRepo.findOperatingHoursByRoute();
        Map<String, Object> result = new LinkedHashMap<>();
        int globalMin = 23;
        int globalMax = 0;

        for (Object[] row : rows) {
            String routeId  = (String) row[0];
            int    minSec   = ((Number) row[1]).intValue();
            int    maxSec   = ((Number) row[2]).intValue();
            int    firstHr  = minSec / 3600;
            int    lastHr   = maxSec / 3600 + 1;
            Map<String, Object> hours = new LinkedHashMap<>();
            hours.put("firstHour", firstHr);
            hours.put("lastHour",  lastHr);
            hours.put("firstTime", String.format("%02d:00", firstHr));
            hours.put("lastTime",  String.format("%02d:00", lastHr));
            result.put(routeId, hours);
            if (firstHr < globalMin) globalMin = firstHr;
            if (lastHr  > globalMax) globalMax = lastHr;
        }

        if (globalMin > globalMax) { globalMin = 6; globalMax = 22; }
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("firstHour", globalMin);
        global.put("lastHour",  globalMax);
        global.put("firstTime", String.format("%02d:00", globalMin));
        global.put("lastTime",  String.format("%02d:00", globalMax));
        result.put("_global", global);
        return result;
    }

    // ── CO2 saved vs private cars (GET /api/v1/analytics/co2) ────────────────

    public Map<String, Object> getCo2Saved(String startTime, String endTime,
                                           List<String> routeIds, String busId) {
        String range     = buildFluxRange(startTime, endTime);
        String busFilter = buildVehicleFilter(busId);

        // Sum of passenger readings over the period
        String fluxPax = String.format(
            "from(bucket: \"%s\") " +
            "|> range(%s) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
            "|> filter(fn: (r) => r[\"_field\"] == \"passengers\")%s " +
            "|> sum()", bucket, range, busFilter);

        // Mean vehicle speed over the same period (for passenger-km estimate)
        String fluxSpeed = String.format(
            "from(bucket: \"%s\") " +
            "|> range(%s) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
            "|> filter(fn: (r) => r[\"_field\"] == \"speed_kmh\")%s " +
            "|> mean()", bucket, range, busFilter);

        double totalPaxReadings = 0;
        double meanSpeedKmh     = AVG_SPEED_KMH_FALLBACK;

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxPax);
            double sum = 0;
            for (FluxTable t : tables)
                for (FluxRecord r : t.getRecords()) {
                    Number v = (Number) r.getValue();
                    if (v != null) sum += v.doubleValue();
                }
            totalPaxReadings = sum;
        } catch (Exception e) {
            log.warn("CO2 calc: passengers query failed: {}", e.getMessage());
        }

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxSpeed);
            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Number v = (Number) tables.get(0).getRecords().get(0).getValue();
                if (v != null && v.doubleValue() > 0) meanSpeedKmh = v.doubleValue();
            }
        } catch (Exception e) {
            log.warn("CO2 calc: speed query failed, using {}km/h fallback", AVG_SPEED_KMH_FALLBACK);
        }

        // passenger-km = Σ(passengers_i) × Δt_hours × mean_speed_kmh
        // (each reading is sampled every READING_INTERVAL_H hours)
        double passengerKm = totalPaxReadings * READING_INTERVAL_H * meanSpeedKmh;
        double co2SavedKg  = passengerKm * (CO2_CAR_G_PER_KM - CO2_BUS_G_PER_KM) / 1000.0;
        double vsCarCo2Kg  = passengerKm * CO2_CAR_G_PER_KM / 1000.0;
        double greenIndex  = 100.0 - (CO2_BUS_G_PER_KM / CO2_CAR_G_PER_KM * 100.0);

        String label = greenIndex >= 90 ? "Excellent" : greenIndex >= 70 ? "Good"
                     : greenIndex >= 50 ? "Moderate"  : greenIndex >= 30 ? "Poor" : "Very Poor";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("co2_saved_kg",   Math.round(co2SavedKg  * 10.0) / 10.0);
        out.put("passenger_km",   Math.round(passengerKm  * 10.0) / 10.0);
        out.put("green_index",    Math.round(greenIndex   * 10.0) / 10.0);
        out.put("vs_car_co2_kg",  Math.round(vsCarCo2Kg  * 10.0) / 10.0);
        out.put("green_label",    label);
        out.put("mean_speed_kmh", Math.round(meanSpeedKmh * 10.0) / 10.0);
        return out;
    }

    // ── Metric by route + time slot (internal) ────────────────────────────────

    private Map<String, Object> getMetricByRouteAndHour(
            String fieldName, String startTime, String endTime,
            List<String> routeIds, String busId, String groupBy) {

        Map<String, Object> result = new LinkedHashMap<>();
        String range     = buildFluxRange(startTime, endTime);
        String busFilter = buildVehicleFilter(busId);
        boolean byDay    = "day".equalsIgnoreCase(groupBy);

        String fluxQuery = String.format(
            "from(bucket: \"%s\") " +
            "|> range(%s) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
            "|> filter(fn: (r) => r[\"_field\"] == \"%s\")%s",
            bucket, range, fieldName, busFilter);

        Map<String, List<Double>> valuesByTrip    = new LinkedHashMap<>();
        Map<String, Instant>      firstSeenByTrip = new LinkedHashMap<>();

        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxQuery);
            for (FluxTable table : tables)
                for (FluxRecord record : table.getRecords()) {
                    String tripId = record.getValueByKey("trip_id") != null
                            ? record.getValueByKey("trip_id").toString() : null;
                    Number val   = record.getValue() != null ? (Number) record.getValue() : null;
                    Instant time = record.getTime();
                    if (tripId == null || val == null || time == null) continue;
                    valuesByTrip.computeIfAbsent(tripId, k -> new ArrayList<>()).add(val.doubleValue());
                    firstSeenByTrip.merge(tripId, time, (a, b) -> a.isBefore(b) ? a : b);
                }
        } catch (Exception e) {
            log.error("Error fetching '{}' by route and hour: {}", fieldName, e.getMessage());
            return result;
        }

        if (valuesByTrip.isEmpty()) return result;

        Map<String, Trip> tripsById = tripRepository
                .findAllByIdInWithRouteAndBus(new ArrayList<>(valuesByTrip.keySet()))
                .stream().collect(Collectors.toMap(Trip::getId, t -> t));

        Set<String> routeFilter = (routeIds != null && !routeIds.isEmpty())
                ? new HashSet<>(routeIds) : null;

        Map<String, Map<String, List<Double>>> grouped = new LinkedHashMap<>();

        for (String tripId : valuesByTrip.keySet()) {
            Trip trip = tripsById.get(tripId);
            if (trip == null) continue;
            String routeKey = trip.getRoute().getId();
            if (routeFilter != null && !routeFilter.contains(routeKey)) continue;

            double tripAvg = valuesByTrip.get(tripId).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(0.0);

            ZonedDateTime zdt = firstSeenByTrip.get(tripId).atZone(ZoneId.systemDefault());
            String slotLabel;
            if (byDay) {
                slotLabel = zdt.toLocalDate().toString(); // "2026-06-23"
            } else {
                int hour = zdt.getHour();
                if (hour < 6 || hour >= 22) continue;
                slotLabel = String.format("%02d:00", hour);
            }

            grouped.computeIfAbsent(routeKey, k -> new LinkedHashMap<>())
                   .computeIfAbsent(slotLabel, k -> new ArrayList<>())
                   .add(tripAvg);
        }

        for (Map.Entry<String, Map<String, List<Double>>> routeEntry : grouped.entrySet()) {
            Map<String, Double> bySlot = new LinkedHashMap<>();
            for (Map.Entry<String, List<Double>> slotEntry : routeEntry.getValue().entrySet()) {
                double avg = slotEntry.getValue().stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0.0);
                bySlot.put(slotEntry.getKey(), Math.round(avg * 10) / 10.0);
            }
            result.put(routeEntry.getKey(), bySlot);
        }
        return result;
    }

    public Map<String, Object> getPassengersByRouteAndHour(
            String startTime, String endTime, List<String> routeIds, String busId, String groupBy) {
        return getMetricByRouteAndHour("passengers", startTime, endTime, routeIds, busId, groupBy);
    }

    public Map<String, Object> getDelayByRouteAndHour(
            String startTime, String endTime, List<String> routeIds, String busId, String groupBy) {
        return getMetricByRouteAndHour("delay", startTime, endTime, routeIds, busId, groupBy);
    }

    // No-arg overloads kept for backward compatibility
    public Map<String, Object> getPassengersByRouteAndHour() {
        return getMetricByRouteAndHour("passengers", null, null, null, null, "hour");
    }

    public Map<String, Object> getDelayByRouteAndHour() {
        return getMetricByRouteAndHour("delay", null, null, null, null, "hour");
    }

    // ── Route catalogue (for filter dropdowns) ────────────────────────────────

    public List<Map<String, Object>> getRoutes() {
        return routeRepo.findAll().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",        r.getId());
                    m.put("shortName", r.getShortName());
                    m.put("longName",  r.getLongName());
                    m.put("active",    r.isActive());
                    return m;
                })
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getRoutesWithStops() {
        List<Object[]> rows = scheduledStopRepo.findStopsGroupedByRoute();
        Map<String, Map<String, Object>> byRoute = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String routeId   = (String) row[0];
            String shortName = (String) row[1];
            String longName  = (String) row[2];
            String stopId    = (String) row[3];
            String stopName  = (String) row[4];
            double lat       = ((Number) row[5]).doubleValue();
            double lon       = ((Number) row[6]).doubleValue();

            Map<String, Object> route = byRoute.computeIfAbsent(routeId, k -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("routeId",   routeId);
                r.put("shortName", shortName);
                r.put("longName",  longName);
                r.put("stops",     new ArrayList<>());
                return r;
            });

            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("id",   stopId);
            stop.put("name", stopName);
            stop.put("lat",  lat);
            stop.put("lon",  lon);
            //noinspection unchecked
            ((List<Map<String, Object>>) route.get("stops")).add(stop);
        }

        return new ArrayList<>(byRoute.values());
    }
}
