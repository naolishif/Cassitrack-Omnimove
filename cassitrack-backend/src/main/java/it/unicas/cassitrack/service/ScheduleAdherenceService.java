package it.unicas.cassitrack.service;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import it.unicas.cassitrack.model.VehiclePosition;
import it.unicas.cassitrack.model.VehiclePosition.ScheduleStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;


@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduleAdherenceService {

    private final VehicleStateCache vehicleStateCache;
    private final RouteMatchingService routeMatchingService;

    // Injects the InfluxDB writer to keep historical records of delays and crowding
    private final WriteApiBlocking influxWriteApi;

    private static final int SLIGHTLY_LATE_MINUTES = 3;
    private static final int SIGNIFICANTLY_LATE_MINUTES = 10;
    private static final ZoneId ITALY_TZ = ZoneId.of("Europe/Rome");

    /**
     * Il minimo dell'avvicinamento deve cadere sotto questa soglia perché il
     * passaggio conti come arrivo. Un bus che sfila a 200 m ha preso una deviazione.
     */
    private static final double APPROACH_GATE_METRES = 80.0;

    /**
     * La distanza deve crescere di ALMENO tanto perché sia un vero allontanamento
     * e non rumore GPS. Finestra utile: [17,0 · 19,7] m — sotto i 17,0 il rumore
     * (±6 m su lat e lon, quindi 2·6·√2) genera falsi arrivi; sopra i 19,7 si perde
     * il passo di interpolazione più corto della rete (XXS→GIA).
     */
    private static final double RECESSION_MARGIN_METRES = 18.0;

    /**
     * Quanto deve restare fermo un bus perché la sosta valga come prova
     * d'arrivo, in assenza di ripartenza.
     *
     * Più lunga della sosta normale in fermata (il simulatore ne usa una da
     * ~45 s): sotto questa soglia l'arrivo lo conferma comunque la partenza,
     * sopra vuol dire che il bus è lì e non riparte — tipicamente il capolinea.
     */
    private static final long SETTLED_SECONDS = 90;

    /**
     * Quanti arretramenti di fila bastano a dichiarare sbagliata la corsa.
     *
     * Tre e non uno: a cavallo di una fermata l'indice piu' vicino puo'
     * alternarsi fra due valori per il solo rumore del GPS, e un mezzo che
     * manovra al capolinea indietreggia davvero per qualche metro. Tre passi
     * indietro consecutivi, ciascuno con un movimento vero alle spalle, non
     * capitano per caso su una corsa percorsa nel verso giusto.
     */
    public static final int WRONG_WAY_STRIKES = 3;

    /**
     * Sotto questo spostamento fra due fix non si giudica il verso.
     *
     * Fermo al semaforo o in sosta, la posizione oscilla di pochi metri e
     * l'indice piu' vicino puo' cambiare senza che il mezzo si sia mosso:
     * conterebbero passi indietro che non sono avvenuti.
     */
    private static final double WRONG_WAY_MIN_MOVE_M = 60.0;

    /** Da quanti secondi il mezzo non si sposta davvero (0 se sta viaggiando). */
    private static long stoppedFor(VehiclePosition pos) {
        if (pos.getStationarySince() == null) return 0;
        return java.time.Duration.between(pos.getStationarySince(), Instant.now()).getSeconds();
    }

    /**
     * Limiti oltre i quali la retta fra due fix non rappresenta più il percorso
     * davvero seguito. 3 minuti coprono con margine l'invio a 60 s dell'OBU
     * reale, anche saltandone uno; 2 km sono più di quanto un bus urbano
     * percorra in quel tempo, ma molto meno del salto che si vede dopo
     * un'assenza vera.
     */
    private static final Duration MAX_SEGMENT_GAP    = Duration.ofMinutes(3);
    private static final double   MAX_SEGMENT_METRES = 2000.0;

    /** Unica fonte di verità: da minuti di ritardo a stato di puntualità. */
    public static ScheduleStatus statusFromDelay(Integer delayMinutes) {
        if (delayMinutes == null)                       return ScheduleStatus.UNKNOWN;
        if (delayMinutes < -1)                          return ScheduleStatus.EARLY;
        if (delayMinutes <= SLIGHTLY_LATE_MINUTES)      return ScheduleStatus.ON_TIME;
        if (delayMinutes <= SIGNIFICANTLY_LATE_MINUTES) return ScheduleStatus.SLIGHTLY_LATE;
        return ScheduleStatus.SIGNIFICANTLY_LATE;
    }

    @Scheduled(fixedRate = 30000)
    public void checkAdherence() {
        Collection<VehiclePosition> activeBuses = vehicleStateCache.getActive();
        for (VehiclePosition pos : activeBuses) {
            processBusAdherence(pos);
            vehicleStateCache.update(pos.getVehicleId(), pos);
        }
    }

    public void processBusAdherence(VehiclePosition pos) {
        try {
            // PRIMA di ogni altra cosa: recupera quello che già sappiamo di
            // questo veicolo. Le uscite qui sotto non devono MAI cancellare
            // un'aderenza già misurata.
            carryOverState(pos);

            if (pos.getLat() == null || pos.getLon() == null) return;

            // Fra una corsa e l'altra il bus non ha un trip: TripResolutionService
            // non restituisce nulla finché non inizia la successiva. Non è una
            // perdita di informazione — il bus è ancora in ritardo di quanto lo
            // era al capolinea — quindi l'ultimo stato misurato resta.
            if (pos.getTripId() == null) return;

            int nowSeconds = secondsOfDay(pos.getTimestamp());

            // La corsa e' assegnata ma non e' ancora partita: il mezzo sta
            // aspettando al capolinea. Non c'e' nulla da misurare, e misurare
            // qui darebbe un anticipo fittizio pari all'attesa — un bus fermo
            // in orario risulterebbe "in anticipo di dodici minuti".
            if (pos.getTripStartSeconds() != null && nowSeconds < pos.getTripStartSeconds()) {
                if (pos.getScheduleStatus() == null) pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
                return;
            }

            // ── Sta percorrendo questa corsa al contrario? ────────
            // Prima dell'aggancio, perche' non dipende dall'ancora e perche'
            // dev'essere valutato anche quando la macchina degli arrivi non
            // conclude nulla — che e' proprio il caso in cui serve.
            trackDirection(pos, nowSeconds);

            // ── Aggancio iniziale ────────────────────────────────
            if (pos.getLastStopSequence() == null) {
                Integer seq = routeMatchingService.bootstrapSequence(pos.getTripId(), nowSeconds);
                if (seq == null) {
                    // Corsa senza fermate: non è tracciabile. Non cancella però
                    // una misura già riportata — resterebbe comunque l'ultima
                    // informazione vera che abbiamo su questo bus.
                    if (pos.getScheduleStatus() == null) pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
                    return;
                }

                // SOLO l'ancora. lastStopRegisteredId resta vuoto di proposito:
                // significa "fermata dove il mezzo e' stato visto", e qui non
                // abbiamo visto niente — la posizione l'ha dedotta l'orologio.
                //
                // Scriverla faceva comparire sotto LAST STOP una fermata mai
                // toccata, tipicamente il capolinea di partenza della corsa,
                // mentre due righe piu' sotto il pannello ammetteva "Awaiting
                // first arrival". Due affermazioni opposte sulla stessa scheda:
                // quella sbagliata era la prima.
                //
                // Il trattino che resta e' l'informazione giusta, e dura poco:
                // alla prima fermata davvero superata il campo si riempie.
                pos.setLastStopSequence(seq);
                resetApproach(pos);

                var anchor = routeMatchingService.stopAtSequence(pos.getTripId(), seq);
                log.info("Bus {} agganciato alla corsa {} da seq {} ({}) — per orario, "
                       + "nessun arrivo osservato",
                        pos.getVehicleId(), pos.getTripId(), seq,
                        anchor != null ? anchor.stopId() : "?");
                // Nessun ritardo NUOVO: quell'arrivo non l'abbiamo osservato.
                // Lo stato riportato da carryOverState resta valido fino alla
                // prossima fermata, che lo ricalcolerà.
                return;
            }

            // ── Il candidato è SEMPRE la fermata successiva. Mai una precedente. ──
            var candidate = routeMatchingService.stopAtSequence(
                    pos.getTripId(), pos.getLastStopSequence() + 1);
            if (candidate == null) return;   // capolinea: TripResolutionService riaggancerà

            // Distanza e istante di massimo avvicinamento sul TRATTO percorso
            // dall'ultimo fix, non sul fix isolato. Vedi measureApproach().
            Approach ap = measureApproach(pos, candidate.stopId());
            if (ap == null) return;
            double  d      = ap.metres();
            Instant closest = ap.at();

            // Nuovo candidato → inizializza il minimo
            if (!Integer.valueOf(candidate.stopSequence()).equals(pos.getApproachStopSequence())
                    || pos.getApproachMinDistanceMetres() == null) {
                pos.setApproachStopSequence(candidate.stopSequence());
                pos.setApproachMinDistanceMetres(d);
                pos.setApproachMinTimestamp(closest);
                return;
            }

            // Ancora in avvicinamento → il minimo scende
            if (d < pos.getApproachMinDistanceMetres()) {
                pos.setApproachMinDistanceMetres(d);
                pos.setApproachMinTimestamp(closest);
                return;
            }

            // Due prove possibili che l'arrivo è avvenuto.
            //
            // 1. ALLONTANAMENTO — il bus riparte: il minimo era la fermata.
            //    Vale per le fermate intermedie, dove il bus riparte sempre.
            //
            // 2. SOSTA PROLUNGATA — il bus si ferma lì e non riparte.
            //    Al CAPOLINEA la prova 1 non arriva mai: il mezzo resta fermo a
            //    fine corsa, quindi l'ultima fermata non veniva mai registrata e
            //    la corsa restava per sempre a N-1 su N (OVERDUE a vita).
            //    Essersi fermati accanto alla fermata è una prova d'arrivo
            //    altrettanto buona della ripartenza — purché la sosta superi
            //    quella normale di servizio, altrimenti registreremmo l'arrivo
            //    mentre il bus sta ancora facendo salire i passeggeri.
            boolean movedAway = d >= pos.getApproachMinDistanceMetres() + RECESSION_MARGIN_METRES;
            boolean settled   = d <= APPROACH_GATE_METRES && stoppedFor(pos) >= SETTLED_SECONDS;
            if (!movedAway && !settled) return;

            // ── Il minimo e' stato superato: era quello l'arrivo? ──
            double  minDist = pos.getApproachMinDistanceMetres();
            Instant minAt   = pos.getApproachMinTimestamp();

            // TROPPO LONTANO: NON SI AVANZA, SI RI-AGGANCIA
            //
            // Prima l'ancora avanzava comunque di una posizione. Sembra
            // prudente — il mezzo di li' e' passato, sia pure alla larga — ma
            // se l'ancora si trova DIETRO al senso di marcia ogni candidata e'
            // alle spalle del bus: la distanza puo' solo crescere, la prova di
            // allontanamento scatta al primo confronto, e l'ancora percorre da
            // sola tutta la sequenza fino al capolinea, una fermata ogni due
            // messaggi. Da li' in poi la fermata successiva non esiste piu' e
            // la macchina resta bloccata fino al cambio di corsa: e' il mezzo
            // senza NEXT STOP, con il ritardo fermo a una fermata di parecchio
            // precedente.
            //
            // Il rimedio non e' fermarsi — l'ancora ferma sarebbe altrettanto
            // sbagliata — ma rimetterla DOVE IL MEZZO SI TROVA, chiedendolo
            // alla geografia invece che alla sequenza. Nel caso legittimo (una
            // fermata sfiorata a 120 m perche' il tracciato non passa sul
            // punto) la piu' vicina e' proprio quella, e il comportamento resta
            // quello di prima: si avanza, senza misurare.
            if (minDist > APPROACH_GATE_METRES) {
                Integer here = routeMatchingService.nearestSequence(
                        pos.getTripId(), pos.getLat(), pos.getLon(), nowSeconds);
                resetApproach(pos);
                if (here != null && !here.equals(pos.getLastStopSequence())) {
                    // Si sposta l'ANCORA, non la fermata dichiarata.
                    // lastStopRegisteredId viene scritto in un punto solo di
                    // tutta la classe — l'arrivo confermato qui sotto — e
                    // questo non lo e': e' un recupero, la fermata piu' vicina
                    // puo' essere a centinaia di metri. Il pannello continua a
                    // mostrare l'ultima fermata davvero osservata, o il
                    // trattino se non ce n'e' ancora una.
                    var at = routeMatchingService.stopAtSequence(pos.getTripId(), here);
                    pos.setLastStopSequence(here);
                    log.warn("Bus {} passato a {} m da {} — troppo lontano. Ancora "
                           + "riportata a seq {} ({}), la fermata piu' vicina al fix.",
                            pos.getVehicleId(), Math.round(minDist), candidate.stopId(),
                            here, at != null ? at.stopId() : "?");
                } else {
                    log.warn("Bus {} passato a {} m da {} — troppo lontano, arrivo non "
                           + "registrato e ancora invariata.",
                            pos.getVehicleId(), Math.round(minDist), candidate.stopId());
                }
                return;   // nessuna misura: il ritardo resta quello di prima
            }

            // ── Arrivo confermato: il minimo ERA la fermata ──
            pos.setLastStopSequence(candidate.stopSequence());
            pos.setLastStopRegisteredId(candidate.stopId());
            resetApproach(pos);

            int arrivedAt    = secondsOfDay(minAt);
            int delaySeconds = arrivedAt - candidate.arrivalSeconds();
            int delayMinutes = Math.round(delaySeconds / 60.0f);   // arrotonda, non tronca

            pos.setDelayMinutes(delayMinutes);
            pos.setScheduleStatus(statusFromDelay(delayMinutes));
            pos.setDelayStopId(candidate.stopId());
            pos.setDelayStopName(routeMatchingService.stopName(candidate.stopId()));
            pos.setDelayStopSequence(candidate.stopSequence());
            pos.setDelayMeasuredAt(minAt);

            writeArrivalEvent(pos, candidate, delayMinutes, minAt);
            logArrival(pos, candidate, arrivedAt, delaySeconds, delayMinutes, minDist);

        } catch (Exception e) {
            log.warn("Adherence non calcolabile per {}: {}", pos.getVehicleId(), e.getMessage());
            // Un errore di calcolo non è una notizia sul bus: se un ritardo era
            // già stato misurato resta valido. UNKNOWN solo se non sappiamo nulla.
            if (pos.getScheduleStatus() == null) pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
        } finally {
            // In finally perché il metodo ha una dozzina di uscite anticipate e
            // il tratto successivo deve comunque partire da qui.
            rememberFix(pos);
        }
    }

    /**
     * Il mezzo sta avanzando o arretrando lungo la corsa che gli e' assegnata?
     *
     * L'IDEA
     * La corsa e' una sequenza ordinata di fermate. Si guarda dove cadeva il
     * mezzo lungo quella sequenza al fix precedente e dove cade adesso: se
     * l'indice sale sta percorrendo la corsa nel verso giusto, se scende la sta
     * percorrendo al contrario — e allora quella corsa non e' la sua.
     *
     * PERCHE' L'ORDINE E NON LE FERMATE
     * Confrontare QUALI fermate ha toccato non servirebbe: andata e ritorno
     * hanno lo stesso insieme di fermate, e un mezzo che va al contrario le
     * tocca tutte lo stesso. Cambia solo l'ordine, ed e' li' che si vede.
     *
     * PERCHE' NON SI GUARDA LO STORICO DEGLI ARRIVI
     * Perche' con il verso sbagliato quello storico e' VUOTO, ed e' proprio il
     * sintomo: ogni fermata candidata sta dietro al mezzo, il gate degli 80 m
     * la rifiuta sempre, nessun arrivo viene mai registrato. Aspettare gli
     * arrivi significherebbe aspettare una cosa che non arrivera' mai. Il
     * movimento fra due fix invece c'e' da subito.
     *
     * SUGLI ANELLI FUNZIONA LO STESSO: la sequenza di una corsa e' comunque
     * lineare, 1..N, anche quando ripassa dalle stesse fermate — e nearestSequence
     * scioglie le ripetizioni con l'orario di tabella.
     *
     * Non decide niente: aggiorna solo un contatore. Chi lo legge decide.
     */
    private void trackDirection(VehiclePosition pos, int nowSeconds) {
        Double pLat = pos.getPrevFixLat(), pLon = pos.getPrevFixLon();
        if (pLat == null || pLon == null || pos.getLat() == null || pos.getLon() == null) return;

        // Movimento troppo piccolo per dire qualcosa sul verso.
        if (routeMatchingService.haversineMetres(pLat, pLon, pos.getLat(), pos.getLon())
                < WRONG_WAY_MIN_MOVE_M) return;

        Integer before = routeMatchingService.nearestSequence(pos.getTripId(), pLat, pLon, nowSeconds);
        Integer nowAt  = routeMatchingService.nearestSequence(
                pos.getTripId(), pos.getLat(), pos.getLon(), nowSeconds);
        if (before == null || nowAt == null) return;

        int strikes = pos.getWrongWayStrikes() == null ? 0 : pos.getWrongWayStrikes();
        if (nowAt < before) {
            strikes++;
            log.debug("Bus {} arretrato lungo {} — da seq {} a seq {} ({}/{})",
                    pos.getVehicleId(), pos.getTripId(), before, nowAt, strikes, WRONG_WAY_STRIKES);
        } else if (nowAt > before) {
            strikes = 0;      // un passo avanti cancella il sospetto
        }
        // nowAt == before: fermo lungo la sequenza, il contatore non si muove
        pos.setWrongWayStrikes(strikes);
    }

    /** Il mezzo ha smentito la corsa assegnata percorrendola al contrario. */
    public static boolean runsWrongWay(VehiclePosition pos) {
        return pos != null && pos.getWrongWayStrikes() != null
                && pos.getWrongWayStrikes() >= WRONG_WAY_STRIKES;
    }

    /**
     * Lo stato vive in Redis. Due categorie, con regole diverse.
     *
     * ANCORAGGIO (lastStopSequence, approach*) — relativo alla CORSA. Al cambio
     * di corsa riparte da zero: una sequenza della corsa precedente farebbe
     * cercare la fermata sbagliata.
     *
     * ADERENZA (delayMinutes, scheduleStatus e il contesto della misura) —
     * relativa al VEICOLO. Un bus arrivato con 6 minuti di ritardo al capolinea
     * è ancora in ritardo di 6 minuti quando parte per la corsa successiva: il
     * ritardo non si azzera perché cambia l'identificativo della corsa. Quindi
     * l'ultimo stato misurato viene mantenuto fino alla fermata successiva, che
     * lo ricalcola — invece di tornare UNKNOWN a ogni inizio corsa.
     */
    private void carryOverState(VehiclePosition pos) {
        vehicleStateCache.get(pos.getVehicleId()).ifPresent(prev -> {
            // Il fix precedente è un fatto sul VEICOLO: vale anche se nel
            // frattempo è cambiata la corsa, perché il bus il tratto lo ha
            // percorso comunque.
            pos.setPrevFixLat(prev.getPrevFixLat());
            pos.setPrevFixLon(prev.getPrevFixLon());
            pos.setPrevFixAt(prev.getPrevFixAt());

            if (java.util.Objects.equals(prev.getTripId(), pos.getTripId())) {
                pos.setLastStopSequence(prev.getLastStopSequence());
                pos.setLastStopRegisteredId(prev.getLastStopRegisteredId());
                pos.setApproachStopSequence(prev.getApproachStopSequence());
                pos.setApproachMinDistanceMetres(prev.getApproachMinDistanceMetres());
                pos.setApproachMinTimestamp(prev.getApproachMinTimestamp());
                // Anche il sospetto sul verso e' relativo alla CORSA: si accumula
                // fra un messaggio e l'altro, ma cambiando corsa riparte da zero.
                // Portarselo dietro accuserebbe la corsa nuova di cio' che ha
                // fatto il mezzo sulla precedente.
                pos.setWrongWayStrikes(prev.getWrongWayStrikes());
            }
            if (!adherenceStillMeaningful(prev)) return;

            pos.setDelayMinutes(prev.getDelayMinutes());
            pos.setScheduleStatus(prev.getScheduleStatus());
            pos.setDelayStopId(prev.getDelayStopId());
            pos.setDelayStopName(prev.getDelayStopName());
            pos.setDelayStopSequence(prev.getDelayStopSequence());
            pos.setDelayMeasuredAt(prev.getDelayMeasuredAt());
        });
        if (pos.getScheduleStatus() == null) pos.setScheduleStatus(ScheduleStatus.UNKNOWN);
    }

    /**
     * Un ritardo misurato vale per tutta la giornata di servizio in cui è stato
     * preso, e solo per quella.
     *
     * Il criterio è la DATA, non un intervallo di tempo. Una soglia a minuti
     * sembrava prudente ma è sbagliata: basta che un bus passi troppo lontano
     * dalle sue fermate per un po' — succede davvero, vedi il gate degli 80 m —
     * e lo stato tornerebbe a UNKNOWN nel mezzo del servizio, che è esattamente
     * ciò che non deve accadere. Il record Redis però non scade, quindi senza
     * alcun limite un bus fermo in deposito ripartirebbe la mattina dopo
     * esibendo il ritardo del giorno prima: da qui il confronto fra date.
     */
    private boolean adherenceStillMeaningful(VehiclePosition prev) {
        if (prev.getScheduleStatus() == null) return false;
        Instant measuredAt = prev.getDelayMeasuredAt();
        // Nessuna misura da riportare: c'è solo lo stato, che a questo punto
        // non può che essere UNKNOWN.
        if (measuredAt == null) return false;
        return measuredAt.atZone(ITALY_TZ).toLocalDate()
                .equals(Instant.now().atZone(ITALY_TZ).toLocalDate());
    }

    /**
     * Un punto per arrivo su InfluxDB. Non uno per ping GPS: quello è il campo
     * "delay" di vehicle_position, scritto dal MqttMessageHandler.
     *
     * L'istante è quello del fix in cui il bus era più vicino alla fermata,
     * non quello in cui il server se n'è accorto: l'allontanamento viene
     * confermato uno o due campioni dopo.
     */
    private void writeArrivalEvent(VehiclePosition pos,
                                   RouteMatchingService.StopOnTrip stop,
                                   int delayMinutes,
                                   Instant arrivedAt) {

        String routeId = pos.getRouteId() != null ? pos.getRouteId() : "UNKNOWN_ROUTE";

        Integer pax = CrowdingService.effectivePassengers(
                pos.getPassengers(), pos.getBleDeviceCount());

        // trip_id is what makes an arrival attributable to a RUN rather than just
        // to a bus at a stop. Without it the Active Trips view cannot tell which
        // of the day's passes through this stop it is looking at — the routes are
        // rings, so the same stop_id recurs many times per vehicle per day.
        Point arrivalEvent = Point.measurement("stop_arrival")
                .addTag("vehicle_id", pos.getVehicleId())
                .addTag("stop_id",    stop.stopId())
                .addTag("route_id",   routeId)
                .addTag("trip_id",    pos.getTripId() != null ? pos.getTripId() : "UNKNOWN_TRIP")
                .addField("bus_id",               pos.getBusId() != null ? pos.getBusId() : 0)
                .addField("stop_sequence",        stop.stopSequence())
                .addField("delay_minutes",        delayMinutes)
                .addField("estimated_passengers", pax != null ? pax : 0)
                .time(arrivedAt != null ? arrivedAt : Instant.now(), WritePrecision.S);

        influxWriteApi.writePoint(arrivalEvent);
    }

    /** Massimo avvicinamento a una fermata, con l'istante in cui è avvenuto. */
    private record Approach(double metres, Instant at) {}

    /**
     * Quanto il bus si è avvicinato alla fermata DA QUANDO l'abbiamo visto
     * l'ultima volta, e quando.
     *
     * Il fix isolato non basta più. Un OBU reale trasmette una volta al minuto:
     * a 25 km/h sono circa 420 m fra un punto e il successivo, e la fermata
     * finisce quasi sempre nel mezzo, mai abbastanza vicina a un fix da
     * superare il gate degli 80 m. Il risultato era un bus che non registrava
     * MAI un arrivo e restava eternamente UNKNOWN.
     *
     * Si misura quindi la distanza dal segmento fra il fix precedente e questo,
     * e si data l'arrivo interpolando fra i due istanti: su 60 s di intervallo
     * attribuirlo a uno dei due estremi significherebbe sbagliare il ritardo
     * fino a un minuto.
     */
    private Approach measureApproach(VehiclePosition pos, String stopId) {
        Instant now = pos.getTimestamp() != null ? pos.getTimestamp() : Instant.now();

        Double  pLat = pos.getPrevFixLat();
        Double  pLon = pos.getPrevFixLon();
        Instant pAt  = pos.getPrevFixAt();

        if (pLat != null && pLon != null && pAt != null && usableSegment(pos, pLat, pLon, pAt)) {
            var seg = routeMatchingService.approachToStopAlongSegment(
                    stopId, pLat, pLon, pos.getLat(), pos.getLon());
            if (seg != null) {
                long span = Duration.between(pAt, now).toMillis();
                Instant at = span > 0
                        ? pAt.plusMillis(Math.round(seg.t() * span))
                        : now;
                return new Approach(seg.metres(), at);
            }
        }

        // Primo fix dopo un riavvio, o salto troppo grande per fidarsi della
        // retta: si ricade sulla misura puntuale, come prima.
        Double d = routeMatchingService.distanceToStop(stopId, pos.getLat(), pos.getLon());
        return d == null ? null : new Approach(d, now);
    }

    /**
     * Il segmento vale solo se i due fix sono abbastanza vicini nel tempo e
     * nello spazio da rendere plausibile la retta che li unisce.
     *
     * Dopo un'assenza lunga — bus spento, unità in avaria, backend riavviato —
     * la congiungente attraversa mezza città e passerebbe "sopra" fermate che
     * il bus non ha mai servito, inventando arrivi.
     */
    private boolean usableSegment(VehiclePosition pos, double pLat, double pLon, Instant pAt) {
        Instant now = pos.getTimestamp() != null ? pos.getTimestamp() : Instant.now();
        Duration gap = Duration.between(pAt, now);
        if (gap.isNegative() || gap.compareTo(MAX_SEGMENT_GAP) > 0) return false;

        double span = routeMatchingService.haversineMetres(pLat, pLon, pos.getLat(), pos.getLon());
        return span <= MAX_SEGMENT_METRES;
    }

    /**
     * Ricorda questo fix come punto di partenza del prossimo tratto.
     *
     * Va fatto a ogni messaggio, qualunque strada prenda il calcolo: se un
     * ritorno anticipato saltasse l'aggiornamento, il tratto successivo
     * partirebbe da un punto vecchio e coprirebbe due intervalli.
     */
    private void rememberFix(VehiclePosition pos) {
        if (pos.getLat() == null || pos.getLon() == null) return;   // niente da ricordare
        pos.setPrevFixLat(pos.getLat());
        pos.setPrevFixLon(pos.getLon());
        pos.setPrevFixAt(pos.getTimestamp() != null ? pos.getTimestamp() : Instant.now());
    }

    private void resetApproach(VehiclePosition pos) {
        pos.setApproachStopSequence(null);
        pos.setApproachMinDistanceMetres(null);
        pos.setApproachMinTimestamp(null);
    }

    /** Secondi dalla mezzanotte dell'ISTANTE DEL FIX, non dell'orologio del server. */
    private int secondsOfDay(Instant fix) {
        Instant t = (fix != null) ? fix : Instant.now();
        return t.atZone(ITALY_TZ).toLocalTime().toSecondOfDay();
    }

    private void logArrival(VehiclePosition pos, RouteMatchingService.StopOnTrip stop,
                            int arrivedAt, int delaySeconds, int delayMinutes, double minDist) {
        String segno = delaySeconds >= 0 ? "+" : "-";
        log.info("{} · {} (seq {}) · previsto {} · reale {} · {}{} s ({} min) · {} · min {} m",
                pos.getVehicleId(),
                routeMatchingService.stopName(stop.stopId()),
                stop.stopSequence(),
                LocalTime.ofSecondOfDay(stop.arrivalSeconds()),
                LocalTime.ofSecondOfDay(arrivedAt),
                segno, Math.abs(delaySeconds), delayMinutes,
                pos.getScheduleStatus(),
                Math.round(minDist));
    }

}