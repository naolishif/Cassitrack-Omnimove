package it.unicas.cassitrack.service;

import it.unicas.cassitrack.model.ScheduledStop;
import it.unicas.cassitrack.model.Stop;
import it.unicas.cassitrack.repository.ScheduledStopRepository;
import it.unicas.cassitrack.repository.StopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Matches a bus GPS position to:
 *   1. The nearest bus stop
 *   2. The scheduled arrival time at that stop
 *
 * This is how we know a bus is "at" a stop
 * even though GPS coordinates are never exact.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RouteMatchingService {

    private final ScheduledStopRepository scheduledStopRepo;
    private final StopRepository stopRepository;
    private final RoutePatternService routePatternService;

    /**
     * La prossima fermata: identita' e orario previsto.
     *
     * arrivalSeconds è l'orario di tabella (secondi dalla mezzanotte), non
     * un'attesa: chi lo riceve calcola quanto manca sommandoci il ritardo
     * misurato e sottraendo l'ora corrente.
     */
    public record UpcomingStop(String id, String name, Integer arrivalSeconds) {}

    /** Una riga di orario con la sua fermata, risolta dal pattern della linea. */
    public record StopOnTrip(String stopId, int stopSequence, int arrivalSeconds) {}

    public List<ScheduledStop> tripSequence(String tripId) {
        return tripId == null ? List.of()
                : scheduledStopRepo.findByTripIdOrderByStopSequenceAsc(tripId);
    }

    /**
     * La fermata in posizione {@code stopSequence}, o null oltre il capolinea.
     *
     * Da V27 lo stopId arriva dal pattern della linea. Il pattern è in cache,
     * quindi questo metodo — che gira a ogni messaggio GPS — non paga più una
     * query per risolvere la fermata.
     */
    public StopOnTrip stopAtSequence(String tripId, int stopSequence) {
        for (ScheduledStop ss : tripSequence(tripId)) {
            if (ss.getStopSequence() == stopSequence) {
                String routeId = ss.getTrip() != null && ss.getTrip().getRoute() != null
                        ? ss.getTrip().getRoute().getId() : null;
                String stopId  = routePatternService.stopIdAt(routeId, stopSequence);
                if (stopId == null) return null;   // pattern più corto dell'orario
                return new StopOnTrip(stopId, ss.getStopSequence(), ss.getArrivalSeconds());
            }
        }
        return null;
    }

    /** Distanza dal fix a una fermata, o null se la fermata è sconosciuta. */
    public Double distanceToStop(String stopId, double lat, double lon) {
        Stop s = stopRepository.findById(stopId).orElse(null);
        if (s == null || s.getLat() == null || s.getLon() == null) return null;
        return haversineMetres(lat, lon, s.getLat(), s.getLon());
    }

    /**
     * Quanto si è avvicinato alla fermata il TRATTO percorso fra due fix, e in
     * che punto del tratto.
     *
     * @param metres distanza minima fra la fermata e il segmento A→B
     * @param t      posizione del punto più vicino lungo il segmento, 0 = A, 1 = B
     */
    public record SegmentApproach(double metres, double t) {}

    /**
     * Il passaggio da una fermata va cercato sul PERCORSO fra due fix, non sui
     * fix stessi.
     *
     * Un OBU che trasmette una volta al minuto a 25 km/h lascia buchi di circa
     * 420 m: la fermata viene superata *fra* due invii e nessuno dei due cade
     * abbastanza vicino da contare come arrivo. Misurando invece la distanza
     * dal segmento che li unisce, la fermata superata risulta a pochi metri
     * dal percorso, come deve essere.
     *
     * Il segmento è una retta: fra due fix il bus ha in realtà seguito la
     * strada. È un'approssimazione accettabile perché serve solo a stabilire
     * SE la fermata è stata superata, non a ricostruire la traiettoria.
     *
     * {@code t} permette di datare l'arrivo per interpolazione invece di
     * attribuirlo all'istante di uno dei due fix: con 60 s di intervallo,
     * sbagliare estremo significa sbagliare il ritardo di un minuto intero.
     */
    public SegmentApproach approachToStopAlongSegment(String stopId,
                                                      double aLat, double aLon,
                                                      double bLat, double bLon) {
        Stop s = stopRepository.findById(stopId).orElse(null);
        if (s == null || s.getLat() == null || s.getLon() == null) return null;

        // Proiezione equirettangolare centrata sulla fermata: su distanze di
        // poche centinaia di metri l'errore è trascurabile e permette di
        // lavorare in metri con la geometria piana.
        double lat0 = Math.toRadians(s.getLat());
        double mPerDegLat = 111_132.0;
        double mPerDegLon = 111_320.0 * Math.cos(lat0);

        double ax = (aLon - s.getLon()) * mPerDegLon, ay = (aLat - s.getLat()) * mPerDegLat;
        double bx = (bLon - s.getLon()) * mPerDegLon, by = (bLat - s.getLat()) * mPerDegLat;

        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;

        // Fix fermo o duplicato: il segmento degenera in un punto.
        if (len2 < 1e-9) return new SegmentApproach(Math.hypot(ax, ay), 1.0);

        // Proiezione della fermata (origine) sul segmento, vincolata agli estremi.
        double t = -(ax * dx + ay * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));

        double cx = ax + t * dx, cy = ay + t * dy;
        return new SegmentApproach(Math.hypot(cx, cy), t);
    }

    /**
     * Aggancio iniziale: quando non sappiamo ancora dove sia il bus lungo la corsa
     * (avvio del servizio, cambio corsa), si sceglie l'occorrenza il cui orario di
     * tabella è più vicino all'ora corrente. Su un anello questo è l'unico criterio
     * che distingue il quinto passaggio dal quattordicesimo.
     */
    public Integer bootstrapSequence(String tripId, int nowSecondsOfDay) {
        ScheduledStop best = null;
        for (ScheduledStop ss : tripSequence(tripId)) {   // già ordinata per stop_sequence
            if (ss.getArrivalSeconds() <= nowSecondsOfDay) best = ss;
            else break;
        }
        // Prima della partenza: aggancia al capolinea, il candidato sarà la seconda fermata.
        return best != null ? best.getStopSequence()
                : (tripSequence(tripId).isEmpty() ? null : 1);
    }
    /**
     * La fermata immediatamente successiva nella sequenza. Null al capolinea.
     *
     * SENZA ANCORAGGIO SI RISPONDE LO STESSO
     * stopSequence e' null quando il mezzo non ha ancora superato nessuna fermata.
     * Succede per tutta l'attesa al capolinea, che non e' un caso raro: la corsa
     * viene assegnata fino a PRE_TRIP_LEAD_SECONDS (mezz'ora) prima di partire, e
     * con un orario semihorario significa che a ogni istante buona parte della
     * flotta e' in quello stato.
     *
     * Prima si tornava null e l'interfaccia mostrava un trattino. Ma un mezzo
     * fermo al capolinea la prossima fermata ce l'ha eccome — e' la prima della
     * corsa che sta per fare — e la conosciamo gia'. La fermata PRECEDENTE resta
     * invece vuota, ed e' corretto: di fermate non ne ha ancora fatte.
     */
    public UpcomingStop nextStopAfterSequence(String tripId, Integer stopSequence) {
        if (stopSequence == null) {
            List<ScheduledStop> seq = tripSequence(tripId);
            if (seq.isEmpty()) return null;

            // Si risolve qui invece di chiamare stopAtSequence: quello
            // riinterrogherebbe la stessa sequenza appena letta, e questo metodo
            // gira a ogni messaggio GPS di ogni mezzo in attesa.
            //
            // Non si assume che la prima posizione sia 1: si prende quella che la
            // corsa dichiara per prima.
            ScheduledStop first = seq.get(0);
            String routeId = first.getTrip() != null && first.getTrip().getRoute() != null
                    ? first.getTrip().getRoute().getId() : null;
            String stopId = routePatternService.stopIdAt(routeId, first.getStopSequence());
            return stopId == null ? null
                    : new UpcomingStop(stopId, stopName(stopId), first.getArrivalSeconds());
        }
        StopOnTrip next = stopAtSequence(tripId, stopSequence + 1);
        return next == null ? null
                : new UpcomingStop(next.stopId(), stopName(next.stopId()), next.arrivalSeconds());
    }

    /**
     * Fra due occorrenze equidistanti si sceglie per orario: sotto questa
     * soglia le distanze si considerano uguali. Un metro basta — su un anello
     * la stessa fermata ripetuta da' distanze identiche al bit, non simili.
     */
    private static final double SAME_DISTANCE_METRES = 1.0;

    /**
     * La posizione della sequenza piu' vicina al punto in cui si trova il mezzo.
     *
     * A COSA SERVE
     * A rimettere l'ancora dove il bus e' davvero, quando la macchina degli
     * arrivi si accorge di averla persa. E' una risposta della GEOGRAFIA, non
     * della sequenza: non presuppone di sapere quanta strada sia stata fatta,
     * ed e' esattamente cio' che serve quando quel presupposto e' saltato.
     *
     * ANELLI
     * Su una linea che ripassa dalle stesse fermate la distanza da sola non
     * distingue il quinto passaggio dal quattordicesimo: sono lo stesso punto.
     * A parita' di distanza decide l'orario di tabella piu' vicino all'ora
     * corrente — lo stesso criterio di bootstrapSequence, per non avere due
     * regole diverse che rispondono alla stessa domanda.
     *
     * @return la stop_sequence piu' vicina, o null se la corsa non e' leggibile
     */
    public Integer nearestSequence(String tripId, Double lat, Double lon, int nowSecondsOfDay) {
        if (lat == null || lon == null) return null;
        List<ScheduledStop> seq = tripSequence(tripId);
        if (seq.isEmpty()) return null;

        String routeId = seq.get(0).getTrip() != null && seq.get(0).getTrip().getRoute() != null
                ? seq.get(0).getTrip().getRoute().getId() : null;
        if (routeId == null) return null;

        Integer best = null;
        double  bestDistance = Double.MAX_VALUE;
        int     bestTimeGap  = Integer.MAX_VALUE;

        for (ScheduledStop ss : seq) {
            String stopId = routePatternService.stopIdAt(routeId, ss.getStopSequence());
            if (stopId == null) continue;
            Double d = distanceToStop(stopId, lat, lon);
            if (d == null) continue;

            int gap = Math.abs(ss.getArrivalSeconds() - nowSecondsOfDay);
            boolean closer   = d < bestDistance - SAME_DISTANCE_METRES;
            boolean sameSpot = Math.abs(d - bestDistance) <= SAME_DISTANCE_METRES;

            if (closer || (sameSpot && gap < bestTimeGap)) {
                best         = ss.getStopSequence();
                bestDistance = Math.min(d, bestDistance);
                bestTimeGap  = gap;
            }
        }
        return best;
    }

    /**
     * Resolve a stop id to its display name.
     * Returns the id itself if the stop is unknown, never null-propagates.
     */
    public String stopName(String stopId) {
        if (stopId == null) return null;
        return stopRepository.findById(stopId).map(Stop::getName).orElse(stopId);
    }

    /**
     * Calculate the distance in metres between two GPS coordinates.
     * Haversine formula — the same used in navigation systems.
     */
    public double haversineMetres(
            double lat1, double lon1,
            double lat2, double lon2) {

        final double R = 6371000; // Earth radius in metres
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
