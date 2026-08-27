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

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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

    // ── Pesi del punteggio multi-criterio ──────────────────────────
    // Ogni profilo pesa le tre metriche in modo diverso. La somma fa 1.0, cosi'
    // i punteggi restano nell'intervallo [0,1] e sono confrontabili fra opzioni
    // della STESSA ricerca. Sono una scelta di progetto, non una taratura
    // sperimentale: modificarli qui cambia l'ordinamento senza toccare altro.
    //                                        tempo  costo  ambiente
    private static final double[] W_FAST   = { 0.70,  0.10,  0.20 };
    private static final double[] W_BUDGET = { 0.20,  0.70,  0.10 };
    private static final double[] W_ECO    = { 0.20,  0.10,  0.70 };

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

    public JourneyResponse plan(JourneyRequest req) {
        log.info("Planning: {} → {}", req.getOriginName(), req.getDestName());

        WeatherService.WeatherData weather = weatherService.getCurrentWeather();
        boolean realtimeAvailable = cassitrackClient.isAvailable();

        List<String> modes = new ArrayList<>(
                (req.getModes() != null && !req.getModes().isEmpty())
                        ? req.getModes()
                        : List.of("BUS","BIKE","SCOOTER","WALK")
        );

        boolean preferBike = false;
        int maxBikeWalk = 500;   // metres — overridden by the user preference
        if (req.getUserId() != null) {
            var prefsOpt = preferencesRepository.findByUserId(req.getUserId());
            if (prefsOpt.isPresent()) {
                var prefs = prefsOpt.get();
                if (Boolean.FALSE.equals(prefs.getShowWalking())) {
                    modes.remove("WALK");
                }
                preferBike = Boolean.TRUE.equals(prefs.getPreferBikeOverBus());
                if (prefs.getMaxBikeWalkMetres() != null && prefs.getMaxBikeWalkMetres() > 0) {
                    maxBikeWalk = prefs.getMaxBikeWalkMetres();
                }
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
                    case "BUS"     -> planBus(req, msgs, weather);
                    case "BIKE"    -> planBike(req, msgs, weather, maxBikeWalk);
                    case "SCOOTER" -> planScooter(req, msgs, weather, maxBikeWalk);
                    case "WALK"    -> planWalk(req, weather);
                    default -> null;
                };
                if (opt != null) options.add(opt);
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
        // Always try to put one genuinely multimodal proposal on the table: bus
        // plus a shared vehicle, in whichever order this particular trip allows.
        if (busRequested && (modes.contains("BIKE") || modes.contains("SCOOTER"))) {
            JourneyOption combined = planMultiModal(req, weather, maxBikeWalk);
            if (combined != null) options.add(combined);
            else log.debug("Nessuna combinazione possibile: entrambi gli estremi sono su una fermata, "
                    + "oppure non c'e' un mezzo Elerent raggiungibile.");
        }

        if (busDeferred) {
            boolean bikeAvailable = options.stream().anyMatch(o -> "BIKE".equals(o.getMode()));
            if (!bikeAvailable) {
                // la bici non è disponibile → calcola il bus come riserva
                try {
                    JourneyOption bus = planBus(req,msgs, weather);
                    if (bus != null) options.add(bus);
                } catch (Exception e) {
                    log.warn("Failed BUS fallback option: {}", e.getMessage());
                }
            }

        }

        boolean raining =
                weather.condition == WeatherService.WeatherCondition.RAIN ||
                        weather.condition == WeatherService.WeatherCondition.HEAVY_RAIN;

        options.sort(
                Comparator.comparingInt((JourneyOption o) ->
                                raining && exposedToRain(o.getMode()) ? 1 : 0)   // bus prima se piove
                        .thenComparingInt(JourneyOption::getDurationMinutes));

        boolean onlyBusWhenRaining = true; // default
        if (req.getUserId() != null) {
            onlyBusWhenRaining = preferencesRepository.findByUserId(req.getUserId())
                    .map(p -> Boolean.TRUE.equals(p.getOnlyBusWhenRaining()))
                    .orElse(true);
        }
        if (raining && onlyBusWhenRaining) {
            options.removeIf(o -> exposedToRain(o.getMode()));
        }

        // Punteggio multi-criterio: si calcola sull'insieme DEFINITIVO delle
        // opzioni (dopo il filtro pioggia), perche' la normalizzazione dipende
        // dal min/max di cio' che l'utente vedra' davvero.
        computeScores(options);

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


    /**
     * Assegna a ogni opzione tre punteggi in [0,1], uno per profilo (FAST /
     * BUDGET / ECO). Il frontend ordina per il punteggio del chip attivo.
     *
     * Perche' un punteggio e non un semplice sort su una metrica: quando piu'
     * opzioni pareggiano sul criterio principale (es. bici, monopattino e piedi
     * hanno tutti green_index = 100), il criterio singolo non sa distinguerle e
     * l'ordine diventa arbitrario. Il punteggio usa le altre due metriche come
     * spareggio, con peso minore.
     *
     * Normalizzazione min-max SULLA SINGOLA RICERCA: i punteggi servono a
     * ordinare le alternative fra loro, non hanno significato assoluto e non
     * sono confrontabili fra ricerche diverse.
     */
    private void computeScores(List<JourneyOption> options) {
        if (options == null || options.isEmpty()) return;

        double[] time  = normalise(options, o -> value(o.getDurationMinutes()));
        double[] cost  = normalise(options, o -> value(o.getCostEuros()));
        double[] green = normalise(options, o -> value(o.getGreenIndex()));

        for (int i = 0; i < options.size(); i++) {
            JourneyOption o = options.get(i);
            // tempo e costo: piu' basso e' meglio -> si inverte (1 - x)
            // ambiente: piu' alto e' meglio -> si usa diretto
            o.setScoreFast(  score(W_FAST,   time[i], cost[i], green[i]));
            o.setScoreBudget(score(W_BUDGET, time[i], cost[i], green[i]));
            o.setScoreEco(   score(W_ECO,    time[i], cost[i], green[i]));
        }
    }

    private double score(double[] w, double tNorm, double cNorm, double gNorm) {
        double s = w[0] * (1 - tNorm) + w[1] * (1 - cNorm) + w[2] * gNorm;
        return Math.round(s * 1000) / 1000.0;
    }

    /**
     * Min-max sulle opzioni presenti. Se tutte hanno lo stesso valore la metrica
     * non discrimina: si restituisce 0.5 (neutro) per non favorire ne' penalizzare.
     */
    private double[] normalise(List<JourneyOption> options,
                               java.util.function.ToDoubleFunction<JourneyOption> f) {
        int n = options.size();
        double[] raw = new double[n];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            raw[i] = f.applyAsDouble(options.get(i));
            lo = Math.min(lo, raw[i]);
            hi = Math.max(hi, raw[i]);
        }
        double[] out = new double[n];
        if (hi - lo < 1e-9) {
            java.util.Arrays.fill(out, 0.5);
            return out;
        }
        for (int i = 0; i < n; i++) out[i] = (raw[i] - lo) / (hi - lo);
        return out;
    }

    /** Null-safe: un'opzione senza metrica non deve far saltare il calcolo. */
    private double value(Number n) {
        return n != null ? n.doubleValue() : 0.0;
    }

    private JourneyOption planBus(JourneyRequest req, List<String> msgs,
                                  WeatherService.WeatherData weather) {

        String nearestStop = req.getOriginStopId() != null
                ? req.getOriginStopId()
                : findNearestStopId(req.getOriginLat(), req.getOriginLon());

        String destStop = req.getDestStopId() != null
                ? req.getDestStopId()
                : findNearestStopId(req.getDestLat(), req.getDestLon());

        // Punto 3: base oraria del viaggio e stato del flag google.search.
        boolean isNow = (req.getDepartureTime() == null || req.getDepartureTime().isBlank());
        java.time.Instant departureBase = resolveDepartureBase(req.getDepartureTime(), msgs, req.isItalian());

        // "Arrive by" mode: shift search window back by 45 min so found routes arrive near target time
        if (req.isArriveBy() && !isNow) {
            departureBase = departureBase.minus(45, java.time.temporal.ChronoUnit.MINUTES);
            msgs.add(req.isItalian()
                ? "ℹ️ Percorso pianificato per arrivare entro le " + req.getDepartureTime() + "."
                : "ℹ️ Route planned to arrive by " + req.getDepartureTime() + ".");
        }

        boolean useGoogle = googleApiSettings.isSearchEnabled();

        // Il ritardo ha senso solo per una ricerca "adesso": per un viaggio
        // futuro il ritardo attuale di un bus che gira ora non significa nulla.


        // --- Step 1: walk to bus stop ---
        boolean fromGps = Boolean.TRUE.equals(req.getOriginIsGps());

        double walkMetres = 0;
        int walkMin = 0;
        List<double[]> walkPoints = List.of();
        if (fromGps) {
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
        double busMetres ;     // default per cambio/ripiego
        List<JourneyLeg> busLegs = new ArrayList<>();
        DelayInfo busDelay = DelayInfo.none();

        if (!direct.isEmpty()) {
            var line = direct.get(0);
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
        double destWalkMetres = 0;
        int destWalkMin = 0;
        List<double[]> destWalkPoints = List.of();
        if (req.isDestGps()) {
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
        if (req.getUserId() != null) {
            boolean avoid = preferencesRepository.findByUserId(req.getUserId())
                    .map(p -> Boolean.TRUE.equals(p.getAvoidHighOccupancy()))
                    .orElse(false);
            if (avoid) {
                boolean highOccupancy = cassitrackClient.getActiveVehicles().stream()
                        .anyMatch(v -> "HIGH".equalsIgnoreCase(v.getCrowdingLevel())
                                || "VERY_HIGH".equalsIgnoreCase(v.getCrowdingLevel()));
                if (highOccupancy) occupancyWarning = "⚠️ High occupancy";
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
     * The multimodal proposal: at least one option that puts a shared vehicle
     * and the bus in the same trip, whichever way round suits this journey.
     *
     * Four shapes are tried — bike or scooter, before the bus or after it — and
     * the quickest that holds together is the one offered. Which shapes are even
     * possible depends on the endpoints: a vehicle can only replace a walk that
     * exists, so the ride-then-bus shape needs an origin away from the stops,
     * and the bus-then-ride shape a destination away from them.
     *
     * None of the two planners is duplicated here. Both take a JourneyRequest,
     * so a combined trip is two sub-requests meeting at a stop, with their legs
     * stitched into one option.
     */
    private JourneyOption planMultiModal(JourneyRequest req, WeatherService.WeatherData weather,
                                         int maxWalkM) {
        // The two ends' own stops: needed to ask whether a bus even connects the
        // interchange to the other side. Straight-line, deliberately — this is a
        // filter, and findNearestStopId() spends Google calls refining a choice
        // that only has to be roughly right here.
        String originStop = firstOrNull(nearestStopIds(req.getOriginLat(), req.getOriginLon(), 1));
        String destStop   = firstOrNull(nearestStopIds(req.getDestLat(),   req.getDestLon(),   1));

        // secondStop non nullo = catena bus → mezzo → bus
        record Candidate(boolean busFirst, String vehicleMode, String stopId,
                         double proxyMetres, String secondStop) {}
        List<Candidate> viable = new ArrayList<>();

        for (String vehicleMode : List.of("BIKE", "SCOOTER")) {
            if (!hasMode(req, vehicleMode)) continue;

            // Bus first: the interchange sits near the destination
            if (originStop != null) {
                for (String stopId : nearestStopIds(req.getDestLat(), req.getDestLon(), INTERCHANGE_CANDIDATES)) {
                    double sLat = getStopLat(stopId), sLon = getStopLon(stopId);
                    if (stopId.equals(originStop)) continue;
                    if (haversineMetres(sLat, sLon, req.getDestLat(), req.getDestLon()) < MIN_VEHICLE_LEG_M) continue;
                    if (bikeSharingService.findNearest(sLat, sLon, vehicleMode, maxWalkM).isEmpty()) continue;
                    if (scheduledStopRepository.findLinesConnecting(originStop, stopId).isEmpty()) continue;
                    viable.add(new Candidate(true, vehicleMode, stopId,
                            haversineMetres(req.getOriginLat(), req.getOriginLon(), sLat, sLon)
                          + haversineMetres(sLat, sLon, req.getDestLat(), req.getDestLon()), null));
                }
            }

            // Vehicle first: the interchange sits near the origin
            if (destStop != null
                    && !bikeSharingService.findNearest(req.getOriginLat(), req.getOriginLon(),
                                                       vehicleMode, maxWalkM).isEmpty()) {
                for (String stopId : nearestStopIds(req.getOriginLat(), req.getOriginLon(), INTERCHANGE_CANDIDATES)) {
                    double sLat = getStopLat(stopId), sLon = getStopLon(stopId);
                    if (stopId.equals(destStop)) continue;
                    if (haversineMetres(req.getOriginLat(), req.getOriginLon(), sLat, sLon) < MIN_VEHICLE_LEG_M) continue;
                    if (scheduledStopRepository.findLinesConnecting(stopId, destStop).isEmpty()) continue;
                    viable.add(new Candidate(false, vehicleMode, stopId,
                            haversineMetres(req.getOriginLat(), req.getOriginLon(), sLat, sLon)
                          + haversineMetres(sLat, sLon, req.getDestLat(), req.getDestLon()), null));
                }
            }
        }

        // Bus → vehicle → bus. Two set queries answer where a bus can take you and
        // where a bus can finish the trip; everything after that is arithmetic on
        // the cached fleet, so the pairing costs nothing per candidate.
        if (originStop != null && destStop != null) {
            List<String> reachable = scheduledStopRepository.findStopsReachableFrom(originStop);
            List<String> feeding   = scheduledStopRepository.findStopsConnectingTo(destStop);
            for (String vehicleMode : List.of("BIKE", "SCOOTER")) {
                if (!hasMode(req, vehicleMode)) continue;
                for (String s1 : reachable) {
                    if (s1.equals(originStop) || s1.equals(destStop)) continue;
                    double s1Lat = getStopLat(s1), s1Lon = getStopLon(s1);
                    if (bikeSharingService.findNearest(s1Lat, s1Lon, vehicleMode, maxWalkM).isEmpty()) continue;
                    for (String s2 : feeding) {
                        if (s2.equals(s1) || s2.equals(originStop)) continue;
                        double s2Lat = getStopLat(s2), s2Lon = getStopLon(s2);
                        double bridge = haversineMetres(s1Lat, s1Lon, s2Lat, s2Lon);
                        if (bridge < MIN_VEHICLE_LEG_M || bridge > MAX_BRIDGE_M) continue;
                        viable.add(new Candidate(true, vehicleMode, s1,
                                haversineMetres(req.getOriginLat(), req.getOriginLon(), s1Lat, s1Lon)
                              + bridge
                              + haversineMetres(s2Lat, s2Lon, req.getDestLat(), req.getDestLon()),
                                s2));
                    }
                }
            }
        }

        // Only the most direct few are planned for real: each survivor costs a bus
        // plan plus a couple of Google routes, and the detour proxy separates them
        // well enough that the rest would not have won anyway.
        List<JourneyOption> planned = new ArrayList<>();
        viable.stream()
                .sorted(Comparator.comparingDouble(Candidate::proxyMetres))
                .limit(MAX_COMBOS_PLANNED)
                .forEach(c -> {
                    try {
                        JourneyOption o = c.secondStop() != null
                                ? busVehicleBus(req, weather, maxWalkM, c.vehicleMode(), c.stopId(), c.secondStop())
                                : c.busFirst()
                                    ? busThenVehicle(req, weather, maxWalkM, c.vehicleMode(), c.stopId())
                                    : vehicleThenBus(req, weather, maxWalkM, c.vehicleMode(), c.stopId());
                        if (o != null) planned.add(o);
                    } catch (Exception e) {
                        log.warn("Failed combined option ({} via {}): {}",
                                c.vehicleMode(), c.stopId(), e.getMessage());
                    }
                });

        return planned.stream()
                .min(Comparator.comparingInt(JourneyOption::getDurationMinutes))
                .orElse(null);
    }

    private static String firstOrNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Bus for the long haul, shared vehicle from the alighting stop to the door. */
    private JourneyOption busThenVehicle(JourneyRequest req, WeatherService.WeatherData weather,
                                         int maxWalkM, String vehicleMode, String stopId) {
        if (stopId == null) return null;

        double stopLat = getStopLat(stopId), stopLon = getStopLon(stopId);

        // Nothing to ride if the stop is already on top of the destination
        if (haversineMetres(stopLat, stopLon, req.getDestLat(), req.getDestLon()) < MIN_VEHICLE_LEG_M) {
            return null;
        }
        if (bikeSharingService.findNearest(stopLat, stopLon, vehicleMode, maxWalkM).isEmpty()) return null;

        JourneyRequest trunk = copyRequest(req);
        trunk.setDestLat(stopLat);
        trunk.setDestLon(stopLon);
        trunk.setDestName(fmtStop(stopId));
        trunk.setDestStopId(stopId);
        trunk.setDestIsGps(false);

        JourneyRequest lastMile = copyRequest(req);
        lastMile.setOriginLat(stopLat);
        lastMile.setOriginLon(stopLon);
        lastMile.setOriginName(fmtStop(stopId));
        lastMile.setOriginStopId(stopId);
        lastMile.setOriginIsGps(false);

        List<String> quiet = new ArrayList<>();
        JourneyOption bus  = planBus(trunk, quiet, weather);
        if (bus == null) return null;
        JourneyOption ride = rideOption(lastMile, quiet, weather, maxWalkM, vehicleMode);
        if (ride == null) return null;

        return stitch(req, weather, vehicleMode, List.of(bus, ride), "BUS_" + vehicleMode,
                bus.getModeLabel() + " + " + ride.getModeLabel(),
                (req.isItalian() ? "Bus fino a " : "Bus to ") + fmtStop(stopId)
                        + (req.isItalian() ? ", poi " : ", then ") + vehicleWord(req, vehicleMode),
                busEmoji(vehicleMode, true));
    }

    /** Shared vehicle to the boarding stop, bus for the long haul. */
    private JourneyOption vehicleThenBus(JourneyRequest req, WeatherService.WeatherData weather,
                                         int maxWalkM, String vehicleMode, String stopId) {
        if (stopId == null) return null;

        double stopLat = getStopLat(stopId), stopLon = getStopLon(stopId);

        // Nothing to ride if you are already standing at the stop
        if (haversineMetres(req.getOriginLat(), req.getOriginLon(), stopLat, stopLon) < MIN_VEHICLE_LEG_M) {
            return null;
        }
        if (bikeSharingService.findNearest(req.getOriginLat(), req.getOriginLon(), vehicleMode, maxWalkM).isEmpty()) {
            return null;
        }

        JourneyRequest firstMile = copyRequest(req);
        firstMile.setDestLat(stopLat);
        firstMile.setDestLon(stopLon);
        firstMile.setDestName(fmtStop(stopId));
        firstMile.setDestStopId(stopId);
        firstMile.setDestIsGps(false);

        JourneyRequest trunk = copyRequest(req);
        trunk.setOriginLat(stopLat);
        trunk.setOriginLon(stopLon);
        trunk.setOriginName(fmtStop(stopId));
        trunk.setOriginStopId(stopId);
        trunk.setOriginIsGps(false);

        List<String> quiet = new ArrayList<>();
        JourneyOption ride = rideOption(firstMile, quiet, weather, maxWalkM, vehicleMode);
        if (ride == null) return null;
        JourneyOption bus = planBus(trunk, quiet, weather);
        if (bus == null) return null;

        return stitch(req, weather, vehicleMode, List.of(ride, bus), vehicleMode + "_BUS",
                ride.getModeLabel() + " + " + bus.getModeLabel(),
                capitalise(vehicleWord(req, vehicleMode))
                        + (req.isItalian() ? " fino a " : " to ") + fmtStop(stopId)
                        + (req.isItalian() ? ", poi bus" : ", then bus"),
                busEmoji(vehicleMode, false));
    }

    /**
     * Bus, shared vehicle, bus again: the vehicle bridges two stops that no line
     * joins, or joins badly. This is the shape that makes the trip properly
     * multimodal rather than merely two-legged — get off where a vehicle is
     * waiting, ride across the gap, board again on a corridor that actually goes
     * where you are going, and leave the vehicle at that stop.
     */
    private JourneyOption busVehicleBus(JourneyRequest req, WeatherService.WeatherData weather,
                                        int maxWalkM, String vehicleMode,
                                        String stop1, String stop2) {
        if (stop1 == null || stop2 == null || stop1.equals(stop2)) return null;

        double s1Lat = getStopLat(stop1), s1Lon = getStopLon(stop1);
        double s2Lat = getStopLat(stop2), s2Lon = getStopLon(stop2);
        if (haversineMetres(s1Lat, s1Lon, s2Lat, s2Lon) < MIN_VEHICLE_LEG_M) return null;

        JourneyRequest first = copyRequest(req);
        first.setDestLat(s1Lat); first.setDestLon(s1Lon);
        first.setDestName(fmtStop(stop1)); first.setDestStopId(stop1); first.setDestIsGps(false);

        JourneyRequest middle = copyRequest(req);
        middle.setOriginLat(s1Lat); middle.setOriginLon(s1Lon);
        middle.setOriginName(fmtStop(stop1)); middle.setOriginStopId(stop1); middle.setOriginIsGps(false);
        middle.setDestLat(s2Lat); middle.setDestLon(s2Lon);
        middle.setDestName(fmtStop(stop2)); middle.setDestStopId(stop2); middle.setDestIsGps(false);

        JourneyRequest last = copyRequest(req);
        last.setOriginLat(s2Lat); last.setOriginLon(s2Lon);
        last.setOriginName(fmtStop(stop2)); last.setOriginStopId(stop2); last.setOriginIsGps(false);

        List<String> quiet = new ArrayList<>();
        JourneyOption busA = planBus(first, quiet, weather);
        if (busA == null) return null;
        JourneyOption ride = rideOption(middle, quiet, weather, maxWalkM, vehicleMode);
        if (ride == null) return null;
        JourneyOption busB = planBus(last, quiet, weather);
        if (busB == null) return null;

        return stitch(req, weather, vehicleMode, List.of(busA, ride, busB),
                "BUS_" + vehicleMode + "_BUS",
                busA.getModeLabel() + " + " + ride.getModeLabel() + " + " + busB.getModeLabel(),
                (req.isItalian() ? "Bus, " : "Bus, ") + vehicleWord(req, vehicleMode)
                        + (req.isItalian() ? " da " : " from ") + fmtStop(stop1)
                        + (req.isItalian() ? " a " : " to ") + fmtStop(stop2)
                        + (req.isItalian() ? ", poi bus" : ", then bus"),
                "🚌+" + ("SCOOTER".equalsIgnoreCase(vehicleMode) ? "🛴" : "🚲") + "+🚌");
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

        // The bus segments carry the delay and the emissions; the vehicle carries
        // the Elerent details. With two bus rides the first one owns the delay:
        // it is the one the traveller is about to catch.
        JourneyOption bus  = chain.stream().filter(o -> "BUS".equals(o.getMode())).findFirst().orElse(chain.get(0));
        JourneyOption ride = chain.stream().filter(o -> !"BUS".equals(o.getMode())).findFirst().orElse(chain.get(0));

        return JourneyOption.builder()
                .mode(mode).modeLabel(modeLabel)
                .durationMinutes(totalMin).distanceMetres(totalM)
                .costEuros(cost)
                // The bus is the only emitting part; the shared vehicle adds none.
                .greenIndex(bus.getGreenIndex())
                .co2Grams(chain.stream().mapToDouble(o -> nz(o.getCo2Grams())).sum())
                .etaMinutes(totalMin)
                .summary(emoji + " " + summaryText
                        + " — " + totalMin + " min (~€" + String.format("%.2f", cost) + ")")
                .weatherWarning(ride.getWeatherWarning() != null ? ride.getWeatherWarning() : bus.getWeatherWarning())
                .weatherSuggestion(weather.suggestion)
                .delayMinutes(bus.getDelayMinutes()).delayStatus(bus.getDelayStatus())
                .delayRealTime(bus.getDelayRealTime()).delayAtStop(bus.getDelayAtStop())
                .delayLabel(bus.getDelayLabel())
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

    private static String busEmoji(String vehicleMode, boolean busFirst) {
        String v = "SCOOTER".equalsIgnoreCase(vehicleMode) ? "🛴" : "🚲";
        return busFirst ? "🚌+" + v : v + "+🚌";
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

    /**
     * How many nearby stops are considered as interchange points. Three keeps the
     * search cheap while allowing the vehicle to reach past the closest stop when
     * the closest one is poorly served.
     */
    private static final int INTERCHANGE_CANDIDATES = 5;

    /** Survivors of the cheap filter that get a full plan, most direct first. */
    private static final int MAX_COMBOS_PLANNED = 3;

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
     * A walking stretch: how far, how long, and the shape to draw it with.
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

        int cursor = 0, firstIdx = -1, lastIdx = -1;
        for (String stopId : legStops) {
            double lat = getStopLat(stopId), lon = getStopLon(stopId);

            int found = -1;
            for (int i = cursor; i < shape.size(); i++) {
                var p = shape.get(i);
                if (Math.abs(p.getLat() - lat) < STOP_MATCH_TOLERANCE
                 && Math.abs(p.getLon() - lon) < STOP_MATCH_TOLERANCE) {
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

        List<double[]> out = new ArrayList<>(lastIdx - firstIdx + 1);
        for (int i = firstIdx; i <= lastIdx; i++) {
            out.add(new double[]{ shape.get(i).getLat(), shape.get(i).getLon() });
        }
        return out;
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