package it.unicas.cassitrack.service;

import it.unicas.cassitrack.dto.StopArrivalDTO;
import it.unicas.cassitrack.dto.siri.Siri;
import it.unicas.cassitrack.model.Route;
import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.repository.RouteRepository;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the SIRI documents CassiTrack publishes: VehicleMonitoring (the REST
 * endpoint and every SSE push), StopMonitoring and CheckStatus.
 *
 * <p>The single place SIRI is produced. Everything in the document comes from
 * data the system already holds — the live cache, the line register, the stop
 * pattern and the timetable — and nothing is invented: a field we cannot fill
 * honestly is left out rather than defaulted, which is what {@code NON_NULL}
 * on the model is for.
 *
 * <p>The VM document is rebuilt every five seconds for the stream, so the
 * lookups behind it are cached: lines and stops for a minute, a trip's
 * timetable for five. Stop patterns are cached by {@link RoutePatternService}.
 */
@Service
@Slf4j
public class SiriService {

    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");
    private static final Duration REGISTER_TTL  = Duration.ofMinutes(1);
    private static final Duration TIMETABLE_TTL = Duration.ofMinutes(5);
    /** ValidUntilTime: how long a position may be considered current. */
    private static final int VALID_FOR_SECONDS = 60;

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final ScheduledStopRepository scheduledStopRepository;
    private final RoutePatternService routePatternService;
    private final ETAService etaService;
    private final String operatorRef;
    private final Instant serviceStarted = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private record Cached<T>(T value, Instant at) {}
    private volatile Cached<Map<String, Route>> routes;
    private volatile Cached<Map<String, Stop>> stops;
    private final Map<String, Cached<List<ScheduledStop>>> timetables = new ConcurrentHashMap<>();

    public SiriService(RouteRepository routeRepository,
                       StopRepository stopRepository,
                       ScheduledStopRepository scheduledStopRepository,
                       RoutePatternService routePatternService,
                       ETAService etaService,
                       @Value("${siri.operator-ref:CASSITRACK}") String operatorRef) {
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.scheduledStopRepository = scheduledStopRepository;
        this.routePatternService = routePatternService;
        this.etaService = etaService;
        this.operatorRef = operatorRef;
    }

    // ── CheckStatus ────────────────────────────────────────────────────────────

    public Siri checkStatus() {
        Siri.CheckStatusResponse r = new Siri.CheckStatusResponse();
        r.setServiceStartedTime(serviceStarted.toString());
        return new Siri(r);
    }

    // ── VehicleMonitoring ──────────────────────────────────────────────────────

    public Siri vehicleMonitoring(Collection<VehiclePosition> vehicles) {
        Map<String, Route> routeMap = routes();
        Map<String, Stop>  stopMap  = stops();
        Instant base = LocalDate.now(ITALY_TZ).atStartOfDay(ITALY_TZ).toInstant();
        String today = LocalDate.now(ITALY_TZ).toString();

        List<Siri.VehicleActivity> activities = new ArrayList<>();
        for (VehiclePosition v : vehicles) {
            try {
                activities.add(vehicleActivity(v, routeMap, stopMap, base, today));
            } catch (Exception e) {
                // One bad vehicle must not empty the whole delivery
                log.warn("SIRI VM: skipping {}: {}", v.getVehicleId(), e.toString());
            }
        }

        Siri.VehicleMonitoringDelivery vmd = new Siri.VehicleMonitoringDelivery();
        vmd.setVehicleActivity(activities);
        Siri.ServiceDelivery sd = new Siri.ServiceDelivery();
        sd.setVehicleMonitoringDelivery(vmd);
        return new Siri(sd);
    }

    private Siri.VehicleActivity vehicleActivity(VehiclePosition v, Map<String, Route> routeMap,
                                                 Map<String, Stop> stopMap, Instant base, String today) {
        String routeId = v.getRouteId() != null ? v.getRouteId() : v.getMatchedRouteId();
        Route route = routeId == null ? null : routeMap.get(routeId);
        boolean onTrip = v.getTripId() != null;

        Siri.MonitoredVehicleJourney j = new Siri.MonitoredVehicleJourney();

        // ── Line and journey ──────────────────────────────────────────────
        if (routeId != null) {
            j.setLineRef(routeId);
            j.setDirectionRef(routeId.endsWith("_R") ? "inbound" : "outbound");
        }
        if (onTrip) j.setFramedVehicleJourneyRef(new Siri.FramedVehicleJourneyRef(today, v.getTripId()));
        j.setVehicleMode("bus");
        if (route != null) {
            j.setPublishedLineName(route.getShortName());
            j.setDirectionName(route.getLongName());
        } else if (v.getRouteName() != null) {
            j.setPublishedLineName(v.getRouteName());
        }
        j.setOperatorRef(operatorRef);

        // ── Origin, destination and the journey's timetable ───────────────
        List<RoutePatternService.PatternStop> pattern = routeId == null ? List.of() : routePatternService.pattern(routeId);
        Map<Integer, String> stopAtSeq = pattern.stream().collect(Collectors.toMap(
                RoutePatternService.PatternStop::stopSequence, RoutePatternService.PatternStop::stopId, (a, b) -> a));
        if (!pattern.isEmpty()) {
            String first = pattern.get(0).stopId();
            String last  = pattern.get(pattern.size() - 1).stopId();
            j.setOriginRef(first);
            j.setOriginName(stopName(first, stopMap));
            j.setDestinationRef(last);
            j.setDestinationName(stopName(last, stopMap));
        }
        List<ScheduledStop> timetable = onTrip ? timetable(v.getTripId()) : List.of();
        Map<Integer, Integer> aimedAtSeq = timetable.stream().collect(Collectors.toMap(
                ScheduledStop::getStopSequence, ScheduledStop::getArrivalSeconds, (a, b) -> a));
        if (v.getTripStartSeconds() != null) {
            j.setOriginAimedDepartureTime(base.plusSeconds(v.getTripStartSeconds()).toString());
        } else if (!timetable.isEmpty()) {
            j.setOriginAimedDepartureTime(base.plusSeconds(timetable.get(0).getArrivalSeconds()).toString());
        }
        if (!timetable.isEmpty()) {
            j.setDestinationAimedArrivalTime(
                    base.plusSeconds(timetable.get(timetable.size() - 1).getArrivalSeconds()).toString());
        }

        // ── Progress ──────────────────────────────────────────────────────
        j.setMonitored(onTrip);
        j.setVehicleLocation(new Siri.VehicleLocation(
                v.getLon() != null ? v.getLon() : 0.0,
                v.getLat() != null ? v.getLat() : 0.0));
        j.setBearing(v.getHeadingDeg());
        if (v.getSpeedKmh() != null) j.setVelocity((int) Math.round(v.getSpeedKmh() / 3.6));
        Integer passengers = CrowdingService.effectivePassengers(v.getPassengers(), v.getBleDeviceCount());
        j.setOccupancy(CrowdingService.siriOccupancy(passengers, v.getCapacity()));
        j.setDelay(isoDelay(v.getDelayMinutes()));
        if (onTrip) j.setVehicleStatus(v.getLastStopSequence() == null ? "atOrigin" : "inProgress");
        j.setVehicleRef(v.getVehicleId());

        // ── Calls: the one behind, the one ahead, the ones after that ─────
        int delaySeconds = 60 * (v.getDelayMinutes() == null ? 0 : v.getDelayMinutes());
        String status = arrivalStatus(v.getScheduleStatus());

        if (v.getLastStopRegistered() != null) {
            String prevRef = v.getLastStopRegisteredId() != null
                    ? v.getLastStopRegisteredId() : toStopCode(v.getLastStopRegistered());
            Siri.PreviousCall prev = new Siri.PreviousCall(prevRef, v.getLastStopRegistered());
            prev.setOrder(v.getLastStopSequence());
            j.setPreviousCalls(List.of(prev));
        }

        Integer nextSeq = null;
        if (v.getNextStop() != null) {
            String nextRef = v.getNextStopId() != null ? v.getNextStopId() : toStopCode(v.getNextStop());
            Siri.MonitoredCall call = new Siri.MonitoredCall(nextRef, v.getNextStop());
            nextSeq = sequenceOf(nextRef, stopAtSeq, v.getLastStopSequence());
            call.setOrder(nextSeq);
            // nextStopArrivalSeconds is the timetable's second-of-day at the next
            // stop, not a countdown; the timetable row is the fallback when the
            // cache does not carry it. Expected = aimed shifted by the delay
            // measured at the last stop — the same rule VehicleService applies.
            Integer aimed = v.getNextStopArrivalSeconds() != null
                    ? v.getNextStopArrivalSeconds()
                    : (nextSeq == null ? null : aimedAtSeq.get(nextSeq));
            if (aimed != null) {
                call.setAimedArrivalTime(base.plusSeconds(aimed).toString());
                call.setExpectedArrivalTime(base.plusSeconds(aimed + delaySeconds).toString());
                call.setArrivalStatus(status);
                // Past the expected time and still not registered there: it is
                // pulling in, not "arriving in minus two minutes"
                call.setVehicleAtStop(base.plusSeconds(aimed + delaySeconds).isBefore(Instant.now()));
            }
            j.setMonitoredCall(call);
        }

        if (nextSeq != null && !timetable.isEmpty()) {
            List<Siri.OnwardCall> onward = new ArrayList<>();
            for (ScheduledStop ss : timetable) {
                if (ss.getStopSequence() == null || ss.getStopSequence() <= nextSeq) continue;
                String ref = stopAtSeq.get(ss.getStopSequence());
                if (ref == null) continue;
                Siri.OnwardCall oc = new Siri.OnwardCall();
                oc.setStopPointRef(ref);
                oc.setOrder(ss.getStopSequence());
                oc.setStopPointName(stopName(ref, stopMap));
                oc.setAimedArrivalTime(base.plusSeconds(ss.getArrivalSeconds()).toString());
                // The delay measured at the last stop carried forward: the best
                // estimate there is for stops the ETA engine has not reached yet
                oc.setExpectedArrivalTime(base.plusSeconds(ss.getArrivalSeconds() + delaySeconds).toString());
                oc.setArrivalStatus(status);
                onward.add(oc);
            }
            if (!onward.isEmpty()) j.setOnwardCalls(onward);
        }

        // ── Extensions: unchanged, OmniMove reads these ───────────────────
        Siri.Extensions ext = new Siri.Extensions();
        ext.setVelocity(v.getSpeedKmh());
        ext.setNumberOfSeats(v.getNumeroPosti());
        ext.setPassengers(passengers);
        ext.setWheelchairAccess(v.getWheelchairAccessible());

        Siri.VehicleActivity a = new Siri.VehicleActivity();
        if (v.getTimestamp() != null) {
            Instant rec = v.getTimestamp().truncatedTo(ChronoUnit.MILLIS);
            a.setRecordedAtTime(rec.toString());
            a.setValidUntilTime(rec.plusSeconds(VALID_FOR_SECONDS).toString());
        }
        a.setVehicleMonitoringRef(v.getVehicleId());
        a.setMonitoredVehicleJourney(j);
        a.setExtensions(ext);
        return a;
    }

    // ── StopMonitoring ─────────────────────────────────────────────────────────

    /**
     * @return empty if the stop does not exist — the caller decides the HTTP answer
     */
    public Optional<Siri> stopMonitoring(String stopId, int maxVisits) {
        Stop stop = stops().get(stopId);
        if (stop == null) return Optional.empty();

        Map<String, Route> routeMap = routes();
        Map<String, Stop>  stopMap  = stops();
        String today = LocalDate.now(ITALY_TZ).toString();

        List<Siri.MonitoredStopVisit> visits = new ArrayList<>();
        for (StopArrivalDTO arr : etaService.getArrivalsAtStop(stopId)) {
            if (visits.size() >= maxVisits) break;

            Siri.MonitoredVehicleJourney j = new Siri.MonitoredVehicleJourney();
            String routeId = arr.getRouteId();
            Route route = routeId == null ? null : routeMap.get(routeId);
            if (routeId != null) {
                j.setLineRef(routeId);
                j.setDirectionRef(routeId.endsWith("_R") ? "inbound" : "outbound");
            }
            if (arr.getTripId() != null) j.setFramedVehicleJourneyRef(new Siri.FramedVehicleJourneyRef(today, arr.getTripId()));
            j.setVehicleMode("bus");
            j.setPublishedLineName(route != null ? route.getShortName() : arr.getRouteShortName());
            j.setDirectionName(route != null ? route.getLongName() : arr.getRouteName());
            j.setOperatorRef(operatorRef);

            List<RoutePatternService.PatternStop> pattern = routeId == null ? List.of() : routePatternService.pattern(routeId);
            if (!pattern.isEmpty()) {
                String first = pattern.get(0).stopId();
                String last  = pattern.get(pattern.size() - 1).stopId();
                j.setOriginRef(first);
                j.setOriginName(stopName(first, stopMap));
                j.setDestinationRef(last);
                j.setDestinationName(stopName(last, stopMap));
            }
            if (arr.getScheduledDeparture() != null) j.setOriginAimedDepartureTime(arr.getScheduledDeparture().toString());

            // inTransit = the bus is on the road and the ETA comes from its position;
            // otherwise the run has not left yet and the time is the timetable's
            j.setMonitored(arr.isInTransit());
            j.setDelay(isoDelay(arr.getDelayMinutes()));
            j.setVehicleRef(arr.getVehicleId());

            Siri.MonitoredCall call = new Siri.MonitoredCall(stopId, stop.getName());
            if (arr.getScheduledArrival() != null) call.setAimedArrivalTime(arr.getScheduledArrival().truncatedTo(ChronoUnit.SECONDS).toString());
            if (arr.getEstimatedArrival() != null) call.setExpectedArrivalTime(arr.getEstimatedArrival().truncatedTo(ChronoUnit.SECONDS).toString());
            call.setArrivalStatus(arr.isInTransit() ? arrivalStatusOf(arr.getScheduleStatus()) : "noReport");
            j.setMonitoredCall(call);

            Siri.MonitoredStopVisit visit = new Siri.MonitoredStopVisit();
            visit.setMonitoringRef(stopId);
            visit.setItemIdentifier(stopId + ":" + (arr.getTripId() != null ? arr.getTripId() : arr.getVehicleId()));
            visit.setMonitoredVehicleJourney(j);
            visits.add(visit);
        }

        Siri.StopMonitoringDelivery smd = new Siri.StopMonitoringDelivery();
        smd.setMonitoringRef(stopId);
        smd.setMonitoredStopVisit(visits);
        Siri.ServiceDelivery sd = new Siri.ServiceDelivery();
        sd.setStopMonitoringDelivery(smd);
        return Optional.of(new Siri(sd));
    }

    // ── Lookups ────────────────────────────────────────────────────────────────

    private Map<String, Route> routes() {
        Cached<Map<String, Route>> c = routes;
        if (c == null || c.at().plus(REGISTER_TTL).isBefore(Instant.now())) {
            c = new Cached<>(routeRepository.findAll().stream()
                    .collect(Collectors.toMap(Route::getId, Function.identity(), (a, b) -> a)), Instant.now());
            routes = c;
        }
        return c.value();
    }

    private Map<String, Stop> stops() {
        Cached<Map<String, Stop>> c = stops;
        if (c == null || c.at().plus(REGISTER_TTL).isBefore(Instant.now())) {
            c = new Cached<>(stopRepository.findAll().stream()
                    .collect(Collectors.toMap(Stop::getId, Function.identity(), (a, b) -> a)), Instant.now());
            stops = c;
        }
        return c.value();
    }

    private List<ScheduledStop> timetable(String tripId) {
        Cached<List<ScheduledStop>> c = timetables.get(tripId);
        if (c == null || c.at().plus(TIMETABLE_TTL).isBefore(Instant.now())) {
            List<ScheduledStop> rows = scheduledStopRepository.findByTripIdOrderByStopSequenceAsc(tripId);
            c = new Cached<>(rows, Instant.now());
            timetables.put(tripId, c);
            // Yesterday's trips would otherwise stay here forever
            if (timetables.size() > 2000) timetables.clear();
        }
        return c.value();
    }

    private static String stopName(String stopId, Map<String, Stop> stopMap) {
        Stop s = stopMap.get(stopId);
        return s != null && s.getName() != null ? s.getName() : stopId;
    }

    /**
     * Where a stop sits in the pattern. Looked up by id; when the id is not in
     * the pattern the sequence after the last registered stop is the answer.
     */
    private static Integer sequenceOf(String stopId, Map<Integer, String> stopAtSeq, Integer lastSeq) {
        for (Map.Entry<Integer, String> e : stopAtSeq.entrySet()) {
            if (e.getValue().equals(stopId) && (lastSeq == null || e.getKey() > lastSeq)) return e.getKey();
        }
        return lastSeq == null ? null : lastSeq + 1;
    }

    /** Ritardo in ISO 8601 (PT0S, PT2M, -PT1M). */
    private static String isoDelay(Integer minutes) {
        if (minutes == null) return null;
        int d = minutes;
        return d == 0 ? "PT0S" : (d > 0 ? "PT" + d + "M" : "-PT" + Math.abs(d) + "M");
    }

    /** ScheduleStatus → CallStatusEnumeration (onTime | early | delayed | noReport). */
    private static String arrivalStatus(VehiclePosition.ScheduleStatus s) {
        return arrivalStatusOf(s == null ? null : s.name());
    }

    private static String arrivalStatusOf(String s) {
        if (s == null) return "noReport";
        return switch (s) {
            case "ON_TIME"                              -> "onTime";
            case "EARLY"                                -> "early";
            case "SLIGHTLY_LATE", "SIGNIFICANTLY_LATE"  -> "delayed";
            default                                     -> "noReport";
        };
    }

    /**
     * Converte un nome di fermata in un codice valido per StopPointCodeType,
     * che non ammette spazi. Solo come ripiego quando manca l'id.
     */
    private static String toStopCode(String s) {
        if (s == null) return null;
        String code = s.replaceAll("[^A-Za-z0-9]", "");
        return code.isEmpty() ? "UNKNOWN" : code;
    }
}
