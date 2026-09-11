package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers the question the bus can no longer answer for us:
 * "which trip is this vehicle running right now?"
 *
 * How it works:
 *   1. The MQTT payload gives us a vehicle_id → buses.current_vehicle_id → bus_id
 *   2. Postgres knows which trips are assigned to that bus (trips.bus_id)
 *   3. The schedule tells us which of those trips is in service at this moment
 *   4. If more than one candidate survives (overlapping trips), the GPS fix
 *      breaks the tie: we pick the trip whose stops are closest to the bus.
 *
 * The result is cached per vehicle and only re-resolved when the current
 * trip's service window has elapsed. On a 10s telemetry interval this means
 * roughly one Postgres round-trip per bus per trip, not one per message.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TripResolutionService {

    private final ScheduledStopRepository scheduledStopRepo;
    private final StopRepository          stopRepository;
    private final RouteMatchingService    routeMatchingService;
    private final RoutePatternService     routePatternService;

    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");

    /** vehicleId → currently assigned trip */
    private final Map<String, ActiveTrip> cache = new ConcurrentHashMap<>();

    /**
     * vehicleId → corsa che quel mezzo ha SMENTITO percorrendola al contrario.
     *
     * PERCHE' NON BASTA SVUOTARE LA CACHE. Rilasciare e basta rimanderebbe alla
     * stessa ricerca, con gli stessi dati, che rifarebbe la stessa scelta: il
     * mezzo tornerebbe sulla corsa appena smentita al messaggio successivo, in
     * un ciclo. La corsa va tolta dalle candidate.
     *
     * Una sola per mezzo: quella in corso. Non serve una storia — quando la
     * finestra di quella corsa si chiude smette da sola di essere candidabile,
     * e la voce diventa inerte.
     */
    private final Map<String, String> rejected = new ConcurrentHashMap<>();

    /**
     * Il mezzo ha dimostrato coi fatti che questa corsa non e' la sua.
     *
     * La prova e' il verso di marcia lungo la sequenza — vedi
     * ScheduleAdherenceService.trackDirection — non un'inferenza geometrica.
     */
    public void reject(String vehicleId, String tripId) {
        if (vehicleId == null || tripId == null) return;
        rejected.put(vehicleId, tripId);
        cache.remove(vehicleId);
        log.warn("Vehicle {} ha smentito la corsa {}: percorsa al contrario. "
               + "Esclusa dalle candidate.", vehicleId, tripId);
    }

    /**
     * A trip in service, with the window during which it is valid.
     *
     * @param routeName the route short name — what the UI shows as "route"
     */
    public record ActiveTrip(
            String tripId,
            String routeId,
            String routeName,
            String routeLongName,
            int    startSeconds,
            int    endSeconds,
            /** Terminus coordinates: how we know the bus has actually arrived. */
            Double terminusLat,
            Double terminusLon
    ) {
        boolean covers(int nowSeconds) {
            return nowSeconds >= startSeconds - PRE_TRIP_LEAD_SECONDS
                    && nowSeconds <= endSeconds;
        }
    }

    /**
     * Quanto prima della partenza un mezzo viene gia' associato alla sua corsa.
     *
     * PERCHE' ESISTE
     * --------------
     * Fra una corsa e l'altra il bus sta fermo al capolinea, e senza corsa e'
     * invisibile: ETAService non produce previsioni per lui, la mappa dei mezzi
     * in linea non lo mostra (la linea si deduce dalla corsa) e il pianificatore
     * annuncia "nessun bus in tempo reale per questa linea". Il passeggero alla
     * fermata vede solo l'orario di tabella fino all'istante della partenza,
     * proprio quando l'informazione piu' utile e' "il tuo autobus e' quello, ed
     * e' al capolinea".
     *
     * Mezz'ora e' l'intervallo fra due partenze della stessa linea: con un
     * anticipo piu' corto restava una finestra in cui la corsa successiva non
     * era ancora di nessuno, e alla fermata ricompariva il solo orario di
     * tabella. Coprendo l'intero intervallo, ogni corsa ha sempre un mezzo
     * associato — a patto che la linea ne abbia uno che trasmette.
     *
     * Il valore e' pubblico perche' ETAService vi commisura il proprio
     * orizzonte di previsione: assegnare un veicolo con mezz'ora di anticipo e
     * poi scartarne l'arrivo perche' lontano sarebbe lavoro sprecato.
     */
    public static final int PRE_TRIP_LEAD_SECONDS = 30 * 60;

    /**
     * Quanto puo' distare un mezzo dalla partenza di una corsa non ancora
     * cominciata, per potergliela attribuire.
     *
     * Piu' largo di TERMINUS_RADIUS_M, che serve a decidere se un mezzo E'
     * ARRIVATO: qui la domanda e' se stia aspettando li', e un capolinea e' un
     * piazzale, non un punto. Quattrocento metri coprono il mezzo parcheggiato
     * in fondo alla piazza, l'imprecisione del GPS e una fermata il cui punto
     * a registro cade sul marciapiede opposto.
     *
     * Largo abbastanza da non togliere corse a chi ce l'ha davvero, stretto
     * abbastanza da escludere un mezzo che sta percorrendo un'altra parte della
     * rete — che e' l'unico caso che deve escludere.
     */
    private static final double PRE_TRIP_RADIUS_M = 400.0;

    /**
     * A trip is not released when its scheduled end passes — only when the bus
     * really gets to the terminus. A late bus is still running its trip, and
     * dropping it there would blank the route on the map and in SIRI exactly
     * when the delay makes that information most useful.
     */
    private static final double TERMINUS_RADIUS_M = 120.0;

    /**
     * Hard stop for that grace period. Without it a bus that never reaches its
     * terminus (diverted, broken down and towed, GPS drift) would hold its trip
     * for the rest of the day.
     */
    private static final int MAX_OVERRUN_SECONDS = 45 * 60;

    private static double metresBetween(double aLat, double aLon, double bLat, double bLon) {
        double R = 6_371_000;
        double dLat = Math.toRadians(bLat - aLat), dLon = Math.toRadians(bLon - aLon);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
    }

    /** True when the vehicle is standing at (or within a block of) the terminus. */
    private static boolean atTerminus(ActiveTrip trip, Double lat, Double lon) {
        if (lat == null || lon == null
                || trip.terminusLat() == null || trip.terminusLon() == null) return false;
        return metresBetween(lat, lon, trip.terminusLat(), trip.terminusLon()) <= TERMINUS_RADIUS_M;
    }

    /**
     * Resolve the trip this vehicle is currently running.
     *
     * @param busId     from buses.current_vehicle_id lookup — null means unknown vehicle
     * @param vehicleId used as the cache key
     * @param lat       current GPS latitude, used only to disambiguate
     * @param lon       current GPS longitude, used only to disambiguate
     * @return the active trip, or empty outside service hours
     */
    public Optional<ActiveTrip> resolve(Integer busId, String vehicleId,
                                        Double lat, Double lon) {
        if (busId == null || vehicleId == null) return Optional.empty();

        int now = LocalTime.now(ITALY_TZ).toSecondOfDay();

        ActiveTrip cached = cache.get(vehicleId);
        if (cached != null && cached.covers(now)) {
            // ── L'assegnazione in anticipo resta PROVVISORIA ───────
            //
            // covers() comprende i trenta minuti PRIMA della partenza, e questo
            // ramo usciva subito: la corsa veniva scelta una volta sola, al
            // primo messaggio utile, e da li' in poi congelata. Qualunque
            // controllo di plausibilita' piu' a valle non veniva mai raggiunto
            // — ed e' il motivo per cui le correzioni precedenti a choose() non
            // cambiavano niente: sceglievano meglio, ma nessuno le richiamava.
            //
            // Finche' la corsa non e' partita l'assegnazione e' una PREVISIONE,
            // e va riverificata a ogni fix: se il mezzo non e' piu' alla
            // partenza — perche' se ne sta allontanando, o non ci e' mai stato
            // — la previsione e' smentita dai fatti e si lascia cadere.
            //
            // Dalla partenza in poi non si tocca piu' niente: una corsa in
            // svolgimento va tenuta anche quando il mezzo devia, ed e' quello
            // che fa il ramo qui sotto sulla tolleranza del ritardo.
            boolean provisional = now < cached.startSeconds();
            if (!provisional || startsNearby(cached.tripId(), lat, lon)) {
                return Optional.of(cached);
            }
            log.info("Vehicle {} released trip {} — assegnata in anticipo, ma il mezzo "
                   + "non e' alla partenza", vehicleId, cached.tripId());
            cache.remove(vehicleId);
            cached = null;
        }

        // Past the scheduled end, but the bus may simply be late. Hold the trip
        // until it actually reaches the terminus, so a delayed run keeps its
        // route on the map, in SIRI and in the adherence figures.
        if (cached != null && now > cached.endSeconds()) {
            boolean arrived  = atTerminus(cached, lat, lon);
            boolean gaveUp   = now > cached.endSeconds() + MAX_OVERRUN_SECONDS;
            // Il mezzo ha gia' cominciato la corsa successiva: tenere quella
            // scaduta lo lascerebbe sulla linea sbagliata fino allo scadere della
            // tolleranza. Succede ogni volta che l'arrivo al capolinea non viene
            // rilevato — GPS impreciso, tracciato che non passa sul punto, o un
            // riallineamento dell'orario come quello del 2 settembre — e per
            // tre quarti d'ora il veicolo mostra una corsa che non sta facendo.
            boolean superseded = !arrived && !gaveUp && hasStartedLaterTrip(busId, now, cached);
            if (!arrived && !gaveUp && !superseded) {
                return Optional.of(cached);          // late, still out there
            }
            log.info("Vehicle {} released trip {} — {}", vehicleId, cached.tripId(),
                    arrived    ? "arrived at terminus"
                    : superseded ? "a later trip is already in service"
                    : "no arrival " + (MAX_OVERRUN_SECONDS / 60) + " min past its end");
            cache.remove(vehicleId);
            cached = null;
        }

        List<Object[]> all = scheduledStopRepo.findActiveTripsForBus(
                busId, now, now + PRE_TRIP_LEAD_SECONDS);

        // Fuori la corsa che questo mezzo ha gia' smentito percorrendola al
        // contrario: senza, la ricerca la ripescherebbe subito e si tornerebbe
        // al punto di partenza a ogni messaggio.
        String banned = rejected.get(vehicleId);
        List<Object[]> rows = banned == null ? all
                : all.stream().filter(r -> !banned.equals(r[0])).toList();

        if (rows.isEmpty()) {
            if (cached != null) {
                log.info("Vehicle {} finished trip {} — no trip in service at {}s",
                        vehicleId, cached.tripId(), now);
            }
            cache.remove(vehicleId);
            return Optional.empty();
        }

        Object[] chosen = choose(rows, now, lat, lon);

        // Nessuna candidata plausibile: il mezzo non sta facendo niente di
        // quello che l'orario gli attribuisce. Meglio senza corsa che con la
        // corsa di un altro — vedi choose().
        if (chosen == null) {
            if (cached != null) {
                log.info("Vehicle {} released trip {} — nessuna corsa plausibile per la sua posizione",
                        vehicleId, cached.tripId());
            }
            cache.remove(vehicleId);
            return Optional.empty();
        }

        // Terminus looked up once per trip assignment, not per message.
        String tripId = (String) chosen[0];
        Double tLat = null, tLon = null;
        List<Object[]> term = scheduledStopRepo.findTerminusOf(tripId);
        if (!term.isEmpty()) {
            Object[] t = term.get(0);
            tLat = t[1] == null ? null : ((Number) t[1]).doubleValue();
            tLon = t[2] == null ? null : ((Number) t[2]).doubleValue();
        }

        ActiveTrip trip = new ActiveTrip(
                tripId,
                (String) chosen[1],
                (String) chosen[2],
                (String) chosen[3],
                ((Number) chosen[4]).intValue(),
                ((Number) chosen[5]).intValue(),
                tLat, tLon);

        cache.put(vehicleId, trip);
        log.info("Vehicle {} (bus {}) → trip {} on route {} [{}s..{}s]",
                vehicleId, busId, trip.tripId(), trip.routeId(),
                trip.startSeconds(), trip.endSeconds());
        return Optional.of(trip);
    }

    /**
     * Esiste, per questo mezzo, una corsa iniziata dopo quella in cache e gia'
     * in servizio adesso?
     *
     * Solo le corse gia' PARTITE contano: quelle assegnate in anticipo (vedi
     * PRE_TRIP_LEAD_SECONDS) non devono strappare via una corsa ancora in corso
     * a un mezzo semplicemente in ritardo.
     */
    private boolean hasStartedLaterTrip(Integer busId, int now, ActiveTrip cached) {
        for (Object[] row : scheduledStopRepo.findActiveTripsForBus(busId, now, now)) {
            int start = ((Number) row[4]).intValue();
            if (start > cached.startSeconds() && start <= now) return true;
        }
        return false;
    }

    /** Forget a vehicle's assignment — e.g. when it goes offline. */
    public void evict(String vehicleId) {
        cache.remove(vehicleId);
    }

    /**
     * Fra le corse candidate, quale sta facendo davvero il mezzo.
     *
     * PRIMA IL FATTO, POI LA PREVISIONE
     * Le candidate sono di due nature diverse. Una corsa il cui orario e' gia'
     * cominciato e' un fatto: il mezzo la sta percorrendo. Una che deve ancora
     * partire e' una previsione, ammessa solo grazie a PRE_TRIP_LEAD_SECONDS e
     * solo perche' un mezzo senza corsa sparisce da tutto cio' che sta a valle.
     * Metterle in concorrenza fa vincere la previsione sul fatto.
     *
     * E' quello che accadeva. V24 genera per ogni linea non ad anello un'andata
     * e un ritorno con le STESSE fermate in ordine inverso; la partenza del
     * ritorno cade entro la mezz'ora di anticipo, quindi durante tutta l'andata
     * le due corse risultavano candidate insieme. Il confronto geografico non
     * le distingue — distanceToTrip guarda la fermata piu' vicina, e l'insieme
     * delle fermate e' lo stesso nelle due direzioni, quindi il numero e'
     * identico — e a parita' restava la prima della lista, ordinata per
     * partenza piu' recente: il ritorno. Il mezzo appariva percorrere la linea
     * al contrario, con capolinea e fermata successiva scambiati e i ritardi
     * misurati contro l'orario dell'altra direzione.
     *
     * La regola qui sotto e' la stessa gia' applicata da hasStartedLaterTrip:
     * l'anticipo serve a coprire l'attesa al capolinea, non a scavalcare una
     * corsa in svolgimento.
     */
    private Object[] choose(List<Object[]> rows, int now, Double lat, Double lon) {
        // chosen[4] = MIN(arrivalSeconds), la partenza di tabella della corsa.
        List<Object[]> started = rows.stream()
                .filter(r -> ((Number) r[4]).intValue() <= now)
                .toList();

        if (!started.isEmpty()) {
            return started.size() == 1 ? started.get(0) : bestByGps(started, now, lat, lon);
        }

        // ── Nessuna corsa partita: si guarda l'anticipo ────────────
        //
        // L'ANTICIPO VA MERITATO. PRE_TRIP_LEAD_SECONDS esiste per il mezzo che
        // ATTENDE AL CAPOLINEA: senza, per mezz'ora sarebbe invisibile a tutto
        // cio' che sta a valle. Ma finora bastava che una corsa esistesse in
        // quella finestra per attaccarla al mezzo, ovunque esso fosse — e con
        // una sola candidata non c'era nemmeno un confronto geografico, perche'
        // pool.size() == 1 usciva prima.
        //
        // Il risultato e' il mezzo che tutti riconoscono: nessun ritardo
        // ("Awaiting first arrival"), FERMATA PRECEDENTE VUOTA — la corsa non e'
        // partita, quindi non ha ancora agganciato niente — e FERMATA SUCCESSIVA
        // valorizzata, perche' senza ancora si ripiega sulla prima fermata della
        // corsa. Il pannello annuncia cosi' il capolinea di partenza di un
        // viaggio che comincera' altrove, mentre il mezzo se ne allontana.
        //
        // Ora la previsione deve essere plausibile: il mezzo dev'essere alla
        // partenza di quella corsa. Se non c'e', non ha nessuna corsa — che e'
        // la verita': sta rientrando, e' in trasferimento, o e' fermo altrove.
        List<Object[]> plausible = rows.stream()
                .filter(r -> startsNearby((String) r[0], lat, lon))
                .toList();

        if (plausible.isEmpty()) return null;
        return plausible.size() == 1 ? plausible.get(0) : bestByGps(plausible, now, lat, lon);
    }

    /**
     * Il mezzo si trova alla partenza di questa corsa?
     *
     * Senza posizione la domanda non si puo' porre e si risponde di si': il
     * controllo serve a scartare un'assegnazione palesemente sbagliata, non a
     * lasciare senza corsa un mezzo di cui non sappiamo dove sia.
     */
    private boolean startsNearby(String tripId, Double lat, Double lon) {
        if (lat == null || lon == null) return true;

        List<ScheduledStop> calls = scheduledStopRepo.findByTripIdOrderByStopSequenceAsc(tripId);
        if (calls.isEmpty()) return false;

        ScheduledStop first = calls.get(0);
        String routeId = routeIdOf(calls);
        String stopId  = routePatternService.stopIdAt(routeId, first.getStopSequence());
        if (stopId == null) return false;

        Optional<Stop> stopOpt = stopRepository.findById(stopId);
        if (stopOpt.isEmpty()) return false;
        Stop stop = stopOpt.get();
        if (stop.getLat() == null || stop.getLon() == null) return false;

        double d = routeMatchingService.haversineMetres(lat, lon, stop.getLat(), stop.getLon());
        return d <= PRE_TRIP_RADIUS_M;
    }

    /**
     * Fra corse che restano in concorrenza: quella il cui orario mette il mezzo
     * piu' vicino a dove il mezzo si trova davvero.
     *
     * PERCHE' NON "LA FERMATA PIU' VICINA"
     * Era cosi', e non distingueva le direzioni: andata e ritorno hanno lo
     * stesso INSIEME di fermate, quindi la distanza dalla piu' vicina e'
     * identica al bit, la condizione {@code <} non scattava mai e restava la
     * prima della lista — ordinata per partenza piu' recente, cioe' il ritorno.
     * Il mezzo appariva percorrere la linea al contrario.
     *
     * La posizione ATTESA invece le distingue per costruzione, perche' dipende
     * dall'ordine e non dall'insieme: alle 06:05 l'andata delle 06:00 aspetta il
     * mezzo vicino al capolinea di partenza, il ritorno delle 06:30 lo aspetta
     * all'altro capo della linea. Basta guardare dov'e' il mezzo.
     *
     * Vale anche fra due corse non ancora partite — dove la regola "prima il
     * fatto" di choose() non puo' dire niente — ed e' li' che serviva.
     */
    private Object[] bestByGps(List<Object[]> rows, int now, Double lat, Double lon) {
        if (lat == null || lon == null) return rows.get(0);

        Object[] best     = rows.get(0);
        double   bestDist = Double.MAX_VALUE;

        for (Object[] row : rows) {
            double dist = distanceToScheduledPosition((String) row[0], now, lat, lon);
            if (dist < bestDist) {
                bestDist = dist;
                best     = row;
            }
        }
        log.debug("Disambiguated {} overlapping trips by expected position → {} ({} m)",
                rows.size(), best[0], Math.round(bestDist));
        return best;
    }

    /**
     * La linea di una corsa, letta dalla prima riga di orario.
     *
     * Le query che alimentano questi percorsi usano JOIN FETCH su trip e route
     * proprio perché qui non c'è una sessione Hibernate aperta: il gestore MQTT
     * non è transazionale, e una navigazione pigra fallirebbe.
     */
    private static String routeIdOf(List<ScheduledStop> calls) {
        if (calls.isEmpty()) return null;
        ScheduledStop first = calls.get(0);
        return first.getTrip() != null && first.getTrip().getRoute() != null
                ? first.getTrip().getRoute().getId() : null;
    }

    /**
     * Quanto dista il mezzo da dove QUESTA corsa lo vorrebbe adesso.
     *
     * La posizione attesa e' la fermata il cui orario di tabella e' piu' vicino
     * all'istante corrente, con l'istante limitato alla finestra della corsa:
     * prima della partenza e' il capolinea di partenza, dopo l'arrivo e' quello
     * di arrivo, in mezzo la fermata di turno. E' l'ORDINE a decidere, non
     * l'insieme delle fermate — ed e' esattamente per questo che distingue
     * l'andata dal ritorno, che le fermate le hanno tutte in comune.
     *
     * Sostituisce la vecchia "distanza dalla fermata piu' vicina", che per due
     * corse speculari restituiva lo stesso identico numero.
     */
    private double distanceToScheduledPosition(String tripId, int now, double lat, double lon) {
        List<ScheduledStop> calls = scheduledStopRepo.findByTripIdOrderByStopSequenceAsc(tripId);
        if (calls.isEmpty()) return Double.MAX_VALUE;

        // Tutte le righe appartengono alla stessa corsa, quindi alla stessa
        // linea: risolverla una volta invece che a ogni fermata.
        String routeId = routeIdOf(calls);

        // La fermata di turno all'istante chiesto. Fuori dalla finestra della
        // corsa vince l'estremo piu' vicino, che e' il comportamento voluto:
        // una corsa non ancora partita attende il mezzo al suo capolinea.
        ScheduledStop expected = null;
        int bestGap = Integer.MAX_VALUE;
        for (ScheduledStop ss : calls) {
            int gap = Math.abs(ss.getArrivalSeconds() - now);
            if (gap < bestGap) { bestGap = gap; expected = ss; }
        }
        if (expected == null) return Double.MAX_VALUE;

        String patternStopId = routePatternService.stopIdAt(routeId, expected.getStopSequence());
        if (patternStopId == null) return Double.MAX_VALUE;

        Optional<Stop> stopOpt = stopRepository.findById(patternStopId);
        if (stopOpt.isEmpty()) return Double.MAX_VALUE;
        Stop stop = stopOpt.get();
        if (stop.getLat() == null || stop.getLon() == null) return Double.MAX_VALUE;

        return routeMatchingService.haversineMetres(lat, lon, stop.getLat(), stop.getLon());
    }
}
