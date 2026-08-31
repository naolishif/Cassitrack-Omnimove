package it.unicas.omnimove.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import it.unicas.omnimove.repository.JourneyLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

@Service
@Slf4j
public class AnalyticsService {

    @Autowired
    private JourneyLogRepository journeyLogRepository;

    @Value("${influx.url}")    private String influxUrl;
    @Value("${influx.token}")  private String token;
    @Value("${influx.org}")    private String influxOrg;
    @Value("${influx.bucket}") private String bucket;

    // ── CO₂ saved per km vs private car (120 g/km baseline) ─────────────────
    private static final Map<String, Double> CO2_SAVED_PER_KM = Map.of(
        "BUS",     52.0,   // 120 - 68
        "TRAIN",   79.0,   // 120 - 41
        "SCOOTER", 116.0,  // 120 - 4
        "BIKE",    120.0,  // 120 - 0
        "WALK",    120.0   // 120 - 0
    );

    // ── Range helpers ─────────────────────────────────────────────────────────
    private static final Map<String, String> RANGE_MAP = Map.of(
        "1W", "-7d", "1M", "-30d", "3M", "-90d", "6M", "-180d", "1Y", "-365d"
    );
    // Aggregation window for the Green Index trend line
    private static final Map<String, String> WINDOW_MAP = Map.of(
        "1W", "12h", "1M", "1d", "3M", "3d", "6M", "1w", "1Y", "2w"
    );

    /**
     * A custom period, written {@code CUSTOM:<from>:<to>} with ISO dates.
     *
     * <p>Carried inside the existing {@code range} string rather than added as
     * two more parameters to five public methods and their two callers. The
     * preset codes keep working untouched, and everything that already passes a
     * range around — the dashboard, the export — carries a custom period for
     * free.
     */
    private static final String CUSTOM_PREFIX = "CUSTOM:";

    private static boolean isCustom(String range) {
        return range != null && range.toUpperCase().startsWith(CUSTOM_PREFIX);
    }

    /** [from, to] of a custom range, or null when it is a preset or malformed. */
    private static ZonedDateTime[] customBounds(String range) {
        if (!isCustom(range)) return null;
        String[] parts = range.substring(CUSTOM_PREFIX.length()).split(":");
        if (parts.length != 2) return null;
        try {
            // Whole days, in the timezone the service runs in: the operator picks
            // dates on a calendar, not instants. "to" includes its own day, which
            // is what someone choosing 1-31 March means.
            LocalDate from = LocalDate.parse(parts[0].trim());
            LocalDate to   = LocalDate.parse(parts[1].trim());
            if (to.isBefore(from)) { LocalDate swap = from; from = to; to = swap; }
            return new ZonedDateTime[]{
                    from.atStartOfDay(ZoneId.systemDefault()),
                    to.plusDays(1).atStartOfDay(ZoneId.systemDefault())
            };
        } catch (Exception e) {
            log.warn("Unreadable custom range '{}' — falling back to the default", range);
            return null;
        }
    }

    private String influxRange(String range) {
        ZonedDateTime[] b = customBounds(range);
        if (b != null) return b[0].toInstant().toString();      // RFC3339, accepted by Flux
        return RANGE_MAP.getOrDefault(range != null ? range.toUpperCase() : "1M", "-30d");
    }

    /** Flux defaults to now() when no stop is given; a custom period has one. */
    private String influxStop(String range) {
        ZonedDateTime[] b = customBounds(range);
        return b != null ? b[1].toInstant().toString() : "now()";
    }

    /**
     * Aggregation step for the trend line. For a custom period it is chosen from
     * the span, so a fortnight is not drawn with the two-week bucket of a year.
     */
    private String influxWindow(String range) {
        ZonedDateTime[] b = customBounds(range);
        if (b != null) {
            long days = java.time.Duration.between(b[0], b[1]).toDays();
            if (days <= 2)   return "1h";
            if (days <= 14)  return "12h";
            if (days <= 60)  return "1d";
            if (days <= 180) return "3d";
            if (days <= 400) return "1w";
            return "2w";
        }
        return WINDOW_MAP.getOrDefault(range != null ? range.toUpperCase() : "1M", "1d");
    }

    /** Lower bound for the queries that go to Postgres rather than Influx. */
    private ZonedDateTime sinceOf(String range) {
        ZonedDateTime[] b = customBounds(range);
        if (b != null) return b[0];
        return switch (range != null ? range.toUpperCase() : "1M") {
            case "1W" -> ZonedDateTime.now().minusWeeks(1);
            case "3M" -> ZonedDateTime.now().minusMonths(3);
            case "6M" -> ZonedDateTime.now().minusMonths(6);
            case "1Y" -> ZonedDateTime.now().minusYears(1);
            default   -> ZonedDateTime.now().minusMonths(1);
        };
    }

    private ZonedDateTime untilOf(String range) {
        ZonedDateTime[] b = customBounds(range);
        return b != null ? b[1] : ZonedDateTime.now();
    }

    // ── Query 1: mode distribution ─────────────────────────────────────────
    public Map<String, Long> getModeDistribution(String range) {
        String start = influxRange(range);
        String stop  = influxStop(range);
        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "count")
              |> group(columns: ["mode"])
              |> sum()
            """, bucket, start, stop);

        Map<String, Long> result = new LinkedHashMap<>();
        try (InfluxDBClient client = buildClient()) {
            for (FluxTable table : client.getQueryApi().query(flux, influxOrg)) {
                for (FluxRecord r : table.getRecords()) {
                    String mode = (String) r.getValueByKey("mode");
                    Number val  = (Number) r.getValue();
                    if (mode != null && val != null)
                        result.put(mode, val.longValue());
                }
            }
        } catch (Exception e) {
            log.error("getModeDistribution error: {}", e.getMessage());
        }
        return result;
    }

    // ── Query 2: mode × hour stacked bar ──────────────────────────────────
    public Map<String, long[]> getModeByHour(String range) {
        String start = influxRange(range);
        String stop  = influxStop(range);
        String[] modes = {"BUS", "BIKE", "SCOOTER", "WALK"};
        Map<String, long[]> result = new LinkedHashMap<>();
        for (String m : modes) result.put(m, new long[24]);

        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "hour")
              |> group(columns: ["mode"])
              |> keep(columns: ["_value", "mode"])
            """, bucket, start, stop);

        try (InfluxDBClient client = buildClient()) {
            for (FluxTable table : client.getQueryApi().query(flux, influxOrg)) {
                for (FluxRecord r : table.getRecords()) {
                    String mode = (String) r.getValueByKey("mode");
                    Number hourVal = (Number) r.getValue();
                    if (mode == null || hourVal == null) continue;
                    int hour = hourVal.intValue();
                    if (hour < 0 || hour > 23) continue;
                    result.computeIfAbsent(mode.toUpperCase(), k -> new long[24])[hour]++;
                }
            }
        } catch (Exception e) {
            log.error("getModeByHour error: {}", e.getMessage());
        }
        return result;
    }

    // ── Query 3: Green Index daily/windowed trend ──────────────────────────
    public List<Map<String, Object>> getGreenIndexTrend(String range) {
        String start  = influxRange(range);
        String stop   = influxStop(range);
        String window = influxWindow(range);
        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "green_index")
              |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
              |> yield(name: "avg")
            """, bucket, start, stop, window);

        List<Map<String, Object>> result = new ArrayList<>();
        try (InfluxDBClient client = buildClient()) {
            for (FluxTable table : client.getQueryApi().query(flux, influxOrg)) {
                for (FluxRecord r : table.getRecords()) {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("time", r.getTime() != null ? r.getTime().toString().substring(0, 10) : "");
                    Number val = (Number) r.getValue();
                    point.put("value", val != null ? Math.round(val.doubleValue() * 10.0) / 10.0 : 0);
                    result.add(point);
                }
            }
        } catch (Exception e) {
            log.error("getGreenIndexTrend error: {}", e.getMessage());
        }
        return result;
    }

    // ── Query 4: KPI summary ───────────────────────────────────────────────
    public Map<String, Object> getSummaryKpis(String range) {
        String start = influxRange(range);
        String stop  = influxStop(range);

        // Journey selections (written by JourneyEventService on /select)
        String fluxSelections = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "count")
              |> sum()
            """, bucket, start, stop);

        // Journey searches (written by JourneyEventService on /search, new)
        String fluxSearches = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search_query")
              |> filter(fn: (r) => r._field == "count")
              |> sum()
            """, bucket, start, stop);

        // Avg Green Index
        String fluxAvgGI = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "green_index")
              |> mean()
            """, bucket, start, stop);

        // Distance per mode (for CO₂ saved)
        String fluxCo2 = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "distance_km")
              |> group(columns: ["mode"])
              |> sum()
            """, bucket, start, stop);

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("totalSearches",  0L);
        kpis.put("totalSelections", 0L);
        kpis.put("selectionRate",  null);
        kpis.put("avgGreenIndex",  0.0);
        kpis.put("co2SavedKg",     0.0);

        try (InfluxDBClient client = buildClient()) {
            QueryApi q = client.getQueryApi();

            // selections
            long selections = sumFirst(q.query(fluxSelections, influxOrg));
            kpis.put("totalSelections", selections);

            // searches
            long searches = sumFirst(q.query(fluxSearches, influxOrg));
            kpis.put("totalSearches", searches);

            // selection rate
            if (searches > 0)
                kpis.put("selectionRate", Math.round(selections * 1000.0 / searches) / 10.0);

            // avg Green Index
            for (FluxTable t : q.query(fluxAvgGI, influxOrg))
                for (FluxRecord r : t.getRecords()) {
                    Number v = (Number) r.getValue();
                    if (v != null) kpis.put("avgGreenIndex", Math.round(v.doubleValue() * 10.0) / 10.0);
                }

            // CO₂ saved (kg)
            double co2Grams = 0.0;
            for (FluxTable t : q.query(fluxCo2, influxOrg)) {
                for (FluxRecord r : t.getRecords()) {
                    String mode = (String) r.getValueByKey("mode");
                    Number dist = (Number) r.getValue();
                    if (mode == null || dist == null) continue;
                    // Chains are handled below: the table above is keyed by
                    // single mode names and has no row for BUS_SCOOTER, so a
                    // lookup by chain returns nothing.
                    if (mode.contains("_")) continue;
                    double factor = CO2_SAVED_PER_KM.getOrDefault(mode.toUpperCase(), 0.0);
                    co2Grams += dist.doubleValue() * factor;
                }
            }

            // The chains, scored from their own Green Index. Until now they fell
            // through the lookup above and every combined journey was credited
            // with saving nothing — the headline figure understated exactly the
            // journeys the service exists to encourage.
            co2Grams += co2SavedGrams(fetchSelections(range).stream()
                                                            .filter(Selection::combined)
                                                            .toList());
            kpis.put("co2SavedKg", Math.round(co2Grams / 100.0) / 10.0); // g → kg, 1 decimal

        } catch (Exception e) {
            log.error("getSummaryKpis error: {}", e.getMessage());
        }
        return kpis;
    }

    // ── Query 5: trips by day of week (InfluxDB tag day_of_week) ─────────
    public Map<String, Long> getModeByDayOfWeek(String range) {
        String start = influxRange(range);
        String stop  = influxStop(range);
        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "count")
              |> group(columns: ["day_of_week"])
              |> sum()
            """, bucket, start, stop);

        // Keep canonical day order
        Map<String, Long> result = new LinkedHashMap<>();
        List<String> order = List.of("MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY");
        order.forEach(d -> result.put(d, 0L));

        try (InfluxDBClient client = buildClient()) {
            for (FluxTable table : client.getQueryApi().query(flux, influxOrg)) {
                for (FluxRecord r : table.getRecords()) {
                    String day = (String) r.getValueByKey("day_of_week");
                    Number val = (Number) r.getValue();
                    if (day != null && val != null)
                        result.put(day.toUpperCase(), val.longValue());
                }
            }
        } catch (Exception e) {
            log.error("getModeByDayOfWeek error: {}", e.getMessage());
        }
        return result;
    }

    // ── Query 6: top routes from SQL journey_log ──────────────────────────
    public List<Map<String, Object>> getTopRoutes(String range) {
        // Same window as every other panel, custom periods included — this used
        // to compute its own and had no upper bound at all.
        ZonedDateTime since = sinceOf(range);
        ZonedDateTime until = untilOf(range);

        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Object[]> rows = journeyLogRepository.findTopRoutes(since, until, PageRequest.of(0, 8));
            for (Object[] row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("origin",   row[0]);
                entry.put("dest",     row[1]);
                entry.put("uses",     ((Number) row[2]).longValue());
                double avgGi = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
                entry.put("avgGreenIndex", Math.round(avgGi * 10.0) / 10.0);
                result.add(entry);
            }
        } catch (Exception e) {
            log.error("getTopRoutes error: {}", e.getMessage());
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  COMBINED JOURNEYS — the MaaS panel
    // ════════════════════════════════════════════════════════════════════════

    /**
     * One accepted itinerary, read back out of InfluxDB.
     *
     * <p>Anonymous by construction: the write carries no user, no coordinates and
     * no names, so there is nothing here to aggregate away. What is counted is
     * itineraries, never travellers.
     */
    private record Selection(String mode, String dayOfWeek, int hour, int greenIndex,
                             double distanceKm, Double costEuros, int legs,
                             Integer durationMinutes) {

        boolean combined() { return mode.contains("_"); }

        /** The chain's pieces in the order they are ridden: BUS_SCOOTER → [BUS, SCOOTER]. */
        String[] parts() { return mode.split("_"); }

        String firstMode() { return parts()[0]; }

        String lastMode() { String[] p = parts(); return p[p.length - 1]; }

        /** The shared vehicle a chain leans on, or null when it rides none. */
        String vehicleMode() {
            for (String p : parts())
                if ("BIKE".equals(p) || "SCOOTER".equals(p)) return p;
            return null;
        }
    }

    /**
     * Every selection in the window, one row per journey.
     *
     * <p>The other queries let InfluxDB do the arithmetic, which is right when
     * the answer is one number per mode. The combined panel asks questions that
     * cross fields — how long a BUS_SCOOTER takes, how its minutes relate to its
     * kilometres, how the chains rank against each other — and those cannot be
     * assembled from independent per-field aggregates. Pivoting hands back whole
     * journeys instead, and the shape of the data (one point per confirmed trip)
     * keeps that affordable.
     *
     * <p>Two journeys of the same mode confirmed in the same millisecond pivot
     * onto one row and count once. Every figure in the combined panel is drawn
     * from this one list, so the panel stays consistent with itself; against the
     * summed counters above it can be short by that much, which at the rate
     * confirmations arrive is a rounding error rather than a discrepancy.
     */
    private List<Selection> fetchSelections(String range) {
        String flux = String.format("""
            from(bucket: "%s")
              |> range(start: %s, stop: %s)
              |> filter(fn: (r) => r._measurement == "journey_search")
              |> filter(fn: (r) => r._field == "hour" or r._field == "green_index"
                                or r._field == "distance_km" or r._field == "cost_euros"
                                or r._field == "legs" or r._field == "duration_minutes")
              |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
            """, bucket, influxRange(range), influxStop(range));

        List<Selection> out = new ArrayList<>();
        try (InfluxDBClient client = buildClient()) {
            for (FluxTable table : client.getQueryApi().query(flux, influxOrg)) {
                for (FluxRecord r : table.getRecords()) {
                    String mode = (String) r.getValueByKey("mode");
                    if (mode == null || mode.isBlank()) continue;
                    mode = mode.toUpperCase();

                    String day = (String) r.getValueByKey("day_of_week");
                    // Everything before V34 was written without a duration and
                    // without a leg count. Those journeys still count towards how
                    // often a chain is chosen; they are simply absent from the
                    // averages that need a figure they never carried.
                    Integer duration = intOrNull(r.getValueByKey("duration_minutes"));
                    int legs = (int) doubleOr(r.getValueByKey("legs"), mode.split("_").length);

                    out.add(new Selection(
                            mode,
                            day != null ? day.toUpperCase() : "",
                            (int) doubleOr(r.getValueByKey("hour"), -1),
                            (int) doubleOr(r.getValueByKey("green_index"), 0),
                            doubleOr(r.getValueByKey("distance_km"), 0),
                            // Null, not zero: the fare was not written before
                            // V34 either, and reading a missing price as free
                            // would pull every average towards nothing.
                            doubleOrNull(r.getValueByKey("cost_euros")),
                            Math.max(1, legs),
                            duration));
                }
            }
        } catch (Exception e) {
            log.error("fetchSelections error: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Everything the dashboard says about combined journeys.
     *
     * <p>The chain is kept whole and in order: BUS_SCOOTER and SCOOTER_BUS are
     * two different products — one is a scooter solving the last mile out of a
     * bus stop, the other is a scooter solving the first mile into one — and the
     * whole point of measuring a MaaS offer is to know which of the two people
     * actually take. Every figure here is an aggregate over itineraries; nothing
     * in the source data identifies a traveller.
     */
    public Map<String, Object> getCombinedStats(String range) {
        List<Selection> all = fetchSelections(range);
        List<Selection> combined = all.stream().filter(Selection::combined).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalJourneys",    (long) all.size());
        out.put("combinedJourneys", (long) combined.size());
        out.put("combinedShare",    all.isEmpty() ? 0.0
                : round1(combined.size() * 100.0 / all.size()));

        // ── Headline figures for the chains as a whole ──
        out.put("avgDurationMin",    avgDuration(combined));
        out.put("medianDurationMin", medianDuration(combined));
        out.put("avgDistanceKm",     round1(avg(combined, Selection::distanceKm)));
        out.put("avgCostEuros",      avgNullable(combined, Selection::costEuros, 2));
        out.put("avgGreenIndex",     round1(avg(combined, s -> (double) s.greenIndex())));
        out.put("avgLegs",           round1(avg(combined, s -> (double) s.legs())));
        out.put("co2SavedKg",        round1(co2SavedGrams(combined) / 1000.0));
        // How many of the chains carry a duration at all. The field was added
        // with V34, so for a while the average speaks for part of the period and
        // the panel has to be able to say so rather than imply otherwise.
        out.put("timedJourneys",     combined.stream().filter(s -> s.durationMinutes() != null).count());

        // ── The composition itself: which chain, how often ──
        Map<String, List<Selection>> byChain = new LinkedHashMap<>();
        for (Selection s : combined) byChain.computeIfAbsent(s.mode(), k -> new ArrayList<>()).add(s);

        List<Map<String, Object>> chains = new ArrayList<>();
        byChain.entrySet().stream()
               .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
               .forEach(e -> {
                   List<Selection> group = e.getValue();
                   Map<String, Object> row = new LinkedHashMap<>();
                   row.put("chain",  e.getKey());
                   row.put("label",  chainLabel(e.getKey()));
                   row.put("journeys", (long) group.size());
                   row.put("share", combined.isEmpty() ? 0.0
                           : round1(group.size() * 100.0 / combined.size()));
                   row.put("avgDurationMin",    avgDuration(group));
                   row.put("medianDurationMin", medianDuration(group));
                   row.put("avgDistanceKm",  round1(avg(group, Selection::distanceKm)));
                   row.put("avgCostEuros",   avgNullable(group, Selection::costEuros, 2));
                   row.put("avgGreenIndex",  round1(avg(group, s -> (double) s.greenIndex())));
                   row.put("avgLegs",        round1(avg(group, s -> (double) s.legs())));
                   row.put("co2SavedKg",     round1(co2SavedGrams(group) / 1000.0));
                   // Minutes per kilometre: the one figure that says whether a
                   // chain is worth its extra change of vehicle.
                   row.put("minutesPerKm",   paceOf(group));
                   chains.add(row);
               });
        out.put("chains", chains);

        // ── Which mode opens the trip, which closes it, which vehicle it rides ──
        // First versus last is the access/egress question: a vehicle at the front
        // is somebody reaching the network, a vehicle at the back is somebody
        // leaving it, and the two want different investments.
        out.put("firstLeg",   countBy(combined, Selection::firstMode));
        out.put("lastLeg",    countBy(combined, Selection::lastMode));
        out.put("vehicleMix", countBy(combined.stream()
                                              .filter(s -> s.vehicleMode() != null).toList(),
                                      Selection::vehicleMode));
        out.put("legCount",   countBy(combined, s -> String.valueOf(s.legs())));

        // ── How long they take, as a distribution rather than one average ──
        out.put("durationBuckets", durationBuckets(combined));

        // ── When they are taken ──
        long[] hours = new long[24];
        for (Selection s : combined)
            if (s.hour() >= 0 && s.hour() < 24) hours[s.hour()]++;
        out.put("byHour", hours);

        Map<String, Long> dow = new LinkedHashMap<>();
        for (String d : List.of("MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"))
            dow.put(d, 0L);
        for (Selection s : combined) dow.computeIfPresent(s.dayOfWeek(), (k, v) -> v + 1);
        out.put("byDayOfWeek", dow);

        // ── Combined against each single mode, on the same measures ──
        // Without this the panel says how a chain performs but not whether that
        // is good, which is the only way to read a number like "34 minutes".
        List<Map<String, Object>> comparison = new ArrayList<>();
        comparison.add(profile("COMBINED", "Combined", combined));
        for (String simple : List.of("BUS", "SCOOTER", "BIKE", "WALK")) {
            List<Selection> group = all.stream()
                    .filter(s -> !s.combined() && s.mode().equals(simple)).toList();
            if (!group.isEmpty()) comparison.add(profile(simple, chainLabel(simple), group));
        }
        out.put("comparison", comparison);

        return out;
    }

    /** The same measures for any set of journeys, so chains and single modes line up. */
    private Map<String, Object> profile(String mode, String label, List<Selection> group) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mode", mode);
        row.put("label", label);
        row.put("journeys", (long) group.size());
        row.put("avgDurationMin", avgDuration(group));
        row.put("avgDistanceKm",  round1(avg(group, Selection::distanceKm)));
        row.put("avgCostEuros",   avgNullable(group, Selection::costEuros, 2));
        row.put("avgGreenIndex",  round1(avg(group, s -> (double) s.greenIndex())));
        row.put("minutesPerKm",   paceOf(group));
        return row;
    }

    /** "BUS_SCOOTER" → "Bus → E-Scooter", for the report and the tooltips. */
    private static String chainLabel(String chain) {
        List<String> names = new ArrayList<>();
        for (String p : chain.split("_")) {
            names.add(switch (p) {
                case "BUS"     -> "Bus";
                case "BIKE"    -> "Shared Bike";
                case "SCOOTER" -> "E-Scooter";
                case "WALK"    -> "Walking";
                case "TRAIN"   -> "Train";
                default        -> p;
            });
        }
        return String.join(" → ", names);
    }

    /** Journeys grouped into the time bands an operator plans around. */
    private static Map<String, Long> durationBuckets(List<Selection> group) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (String b : List.of("<15 min", "15–30 min", "30–45 min", "45–60 min", "60+ min"))
            buckets.put(b, 0L);
        for (Selection s : group) {
            Integer d = s.durationMinutes();
            if (d == null) continue;
            String key = d < 15 ? "<15 min"
                       : d < 30 ? "15–30 min"
                       : d < 45 ? "30–45 min"
                       : d < 60 ? "45–60 min"
                       : "60+ min";
            buckets.merge(key, 1L, Long::sum);
        }
        return buckets;
    }

    /**
     * CO₂ kept out of the air by a set of journeys, in grams.
     *
     * <p>Derived from the Green Index rather than from a per-mode table, because
     * a table has no row for BUS_SCOOTER: looked up by chain name it returns
     * nothing, and every combined journey silently counted as having saved zero.
     * The index already knows the split — it was computed from the itinerary's
     * real emissions — so inverting it gives what each journey emitted per
     * kilometre, and the difference against the car baseline is what was saved.
     * For a single mode this reproduces the per-km figures exactly: a bus scores
     * 60, which comes back out as 120 − 68 = 52 g/km.
     */
    private static double co2SavedGrams(List<Selection> group) {
        double total = 0;
        for (Selection s : group) {
            double emittedPerKm = (1 - s.greenIndex() / 100.0) * GREEN_INDEX_CAR_G_PER_KM;
            total += Math.max(0, CAR_BASELINE_G_PER_KM - emittedPerKm) * s.distanceKm();
        }
        return total;
    }

    /** The car the Green Index scores against (GreenIndexService.CO2_CAR). */
    private static final double GREEN_INDEX_CAR_G_PER_KM = 170.0;

    /** The car the CO₂-saved figures are compared against, as above. */
    private static final double CAR_BASELINE_G_PER_KM = 120.0;

    /** Minutes per kilometre, over the journeys that carry both. */
    private static Double paceOf(List<Selection> group) {
        double minutes = 0, km = 0;
        for (Selection s : group) {
            if (s.durationMinutes() == null || s.distanceKm() <= 0) continue;
            minutes += s.durationMinutes();
            km += s.distanceKm();
        }
        return km > 0 ? round1(minutes / km) : null;
    }

    /** Null rather than zero when nothing in the set was timed — a gap, not a fast trip. */
    private static Double avgDuration(List<Selection> group) {
        List<Integer> d = group.stream().map(Selection::durationMinutes)
                               .filter(Objects::nonNull).toList();
        if (d.isEmpty()) return null;
        return round1(d.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private static Double medianDuration(List<Selection> group) {
        List<Integer> d = group.stream().map(Selection::durationMinutes)
                               .filter(Objects::nonNull).sorted().toList();
        if (d.isEmpty()) return null;
        int n = d.size();
        return round1(n % 2 == 1 ? d.get(n / 2) : (d.get(n / 2 - 1) + d.get(n / 2)) / 2.0);
    }

    /** The average over the journeys that carry the figure, or null when none do. */
    private static Double avgNullable(List<Selection> group,
                                      java.util.function.Function<Selection, Double> f,
                                      int decimals) {
        List<Double> values = group.stream().map(f).filter(Objects::nonNull).toList();
        if (values.isEmpty()) return null;
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return decimals == 1 ? round1(mean) : round2(mean);
    }

    private static Double doubleOrNull(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static double avg(List<Selection> group, java.util.function.ToDoubleFunction<Selection> f) {
        return group.stream().mapToDouble(f).average().orElse(0);
    }

    /** Counts by whatever the journey is keyed on, busiest first. */
    private static Map<String, Long> countBy(List<Selection> group,
                                             java.util.function.Function<Selection, String> key) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Selection s : group) out.merge(key.apply(s), 1L, Long::sum);
        return out.entrySet().stream()
                  .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                  .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number n ? (int) Math.round(n.doubleValue()) : null;
    }

    private static double doubleOr(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // ── Helpers ───────────────────────────────────────────────────────────
    private long sumFirst(List<FluxTable> tables) {
        long total = 0;
        for (FluxTable t : tables)
            for (FluxRecord r : t.getRecords()) {
                Number v = (Number) r.getValue();
                if (v != null) total += v.longValue();
            }
        return total;
    }

    private InfluxDBClient buildClient() {
        return InfluxDBClientFactory.create(influxUrl, token.toCharArray(), influxOrg, bucket);
    }
}
