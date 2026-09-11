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

    /**
     * Come sono andate le corse del periodo, contate una per una.
     *
     * PERCHE' NON BASTA status_counts
     * Quello conta i mezzi che stanno trasmettendo adesso: e' una fotografia
     * dell'istante, e una corsa conclusa alle nove non ci compare piu'. Per
     * dire com'e' andato un periodo serve contare le CORSE, ciascuna con
     * l'esito con cui si e' chiusa — o con quello attuale se e' ancora in
     * strada.
     *
     * SU PIU' GIORNI
     * L'ultimo delay per trip_id vale per l'INTERA finestra, non per giorno: gli
     * id delle corse si ripetono ogni giorno, quindi una settimana di corse
     * LINEA_1_28800 collasserebbe in una sola. Il raggruppamento include percio'
     * anche il giorno, e ogni esercizio quotidiano conta separatamente.
     *
     * window(every: 1d) taglia a mezzanotte UTC, cioe' alle 01:00 o 02:00 locali.
     * Va bene perche' il servizio va dalle 05 alle 22: nessuna corsa attraversa
     * quel confine e quindi nessuna viene spezzata in due. Se un giorno
     * comparisse un servizio notturno, questo e' il punto da rivedere.
     *
     * COME
     * L'ultimo `delay` registrato per ogni trip_id della giornata, che per una
     * corsa finita e' la misura all'ultima fermata e per una in corso e' la
     * piu' recente. La classificazione passa da statusFromDelay, la stessa che
     * usa il resto del sistema: soglie diverse qui darebbero un anello in
     * disaccordo con i colori sulla mappa.
     *
     * NON CLASSIFICATE
     * Corse che hanno trasmesso una posizione ma per cui non esiste nessun
     * `delay`: il mezzo e' partito e non ha ancora superato una fermata, quindi
     * non c'e' niente da confrontare con l'orario. Si ricavano per differenza
     * fra le corse viste e quelle misurate. Le corse che devono ancora partire
     * non compaiono affatto: non sono "non classificate", non sono successe.
     */
    private Map<String, Long> tripStatusCounts(String startTime, String endTime, String busId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("UNCLASSIFIED",       0L);
        counts.put("EARLY",              0L);
        counts.put("ON_TIME",            0L);
        counts.put("SLIGHTLY_LATE",      0L);
        counts.put("SIGNIFICANTLY_LATE", 0L);

        // Il tag vale "UNKNOWN" quando il mezzo trasmetteva senza una corsa
        // risolta: non e' una corsa e non va contata come tale.
        // Chiave corsa+giorno: la stessa corsa esercitata martedi' e mercoledi'
        // sono due esiti distinti, e con la sola corsa il secondo cancellerebbe
        // il primo.
        java.util.Set<String> measured = new java.util.HashSet<>();
        try {
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(%s) " +
                            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                            "|> filter(fn: (r) => r[\"_field\"] == \"delay\")%s " +
                            "|> window(every: 1d) " +
                            "|> group(columns: [\"trip_id\", \"_start\"]) " +
                            "|> last()", bucket, buildFluxRange(startTime, endTime),
                    buildVehicleFilter(busId));
            for (FluxTable table : influxDBClient.getQueryApi().query(flux))
                for (FluxRecord rec : table.getRecords()) {
                    Object tripId = rec.getValueByKey("trip_id");
                    Object value  = rec.getValue();
                    if (tripId == null || "UNKNOWN".equals(tripId.toString())) continue;
                    if (!(value instanceof Number n)) continue;
                    measured.add(tripDayKey(rec, tripId));
                    String status = ScheduleAdherenceService
                            .statusFromDelay((int) Math.round(n.doubleValue())).name();
                    counts.merge(status, 1L, Long::sum);
                }
        } catch (Exception e) {
            log.error("Error querying today's trip adherence from InfluxDB", e);
            return counts;
        }

        // Le corse VISTE: si interroga un campo sempre presente (lat), perche'
        // delay viene scritto solo quando esiste una misura — ed e' proprio la
        // sua assenza che stiamo cercando di contare.
        try {
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(%s) " +
                            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
                            "|> filter(fn: (r) => r[\"_field\"] == \"lat\")%s " +
                            "|> window(every: 1d) " +
                            "|> group(columns: [\"trip_id\", \"_start\"]) " +
                            "|> last()", bucket, buildFluxRange(startTime, endTime),
                    buildVehicleFilter(busId));
            long unclassified = 0;
            for (FluxTable table : influxDBClient.getQueryApi().query(flux))
                for (FluxRecord rec : table.getRecords()) {
                    Object tripId = rec.getValueByKey("trip_id");
                    if (tripId == null || "UNKNOWN".equals(tripId.toString())) continue;
                    if (!measured.contains(tripDayKey(rec, tripId))) unclassified++;
                }
            counts.put("UNCLASSIFIED", unclassified);
        } catch (Exception e) {
            log.error("Error counting unmeasured trips from InfluxDB", e);
        }

        return counts;
    }

    /** Identifica un ESERCIZIO di una corsa: la corsa piu' il giorno in cui e' avvenuta. */
    private static String tripDayKey(FluxRecord rec, Object tripId) {
        Object start = rec.getValueByKey("_start");
        return tripId + "|" + (start == null ? "" : start.toString());
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

        // Come sono andate le corse del periodo. Sta qui e non in /adherence
        // perche' e' l'unico endpoint che riceve il filtro: la stessa domanda
        // su "oggi" e su "ultimi 7 giorni" ha risposte diverse, e prima
        // l'anello rispondeva sempre e comunque su oggi.
        out.put("trip_status_counts", tripStatusCounts(startTime, endTime, busId));
        out.put("delay_delta",       previousWindowDelta(startTime, endTime, busId, scan.avgDelay()));

        // ── Panel 1: buses on road per line, right now ────────────────────
        out.put("buses_on_road", busesOnRoadByLine());

        // ── Panel 2: average delay per weekday ────────────────────────────
        weekdayPanel(startTime, endTime, busId, scan, out);

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
        /**
         * Gli stessi ritardi, ma tenuti per DATA.
         *
         * Su una finestra corta "martedi'" e' un giorno preciso e va messo dove
         * cade nel calendario; su un mese e' una categoria, e i quattro martedi'
         * vanno mediati insieme. Servono entrambe le viste, e ricavare la prima
         * dalla seconda non si puo': il giorno della settimana ha perso la data.
         */
        final Map<java.time.LocalDate, List<Double>> delayByDate = new TreeMap<>();
        final Map<String, List<Double>>         delayByRoute   = new LinkedHashMap<>();
        /** hour → {passengers, seats} */
        final Map<Integer, long[]>              paxCapByHour   = new HashMap<>();

        /**
         * La media, oppure null quando non c'e' nulla da mediare.
         *
         * Prima restituiva 0.0 in entrambi i casi, e il cruscotto mostrava un
         * sicuro "0.0 min" — cioe' "rete perfettamente in orario" — anche
         * filtrando su un mezzo che non ha mai trasmesso. Il front-end il ramo
         * per il dato assente ce l'aveva gia' (v == null -> "—"), ma non poteva
         * scattare perche' qui il null non arrivava mai.
         */
        Double avgDelay() {
            return allDelays.isEmpty() ? null
                 : allDelays.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    /**
     * Il ritardo medio giorno per giorno.
     *
     * DUE FORME, NON UNA
     * Finche' i giorni della finestra si possono disegnare uno per uno — fino a un
     * mese — ogni punto e' UNA DATA, in ordine cronologico. Chi ha chiesto
     * l'1-8 settembre vuole sapere com'e' andato il 3, non com'e' andato "il
     * mercoledi'": con le categorie due date diverse finivano nella stessa casella
     * e l'asse mostrava sempre e comunque lunedi'-domenica.
     *
     * Oltre il mese le date diventano troppe per un asse leggibile, e la lettura
     * utile torna a essere la categoria: i martedi' del periodo mediati insieme.
     *
     * Prima c'era solo la seconda forma, sempre e comunque sette caselle. Con
     * "Today" ne restava piena una, e un grafico a linee con un punto solo non
     * dice niente — non e' nemmeno una linea.
     *
     * LA FINESTRA MINIMA
     * Se il periodo scelto e' piu' corto della settimana in corso, il pannello si
     * allarga a lunedi'-oggi. E' l'unico dei pannelli a farlo, quindi lo dichiara:
     * `delay_by_weekday_label` dice su cosa e' stato calcolato davvero, e il
     * front-end lo scrive nel sottotitolo invece di ripetere il periodo scelto.
     */
    /**
     * Oltre quanti giorni il grafico smette di disegnarli uno per uno.
     *
     * Un mese di punti su un asse sta ancora in piedi; un trimestre no — e a quel
     * punto la domanda cambia comunque natura: non "com'e' andato il 3 settembre"
     * ma "come vanno i mercoledi'".
     */
    private static final int MAX_DAYS_PLOTTED_ONE_BY_ONE = 31;

    private void weekdayPanel(String startTime, String endTime, String busId,
                              Scan scan, Map<String, Object> out) {

        java.time.ZoneId zone = ZoneId.systemDefault();
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        java.time.LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);

        java.time.LocalDate from = parseDate(startTime, zone);
        java.time.LocalDate to   = parseDate(endTime,   zone);

        String label;
        Scan use = scan;

        // Si allarga per DURATA, non per posizione. "Copre lunedi'-oggi?" avrebbe
        // allargato anche un custom di mercoledi'-venerdi' e il mese scorso, che
        // sono finestre volute e piu' lunghe: la domanda giusta e' se il periodo
        // scelto ha meno giorni della settimana in corso.
        long weekDays = java.time.temporal.ChronoUnit.DAYS.between(monday, today) + 1;
        long askedDays = (from == null || to == null)
                ? 0
                : java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;

        if (askedDays < weekDays) {
            // Nota: il lunedi' la settimana in corso e' un giorno solo, quindi qui
            // non si allarga niente e il grafico ha un punto. E' la conseguenza di
            // aver scelto la settimana di calendario invece di sette giorni mobili.
            from  = monday;
            to    = today;
            label = "This week, from Monday";
            use   = scanPeriod(monday.atStartOfDay(zone).toInstant().toString(),
                               Instant.now().toString(), busId);
        } else {
            label = null;   // il periodo scelto va bene com'e'
        }

        Map<String, Object> series = new LinkedHashMap<>();
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        boolean byDate = days <= MAX_DAYS_PLOTTED_ONE_BY_ONE;

        if (byDate) {
            // Dentro una sola settimana il nome del giorno basta e si legge meglio:
            // sette nomi distinti, nessuna ambiguita'. Appena la finestra ne
            // attraversa due, "Mar" comparirebbe due volte con valori diversi, e
            // allora serve la data. Il confronto e' fra i due lunedi': se e' lo
            // stesso giorno, la settimana ISO e' la stessa.
            boolean sameWeek = from.with(java.time.DayOfWeek.MONDAY)
                          .equals(to.with(java.time.DayOfWeek.MONDAY));

            // Un punto per data, nell'ordine in cui i giorni sono passati.
            for (java.time.LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                List<Double> vals = use.delayByDate.get(d);
                String key = sameWeek
                        ? d.getDayOfWeek().getDisplayName(
                                java.time.format.TextStyle.SHORT, Locale.ENGLISH)
                        : d.getDayOfMonth() + "/" + d.getMonthValue();
                series.put(key,
                           (vals == null || vals.isEmpty()) ? null : round1(average(vals)));
            }
        } else {
            // Sette categorie, da lunedi' a domenica. Un buco resta un buco: dire
            // che domenica non c'e' servizio e' un'informazione, toglierla farebbe
            // sembrare la settimana di sei giorni.
            for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
                List<Double> vals = use.delayByWeekday.get(d);
                series.put(d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                           (vals == null || vals.isEmpty()) ? null : round1(average(vals)));
            }
        }

        out.put("delay_by_weekday", series);
        out.put("delay_by_weekday_label", label);
        // Il front-end cambia anche il TITOLO: un grafico per data non e' "per
        // giorno della settimana", e lasciare l'intestazione vecchia sopra punti
        // datati sarebbe l'unica cosa peggiore di mostrare le categorie.
        out.put("delay_by_weekday_mode", byDate ? "date" : "weekday");
    }

    /** La data di un istante ISO, o null se manca o non e' leggibile. */
    private static java.time.LocalDate parseDate(String iso, java.time.ZoneId zone) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).atZone(zone).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private Scan scanPeriod(String startTime, String endTime, String busId) {
        Scan s = new Scan();

        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(%s) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"vehicle_position\") " +
            "|> filter(fn: (r) => r[\"_field\"] == \"delay\" or r[\"_field\"] == \"passengers\" " +
            "                  or r[\"_field\"] == \"capacity\")%s " +
            // Media oraria PRIMA di aggregare, per due ragioni distinte.
            //
            // La prima e' di metodo: mediando i punti grezzi, ogni valore pesa
            // quanto spesso il mezzo trasmette. Le unita' OBU mandano un
            // messaggio al minuto, gps_simulator3 uno ogni cinque secondi: la
            // stessa ora di servizio contava dodici volte tanto a seconda della
            // sorgente, e una giornata simulata sommergeva una settimana di
            // storico. Su una griglia oraria ogni ora vale uno.
            //
            // La seconda e' di costo: qui passavano centinaia di migliaia di
            // record grezzi, trasferiti e ciclati uno a uno in Java.
            //
            // timeSrc: \"_start\" perche' aggregateWindow marca la finestra con
            // il suo estremo FINALE, e un punto delle 23:30 finirebbe timbrato
            // alle 00:00 del giorno dopo — spostandolo di giorno nel grafico per
            // giorno della settimana.
            "|> aggregateWindow(every: 1h, fn: mean, createEmpty: false, timeSrc: \"_start\")",
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
                            s.delayByDate
                             .computeIfAbsent(zdt.toLocalDate(), k -> new ArrayList<>())
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
    private Double previousWindowDelta(String startTime, String endTime, String busId, Double current) {
        if (current == null) return null;   // senza un valore attuale non c'e' variazione da calcolare
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

    /** Come sopra, ma lascia passare l'assenza di dato invece di trasformarla in zero. */
    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
