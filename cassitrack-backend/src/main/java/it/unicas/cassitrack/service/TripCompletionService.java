package it.unicas.cassitrack.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * When each trip was last observed arriving somewhere — read back from InfluxDB.
 *
 * WHY INFLUX AND NOT POSTGRES
 * ---------------------------
 * {@link ScheduleAdherenceService} already writes one {@code stop_arrival} point
 * per observed arrival, carrying the stop sequence, the delay and the instant of
 * the fix at which the bus was closest to the stop. That is exactly the record a
 * completion is, so reading it back avoids storing the same fact twice.
 *
 * The trade-off, stated plainly: Influx is a metrics store, so this is a
 * time-window query rather than a keyed lookup, and a point that never got
 * written (Influx down, arrival never observed within the 80 m gate) is simply
 * absent. The view degrades to "no actual finish time" in that case, which is
 * honest — we genuinely did not see the bus arrive.
 *
 * WHY ONE QUERY FOR THE WHOLE FLEET
 * ---------------------------------
 * The Active Trips table refreshes every 30 s and shows every running trip.
 * Querying per trip would mean a dozen round-trips per refresh; instead this
 * pulls the whole recent window once and indexes it in memory.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TripCompletionService {

    private final InfluxDBClient influxDBClient;

    @Value("${influx.bucket}")
    private String bucket;

    /**
     * How far back to look. Comfortably longer than the 15-minute linger so a
     * trip that finished early still has its arrival inside the window, but
     * short enough that the query stays small.
     */
    private static final String LOOKBACK = "-90m";

    /**
     * The furthest point a trip has been observed to reach, and when.
     *
     * @param lastSequence stop_sequence of the most recent observed arrival
     * @param finishedAt   instant of the fix at which the bus was closest to it
     * @param delayMinutes delay measured at that stop, signed
     */
    public record Arrival(int lastSequence, Instant finishedAt, Integer delayMinutes) {}

    /**
     * Most recent observed arrival per trip, over the last {@value #LOOKBACK}.
     *
     * Returns an empty map rather than throwing if Influx is unreachable: a
     * missing actual-finish column is a cosmetic loss, and must not take the
     * operational view down with it.
     */
    public Map<String, Arrival> recentArrivalsByTrip() {
        String flux = String.format(
                "from(bucket: \"%s\") " +
                "|> range(start: %s) " +
                "|> filter(fn: (r) => r[\"_measurement\"] == \"stop_arrival\") " +
                "|> filter(fn: (r) => r[\"_field\"] == \"stop_sequence\" or r[\"_field\"] == \"delay_minutes\") " +
                // Puts stop_sequence and delay_minutes on the same row, so one
                // record describes one arrival instead of two half-records.
                // NB: valueColumn is SINGULAR — the plural form is a v1-era
                // spelling that Flux rejects with a 400.
                "|> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")",
                bucket, LOOKBACK);

        Map<String, Arrival> out = new HashMap<>();
        try {
            for (FluxTable table : influxDBClient.getQueryApi().query(flux)) {
                for (FluxRecord record : table.getRecords()) {
                    String tripId = (String) record.getValueByKey("trip_id");
                    // Points written before trip_id was tagged have no trip at
                    // all; they belong to no run we can name, so skip them.
                    if (tripId == null || "UNKNOWN_TRIP".equals(tripId)) continue;

                    Number seq = (Number) record.getValueByKey("stop_sequence");
                    if (seq == null) continue;

                    Instant at = record.getTime();
                    if (at == null) continue;

                    Number delay = (Number) record.getValueByKey("delay_minutes");

                    // Keep the furthest arrival, not the latest by clock: a bus
                    // re-anchored onto the same trip could report an earlier
                    // stop afterwards, and that must not walk the progress back.
                    Arrival existing = out.get(tripId);
                    if (existing == null || seq.intValue() > existing.lastSequence()) {
                        out.put(tripId, new Arrival(seq.intValue(), at,
                                delay == null ? null : delay.intValue()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not read recent arrivals from InfluxDB: {}", e.getMessage());
            return Map.of();
        }
        return out;
    }
}
