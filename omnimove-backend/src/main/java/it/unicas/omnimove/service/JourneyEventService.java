package it.unicas.omnimove.service;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@Slf4j
public class JourneyEventService {

    @Value("${influx.url}")
    private String influxUrl;

    @Value("${influx.token}")
    private String token;

    @Value("${influx.org}")
    private String influxOrg;

    @Value("${influx.bucket}")
    private String bucket;

    /** Written when the user calls /search (counts raw queries). */
    public void recordJourneySearchQuery() {
        try (InfluxDBClient client = InfluxDBClientFactory.create(
                influxUrl, token.toCharArray(), influxOrg, bucket)) {

            Point point = Point.measurement("journey_search_query")
                    .addField("count", 1)
                    .time(Instant.now(), WritePrecision.MS);

            client.getWriteApiBlocking().writePoint(point);
            log.debug("Journey search query recorded");

        } catch (Exception e) {
            log.error("Failed to record search query to InfluxDB: {}", e.getMessage());
        }
    }

    /**
     * Written when the user confirms/selects a journey option.
     *
     * <p>The mode tag carries the chain as the planner stitched it — BUS_SCOOTER
     * is not the same offer as SCOOTER_BUS, and folding the two into a single
     * "combined" here would throw away the one thing the MaaS side of the
     * dashboard is there to measure. The {@code combined} tag exists so that
     * question can be asked without parsing mode names in a Flux query.
     *
     * <p>Nothing here identifies a traveller: no user id, no coordinates, no
     * names. What is stored is one anonymous fact about one accepted itinerary.
     *
     * @param durationMinutes how long the accepted itinerary was expected to
     *                        take, or null when the client did not say — written
     *                        as a field only when known, so it is missing from
     *                        the average rather than pulling it towards zero
     * @param legs            how many moving pieces the itinerary has; 1 for a
     *                        single mode, 2 or more for a chain
     */
    public void recordJourneySearch(String mode, int hour, String dayOfWeek,
                                    int greenIndex, double distanceKm,
                                    Integer durationMinutes, double costEuros, int legs) {
        try (InfluxDBClient client = InfluxDBClientFactory.create(
                influxUrl, token.toCharArray(), influxOrg, bucket)) {

            WriteApiBlocking writeApi = client.getWriteApiBlocking();

            String normalised = mode.toUpperCase();

            Point point = Point.measurement("journey_search")
                    .addTag("mode", normalised)
                    .addTag("day_of_week", dayOfWeek)
                    // Two values, so the cardinality cost is nil and the combined
                    // panels do not have to enumerate every chain the planner may
                    // one day learn to build.
                    .addTag("combined", normalised.contains("_") ? "true" : "false")
                    .addField("hour", hour)
                    .addField("green_index", greenIndex)
                    .addField("distance_km", distanceKm)
                    .addField("cost_euros", costEuros)
                    .addField("legs", legs)
                    .addField("count", 1)
                    .time(Instant.now(), WritePrecision.MS);

            if (durationMinutes != null && durationMinutes > 0)
                point.addField("duration_minutes", durationMinutes);

            writeApi.writePoint(point);
            log.debug("Journey event recorded: mode={} hour={} day={} duration={}",
                    mode, hour, dayOfWeek, durationMinutes);

        } catch (Exception e) {
            log.error("Failed to record journey event to InfluxDB: {}", e.getMessage());
        }
    }
}