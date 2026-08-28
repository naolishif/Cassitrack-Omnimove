package it.unicas.omnimove.service;

import it.unicas.omnimove.client.CassitrackClient;
import it.unicas.omnimove.dto.*;
import it.unicas.omnimove.model.ScheduledStop;
import it.unicas.omnimove.model.UserPreferences;
import it.unicas.omnimove.repository.UserPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import it.unicas.omnimove.repository.ScheduledStopRepository;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

/**
 * OMNIMOVE Journey Planner.
 *
 * Gets live bus data from CASSITRACK via REST API.
 * Never accesses CASSITRACK database directly.
 *
 * BUS option now uses Google Maps Distance Matrix API to compute
 * the real travel time from the bus current GPS position to the
 * destination stop, accounting for live traffic.
 *
 * Fallback chain:
 *   1. Google Maps with bus GPS position (best accuracy)
 *   2. Google Maps with nearest stop as origin (if no bus available)
 *   3. distKm / 25 km/h estimate (if Google Maps unavailable)
 */
@Service
@RequiredArgsConstructor
public class JourneyPlannerService {

    private static final Logger log =
            LoggerFactory.getLogger(JourneyPlannerService.class);

    private final CassitrackClient  cassitrackClient;
    private final GreenIndexService greenIndex;
    private final WeatherService    weatherService;
    private final GoogleMapsService googleMapsService;
    private final it.unicas.omnimove.repository.StopRepository stopRepository;
    private final it.unicas.omnimove.repository.ScheduledStopRepository scheduledStopRepository;
    private final it.unicas.omnimove.repository.TripRepository tripRepository;
    private final it.unicas.omnimove.repository.RouteShapeRepository routeShapeRepository;

    /**
     * How close a shape vertex must be to a stop to count as that stop.
     * ~1 m: the geometry is authored from the stop coordinates, not surveyed
     * separately, so matches are exact in practice.
     */
    private static final double STOP_MATCH_TOLERANCE = 1e-5;
    private final it.unicas.omnimove.repository.UserPreferencesRepository preferencesRepository;
    private final GoogleApiSettingsService googleApiSettings;
    private final BikeSharingService bikeSharingService;

    private static final double SPEED_SCOOTER = 20.0;
    private static final double COST_BUS      = 1.00;

    @Value("${elerent.bike.unlock:1.00}")
    private double bikeUnlock;
    @Value("${elerent.bike.per-minute:0.29}")
    private double bikePerMin;
    @Value("${elerent.scooter.unlock:1.00}")
    private double scooterUnlock;
    @Value("${elerent.scooter.per-minute:0.25}")
    private double scooterPerMin;
    /**
     * Refundable hold taken at unlock. Deliberately NOT added to costEuros: it
     * comes back at the end of the ride, so counting it would make the scooter
     * look more expensive than it is and skew the budget ranking. It is only
     * disclosed, so the figure the traveller sees here matches the fare list.
     */
    @Value("${elerent.scooter.deposit:5.00}")
    private double scooterDeposit;

    /** How far before the deadline the first pass starts looking. */
    private static final int ARRIVE_BY_WINDOW_MIN = 120;

    /**
     * Plans a journey. With "arrive by", plans it backwards from the deadline.
     *
     * WHAT THIS USED TO DO
     * "Arrive by" moved the departure back by a flat 45 minutes and printed
     * "planned to arrive by HH:mm". Nothing checked the claim: a twenty-minute
     * trip arrived twenty-five minutes early, and an hour-long one arrived
     * fifteen minutes LATE under a message promising the opposite.
     *
     * HOW IT WORKS NOW
     * Two passes. The first starts a window before the deadline and exists only
     * to learn how long each option takes. The second is planned from
     * deadline − (longest of those), so the slowest option lands on the
     * deadline rather than an arbitrary distance from it. Options that still
     * arrive late are dropped: an option that misses the deadline is not an
     * answer to "get me there by then".
     *
     * WHY TWO PASSES AND NOT MORE
     * Only the bus depends on when you leave, and only through which run you
     * catch. A third pass would shift the base by minutes and could equally
     * well catch an earlier bus, so it converges on nothing. Each option
     * carries its own departure in the answer, so a quick option leaves late
     * and a slow one leaves early — which is what an arrival deadline means.
     */
    public JourneyResponse plan(JourneyRequest req) {
        if (!req.isArriveBy() || req.getDepartureTime() == null || req.getDepartureTime().isBlank())
            return planOnce(req);

        java.time.Instant deadline = resolveDepartureBase(req.getDepartureTime(), null, req.isItalian());

        JourneyRequest probe = copyRequest(req);
        probe.setArriveBy(false);
        probe.setBaseOverride(deadline.minus(ARRIVE_BY_WINDOW_MIN, java.time.temporal.ChronoUnit.MINUTES));
        JourneyResponse first = planOnce(probe);

        int longest = first.getOptions().stream()
                .map(JourneyOption::getDurationMinutes)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo).orElse(0);

        if (longest == 0) return first;   // nothing to plan backwards from

        JourneyRequest run = copyRequest(req);
        run.setArriveBy(false);
        run.setBaseOverride(deadline.minus(longest, java.time.temporal.ChronoUnit.MINUTES));
        JourneyResponse second = planOnce(run);

        return applyDeadline(second, deadline, run.getBaseOverride(), req);
    }

    /**
     * Stamps each option with its own departure and removes the ones that miss
     * the deadline.
     *
     * An option whose duration does not depend on when it starts — walking,
     * a bike, a scooter — is moved to leave exactly late enough to arrive on
     * time. A bus cannot be moved that way: its departure is the timetable's,
     * so it keeps the one it was planned with and is judged on whether that
     * gets the traveller there.
     */
    private JourneyResponse applyDeadline(JourneyResponse res, java.time.Instant deadline,
                                          java.time.Instant base, JourneyRequest req) {
        List<JourneyOption> kept = new ArrayList<>();
        boolean droppedAny = false;

        for (JourneyOption o : res.getOptions()) {
            Integer dur = o.getDurationMinutes();
            if (dur == null) { kept.add(o); continue; }

            if (hasBusLeg(o.getMode())) {
                java.time.Instant arrival = base.plus(dur, java.time.temporal.ChronoUnit.MINUTES);
                if (arrival.isAfter(deadline)) { droppedAny = true; continue; }
                o.setDepartsAt(base.toEpochMilli());
            } else {
                // Leave as late as the deadline allows
                o.setDepartsAt(deadline.minus(dur, java.time.temporal.ChronoUnit.MINUTES).toEpochMilli());
            }
            kept.add(o);
        }

        List<String> msgs = new ArrayList<>(res.getMessages() == null ? List.of() : res.getMessages());
        String hhmm = req.getDepartureTime();

        if (kept.isEmpty()) {
            msgs.add(req.isItalian()
                    ? "⏰ Nessuna soluzione arriva entro le " + hhmm + ". Prova un orario più tardi."
                    : "⏰ Nothing gets you there by " + hhmm + ". Try a later time.");
        } else {
            msgs.add(req.isItalian()
                    ? "⏰ Soluzioni che arrivano entro le " + hhmm + ", con l'orario di partenza di ciascuna."
                    : "⏰ Options that arrive by " + hhmm + ", each with its own departure time.");
            if (droppedAny)
                msgs.add(req.isItalian()
                        ? "Alcune soluzioni sono state escluse perché arrivavano dopo l'orario richiesto."
                        : "Some options were left out because they arrived after the time you asked for.");
        }

        return JourneyResponse.builder()
                .options(kept)
                .messages(msgs)
                .origin(res.getOrigin())
                .destination(res.getDestination())
                .totalOptions(kept.size())
                .realtimeAvailable(res.isRealtimeAvailable())
                .weatherSummary(res.getWeatherSummary())
                .weatherCondition(res.getWeatherCondition())
                .temperatureCelsius(res.getTemperatureCelsius())
                .build();
    }

    private JourneyResponse planOnce(JourneyRequest req) {
        log.info("Planning: {} → {}", req.getOriginName(), req.getDestName());

        WeatherService.WeatherData weather = weatherService.getCurrentWeather();
        boolean realtimeAvailable = cassitrackClient.isAvailable();

        List<String> modes = new ArrayList<>(
                (req.getModes() != null && !req.getModes().isEmpty())
                        ? req.getModes()
                        : List.of("BUS","BIKE","SCOOTER","WALK")
        );

        // The behavioural preferences always shape CUSTOM — it is the traveller's
        // own profile. Whether they reach FAST, BUDGET and ECO is their call:
        // those three each answer one question, and a profile quietly filtering
        // their results would make them answer a different one than their name
        // promises. Loaded once here and passed down, so every use agrees.
        UserPreferences prefs = activePreferences(req);

        boolean preferBike = false;
        int maxBikeWalk = 500;   // metres — overridden by the user preference
        if (prefs != null) {
            if (Boolean.FALSE.equals(prefs.getShowWalking())) {
                modes.remove("WALK");
            }
            preferBike = Boolean.TRUE.equals(prefs.getPreferBikeOverBus());
            if (prefs.getMaxBikeWalkMetres() != null && prefs.getMaxBikeWalkMetres() > 0) {
                maxBikeWalk = prefs.getMaxBikeWalkMetres();
            }
        }

        // Whether the traveller asked for the bus at all, remembered before the
        // preference below prunes the list. The combined options are built from
        // this rather than from `modes`: deferring the *plain* bus is the point of
        // preferBikeOverBus, but a mixed bus-and-vehicle trip is precisely what
        // that traveller wants, and reading the pruned list hid it from them.
        boolean busRequested = modes.contains("BUS");

        boolean busDeferred = preferBike && modes.contains("BIKE") && busRequested;
        if (busDeferred) modes.remove("BUS");

        List<JourneyOption> options = new ArrayList<>();
        List<String> msgs = new ArrayList<>();

        for (String mode : modes) {
            try {
                JourneyOption opt = switch (mode.toUpperCase()) {
                    case "BUS"     -> planBus(req, msgs, weather, false);
                    case "BIKE"    -> planBike(req, msgs, weather, maxBikeWalk);
                    case "SCOOTER" -> planScooter(req, msgs, weather, maxBikeWalk);
                    case "WALK"    -> planWalk(req, weather);
                    default -> null;
                };
                if (opt != null) {
                    options.add(opt);
                    if ("BUS".equals(mode.toUpperCase())) addBusAlternative(req, weather, opt, options);
                }
                else if ("BUS".equals(mode.toUpperCase())) {
                    msgs.add(req.isItalian()
                            ? "🚌 Nessuna linea bus disponibile o dati insufficienti per questo percorso."
                            : "🚌 No bus line available, or not enough data for this route.");
                }
            } catch (Exception e) {
                log.warn("Failed {} option: {}", mode, e.getMessage());
                if ("BUS".equals(mode.toUpperCase())) {
                    msgs.add(req.isItalian()
                            ? "🚌 Errore nel calcolo del percorso bus."
                            : "🚌 Something went wrong while working out the bus route.");
                }
            }
        }
        // A multimodal proposal, offered when it is worth reading. Combining is
        // only interesting if it beats travelling by a single mode: a card that
        // takes longer than the plain bus, and charges an unlock fee for the
        // privilege, is noise dressed as a choice.
        if (busRequested && (modes.contains("BIKE") || modes.contains("SCOOTER"))) {
            JourneyOption combined = planMultiModal(req, weather, maxBikeWalk);
            int fastestSimple = options.stream()
                    .mapToInt(JourneyOption::getDurationMinutes)
                    .min().orElse(Integer.MAX_VALUE);

            if (combined != null && combined.getDurationMinutes() < fastestSimple) {
                options.add(combined);
            } else if (combined != null) {
                log.debug("Combinazione scartata: {} min contro i {} min della soluzione semplice piu' rapida.",
                        combined.getDurationMinutes(), fastestSimple);
            } else {
                log.debug("Nessuna combinazione possibile: entrambi gli estremi sono su una fermata, "
                        + "oppure non c'e' un mezzo Elerent raggiungibile.");
            }
        }

        if (busDeferred) {
            boolean bikeAvailable = options.stream().anyMatch(o -> "BIKE".equals(o.getMode()));
            if (!bikeAvailable) {
                // la bici non è disponibile → calcola il bus come riserva
                try {
                    JourneyOption bus = planBus(req, msgs, weather, false);
                    if (bus != null) {
                        options.add(bus);
                        addBusAlternative(req, weather, bus, options);
                    }
                } catch (Exception e) {
                    log.warn("Failed BUS fallback option: {}", e.getMessage());
                }
            }

        }

        boolean raining =
                weather.condition == WeatherService.WeatherCondition.RAIN ||
                        weather.condition == WeatherService.WeatherCondition.HEAVY_RAIN;

        boolean rainPrefersBus = prefs == null || Boolean.TRUE.equals(prefs.getRainPrefersBus());

        // RAIN ORDERS, IT NO LONGER HIDES.
        // This used to delete every walking, bike and scooter option when it was
        // raining. That is the app deciding for the traveller — a five-minute
        // walk in light rain is theirs to choose — and on a route with no bus it
        // left the search with nothing at all. The bus now simply comes first,
        // and the reason is said out loud.
        if (raining && rainPrefersBus) {
            options.sort(
                    Comparator.comparingInt((JourneyOption o) -> exposedToRain(o.getMode()) ? 1 : 0)
                            .thenComparingInt(JourneyOption::getDurationMinutes));

            if (options.stream().anyMatch(o -> !exposedToRain(o.getMode())))
                msgs.add(req.isItalian()
                        ? "Piove: ti consigliamo il bus, non ti bagnerai. Le altre opzioni restano disponibili."
                        : "It is raining: we suggest the bus so you stay dry. The other options are still there.");
        } else {
            options.sort(Comparator.comparingInt(JourneyOption::getDurationMinutes));
        }

        // One pass over the final list, so every option gets the fourth criterion
        // regardless of which builder produced it
        options.forEach(o -> o.setReliabilityScore(
                reliabilityOf(o.getMode(), o.getTransferWaitMinutes(), o.getDelayMinutes())));

        return JourneyResponse.builder()
                .options(options)
                .messages(msgs)
                .origin(req.getOriginName() != null ? req.getOriginName() : "Origin")
                .destination(req.getDestName() != null ? req.getDestName() : "Destination")
                .totalOptions(options.size())
                .realtimeAvailable(realtimeAvailable)
                .weatherSummary(weather.suggestion)
                .weatherCondition(weather.condition != null ? weather.condition.name() : null)
                .temperatureCelsius(weather.tempCelsius)
                .build();
    }


    // NESSUN PUNTEGGIO MULTI-CRITERIO
    // Ogni profilo pesava le tre metriche (tempo/costo/ambiente) e il frontend
    // ordinava per il punteggio risultante. Il peso rendeva l'ordine difficile
    // da spiegare: con FAST a 0.70/0.10/0.20 un'opzione piu' lenta poteva
    // precederne una piu' veloce perche' costava meno o inquinava meno, che e'
    // esattamente cio' che il chip "il piu' veloce" dice di non fare.
    //
    // Adesso l'ordinamento e' quello letterale del chip, e vive nel frontend
    // (sortOptions): FAST per durata crescente, BUDGET per costo crescente,
    // ECO per green index decrescente. A parita' esatta l'ordine di arrivo
    // resta invariato — il sort di JavaScript e' stabile — quindi il risultato
    // e' comunque deterministico senza reintrodurre un peso nascosto.

    /** Keeps the three existing call sites on the ordinary behaviour. */
    /**
     * Offers the itinerary with a change alongside the direct one.
     *
     * WHY IT IS NOT ALWAYS ADDED
     * A change costs a second fare and can be missed, so against a direct run
     * it is worse on price and worse on reliability by construction. Its only
     * possible advantage is time — a direct line that takes the long way round
     * loses to a change that cuts across. If it is not faster it is beaten on
     * every criterion at once, and adding it would be noise, not a choice.
     *
     * WHY ONLY WHEN THE FIRST OPTION IS DIRECT
     * With no direct line planBus already returns the change; asking again
     * would recompute the same itinerary, live waits and all.
     *
     * Its messages are thrown away: they describe the same stop and the same
     * lines the first pass already reported, and the traveller should not read
     * them twice.
     */
    private void addBusAlternative(JourneyRequest req, WeatherService.WeatherData weather,
                                   JourneyOption direct, List<JourneyOption> options) {
        if (direct.getTransferWaitMinutes() != null) return;   // already the change

        try {
            JourneyOption viaChange = planBus(req, new ArrayList<>(), weather, true);
            if (viaChange == null || viaChange.getTransferWaitMinutes() == null) return;
            // nz() takes a Double; these are Integer minutes, and an unknown
            // duration cannot be claimed to be faster
            Integer viaMin = viaChange.getDurationMinutes();
            Integer dirMin = direct.getDurationMinutes();
            if (viaMin == null || dirMin == null || viaMin >= dirMin) return;

            options.add(viaChange);
        } catch (Exception e) {
            // The direct option stands on its own; the alternative is a bonus
            log.warn("Failed BUS-with-change alternative: {}", e.getMessage());
        }
    }

    private JourneyOption planBus(JourneyRequest req, List<String> msgs,
                                  WeatherService.WeatherData weather) {
        return planBus(req, msgs, weather, false);
    }

    /**
     * @param viaChange build the itinerary with an interchange even when a
     *                  direct line exists. The direct run is otherwise always
     *                  preferred, which used to mean the traveller never saw
     *                  the two alternatives side by side — and left the
     *                  reliability criterion with nothing to choose between.
     */
    private JourneyOption planBus(JourneyRequest req, List<String> msgs,
                                  WeatherService.WeatherData weather,
                                  boolean viaChange) {

        String nearestStop = req.getOriginStopId() != null
                ? req.getOriginStopId()
                : findNearestStopId(req.getOriginLat(), req.getOriginLon());

        String destStop = req.getDestStopId() != null
                ? req.getDestStopId()
                : findNearestStopId(req.getDestLat(), req.getDestLon());

        // Punto 3: base oraria del viaggio e stato del flag google.search.
        boolean isNow = req.getBaseOverride() == null
                && (req.getDepartureTime() == null || req.getDepartureTime().isBlank());
        java.time.Instant departureBase = req.getBaseOverride() != null
                ? req.getBaseOverride()
                : resolveDepartureBase(req.getDepartureTime(), msgs, req.isItalian());

        boolean useGoogle = googleApiSettings.isSearchEnabled();

        // Il ritardo ha senso solo per una ricerca "adesso": per un viaggio
        // futuro il ritardo attuale di un bus che gira ora non significa nulla.


        // --- Step 1: walk to bus stop ---
        // Walk whenever the journey does not START AT a stop, not merely when it
        // starts from a GPS fix. That was the same thing until a traveller could
        // tap a place on the map: with no stop id and no GPS flag, the itinerary
        // began at the nearest stop as though they were already standing there,
        // and the walk to reach it vanished from both the steps and the total.
        boolean fromLoosepoint = req.getOriginStopId() == null;

        double walkMetres = 0;
        int walkMin = 0;
        List<double[]> walkPoints = List.of();
        if (fromLoosepoint) {
            var w = walkStretch(req.getOriginLat(), req.getOriginLon(),
                                getStopLat(nearestStop), getStopLon(nearestStop));
            walkMetres = w.metres();
            walkMin    = w.minutes();
            walkPoints = w.points();
            if (!w.routed()) {
                msgs.add(req.isItalian()
                        ? "🚶 Percorso a piedi fino alla fermata " + fmtStop(nearestStop)
                            + " stimato in linea d'aria: Google Maps non è disponibile."
                        : "🚶 Walk to stop " + fmtStop(nearestStop)
                            + " estimated as the crow flies: Google Maps is unavailable.");
            }
        }
        // --- Step 2+3: la linea e la sua attesa si calcolano insieme ---
        var direct = scheduledStopRepository.findLinesConnecting(nearestStop, destStop);

        String lineLabel;
        int busMin;
        int waitMin = 5;                       // attesa iniziale, assegnata nei rami
        // Slack at the interchange, for the reliability criterion. Stays null on a
        // direct run: there is no connection to miss, which is not the same as a
        // margin of zero.
        Integer transferWait = null;
        // What a delay watcher has to follow: the runs actually ridden, and the
        // stops ahead of them where their delay can be read
        String boardedRouteId  = null;
        String boardedTripId   = null;
        String changeStopId    = null;
        String changeTripId    = null;
        double busMetres ;     // default per cambio/ripiego
        List<JourneyLeg> busLegs = new ArrayList<>();
        DelayInfo busDelay = DelayInfo.none();

        if (!direct.isEmpty() && !viaChange) {
            var line = direct.get(0);
            boardedRouteId = line.getRouteId();
            boardedTripId  = line.getTripId();
            lineLabel = line.getShortName() + " → " + line.getLongName();
            DelayInfo[] delayOut = { DelayInfo.none() };
            waitMin = waitMinutesForLine(nearestStop, line.getRouteId(), line.getShortName(),
                    msgs, delayOut, departureBase, isNow, req.isItalian());
            busDelay = delayOut[0];

            java.time.Instant boarding = departureBase.plusSeconds(60L * waitMin);
            SegTime seg = busTimeBySegments(line.getTripId(), nearestStop, destStop,
                    useGoogle ? boarding : null);
            if (seg == null) {
                log.warn("BUS: sequenza non risolvibile per trip {}", line.getTripId());
                return null;
            }
            busMin    = seg.minutes();
            busMetres = seg.metres();

            String tripId = line.getTripId();
            StopSlice slice0 = stopSliceBetween(tripId, nearestStop, destStop);
            busLegs.add(JourneyLeg.builder().mode("BUS")
                    .from(fmtStop(nearestStop)).to(req.getDestName())
                    .durationMinutes(busMin).distanceMetres(busMetres)
                    .instruction(lineLabel)
                    .stopCoords(slice0.coords())
                    .stopNames(slice0.names())
                    .busStopCoords(slice0.busStopCoords())
                    .routeId(line.getRouteId())
                    .build());
        } else {
            Transfer t = findBestTransfer(nearestStop, destStop);
            if (t != null) {
                DelayInfo[] delayOut = { DelayInfo.none() };
                waitMin    = waitMinutesForLine(nearestStop, t.l1RouteId(), t.l1Short(),
                        msgs, delayOut, departureBase, isNow, req.isItalian());
                busDelay = delayOut[0];

                java.time.Instant boarding1 = departureBase.plusSeconds(60L * waitMin);
                SegTime s1 = busTimeBySegments(t.l1TripId(), nearestStop, t.stop(),
                        useGoogle ? boarding1 : null);

                // L'attesa al cambio si valuta al momento in cui ci ARRIVI davvero,
                // non alla partenza: per questo serve prima la durata della tratta 1.
                int firstLegMin = (s1 != null) ? s1.minutes() : t.l1Min();
                java.time.Instant atTransfer = boarding1.plusSeconds(60L * firstLegMin);
                boolean liveAtTransfer = isNow && !atTransfer.isAfter(
                        java.time.Instant.now().plusSeconds(15 * 60));
                int changeWait = waitMinutesForLine(t.stop(), t.l2RouteId(), t.l2Short(),
                        msgs, null, atTransfer, liveAtTransfer, req.isItalian());
                transferWait   = changeWait;
                boardedRouteId = t.l1RouteId();
                boardedTripId  = t.l1TripId();
                changeStopId   = t.stop();
                changeTripId   = t.l2TripId();

                java.time.Instant boarding2 = atTransfer.plusSeconds(60L * changeWait);
                SegTime s2 = busTimeBySegments(t.l2TripId(), t.stop(), destStop,
                        useGoogle ? boarding2 : null);

                int    l1Min = (s1 != null) ? s1.minutes() : t.l1Min();
                int    l2Min = (s2 != null) ? s2.minutes() : t.l2Min();
                double m1    = (s1 != null) ? s1.metres()  : 0.0;
                double m2    = (s2 != null) ? s2.metres()  : 0.0;

                lineLabel = t.l1Label() + " + " + t.l2Label();
                busMin    = l1Min + changeWait + l2Min;
                busMetres = m1 + m2;

                StopSlice slice1 = stopSliceBetween(t.l1TripId(), nearestStop, t.stop());
                StopSlice slice2 = stopSliceBetween(t.l2TripId(), t.stop(), destStop);
                busLegs.add(JourneyLeg.builder().mode("BUS")
                        .from(fmtStop(nearestStop)).to(fmtStop(t.stop()))
                        .durationMinutes(l1Min).distanceMetres(m1)
                        .instruction(t.l1Label())
                        .stopCoords(slice1.coords())
                        .stopNames(slice1.names())
                        .busStopCoords(slice1.busStopCoords())
                        .routeId(t.l1RouteId())
                        .build());
                // The change is described by structured fields; the client writes the
                // sentence in the traveller's own language from `from` and transfer_line.
                busLegs.add(JourneyLeg.builder().mode("WAIT")
                        .from(fmtStop(t.stop())).to(fmtStop(t.stop()))
                        .durationMinutes(changeWait).distanceMetres(0.0)
                        .transfer(true).transferLine(t.l2Label()).build());
                busLegs.add(JourneyLeg.builder().mode("BUS")
                        .from(fmtStop(t.stop())).to(req.getDestName())
                        .durationMinutes(l2Min).distanceMetres(m2)
                        .instruction(t.l2Label())
                        .stopCoords(slice2.coords())
                        .stopNames(slice2.names())
                        .busStopCoords(slice2.busStopCoords())
                        .routeId(t.l2RouteId())
                        .build());
            } else {
                log.warn("BUS: nessuna linea diretta né cambio trovato tra {} e {}", nearestStop, destStop);
                return null;
            }
        }
        if (!useGoogle) {
            msgs.add(req.isItalian()
                    ? "ℹ️ Traffico in tempo reale disattivato — gli orari dei bus vengono dalla tabella, non dal live."
                    : "ℹ️ Live traffic is off — bus times are from the timetable, not real-time.");
        }

        // Mirror of the walk that opens a GPS-origin trip: when the destination is the
        // traveller's own position the bus can only reach the nearest stop, so the last
        // stretch is on foot and has to count towards the total.
        // Mirror of the rule above: the last stretch is on foot whenever the
        // journey does not END AT a stop
        double destWalkMetres = 0;
        int destWalkMin = 0;
        List<double[]> destWalkPoints = List.of();
        if (req.getDestStopId() == null) {
            var w = walkStretch(getStopLat(destStop), getStopLon(destStop),
                                req.getDestLat(), req.getDestLon());
            destWalkMetres = w.metres();
            destWalkMin    = w.minutes();
            destWalkPoints = w.points();
            if (!w.routed()) {
                msgs.add(req.isItalian()
                        ? "🚶 Ultimo tratto a piedi dalla fermata " + fmtStop(destStop)
                            + " stimato in linea d'aria: Google Maps non è disponibile."
                        : "🚶 Final walk from stop " + fmtStop(destStop)
                            + " estimated as the crow flies: Google Maps is unavailable.");
            }
        }

        int total = walkMin + waitMin + busMin + destWalkMin;
        List<JourneyLeg> legs = new ArrayList<>();
        if (walkMin > 0) legs.add(JourneyLeg.builder().mode("WALK")
                .from(req.getOriginName()).to(fmtStop(nearestStop))
                .durationMinutes(walkMin).distanceMetres(walkMetres)
                .stopCoords(walkPoints)
                .instruction("Walk " + fmtDist(walkMetres) + " to " + fmtStop(nearestStop))
                .build());
        legs.add(JourneyLeg.builder().mode("WAIT")
                .from(fmtStop(nearestStop)).to(fmtStop(nearestStop))
                .durationMinutes(waitMin).distanceMetres(0.0)
                .instruction("Wait " + waitMin + " min for " + lineLabel).build());
        legs.addAll(busLegs);
        if (destWalkMin > 0) legs.add(JourneyLeg.builder().mode("WALK")
                .from(fmtStop(destStop)).to(req.getDestName())
                .durationMinutes(destWalkMin).distanceMetres(destWalkMetres)
                .stopCoords(destWalkPoints)
                .instruction("Walk " + fmtDist(destWalkMetres) + " to " + req.getDestName())
                .build());

        String occupancyWarning = null;
        {
            var prefs = activePreferences(req);
            boolean avoid = prefs != null && Boolean.TRUE.equals(prefs.getAvoidHighOccupancy());
            int threshold = (prefs != null && prefs.getOccupancyThresholdPct() != null)
                    ? prefs.getOccupancyThresholdPct() : 80;
            if (avoid) {
                boolean highOccupancy = cassitrackClient.getActiveVehicles().stream()
                        .anyMatch(v -> occupancyPct(v.getCrowdingLevel()) >= threshold);
                if (highOccupancy) occupancyWarning = "⚠️ Over " + threshold + "% full";
            }
        }

        // One fare per bus boarded: a journey with a change costs two tickets, not
        // one. This charged a flat COST_BUS however many buses the itinerary
        // used, so a two-leg trip was quoted at the price of a single ride and
        // came out cheaper than it is — which also skewed the Budget ranking
        // against direct routes.
        //
        // Counted from the legs that were actually built, not from a transfer
        // flag, so it stays correct if an itinerary ever needs two changes. The
        // floor of one guards the ranking: a zero-cost bus option would win
        // Budget outright.
        long busRides = busLegs.stream().filter(l -> "BUS".equals(l.getMode())).count();
        double busCost = COST_BUS * Math.max(1L, busRides);

        return JourneyOption.builder()
                .mode("BUS").modeLabel(lineLabel)
                .durationMinutes(total).distanceMetres(busMetres + walkMetres + destWalkMetres)
                .costEuros(busCost)
                .greenIndex(greenIndex.computeGreenIndex("BUS", busMetres / 1000.0))
                .co2Grams(greenIndex.computeCo2Grams("BUS", busMetres / 1000.0))
                .etaMinutes(total)
                .delayMinutes(isNow && busDelay != null ? busDelay.delayMinutes() : null)
                .delayStatus(isNow && busDelay != null ? busDelay.status() : null)
                .delayRealTime(isNow && busDelay != null ? busDelay.realTime() : null)
                .delayAtStop(isNow && busDelay != null ? busDelay.atStop() : null)
                .delayLabel(isNow ? delayLabel(busDelay) : null)
                .transferWaitMinutes(transferWait)
                .boardingStopId(nearestStop)
                .busRouteId(boardedRouteId)
                .boardingTripId(boardedTripId)
                .alightStopId(destStop)
                .transferStopId(changeStopId)
                .transferTripId(changeTripId)
                .summary("Take " + lineLabel + " from " + fmtStop(nearestStop)
                        + (walkMin > 0 ? " (" + fmtDist(walkMetres) + " walk)" : ""))
                .weatherWarning(occupancyWarning != null ? occupancyWarning
                        : weatherService.getModeWarning(weather.condition, "BUS"))
                .weatherSuggestion(weather.suggestion)
                .legs(legs).build();
    }

    private JourneyOption planBike(JourneyRequest req, List<String> msgs,
                                   WeatherService.WeatherData weather, int maxWalkM) {
        return planSharedVehicle(req, msgs, weather, maxWalkM,
                "BIKE", "Elerent Bike Share", 15.0, bikeUnlock, bikePerMin, 0.0, "🚲", "bike");
    }

    private JourneyOption planScooter(JourneyRequest req, List<String> msgs,
                                      WeatherService.WeatherData weather, int maxWalkM) {
        return planSharedVehicle(req, msgs, weather, maxWalkM,
                "SCOOTER", "Elerent E-Scooter", SPEED_SCOOTER, scooterUnlock, scooterPerMin,
                scooterDeposit, "🛴", "e-scooter");
    }

    /**
     * Shared-vehicle option grounded in real Elerent availability:
     * WALK leg to the nearest available vehicle + BIKE/SCOOTER leg to the
     * destination. Returns null (with a user message) when no vehicle is
     * within maxWalkM of the origin — plan()'s preferBikeOverBus fallback
     * then re-plans the bus automatically. Battery never filters a vehicle
     * out: it travels in bike_battery_pct and is rendered as battery bars.
     */
    private JourneyOption planSharedVehicle(JourneyRequest req, List<String> msgs,
            WeatherService.WeatherData weather, int maxWalkM, String mode, String label,
            double speedKmh, double unlock, double perMin, double deposit,
            String emoji, String noun) {

        var nearest = bikeSharingService.findNearest(
                req.getOriginLat(), req.getOriginLon(), mode, maxWalkM);
        if (nearest.isEmpty()) {
            msgs.add(req.isItalian()
                    ? emoji + " Nessun mezzo Elerent (" + noun + ") disponibile entro "
                        + maxWalkM + " m dall'origine."
                    : emoji + " No Elerent " + noun + " available within "
                        + maxWalkM + " m of your origin.");
            return null;
        }
        var v = nearest.get().vehicle();
        int airM = nearest.get().walkMetres();
        String vehName = v.getPlate() != null ? v.getPlate() : v.getBikeId();

        List<JourneyLeg> legs = new ArrayList<>();
        int walkMin = 0;
        double walkM = 0;
        if (airM >= 40) {   // vehicle basically at the origin → skip the walk leg
            var w = walkStretch(req.getOriginLat(), req.getOriginLon(), v.getLat(), v.getLon());
            walkM   = w.metres();
            walkMin = w.minutes();
            legs.add(JourneyLeg.builder().mode("WALK")
                    .from(req.getOriginName()).to("Elerent " + vehName)
                    .durationMinutes(walkMin).distanceMetres(walkM)
                    .stopCoords(w.points())
                    .instruction("Walk " + fmtDist(walkM) + " to " + noun + " " + vehName)
                    .build());
        }

        // A destination outside the operating area — or inside a no-parking zone —
        // is not somewhere the ride may end. The planner used to route the whole
        // ride there anyway and then attach a warning saying it was impossible,
        // which is a plan nobody can follow. Instead the ride stops at the nearest
        // legal point and the last stretch is walked, the way anyone would do it.
        var dropOff = bikeSharingService.findLegalDropOff(req.getDestLat(), req.getDestLon());
        final double rideLat = dropOff.map(BikeSharingService.DropOff::lat).orElse(req.getDestLat());
        final double rideLon = dropOff.map(BikeSharingService.DropOff::lon).orElse(req.getDestLon());
        String rideEndName = dropOff.isPresent()
                ? (req.isItalian() ? "Limite area Elerent" : "Elerent area edge")
                : req.getDestName();

        // Google has no scooter profile: bicycling is the closest road network,
        // and the scooter keeps its own speed for the timing below.
        var r = googleMapsService.getRoute(
                v.getLat(), v.getLon(), rideLat, rideLon, "bicycling");
        double rideM = r.map(g -> (double) g.distanceMetres())
                        .orElseGet(() -> {
                            log.warn("{}: Google non disponibile — uso stima haversine", mode);
                            return haversineMetres(v.getLat(), v.getLon(), rideLat, rideLon) * 1.25;
                        });
        // Google's "bicycling" duration fits bikes; scooters keep their own speed
        int rideMin = "BIKE".equals(mode)
                ? r.map(g -> (int) Math.ceil(g.durationSeconds() / 60.0))
                   .orElse((int) Math.ceil(rideM / 1000.0 / speedKmh * 60))
                : (int) Math.ceil(rideM / 1000.0 / speedKmh * 60);
        double cost = Math.round((unlock + rideMin * perMin) * 100) / 100.0;

        List<double[]> ridePoints = r.map(GoogleMapsService.RouteResult::points)
                .filter(pts -> pts.size() >= 2)
                .orElse(List.of(new double[]{v.getLat(), v.getLon()},
                                new double[]{rideLat, rideLon}));

        legs.add(JourneyLeg.builder().mode(mode)
                .from(legs.isEmpty() ? req.getOriginName() : "Elerent " + vehName)
                .to(rideEndName)
                .durationMinutes(rideMin).distanceMetres(rideM)
                .stopCoords(ridePoints)
                .instruction("Elerent " + noun + " " + vehName + " · Unlock €" + unlock
                        + " + €" + perMin + "/min"
                        + (deposit > 0 ? " · €" + deposit + " refundable hold" : "")
                        + " · elerent.it")
                .build());

        // The stretch the vehicle is not allowed to cover
        final WalkStretch lastWalk = dropOff.isPresent()
                ? walkStretch(rideLat, rideLon, req.getDestLat(), req.getDestLon())
                : null;
        if (lastWalk != null) {
            legs.add(JourneyLeg.builder().mode("WALK")
                    .from(rideEndName).to(req.getDestName())
                    .durationMinutes(lastWalk.minutes()).distanceMetres(lastWalk.metres())
                    .stopCoords(lastWalk.points())
                    .instruction("Walk " + fmtDist(lastWalk.metres()) + " to " + req.getDestName())
                    .build());
        }

        String zoneWarning = dropOff.map(d -> switch (d.reason()) {
            case OUT_OF_OPERATING_AREA -> req.isItalian()
                    ? "ℹ️ Destinazione fuori dalla zona operativa Elerent: la corsa termina al limite dell'area, "
                        + "ultimi " + fmtDist(lastWalk.metres()) + " a piedi."
                    : "ℹ️ Destination outside the Elerent operating area: the ride ends at the boundary, "
                        + "last " + fmtDist(lastWalk.metres()) + " on foot.";
            case NO_PARKING -> req.isItalian()
                    ? "ℹ️ Destinazione in zona divieto di sosta Elerent: si lascia il mezzo ai margini della zona, "
                        + "ultimi " + fmtDist(lastWalk.metres()) + " a piedi."
                    : "ℹ️ Destination inside an Elerent no-parking zone: leave the vehicle at the zone edge, "
                        + "last " + fmtDist(lastWalk.metres()) + " on foot.";
        }).orElse(null);

        int lastWalkMin = lastWalk != null ? lastWalk.minutes() : 0;
        double lastWalkM = lastWalk != null ? lastWalk.metres() : 0;

        int totalMin = walkMin + rideMin + lastWalkMin;
        String summary = ("SCOOTER".equals(mode) ? "🛴 " : "") + "Elerent " + noun + " " + vehName
                + (walkMin > 0 ? (req.isItalian() ? " a " : " at ") + fmtDist(walkM) : "")
                + (lastWalkMin > 0
                    ? " + " + fmtDist(lastWalkM) + (req.isItalian() ? " a piedi" : " on foot")
                    : "")
                + " — " + totalMin + " min (~€" + String.format("%.2f", cost) + ")";

        return JourneyOption.builder()
                .mode(mode).modeLabel(label)
                .durationMinutes(totalMin).distanceMetres(walkM + rideM + lastWalkM)
                .costEuros(cost).greenIndex(100).co2Grams(0.0).etaMinutes(totalMin)
                .summary(summary)
                .weatherWarning(weatherService.getModeWarning(weather.condition, mode))
                .weatherSuggestion(weather.suggestion)
                .bikeId(v.getBikeId()).bikePlate(v.getPlate())
                .bikeBatteryPct(v.getBatteryPct()).bikeWalkMetres((int) Math.round(walkM))
                .bikeWarning(zoneWarning)
                .legs(legs)
                .build();
    }

    /**
     * The combined proposal: one search over every way of chaining the bus and a
     * shared vehicle, not a handful of shapes tried in turn.
     *
     * The network is treated as a graph. Its nodes are the traveller's origin,
     * the traveller's destination and every stop; its edges are the two things
     * that actually move you — a bus ride between stops a line connects, and a
     * shared-vehicle ride between any two points where a vehicle is waiting.
     * Walking never appears as an edge: both sub-planners already walk you to
     * the stop or to the vehicle, and duplicating that here would count it twice.
     *
     * Dijkstra then answers the only question worth asking — what is the quickest
     * way through — with the search state carrying, besides the node, whether a
     * bus and a vehicle have been used yet. The answer read off the destination
     * is therefore the fastest chain that genuinely combines both, which is a
     * different thing from the fastest chain overall (that one is usually the
     * plain bus, and it already has its own card).
     *
     * Costs during the search are estimates: timetable seconds for the bus,
     * straight-line distance over the vehicle's speed for the ride. Only the
     * winning chain is then planned for real, leg by leg, through the same
     * planners the standalone options use. That is what keeps the routing calls
     * bounded while the search itself stays exhaustive.
     */
    private JourneyOption planMultiModal(JourneyRequest req, WeatherService.WeatherData weather,
                                         int maxWalkM) {

        final String ORIGIN = "@origin", DEST = "@dest";

        // ── Nodes ───────────────────────────────────────────────────────────
        Map<String, double[]> at = new HashMap<>();
        at.put(ORIGIN, new double[]{req.getOriginLat(), req.getOriginLon()});
        at.put(DEST,   new double[]{req.getDestLat(),   req.getDestLon()});
        for (it.unicas.omnimove.model.Stop s : stopRepository.findAll()) {
            if (s.getLat() != null && s.getLon() != null) {
                at.put(s.getId(), new double[]{s.getLat(), s.getLon()});
            }
        }

        // The endpoints are points, not stops, so a bus leg that starts or ends
        // at one of them runs from the stop the planner would walk to anyway.
        String originStop = firstOrNull(nearestStopIds(req.getOriginLat(), req.getOriginLon(), 1));
        String destStop   = firstOrNull(nearestStopIds(req.getDestLat(),   req.getDestLon(),   1));

        // ── Edges ───────────────────────────────────────────────────────────
        Map<String, List<Edge>> out = new HashMap<>();

        // Bus: every pair a single run connects, plus the two endpoint stops
        // standing in for the endpoints themselves.
        for (ScheduledStopRepository.StopHop h : scheduledStopRepository.findAllDirectHops()) {
            if (!at.containsKey(h.getOrigin()) || !at.containsKey(h.getDest())) continue;
            double minutes = h.getSeconds() / 60.0 + BOARDING_WAIT_MIN;
            addEdge(out, h.getOrigin(), h.getDest(), "BUS", null, minutes);
            if (h.getOrigin().equals(originStop)) {
                addEdge(out, ORIGIN, h.getDest(), "BUS", null,
                        minutes + walkMinutes(at.get(ORIGIN), at.get(h.getOrigin())));
            }
            if (h.getDest().equals(destStop)) {
                addEdge(out, h.getOrigin(), DEST, "BUS", null,
                        minutes + walkMinutes(at.get(h.getDest()), at.get(DEST)));
            }
        }

        // Shared vehicle: from anywhere one is waiting, to anywhere worth riding to.
        for (String vehicleMode : List.of("BIKE", "SCOOTER")) {
            if (!hasMode(req, vehicleMode)) continue;
            double speed = "SCOOTER".equals(vehicleMode) ? SPEED_SCOOTER : 15.0;

            for (Map.Entry<String, double[]> from : at.entrySet()) {
                if (DEST.equals(from.getKey())) continue;
                var nearest = bikeSharingService.findNearest(
                        from.getValue()[0], from.getValue()[1], vehicleMode, maxWalkM);
                if (nearest.isEmpty()) continue;

                var v = nearest.get().vehicle();
                double toVehicle = walkMinutes(from.getValue(), new double[]{v.getLat(), v.getLon()});

                for (Map.Entry<String, double[]> to : at.entrySet()) {
                    if (to.getKey().equals(from.getKey()) || ORIGIN.equals(to.getKey())) continue;

                    // How far the ride carries you is measured between the two
                    // points, not from wherever the vehicle happens to be parked.
                    // A bike 440 m away, ridden back to the stop you are already
                    // standing at, "travels" 440 m and arrives nowhere — which is
                    // how a bus that reached the destination ended up followed by
                    // a loop out to a bike and back to the same corner.
                    double progress = haversineMetres(from.getValue()[0], from.getValue()[1],
                                                      to.getValue()[0], to.getValue()[1]);
                    if (progress < MIN_VEHICLE_LEG_M || progress > MAX_BRIDGE_M) continue;

                    double ride = haversineMetres(v.getLat(), v.getLon(),
                                                  to.getValue()[0], to.getValue()[1]) * 1.25;
                    addEdge(out, from.getKey(), to.getKey(), vehicleMode, vehicleMode,
                            toVehicle + ride / 1000.0 / speed * 60);
                }
            }
        }

        // ── Search ──────────────────────────────────────────────────────────
        List<Edge> path = quickestCombination(out, ORIGIN, DEST);
        if (path == null) return null;

        // ── Plan the winner for real ────────────────────────────────────────
        return planChain(req, weather, maxWalkM, path, at, ORIGIN, DEST);
    }

    /** A move between two nodes: which mode, and how long we think it takes. */
    private record Edge(String from, String to, String mode, String vehicleMode, double minutes) {}

    private static void addEdge(Map<String, List<Edge>> out, String from, String to,
                                String mode, String vehicleMode, double minutes) {
        out.computeIfAbsent(from, k -> new ArrayList<>())
           .add(new Edge(from, to, mode, vehicleMode, minutes));
    }

    /**
     * Dijkstra whose state is the node plus what has been used to get there: a
     * bus yet, a vehicle yet, and how many legs. Reading the destination with
     * both flags set gives the quickest chain that actually combines the two —
     * asking only for the quickest chain would return the plain bus.
     */
    private static List<Edge> quickestCombination(Map<String, List<Edge>> out, String origin, String dest) {
        record State(String node, boolean bus, boolean vehicle, int legs) {}

        Map<State, Double> best = new HashMap<>();
        Map<State, Edge>   via  = new HashMap<>();
        Map<State, State>  prev = new HashMap<>();

        State start = new State(origin, false, false, 0);
        best.put(start, 0.0);

        PriorityQueue<State> queue =
                new PriorityQueue<>(Comparator.comparingDouble(s -> best.getOrDefault(s, Double.MAX_VALUE)));
        queue.add(start);

        State goal = null;
        double goalCost = Double.MAX_VALUE;

        while (!queue.isEmpty()) {
            State s = queue.poll();
            double cost = best.getOrDefault(s, Double.MAX_VALUE);
            if (cost > goalCost) break;

            if (s.node().equals(dest) && s.bus() && s.vehicle() && cost < goalCost) {
                goal = s;
                goalCost = cost;
                continue;
            }
            if (s.legs() >= MAX_COMBINED_LEGS) continue;

            for (Edge e : out.getOrDefault(s.node(), List.of())) {
                State n = new State(e.to(),
                        s.bus()     || "BUS".equals(e.mode()),
                        s.vehicle() || !"BUS".equals(e.mode()),
                        s.legs() + 1);
                double alt = cost + e.minutes();
                if (alt < best.getOrDefault(n, Double.MAX_VALUE)) {
                    best.put(n, alt);
                    via.put(n, e);
                    prev.put(n, s);
                    queue.add(n);
                }
            }
        }
        if (goal == null) return null;

        LinkedList<Edge> path = new LinkedList<>();
        for (State s = goal; via.containsKey(s); s = prev.get(s)) path.addFirst(via.get(s));
        return path;
    }

    /**
     * Turns the winning chain into a real itinerary: consecutive bus edges become
     * one bus plan, each vehicle edge one Elerent plan, and the endpoints keep
     * the traveller's own origin and destination so the walk to the first stop
     * and from the last one are the real ones.
     */
    private JourneyOption planChain(JourneyRequest req, WeatherService.WeatherData weather,
                                    int maxWalkM, List<Edge> path,
                                    Map<String, double[]> at, String ORIGIN, String DEST) {
        List<JourneyOption> segments = new ArrayList<>();
        List<String> quiet = new ArrayList<>();
        String vehicleMode = null;
        StringBuilder story = new StringBuilder();

        int i = 0;
        while (i < path.size()) {
            Edge e = path.get(i);

            if ("BUS".equals(e.mode())) {
                // a run of bus edges is one journey by bus, changes included
                int j = i;
                while (j + 1 < path.size() && "BUS".equals(path.get(j + 1).mode())) j++;
                Edge last = path.get(j);

                JourneyRequest leg = legRequest(req, at, e.from(), last.to(), ORIGIN, DEST);
                JourneyOption bus = planBus(leg, quiet, weather);
                if (bus == null) return null;
                segments.add(bus);
                story.append(story.length() == 0 ? "" : ", ")
                     .append(req.isItalian() ? "bus fino a " : "bus to ")
                     .append(nodeName(req, at, last.to(), ORIGIN, DEST));
                i = j + 1;

            } else {
                vehicleMode = e.vehicleMode();
                JourneyRequest leg = legRequest(req, at, e.from(), e.to(), ORIGIN, DEST);
                JourneyOption ride = rideOption(leg, quiet, weather, maxWalkM, vehicleMode);
                if (ride == null) return null;
                segments.add(ride);
                story.append(story.length() == 0 ? "" : ", ")
                     .append(vehicleWord(req, vehicleMode))
                     .append(req.isItalian() ? " fino a " : " to ")
                     .append(nodeName(req, at, e.to(), ORIGIN, DEST));
                i++;
            }
        }
        if (segments.size() < 2 || vehicleMode == null) return null;

        // The card's mode id has to be one the frontend has an icon and a button
        // for, and a chain of four legs could otherwise invent names nobody knows.
        // Which mode opens the trip is the distinction that survives.
        String mode = "BUS".equals(path.get(0).mode())
                ? "BUS_" + vehicleMode
                : vehicleMode + "_BUS";

        return stitch(req, weather, vehicleMode, segments, mode,
                segments.stream().map(JourneyOption::getModeLabel).collect(Collectors.joining(" + ")),
                capitalise(story.toString()),
                "🚌+" + ("SCOOTER".equals(vehicleMode) ? "🛴" : "🚲"));
    }

    /** A sub-request for one leg, keeping the traveller's real endpoints at the ends. */
    private JourneyRequest legRequest(JourneyRequest req, Map<String, double[]> at,
                                      String from, String to, String ORIGIN, String DEST) {
        JourneyRequest leg = copyRequest(req);
        if (!ORIGIN.equals(from)) {
            double[] p = at.get(from);
            leg.setOriginLat(p[0]); leg.setOriginLon(p[1]);
            leg.setOriginName(fmtStop(from)); leg.setOriginStopId(from); leg.setOriginIsGps(false);
        }
        if (!DEST.equals(to)) {
            double[] p = at.get(to);
            leg.setDestLat(p[0]); leg.setDestLon(p[1]);
            leg.setDestName(fmtStop(to)); leg.setDestStopId(to); leg.setDestIsGps(false);
        }
        return leg;
    }

    private String nodeName(JourneyRequest req, Map<String, double[]> at,
                            String node, String ORIGIN, String DEST) {
        if (DEST.equals(node))   return req.getDestName();
        if (ORIGIN.equals(node)) return req.getOriginName();
        return fmtStop(node);
    }

    /** Waiting for the bus, averaged: the search compares routes, not departures. */
    private static final double BOARDING_WAIT_MIN = 5.0;

    /** Slack at which an interchange stops feeling tight, in minutes. */
    private static final double COMFORTABLE_CHANGE_MIN = 10.0;

    /** Chains longer than this stop being itineraries and start being puzzles. */
    private static final int MAX_COMBINED_LEGS = 4;

    private double walkMinutes(double[] a, double[] b) {
        return haversineMetres(a[0], a[1], b[0], b[1]) * 1.3 / 1000.0 / 5.0 * 60;
    }

    private static String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }


    private JourneyOption rideOption(JourneyRequest req, List<String> msgs,
                                     WeatherService.WeatherData weather, int maxWalkM, String vehicleMode) {
        return "SCOOTER".equalsIgnoreCase(vehicleMode)
                ? planScooter(req, msgs, weather, maxWalkM)
                : planBike(req, msgs, weather, maxWalkM);
    }

    /** Two sub-plans, in travel order, welded into the option the traveller sees. */
    private JourneyOption stitch(JourneyRequest req, WeatherService.WeatherData weather,
                                 String vehicleMode, List<JourneyOption> chain,
                                 String mode, String modeLabel, String summaryText, String emoji) {
        List<JourneyLeg> legs = new ArrayList<>();
        int totalMin = 0;
        double totalM = 0, rawCost = 0;
        for (JourneyOption seg : chain) {
            legs.addAll(seg.getLegs());
            totalMin += seg.getDurationMinutes();
            totalM   += nz(seg.getDistanceMetres());
            rawCost  += nz(seg.getCostEuros());
        }
        double cost = Math.round(rawCost * 100) / 100.0;
        double chainCo2 = chain.stream().mapToDouble(o -> nz(o.getCo2Grams())).sum();

        // The bus segments carry the delay and the emissions; the vehicle carries
        // the Elerent details. With two bus rides the first one owns the delay:
        // it is the one the traveller is about to catch.
        JourneyOption bus  = chain.stream().filter(o -> "BUS".equals(o.getMode())).findFirst().orElse(chain.get(0));
        JourneyOption ride = chain.stream().filter(o -> !"BUS".equals(o.getMode())).findFirst().orElse(chain.get(0));

        return JourneyOption.builder()
                .mode(mode).modeLabel(modeLabel)
                .durationMinutes(totalMin).distanceMetres(totalM)
                .costEuros(cost)
                // Only the bus legs emit, and the whole distance counts against
                // what a car would have burned — scoring the trip by its bus leg
                // alone threw the clean kilometres away and understated it.
                .greenIndex(greenIndex.greenIndexFor(chainCo2, totalM / 1000.0))
                .co2Grams(chainCo2)
                .etaMinutes(totalMin)
                .summary(emoji + " " + summaryText
                        + " — " + totalMin + " min (~€" + String.format("%.2f", cost) + ")")
                .weatherWarning(ride.getWeatherWarning() != null ? ride.getWeatherWarning() : bus.getWeatherWarning())
                .weatherSuggestion(weather.suggestion)
                .delayMinutes(bus.getDelayMinutes()).delayStatus(bus.getDelayStatus())
                .delayRealTime(bus.getDelayRealTime()).delayAtStop(bus.getDelayAtStop())
                .delayLabel(bus.getDelayLabel())
                // The chain is only as safe as the change inside its bus leg
                .transferWaitMinutes(bus.getTransferWaitMinutes())
                .boardingStopId(bus.getBoardingStopId())
                .busRouteId(bus.getBusRouteId())
                .boardingTripId(bus.getBoardingTripId())
                .alightStopId(bus.getAlightStopId())
                .transferStopId(bus.getTransferStopId())
                .transferTripId(bus.getTransferTripId())
                .bikeId(ride.getBikeId()).bikePlate(ride.getBikePlate())
                .bikeBatteryPct(ride.getBikeBatteryPct()).bikeWalkMetres(ride.getBikeWalkMetres())
                .bikeWarning(ride.getBikeWarning())
                .legs(legs)
                .build();
    }

    private static String vehicleWord(JourneyRequest req, String vehicleMode) {
        boolean scooter = "SCOOTER".equalsIgnoreCase(vehicleMode);
        return req.isItalian() ? (scooter ? "monopattino" : "bici") : (scooter ? "e-scooter" : "bike");
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Modes that leave the traveller in the weather. Matching on substrings is
     * deliberate: BUS_BIKE and BUS_SCOOTER_BUS still mean pedalling, and an exact
     * name check let the combined options slip past the rain filter — the app
     * offered a bike in a downpour to the very people who had asked for bus only.
     */
    /**
     * The live feed reports a crowding band, not a number, so the threshold the
     * traveller sets in percent has to be compared against something. Each band
     * is read as the middle of its range: the setting then picks which bands
     * count as crowded, which is what a percentage means with this data.
     * An unknown or absent level is treated as empty rather than as crowded —
     * a filter must not hide a bus on the strength of a missing reading.
     */
    private static int occupancyPct(String crowdingLevel) {
        if (crowdingLevel == null) return 0;
        return switch (crowdingLevel.toUpperCase()) {
            case "VERY_HIGH" -> 95;
            case "HIGH"      -> 80;
            case "MEDIUM"    -> 55;
            case "LOW"       -> 25;
            default          -> 0;
        };
    }

    /**
     * How robust an itinerary is, 0..1 — the fourth criterion behind Q4.
     *
     * WHY IT IS NOT THE TRANSFER WAIT ON ITS OWN
     * The first version scored options by their interchange slack, min-maxed
     * across the search. It could never work: this planner returns ONE bus
     * option, direct when a direct line exists and a single best transfer
     * otherwise, so at most one option in a set carries a margin at all. Being
     * the only non-null value it was always the maximum, and every option —
     * a one-minute change included — came out at exactly 1.0. The weight
     * shifted every score equally and changed no order whatsoever.
     *
     * WHAT IT IS INSTEAD
     * The risk of the journey failing, judged in absolute terms so it stays
     * meaningful with a single bus option in the set:
     *
     *   nothing to catch (walk, bike, scooter)     1.00 — no timetable to miss
     *   one boarding, no interchange               0.90 — miss it, take the next
     *   an interchange                    0.30 .. 0.90 by its slack
     *
     * Miss the first bus and you wait for another; miss the connection and you
     * are stranded halfway, which is why only the interchange moves the score
     * far. Slack is read against ten minutes: below that a change is tight,
     * above it nothing more is gained.
     *
     * A bus already reported late eats into that margin, so a live delay costs
     * up to a quarter of the score.
     */
    private static double reliabilityOf(String mode, Integer transferWaitMin, Integer delayMin) {
        double score;
        if (!hasBusLeg(mode)) {
            score = 1.0;
        } else if (transferWaitMin == null) {
            score = 0.90;
        } else {
            double slack = Math.min(1.0, Math.max(0, transferWaitMin) / COMFORTABLE_CHANGE_MIN);
            score = 0.30 + 0.60 * slack;
        }

        if (delayMin != null && delayMin > 0)
            score -= Math.min(0.25, delayMin / 40.0);

        return Math.max(0.0, Math.min(1.0, Math.round(score * 1000) / 1000.0));
    }

    /**
     * The traveller's behavioural preferences, or null when they must not apply
     * to this search.
     *
     * They always apply to CUSTOM. For FAST, BUDGET and ECO they apply only if
     * the traveller has asked for it; otherwise null is returned and every
     * caller falls back to the neutral defaults, so those three rankings answer
     * their single question on the full set of options.
     */
    private UserPreferences activePreferences(JourneyRequest req) {
        if (req.getUserId() == null) return null;

        UserPreferences prefs = preferencesRepository.findByUserId(req.getUserId()).orElse(null);
        if (prefs == null) return null;

        // Absent or unrecognised, the preset is treated as CUSTOM: an older
        // client that does not send one keeps the behaviour it had before.
        String preset = req.getSortPreset() == null ? "CUSTOM" : req.getSortPreset().toUpperCase();
        if ("CUSTOM".equals(preset)) return prefs;

        return Boolean.FALSE.equals(prefs.getApplyPrefsToPresets()) ? null : prefs;
    }

    /** A chain such as BUS_BIKE contains a bus leg; WALK, BIKE, SCOOTER do not. */
    private static boolean hasBusLeg(String mode) {
        return mode != null && mode.toUpperCase().contains("BUS");
    }

    private static boolean exposedToRain(String mode) {
        if (mode == null) return false;
        String m = mode.toUpperCase();
        return m.contains("BIKE") || m.contains("SCOOTER") || m.equals("WALK");
    }

    private static boolean hasMode(JourneyRequest req, String mode) {
        return req.getModes() == null || req.getModes().isEmpty()
                || req.getModes().stream().anyMatch(m -> m.equalsIgnoreCase(mode));
    }

    /**
     * A vehicle leg shorter than this is not a combination, it is a rounding
     * error: the stop was already at the door, and the card would just repeat
     * the plain bus option with an unlock fee attached.
     */
    private static final double MIN_VEHICLE_LEG_M = 300;

    /** Past this, the vehicle is doing the bus's job and the chain stops being sensible. */
    private static final double MAX_BRIDGE_M = 4000;

    private static double nz(Double v) { return v != null ? v : 0.0; }

    /** Shallow copy, so a sub-plan can move the endpoints without touching the caller's request. */
    private static JourneyRequest copyRequest(JourneyRequest r) {
        JourneyRequest c = new JourneyRequest();
        c.setOriginLat(r.getOriginLat());   c.setOriginLon(r.getOriginLon());
        c.setOriginName(r.getOriginName()); c.setOriginIsGps(r.getOriginIsGps());
        c.setOriginStopId(r.getOriginStopId());
        c.setDestLat(r.getDestLat());       c.setDestLon(r.getDestLon());
        c.setDestName(r.getDestName());     c.setDestIsGps(r.getDestIsGps());
        c.setDestStopId(r.getDestStopId());
        c.setUserId(r.getUserId());         c.setLang(r.getLang());
        c.setDepartureTime(r.getDepartureTime());
        c.setArriveBy(r.getArriveBy());
        c.setBaseOverride(r.getBaseOverride());
        c.setModes(r.getModes());
        return c;
    }

    private JourneyOption planWalk(JourneyRequest req, WeatherService.WeatherData weather) {
        var r = googleMapsService.getRoute(req.getOriginLat(), req.getOriginLon(),
                                           req.getDestLat(),   req.getDestLon(), "walking");
        double roadM = r.map(g -> (double) g.distanceMetres())
                        .orElseGet(() -> {
                            log.warn("WALK: Google non disponibile — uso stima haversine");
                            return haversineMetres(req.getOriginLat(), req.getOriginLon(),
                                                   req.getDestLat(),   req.getDestLon()) * 1.3;
                        });
        // speed: ~5 km/h walking
        int dur = r.map(g -> (int) Math.ceil(g.durationSeconds() / 60.0))
                   .orElse((int) Math.ceil(roadM / 1000.0 / 5.0 * 60));
        return JourneyOption.builder()
                .mode("WALK").modeLabel("Walking")
                .durationMinutes(dur).distanceMetres(roadM)
                .costEuros(0.0).greenIndex(100).co2Grams(0.0).etaMinutes(dur)
                .summary("🌿 Have a car free day! Walk " + fmtDist(roadM) + " — " + dur + " min")
                .weatherWarning(weatherService.getModeWarning(weather.condition, "WALK"))
                .weatherSuggestion(weather.suggestion)
                .legs(List.of(JourneyLeg.builder().mode("WALK")
                        .from(req.getOriginName()).to(req.getDestName())
                        .durationMinutes(dur).distanceMetres(roadM)
                        .stopCoords(r.map(GoogleMapsService.RouteResult::points)
                                .filter(pts -> pts.size() >= 2)
                                .orElse(List.of(
                                        new double[]{req.getOriginLat(), req.getOriginLon()},
                                        new double[]{req.getDestLat(),   req.getDestLon()})))
                        .instruction("Walk " + fmtDist(roadM) + " to " + req.getDestName())
                        .build()))
                .build();
    }

    /**
     * A walking stretch: how far how long, and the shape to draw it with.
     *
     * @param points [lat, lon] pairs following the pavement when Google routed
     *               it, or just the two endpoints when it did not — which is
     *               what the map used to receive for every walk.
     */
    private record WalkStretch(double metres, int minutes, boolean routed,
                               List<double[]> points) {}

    /**
     * Fastest walking route from Google, with a haversine estimate when Google
     * cannot answer (no API key, quota, network). The fallback matters: without
     * it a missing key left the distance at zero and the caller dropped the leg
     * entirely, so a trip that starts at your GPS position looked like it began
     * standing at the bus stop — the walk vanished from the itinerary and from
     * the total. 1.3x the straight line at 5 km/h is the same estimate
     * planWalk() already used.
     */
    private WalkStretch walkStretch(double fromLat, double fromLon,
                                    double toLat,   double toLon) {
        List<double[]> straight = List.of(new double[]{fromLat, fromLon},
                                          new double[]{toLat,   toLon});

        var g = googleMapsService.getRoute(fromLat, fromLon, toLat, toLon, "walking");
        if (g.isPresent()) {
            var pts = g.get().points();
            return new WalkStretch(g.get().distanceMetres(),
                    (int) Math.ceil(g.get().durationSeconds() / 60.0), true,
                    pts.size() >= 2 ? pts : straight);
        }
        double metres = haversineMetres(fromLat, fromLon, toLat, toLon) * 1.3;
        return new WalkStretch(metres, (int) Math.ceil(metres / 1000.0 / 5.0 * 60), false, straight);
    }

    private double haversineMetres(double lat1,double lon1,double lat2,double lon2) {
        return it.unicas.omnimove.util.GeoUtils.haversineMetres(lat1, lon1, lat2, lon2);
    }

    /**
     * Istante di partenza del viaggio.
     *  - null/vuoto  -> adesso
     *  - "HH:mm"     -> quell'ora oggi (Europe/Rome); se gia' passata, domani
     * Se sposta a domani, lo annuncia in msgs.
     */
    private java.time.Instant resolveDepartureBase(String hhmm, List<String> msgs, boolean italian) {
        if (hhmm == null || hhmm.isBlank()) return java.time.Instant.now();
        try {
            java.time.ZoneId tz = java.time.ZoneId.of("Europe/Rome");
            java.time.LocalTime t = java.time.LocalTime.parse(hhmm.trim());   // "HH:mm"
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(tz);
            java.time.ZonedDateTime cand = now.with(t);
            if (cand.isBefore(now)) {
                cand = cand.plusDays(1);
                if (msgs != null) {
                    msgs.add(italian
                            ? "\u23F0 Le " + hhmm.trim()
                                + " sono gi\u00e0 passate oggi \u2014 mostro i risultati per domani."
                            : "\u23F0 " + hhmm.trim()
                                + " has already passed today \u2014 showing results for tomorrow.");
                }
            }
            return cand.toInstant();
        } catch (Exception e) {
            log.warn("departure_time '{}' non valido, uso now: {}", hhmm, e.getMessage());
            return java.time.Instant.now();
        }
    }

    /** Percorso reale su strada per la modalità Google data (walking/bicycling/driving). */
    private String delayLabel(DelayInfo d) {
        if (d == null || d.delayMinutes() == null) return null;
        int m = d.delayMinutes();
        String base;
        if ("EARLY".equals(d.status()))           base = Math.abs(m) + " min early";
        else if (m <= 0 || "ON_TIME".equals(d.status())) base = "on time";
        else                                      base = m + " min late";

        return d.realTime()
                ? "Bus currently " + base
                : "Bus was " + base + " at " + d.atStop();
    }

    /**
     * The k stops closest to a point, nearest first, by straight line.
     *
     * The combined planner needs more than the single closest stop: riding to a
     * slightly farther stop that a faster line serves is exactly the kind of
     * trade a multimodal trip exists to make.
     */
    private List<String> nearestStopIds(double lat, double lon, int k) {
        record Candidate(String id, double dist) {}
        List<Candidate> candidates = new ArrayList<>();
        for (it.unicas.omnimove.model.Stop stop : stopRepository.findAll()) {
            if (stop.getLat() == null || stop.getLon() == null) continue;
            candidates.add(new Candidate(stop.getId(),
                    haversineMetres(lat, lon, stop.getLat(), stop.getLon())));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::dist))
                .limit(k)
                .map(Candidate::id)
                .toList();
    }

    private String findNearestStopId(double lat, double lon) {
        // Passo 1: pre-selezione con haversine — troviamo le 3 fermate più vicine in linea d'aria
        record Candidate(String id, double dist) {}
        List<Candidate> candidates = new ArrayList<>();

        for (it.unicas.omnimove.model.Stop stop : stopRepository.findAll()) {
            if (stop.getLat() == null || stop.getLon() == null) continue;
            double d = haversineMetres(lat, lon, stop.getLat(), stop.getLon());
            candidates.add(new Candidate(stop.getId(), d));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::dist));
        List<Candidate> top = candidates.stream().limit(3).toList();

        if (top.isEmpty()) return null;
        if (top.size() == 1) return top.get(0).id();

        // Passo 2: conferma con Google (walking) — quale delle 3 è davvero la più vicina a piedi?
        String bestId   = top.get(0).id();   // fallback: la prima haversine
        long   bestSec  = Long.MAX_VALUE;

        for (Candidate c : top) {
            var result = googleMapsService.getTravelTime(
                    lat, lon,
                    getStopLat(c.id()), getStopLon(c.id()),
                    "walking");
            if (result.isPresent()) {
                long sec = (long) result.get().durationSeconds();
                if (sec < bestSec) {
                    bestSec = sec;
                    bestId  = c.id();
                }
            }
        }
        return bestId;
    }

    private double getStopLat(String id) {
        if (id == null) return 41.4925;
        return stopRepository.findById(id)
                .filter(s -> s.getLat() != null)
                .map(it.unicas.omnimove.model.Stop::getLat)
                .orElse(41.4925);
    }

    private double getStopLon(String id) {
        if (id == null) return 13.8306;
        return stopRepository.findById(id)
                .filter(s -> s.getLon() != null)
                .map(it.unicas.omnimove.model.Stop::getLon)
                .orElse(13.8306);
    }

    private String fmtStop(String id) {
        if (id == null) return "Unknown stop";
        return stopRepository.findById(id)
                .map(it.unicas.omnimove.model.Stop::getName)
                .orElse(id);
    }

    /** Returns the slice of the trip's stops between origin and dest (inclusive), sorted by sequence. */
    private record StopSlice(List<double[]> coords, List<String> names, List<double[]> busStopCoords) {}

    private StopSlice stopSliceBetween(String tripId, String originStopId, String destStopId) {
        if (tripId == null) return new StopSlice(List.of(), List.of(), List.of());

        var seq = new ArrayList<>(scheduledStopRepository.findByTripId(tripId));
        seq.sort(Comparator.comparingInt(it.unicas.omnimove.model.ScheduledStop::getStopSequence));

        // posizioni (indici nella sequenza ordinata) di origine e cambio/destinazione
        int oi = -1, di = -1;
        for (int i = 0; i < seq.size(); i++) {
            String sid = seq.get(i).getStopId();
            if (oi < 0 && sid.equals(originStopId)) oi = i;
            else if (oi >= 0 && di < 0 && sid.equals(destStopId)) { di = i; break; }
        }
        // se non le ho trovate in quest'ordine, riprovo senza vincolo di precedenza
        if (oi < 0 || di < 0) {
            oi = indexOfStop(seq, originStopId);
            di = indexOfStop(seq, destStopId);
        }
        if (oi < 0 || di < 0) return new StopSlice(List.of(), List.of(), List.of());

        int from = Math.min(oi, di);
        int to   = Math.max(oi, di);

        // The stops this leg calls at, in travel order.
        List<String> legStops = new ArrayList<>();
        for (int i = from; i <= to; i++) legStops.add(seq.get(i).getStopId());

        // Actual stop coordinates (used for dot markers regardless of road geometry).
        List<double[]> stopPoints = new ArrayList<>();
        List<String>   names      = new ArrayList<>();
        for (String sid : legStops) {
            stopPoints.add(new double[]{ getStopLat(sid), getStopLon(sid) });
            names.add(fmtStop(sid));
        }

        // Prefer the real road geometry, so the drawn leg follows the streets
        // the bus follows. Falls back to stop-to-stop when the route has no
        // shape (or the shape cannot be matched) — see roadPathAlong().
        List<double[]> road = roadPathAlong(tripId, legStops);
        if (!road.isEmpty()) {
            return new StopSlice(road, names, stopPoints);
        }

        // No road shape: the stop coords are the polyline as well.
        return new StopSlice(stopPoints, names, stopPoints);
    }

    private List<double[]> stopCoordsBetween(String tripId, String originStopId, String destStopId) {
        return stopSliceBetween(tripId, originStopId, destStopId).coords();
    }

    private List<String> stopNamesBetween(String tripId, String originStopId, String destStopId) {
        return stopSliceBetween(tripId, originStopId, destStopId).names();
    }

    /**
     * Slice the portion of a route's road geometry that this leg travels.
     *
     * <p>Walking rather than indexing matters: on a ring line the same stop is
     * called at twice (LINEA_1 passes VBO, SFF and VGA on the way out and again
     * on the way back), so "the vertex for stop X" is ambiguous on its own. By
     * scanning forward from wherever the previous stop matched, each stop binds
     * to the correct visit and the slice follows the direction of travel.
     *
     * @return the ordered points from the first stop to the last, or an EMPTY
     *         list when the route has no shape or a stop cannot be located —
     *         the caller then keeps the old stop-to-stop rendering.
     */
    private List<double[]> roadPathAlong(String tripId, List<String> legStops) {
        if (tripId == null || legStops.size() < 2) return List.of();

        String routeId = tripRepository.findById(tripId)
                .map(t -> t.getRoute() == null ? null : t.getRoute().getId())
                .orElse(null);
        if (routeId == null) return List.of();

        var shape = routeShapeRepository.findByRouteIdOrderBySeqAsc(routeId);
        if (shape.size() < 2) return List.of();          // no geometry for this route

        List<double[]> points = new ArrayList<>(shape.size());
        for (var p : shape) points.add(new double[]{ p.getLat(), p.getLon() });

        List<double[]> out = sliceAlong(points, legStops);
        if (!out.isEmpty()) return out;

        // A line's geometry is stored once, in the outbound direction, but a
        // return run travels it the other way: its stops appear at descending
        // positions and the forward scan gives up at the second one, leaving the
        // leg drawn as straight hops between stops. Walking the reversed shape
        // binds each stop to the right vertex and hands back the slice already
        // in travel order.
        List<double[]> backwards = new ArrayList<>(points);
        Collections.reverse(backwards);
        return sliceAlong(backwards, legStops);
    }

    /**
     * The stretch of {@code points} that runs from the first of {@code legStops}
     * to the last, or an empty list when a stop cannot be found ahead of the
     * previous one.
     *
     * <p>Scanning forward from the previous match rather than indexing matters:
     * on a ring line the same stop is called at twice (LINEA_1 passes VBO, SFF
     * and VGA outbound and again on the way back), so "the vertex for stop X" is
     * ambiguous on its own.
     */
    private List<double[]> sliceAlong(List<double[]> points, List<String> legStops) {
        int cursor = 0, firstIdx = -1, lastIdx = -1;
        for (String stopId : legStops) {
            double lat = getStopLat(stopId), lon = getStopLon(stopId);

            int found = -1;
            for (int i = cursor; i < points.size(); i++) {
                if (Math.abs(points.get(i)[0] - lat) < STOP_MATCH_TOLERANCE
                 && Math.abs(points.get(i)[1] - lon) < STOP_MATCH_TOLERANCE) {
                    found = i;
                    break;
                }
            }
            if (found < 0) return List.of();             // unmatched → give up cleanly

            if (firstIdx < 0) firstIdx = found;
            lastIdx = found;
            cursor  = found + 1;
        }
        if (firstIdx < 0 || lastIdx <= firstIdx) return List.of();

        return new ArrayList<>(points.subList(firstIdx, lastIdx + 1));
    }

    private int indexOfStop(List<it.unicas.omnimove.model.ScheduledStop> seq, String stopId) {
        for (int i = 0; i < seq.size(); i++)
            if (seq.get(i).getStopId().equals(stopId)) return i;
        return -1;
    }
    private record SegTime(int minutes, double metres) {}

    /** Ciò che sappiamo del ritardo del bus scelto per questa tratta. */
    private record DelayInfo(Integer delayMinutes, String status, boolean realTime, String atStop) {
        static DelayInfo none() { return new DelayInfo(null, null, false, null); }
    }

    /** Tempo e distanza del bus lungo la sequenza reale delle fermate:
     *  per ogni tratta consecutiva una chiamata Google (traffico live),
     *  con ripiego sull'orario del DB se Google non risponde. */
    private SegTime busTimeBySegments(String tripId, String originStop, String destStop,
                                      java.time.Instant boardingTime) {
        var seq = new ArrayList<>(scheduledStopRepository.findByTripId(tripId));
        seq.sort(Comparator.comparingInt(it.unicas.omnimove.model.ScheduledStop::getStopSequence));

        // Su una corsa ad anello le fermate ricompaiono. Scegli la coppia di
        // occorrenze in cui l'origine precede la destinazione, la più corta:
        // è il segmento che l'utente percorre davvero.
        int from = -1, to = -1;
        for (int i = 0; i < seq.size(); i++) {
            if (!seq.get(i).getStopId().equals(originStop)) continue;
            for (int j = i + 1; j < seq.size(); j++) {
                if (!seq.get(j).getStopId().equals(destStop)) continue;
                if (from < 0 || (j - i) < (to - from)) { from = i; to = j; }
                break;   // per questa occorrenza di origine, la prima destinazione a valle basta
            }
        }
        if (from < 0) return null;   // destinazione mai raggiungibile dopo l'origine su questa corsa

        int totalSec = 0;
        double totalM = 0;
        // boardingTime == null significa Google disattivato per la ricerca:
        // in quel caso si usa solo l'orario statico del DB, nessuna chiamata.
        java.time.Instant legStart = boardingTime;
        for (int i = from; i < to; i++) {
            var a = seq.get(i);
            var b = seq.get(i + 1);
            int segSec;
            if (boardingTime != null) {
                var g = googleMapsService.getTravelTime(
                        getStopLat(a.getStopId()), getStopLon(a.getStopId()),
                        getStopLat(b.getStopId()), getStopLon(b.getStopId()),
                        "driving", legStart);
                if (g.isPresent()) {
                    segSec  = (int) g.get().durationInTrafficSeconds();
                    totalM += g.get().distanceMetres();
                } else {
                    segSec = Math.abs(b.getArrivalSeconds() - a.getArrivalSeconds());
                }
            } else {
                segSec = Math.abs(b.getArrivalSeconds() - a.getArrivalSeconds());
            }
            totalSec += segSec;
            if (legStart != null) legStart = legStart.plusSeconds(segSec);
        }
        return new SegTime((int) Math.ceil(totalSec / 60.0), totalM);
    }

    private String fmtDist(double m) {
        return m<1000 ? (int)m+"m" : String.format("%.1f km", m/1000); }

    private record Transfer(String stop, String l1Label, int l1Min,
                            String l2Label, int l2Min, int totalMin,
                            String l2RouteId, String l2Short,
                            String l1RouteId, String l1Short,
                            String l1TripId,  String l2TripId) {}

    /** Cerca il miglior percorso con UN cambio: origine → X → destinazione. */
    private Transfer findBestTransfer(String origin, String dest) {
        var rows = scheduledStopRepository.findBestTransfer(origin, dest);
        if (rows.isEmpty()) return null;
        var x = rows.get(0);
        int m1 = (int) Math.ceil(x.getL1Sec() / 60.0);
        int m2 = (int) Math.ceil(x.getL2Sec() / 60.0);
        return new Transfer(
                x.getTransferStop(),
                x.getL1Short() + " → " + x.getL1Long(), m1,
                x.getL2Short() + " → " + x.getL2Long(), m2,
                m1 + m2,
                x.getL2RouteId(), x.getL2Short(),
                x.getL1RouteId(), x.getL1Short(),
                x.getL1TripId(),  x.getL2TripId());
    }

    /**
     * Minuti di attesa per una linea specifica a una fermata, dall'ETA real-time di CassiTrack.
     *
     * Logica:
     *   1. Cerca il match esatto sulla linea richiesta (per routeId, poi per routeShort parziale).
     *   2. Se non trova la linea nei dati RT, usa il prossimo bus generico alla fermata
     *      e aggiunge un avviso in msgs.
     *   3. Se CassiTrack non risponde o la lista è vuota, cade su waitMinutesFromSchedule (DB).
     */
    private int waitMinutesForLine(String stopId, String routeId, String routeShort,
                                   List<String> msgs, DelayInfo[] out,
                                   java.time.Instant when, boolean useLive, boolean italian) {
        // useLive == false -> ricerca per un orario futuro: un bus tracciato ADESSO
        // non dice nulla su quel momento, quindi si va diritti all'orario di tabella.
        if (useLive) {
            try {
                List<StopArrivalDTO> arrivals = cassitrackClient.getArrivalsAtStop(stopId);

                // Solo un bus DELLA LINEA RICHIESTA e' rilevante: il prossimo bus di
                // un'altra linea non dice nulla ne' sull'attesa ne' sul ritardo.
                Optional<StopArrivalDTO> exactMatch = arrivals.stream()
                        .filter(a -> (routeId    != null && routeId.equals(a.getRouteId()))
                                || (routeShort != null && a.getRouteName() != null
                                && a.getRouteName().contains(routeShort)))
                        .findFirst();

                if (exactMatch.isPresent()) {
                    StopArrivalDTO match = exactMatch.get();

                    // Il ritardo si raccoglie SOLO dal match esatto. Prima veniva preso
                    // dal primo bus qualsiasi: la scheda mostrava il ritardo di un altro
                    // bus spacciandolo per quello del viaggio dell'utente.
                    if (out != null) {
                        boolean live = match.getVehicleId() != null;
                        out[0] = new DelayInfo(
                                match.getDelayMinutes(),
                                match.getScheduleStatus(),
                                live,
                                fmtStop(stopId));
                    }

                    if (match.getEstimatedArrival() != null) {
                        long etaSec = match.getEstimatedArrival().getEpochSecond()
                                - System.currentTimeMillis() / 1000;
                        return (int) Math.max(0, etaSec / 60);
                    }
                } else {
                    // Linea non tracciata ora: si usa l'ORARIO DI TABELLA della linea
                    // giusta (sotto), non l'ETA di un bus di un'altra linea.
                    if (msgs != null) {
                        String lineLabel = routeShort != null ? routeShort : routeId;
                        msgs.add(italian
                            ? "\u2139\ufe0f Nessun bus in tempo reale per la linea " + lineLabel + " al momento \u2014 "
                                + "attesa alla fermata " + fmtStop(stopId) + " stimata dall'orario."
                            : "\u2139\ufe0f No live bus for route " + lineLabel + " right now \u2014 "
                                + "wait time at " + fmtStop(stopId) + " estimated from the timetable.");
                    }
                    log.debug("waitMinutesForLine: nessun bus live per linea {} a {}, uso orario DB",
                            routeShort, stopId);
                }

            } catch (Exception e) {
                log.debug("ETA per linea non disponibile a {}: {}", stopId, e.getMessage());
            }
        }

        // Orario statico della linea richiesta, all'istante di riferimento.
        return waitMinutesFromSchedule(stopId, routeShort, when);
    }

    /**
     * Calcola i minuti al prossimo passaggio di una linea a questa fermata
     * usando l'orario statico nel DB (arrivalSeconds = secondi dalla mezzanotte).
     *
     * Usato quando CassiTrack non è disponibile o non ha dati per quella linea.
     * Restituisce 5 solo se non ci sono più corse oggi (ultima corsa già passata).
     */
    private int waitMinutesFromSchedule(String stopId, String routeShort, java.time.Instant when) {
        // Secondi dalla mezzanotte dell'ISTANTE DI RIFERIMENTO, non di "adesso":
        // per una ricerca futura conta l'orario scelto, non l'ora corrente.
        int nowSec = when.atZone(ZoneId.of("Europe/Rome")).toLocalTime().toSecondOfDay();

        List<ScheduledStop> candidates = (routeShort != null)
                ? scheduledStopRepository.findByStopIdAndRouteShort(stopId, routeShort)
                : scheduledStopRepository.findByStopId(stopId);

        return candidates.stream()
                .mapToInt(ScheduledStop::getArrivalSeconds)
                .filter(sec -> sec > nowSec)            // solo corse non ancora passate
                .map(sec -> sec - nowSec)               // secondi rimanenti
                .min()
                .stream()
                .mapToObj(diff -> (int) Math.ceil(diff / 60.0))
                .findFirst()
                .orElse(5);   // nessuna corsa rimasta oggi → stima minima
    }

}