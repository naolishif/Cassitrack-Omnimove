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
            // Which line the bus is working. The dashboard table shows it so a
            // late vehicle can be traced back to the line it is delaying.
            info.put("route_name",    v.getRouteName());
            return info;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status_counts", counts);
        out.put("vehicles",      vehicles);
        out.put("total_active",  active.size());
        return out;
    }

    // ── Busiest hours (GET /api/v1/analytics/busiest-hours) ───────────────────

    public Map<String, Object> getBusiestHours(String startTime, String endTime, String busId) {
        List<Map<String, Object>> hourlyData = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("hour",  String.format("%02d:00", h));
            p.put("count", 0);
            hourlyData.add(p);
        }

        String fluxBusiest = String.format(
                "from(bucket: \"%s\") " +
                        "|> range(%s) " +
                        "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                        "|> filter(fn: (r) => r[\"_field\"] == \"lat\")%s " +
                        "|> aggregateWindow(every: 1h, fn: count, createEmpty: false)",
                bucket, buildFluxRange(startTime, endTime), buildVehicleFilter(busId));

        // Counting position reports rather than averaging ble_device_count: the
        // simulator publishes that field as a literal 0 on every message, and
        // MqttMessageHandler stores the zero instead of skipping it, so the old
        // query returned twenty-four empty buckets against a fleet that was
        // plainly running. 'lat' is written unconditionally, so one record there
        // is one GPS report. Buckets are summed, not averaged, because a range
        // spanning several days should answer "how many reports landed in the
        // 08:00 hour over this period".
        long[] reports = new long[24];
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxBusiest);
            for (FluxTable table : tables)
                for (FluxRecord record : table.getRecords()) {
                    Instant time = record.getTime();
                    Number  val  = (Number) record.getValue();
                    if (time != null && val != null) {
                        int hour = time.atZone(ZoneId.systemDefault()).getHour();
                        if (hour >= 0 && hour < 24) reports[hour] += val.longValue();
                    }
                }
        } catch (Exception e) {
            log.error("Error querying busiest hours from InfluxDB", e);
        }

        for (int h = 0; h < 24; h++) {
            if (reports[h] > 0)
                hourlyData.get(h).put("count", (int) Math.min(Integer.MAX_VALUE, reports[h]));
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

    // ── Network overview (GET /api/v1/analytics/network) ──────────────────────

    /**
     * Everything the Analytics dashboard's four panels need, in one round trip.
     *
     * They are grouped here rather than split into four endpoints because they
     * share the same expensive step: a single Flux scan of vehicle_position over
     * the selected period. Splitting them would run that scan four times for
     * data that must agree with itself anyway — a delay figure in the KPI band
     * that disagrees with the per-line bars below it is worse than a slow page.
     *
     * Two of the four are historical and honour the period filter (delay by
     * weekday, occupancy by hour, delay by line); "buses on road" is live by
     * definition and ignores it, which the NOW chip in its header states.
     */
    public Map<String, Object> getNetworkOverview(String startTime, String endTime, String busId) {

        Map<String, Object> out = new LinkedHashMap<>();
        Scan scan = scanPeriod(startTime, endTime, busId);

        // ── KPI 1: how many lines actually ran ────────────────────────────
        // Distinct routes observed beats routeRepo.count(): a line that exists
        // in the catalogue but ran nothing today is not an "active" line. Fall
        // back to the catalogue only when telemetry is empty, so a fresh
        // install shows the fleet size instead of a bare zero.
        int activeLines = scan.routeIds.size();
        if (activeLines == 0) activeLines = (int) routeRepo.count();
        out.put("active_lines", activeLines);

        // ── KPI 2: average delay, and the change against the previous window ──
        out.put("avg_delay_minutes", round1(scan.avgDelay()));
        out.put("delay_delta",       previousWindowDelta(startTime, endTime, busId, scan.avgDelay()));

        // ── Panel 1: buses on road per line, right now ────────────────────
        out.put("buses_on_road", busesOnRoadByLine());

        // ── Panel 2: average delay per weekday ────────────────────────────
        // Always all seven days, in calendar order, with null for days the
        // period never covered. A gap in the line is honest; silently dropping
        // Sunday would make a 6-day week look like a 7-day one.
        Map<String, Object> byWeekday = new LinkedHashMap<>();
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            List<Double> vals = scan.delayByWeekday.get(d);
            byWeekday.put(
                d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                (vals == null || vals.isEmpty()) ? null : round1(average(vals)));
        }
        out.put("delay_by_weekday", byWeekday);

        // ── Panel 3: occupancy per hour ───────────────────────────────────
        // Weighted by capacity (sum of passengers / sum of seats), not an
        // average of per-reading percentages: a 50-seat bus at 90% and a
        // 10-seat one at 10% is not "50% full" across the hour.
        List<Map<String, Object>> occupancy = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : new TreeMap<>(scan.paxCapByHour).entrySet()) {
            long pax = e.getValue()[0], cap = e.getValue()[1];
            if (cap <= 0) continue;
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("slot", String.format("%02d-%02d", e.getKey(), (e.getKey() + 1) % 24));
            slot.put("pct",  (int) Math.round(100.0 * pax / cap));
            occupancy.add(slot);
        }
        out.put("occupancy_by_hour", occupancy);

        // ── Panel 4: average delay per line, worst first ──────────────────
        Map<String, Route> routes = routeRepo.findAllById(scan.delayByRoute.keySet())
                .stream().collect(Collectors.toMap(Route::getId, r -> r));

        List<Map<String, Object>> delayByLine = scan.delayByRoute.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("route_id",      e.getKey());
                    row.put("label",         routeLabel(routes.get(e.getKey()), e.getKey()));
                    row.put("delay_minutes", round1(average(e.getValue())));
                    return row;
                })
                .sorted((a, b) -> Double.compare(
                        (Double) b.get("delay_minutes"), (Double) a.get("delay_minutes")))
                .collect(Collectors.toList());
        out.put("delay_by_line", delayByLine);

        out.put("generated_at", Instant.now().toString());
        return out;
    }

    /** One pass over vehicle_position, accumulating every figure the panels need. */
    private static final class Scan {
        final Set<String>                       routeIds       = new HashSet<>();
        final List<Double>                      allDelays      = new ArrayList<>();
        final Map<java.time.DayOfWeek, List<Double>> delayByWeekday = new EnumMap<>(java.time.DayOfWeek.class);
        final Map<String, List<Double>>         delayByRoute   = new LinkedHashMap<>();
        /** hour → {passengers, seats} */
        final Map<Integer, long[]>              paxCapByHour   = new HashMap<>();

        double avgDelay() {
            return allDelays.isEmpty() ? 0.0
                 : allDelays.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    private Scan scanPeriod(String startTime, String endTime, String busId) {
        Scan s = new Scan();

        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(%s) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
            "|> filter(fn: (r) => r[\"_field\"] == \"delay\" or r[\"_field\"] == \"passengers\" " +
            "                  or r[\"_field\"] == \"capacity\")%s",
            bucket, buildFluxRange(startTime, endTime), buildVehicleFilter(busId));

        try {
            for (FluxTable table : influxDBClient.getQueryApi().query(flux))
                for (FluxRecord rec : table.getRecords()) {
                    Instant t = rec.getTime();
                    Object  v = rec.getValue();
                    if (t == null || !(v instanceof Number)) continue;

                    ZonedDateTime zdt = t.atZone(ZoneId.systemDefault());
                    String field   = String.valueOf(rec.getField());
                    double value   = ((Number) v).doubleValue();
                    String routeId = rec.getValueByKey("route_id") != null
                                   ? rec.getValueByKey("route_id").toString() : null;
                    boolean realRoute = routeId != null && !"UNKNOWN".equals(routeId);

                    switch (field) {
                        case "delay" -> {
                            s.allDelays.add(value);
                            s.delayByWeekday
                             .computeIfAbsent(zdt.getDayOfWeek(), k -> new ArrayList<>())
                             .add(value);
                            if (realRoute) {
                                s.routeIds.add(routeId);
                                s.delayByRoute.computeIfAbsent(routeId, k -> new ArrayList<>()).add(value);
                            }
                        }
                        case "passengers" -> bump(s.paxCapByHour, zdt.getHour(), 0, (long) value);
                        case "capacity"   -> bump(s.paxCapByHour, zdt.getHour(), 1, (long) value);
                        default -> { }
                    }
                }
        } catch (Exception e) {
            log.error("Network overview scan failed: {}", e.getMessage());
        }
        return s;
    }

    private static void bump(Map<Integer, long[]> acc, int hour, int slot, long by) {
        acc.computeIfAbsent(hour, k -> new long[2])[slot] += by;
    }

    /**
     * Average delay over the window immediately before this one, expressed as a
     * delta. Null — not zero — when there is nothing to compare against: an
     * open-ended period has no "previous", and a genuine 0.0 change is a
     * different statement from "unknown".
     */
    private Double previousWindowDelta(String startTime, String endTime, String busId, double current) {
        if (startTime == null || startTime.isBlank() || endTime == null || endTime.isBlank()) return null;
        try {
            Instant from = Instant.parse(startTime), to = Instant.parse(endTime);
            long span = to.toEpochMilli() - from.toEpochMilli();
            if (span <= 0) return null;

            Scan prev = scanPeriod(from.minusMillis(span).toString(), from.toString(), busId);
            if (prev.allDelays.isEmpty()) return null;
            return round1(current - prev.avgDelay());
        } catch (Exception e) {
            log.debug("No comparable previous window: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Buses currently transmitting, counted per line.
     *
     * Vehicles with no resolved route are skipped rather than bucketed under
     * "Unknown": the panel answers "how is each line staffed", and a column for
     * buses we cannot attribute would not help answer it.
     */
    private List<Map<String, Object>> busesOnRoadByLine() {
        Map<String, Long> counts = vehicleService.getAllActiveVehicles().stream()
                .filter(v -> v.getRouteId() != null && !v.getRouteId().isBlank())
                .collect(Collectors.groupingBy(VehicleStatusDTO::getRouteId, Collectors.counting()));

        Map<String, Route> routes = routeRepo.findAllById(counts.keySet())
                .stream().collect(Collectors.toMap(Route::getId, r -> r));

        return counts.entrySet().stream()
                .map(e -> {
                    Route r = routes.get(e.getKey());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("route_id", e.getKey());
                    row.put("label",    r != null && r.getShortName() != null ? r.getShortName() : e.getKey());
                    row.put("name",     routeLabel(r, e.getKey()));
                    row.put("buses",    e.getValue());
                    return row;
                })
                .sorted(Comparator.comparing(
                        (Map<String, Object> m) -> lineSortKey(String.valueOf(m.get("label")))))
                .collect(Collectors.toList());
    }

    /** "14" sorts before "3" as text; pad the numeric part so lines read 1,2,3…14. */
    private static String lineSortKey(String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)(.*)$").matcher(label.trim());
        return m.matches() ? String.format("%04d%s", Integer.parseInt(m.group(1)), m.group(2)) : "9999" + label;
    }

    private static String routeLabel(Route r, String fallbackId) {
        if (r == null) return fallbackId;
        String s = r.getShortName(), l = r.getLongName();
        if (s != null && l != null) return s + " — " + l;
        return s != null ? s : (l != null ? l : fallbackId);
    }

    private static double average(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
