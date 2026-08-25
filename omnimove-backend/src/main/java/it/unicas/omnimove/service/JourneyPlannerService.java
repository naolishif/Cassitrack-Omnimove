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

    @Value("${elerent.bike.unlock:0.50}")
    private double bikeUnlock;
    @Value("${elerent.bike.per-minute:0.15}")
    private double bikePerMin;
    @Value("${elerent.scooter.unlock:1.00}")
    private double scooterUnlock;
    @Value("${elerent.scooter.per-minute:0.25}")
    private double scooterPerMin;

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

        boolean busDeferred = preferBike && modes.contains("BIKE") && modes.contains("BUS");
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
                    msgs.add("🚌 Nessuna linea bus disponibile o dati insufficienti per questo percorso.");
                }
            } catch (Exception e) {
                log.warn("Failed {} option: {}", mode, e.getMessage());
                if ("BUS".equals(mode.toUpperCase())) {
                    msgs.add("🚌 Errore nel calcolo del percorso bus.");
                }
            }
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
                                raining && !"BUS".equals(o.getMode()) ? 1 : 0)   // bus prima se piove
                        .thenComparingInt(JourneyOption::getDurationMinutes));

        boolean onlyBusWhenRaining = true; // default
        if (req.getUserId() != null) {
            onlyBusWhenRaining = preferencesRepository.findByUserId(req.getUserId())
                    .map(p -> Boolean.TRUE.equals(p.getOnlyBusWhenRaining()))
                    .orElse(true);
        }
        if (raining && onlyBusWhenRaining) {
            options.removeIf(o -> "BIKE".equals(o.getMode())
                    || "SCOOTER".equals(o.getMode())
                    || "WALK".equals(o.getMode()));
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
        java.time.Instant departureBase = resolveDepartureBase(req.getDepartureTime(), msgs);

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
        if (fromGps) {
            var walkResult = googleMapsService.getTravelTime(
                    req.getOriginLat(), req.getOriginLon(),
                    getStopLat(nearestStop), getStopLon(nearestStop),
                    "walking");
            if (walkResult.isPresent()) {
                walkMetres = walkResult.get().distanceMetres();
                walkMin    = (int) Math.ceil(walkResult.get().durationSeconds() / 60.0);
            } else {
                msgs.add("🚶 Non riesco a calcolare il percorso a piedi fino alla fermata "
                        + fmtStop(nearestStop)
                        + ". Recati alla fermata per prendere il bus.");
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
                        .routeId(t.l1RouteId())
                        .build());
                busLegs.add(JourneyLeg.builder().mode("WAIT")
                        .from(fmtStop(t.stop())).to(fmtStop(t.stop()))
                        .durationMinutes(changeWait).distanceMetres(0.0)
                        .instruction("Change at " + fmtStop(t.stop()) + " · take " + t.l2Label()).build());
                busLegs.add(JourneyLeg.builder().mode("BUS")
                        .from(fmtStop(t.stop())).to(req.getDestName())
                        .durationMinutes(l2Min).distanceMetres(m2)
                        .instruction(t.l2Label())
                        .stopCoords(slice2.coords())
                        .stopNames(slice2.names())
                        .routeId(t.l2RouteId())
                        .build());
            } else {
                log.warn("BUS: nessuna linea diretta né cambio trovato tra {} e {}", nearestStop, destStop);
                return null;
            }
        }
        if (!useGoogle) {
            msgs.add("ℹ️ Live traffic is off — bus times are from the timetable, not real-time.");
        }

        int total = walkMin + waitMin + busMin;
        List<JourneyLeg> legs = new ArrayList<>();
        if (walkMin > 0) legs.add(JourneyLeg.builder().mode("WALK")
                .from(req.getOriginName()).to(fmtStop(nearestStop))
                .durationMinutes(walkMin).distanceMetres(walkMetres)
                .instruction("Walk to bus stop").build());
        legs.add(JourneyLeg.builder().mode("WAIT")
                .from(fmtStop(nearestStop)).to(fmtStop(nearestStop))
                .durationMinutes(waitMin).distanceMetres(0.0)
                .instruction("Wait " + waitMin + " min for " + lineLabel).build());
        legs.addAll(busLegs);

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

        return JourneyOption.builder()
                .mode("BUS").modeLabel(lineLabel)
                .durationMinutes(total).distanceMetres(busMetres)
                .costEuros(COST_BUS)
                .greenIndex(greenIndex.computeGreenIndex("BUS", busMetres / 1000.0))
                .co2Grams(greenIndex.computeCo2Grams("BUS", busMetres / 1000.0))
                .etaMinutes(total)
                .delayMinutes(isNow && busDelay != null ? busDelay.delayMinutes() : null)
                .delayStatus(isNow && busDelay != null ? busDelay.status() : null)
                .delayRealTime(isNow && busDelay != null ? busDelay.realTime() : null)
                .delayAtStop(isNow && busDelay != null ? busDelay.atStop() : null)
                .delayLabel(isNow ? delayLabel(busDelay) : null)
                .summary("Take " + lineLabel + " from " + fmtStop(nearestStop))
                .weatherWarning(occupancyWarning != null ? occupancyWarning
                        : weatherService.getModeWarning(weather.condition, "BUS"))
                .weatherSuggestion(weather.suggestion)
                .legs(legs).build();
    }

    private JourneyOption planBike(JourneyRequest req, List<String> msgs,
                                   WeatherService.WeatherData weather, int maxWalkM) {
        return planSharedVehicle(req, msgs, weather, maxWalkM,
                "BIKE", "Elerent Bike Share", 15.0, bikeUnlock, bikePerMin, "🚲", "bike");
    }

    private JourneyOption planScooter(JourneyRequest req, List<String> msgs,
                                      WeatherService.WeatherData weather, int maxWalkM) {
        return planSharedVehicle(req, msgs, weather, maxWalkM,
                "SCOOTER", "Elerent E-Scooter", SPEED_SCOOTER, scooterUnlock, scooterPerMin, "🛴", "e-scooter");
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
            double speedKmh, double unlock, double perMin, String emoji, String noun) {

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
            var w = googleMapsService.getTravelTime(
                    req.getOriginLat(), req.getOriginLon(), v.getLat(), v.getLon(), "walking");
            walkM = w.map(g -> (double) g.distanceMetres()).orElse(airM * 1.3);
            walkMin = w.map(g -> (int) Math.ceil(g.durationSeconds() / 60.0))
                       .orElse((int) Math.ceil(walkM / 1000.0 / 5.0 * 60));
            legs.add(JourneyLeg.builder().mode("WALK")
                    .from(req.getOriginName()).to("Elerent " + vehName)
                    .durationMinutes(walkMin).distanceMetres(walkM)
                    .stopCoords(List.of(
                            new double[]{req.getOriginLat(), req.getOriginLon()},
                            new double[]{v.getLat(), v.getLon()}))
                    .instruction("Walk " + fmtDist(walkM) + " to " + noun + " " + vehName)
                    .build());
        }

        var r = googleMapsService.getTravelTime(
                v.getLat(), v.getLon(), req.getDestLat(), req.getDestLon(), "bicycling");
        double rideM = r.map(g -> (double) g.distanceMetres())
                        .orElseGet(() -> {
                            log.warn("{}: Google non disponibile — uso stima haversine", mode);
                            return haversineMetres(v.getLat(), v.getLon(),
                                                   req.getDestLat(), req.getDestLon()) * 1.25;
                        });
        // Google's "bicycling" duration fits bikes; scooters keep their own speed
        int rideMin = "BIKE".equals(mode)
                ? r.map(g -> (int) Math.ceil(g.durationSeconds() / 60.0))
                   .orElse((int) Math.ceil(rideM / 1000.0 / speedKmh * 60))
                : (int) Math.ceil(rideM / 1000.0 / speedKmh * 60);
        double cost = Math.round((unlock + rideMin * perMin) * 100) / 100.0;

        legs.add(JourneyLeg.builder().mode(mode)
                .from(legs.isEmpty() ? req.getOriginName() : "Elerent " + vehName)
                .to(req.getDestName())
                .durationMinutes(rideMin).distanceMetres(rideM)
                .stopCoords(List.of(
                        new double[]{v.getLat(), v.getLon()},
                        new double[]{req.getDestLat(), req.getDestLon()}))
                .instruction("Elerent " + noun + " " + vehName + " · Unlock €" + unlock
                        + " + €" + perMin + "/min · elerent.it")
                .build());

        String zoneWarning = bikeSharingService
                .checkDestinationZones(req.getDestLat(), req.getDestLon())
                .map(issue -> switch (issue) {
                    case OUT_OF_OPERATING_AREA -> req.isItalian()
                            ? "⚠️ Destinazione fuori dalla zona operativa Elerent: la corsa non può terminare lì."
                            : "⚠️ Destination outside the Elerent operating area: the ride cannot end there.";
                    case NO_PARKING -> req.isItalian()
                            ? "⚠️ Destinazione in zona divieto di sosta Elerent: parcheggia ai margini della zona."
                            : "⚠️ Destination inside an Elerent no-parking zone: park at the zone edge.";
                })
                .orElse(null);

        int totalMin = walkMin + rideMin;
        String summary = ("SCOOTER".equals(mode) ? "🛴 " : "") + "Elerent " + noun + " " + vehName
                + (walkMin > 0 ? (req.isItalian() ? " a " : " at ") + fmtDist(airM) : "")
                + " — " + totalMin + " min (~€" + String.format("%.2f", cost) + ")";

        return JourneyOption.builder()
                .mode(mode).modeLabel(label)
                .durationMinutes(totalMin).distanceMetres(walkM + rideM)
                .costEuros(cost).greenIndex(100).co2Grams(0.0).etaMinutes(totalMin)
                .summary(summary)
                .weatherWarning(weatherService.getModeWarning(weather.condition, mode))
                .weatherSuggestion(weather.suggestion)
                .bikeId(v.getBikeId()).bikePlate(v.getPlate())
                .bikeBatteryPct(v.getBatteryPct()).bikeWalkMetres(airM)
                .bikeWarning(zoneWarning)
                .legs(legs)
                .build();
    }

    private JourneyOption planWalk(JourneyRequest req, WeatherService.WeatherData weather) {
        var r = route(req, "walking");
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
                        .instruction("Walk the entire route").build()))
                .build();
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
    private java.time.Instant resolveDepartureBase(String hhmm, List<String> msgs) {
        if (hhmm == null || hhmm.isBlank()) return java.time.Instant.now();
        try {
            java.time.ZoneId tz = java.time.ZoneId.of("Europe/Rome");
            java.time.LocalTime t = java.time.LocalTime.parse(hhmm.trim());   // "HH:mm"
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(tz);
            java.time.ZonedDateTime cand = now.with(t);
            if (cand.isBefore(now)) {
                cand = cand.plusDays(1);
                if (msgs != null) {
                    msgs.add("\u23F0 " + hhmm.trim()
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
    private Optional<GoogleMapsService.TrafficResult> route(JourneyRequest req, String mode) {
        return googleMapsService.getTravelTime(
                req.getOriginLat(), req.getOriginLon(),
                req.getDestLat(),   req.getDestLon(), mode);
    }

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
    private record StopSlice(List<double[]> coords, List<String> names) {}

    private StopSlice stopSliceBetween(String tripId, String originStopId, String destStopId) {
        if (tripId == null) return new StopSlice(List.of(), List.of());

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
        if (oi < 0 || di < 0) return new StopSlice(List.of(), List.of());

        int from = Math.min(oi, di);
        int to   = Math.max(oi, di);

        // The stops this leg calls at, in travel order.
        List<String> legStops = new ArrayList<>();
        for (int i = from; i <= to; i++) legStops.add(seq.get(i).getStopId());

        // Prefer the real road geometry, so the drawn leg follows the streets
        // the bus follows. Falls back to stop-to-stop when the route has no
        // shape (or the shape cannot be matched) — see roadPathAlong().
        List<double[]> road = roadPathAlong(tripId, legStops);
        if (!road.isEmpty()) {
            // Build stop names in travel order alongside the road coords
            List<String> roadNames = new ArrayList<>();
            for (String sid : legStops) roadNames.add(fmtStop(sid));
            return new StopSlice(road, roadNames);
        }

        List<double[]> coords = new ArrayList<>();
        List<String>   names  = new ArrayList<>();
        for (String sid : legStops) {
            coords.add(new double[]{ getStopLat(sid), getStopLon(sid) });
            names.add(fmtStop(sid));
        }
        return new StopSlice(coords, names);
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