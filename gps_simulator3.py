#!/usr/bin/env python3
"""
CASSITRACK — GPS Bus Simulator v3
University of Cassino and Southern Lazio

COSA CAMBIA RISPETTO A gps_simulator2
─────────────────────────────────────
Il v2 non muove i pullman: ne CALCOLA la posizione dall'orologio. A ogni
pubblicazione cerca il punto del tracciato il cui orario corrisponde a
adesso, e lo pubblica. Ne seguono tre limiti che nessuna aggiunta poteva
togliere:

  · il ritardo è impossibile per costruzione — non esiste una variabile in
    cui possa vivere;
  · il tracciato è una retta fra fermata e fermata, quindi i bus tagliano
    gli isolati invece di seguire le strade disegnate;
  · la velocità è inventata (random.uniform) e scollegata dallo spostamento.

Qui la posizione è STATO: il bus parte da un punto e ci si muove sopra,
integrando `pos += v · dt` lungo i vertici di route_shapes. Da questo solo
cambiamento discende tutto il resto.

IL RITARDO NON SI IMPONE, EMERGE
────────────────────────────────
Non c'è nessun `delay_seconds` sommato o sottratto. Ogni tratta ha un passo
nominale — la distanza REALE lungo la strada divisa per il tempo che
l'orario le concede — e il bus lo percorre al proprio passo, che dipende da
com'è fatta quella corsa e da cosa trova per strada. Arrivare tardi è un
esito fisico, non un numero.

Ne viene gratis anche l'ANTICIPO, che il v2 non poteva produrre: una corsa
con margine, percorsa a passo svelto, arriva prima. Lo stato EARLY esiste
nel backend e finora non lo esercitava nessuno.

L'AUTORITÀ DI RECUPERO È LIMITATA, ED È IL PUNTO
────────────────────────────────────────────────
L'altro simulatore del progetto (tools/simulate_bus_scheduled.py) integra
davvero il moto, eppure è puntuale quasi sempre: il suo controllore ricalcola
a ogni passo la velocità che serve per arrivare in orario, con un tetto di
50 km/h contro i ~20 km/h richiesti dall'orario. Con un margine di 2,5× può
riassorbire qualunque ritardo prima della fermata successiva.

Qui il recupero è limitato a una frazione del passo nominale
(CATCHUP_MAX / SLACK_MAX): un piccolo scarto si riassorbe, un ingorgo vero
no. È la differenza fra un autista che spinge un po' e un autista che
teletrasporta il mezzo.

USO NORMALE
    python gps_simulator3.py

È tutto. Ritardi e anticipi non hanno un interruttore: non sono una funzione
aggiunta, sono la conseguenza del fatto che il mezzo si muove davvero. Anche
guasti e perdite di segnale capitano da soli, con la loro probabilità.

SOLO PER PROVE MIRATE
    python gps_simulator3.py --force-breakdown BUS-101    # STALLED subito
    python gps_simulator3.py --force-signal-loss BUS-102  # NO_SIGNAL subito
    python gps_simulator3.py --raw                        # stampa il JSON

Le due --force servono a provocare l'anomalia ADESSO su un mezzo scelto,
invece di aspettare che càpiti durante una dimostrazione.

Dipendenze:
    pip install paho-mqtt psycopg2-binary tzdata
"""

import argparse
import json
import math
import os
import random
import time
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import paho.mqtt.client as mqtt
import psycopg2
import psycopg2.extras


# ─────────────────────────────────────────────────────────────────
#  Configurazione
# ─────────────────────────────────────────────────────────────────

def _load_env_file(path):
    """Legge cassitrack-backend/.env senza sovrascrivere l'ambiente reale."""
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key, value = key.strip(), value.split("#", 1)[0].strip()
            if key and key not in os.environ:
                os.environ[key] = value


_load_env_file(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "cassitrack-backend", ".env"))

DB_CONFIG = {
    "host":     os.environ.get("SPRING_DATASOURCE_HOST", "localhost"),
    "port":     int(os.environ.get("SPRING_DATASOURCE_PORT", "5433")),
    "dbname":   os.environ.get("SPRING_DATASOURCE_DB", "cassitrack"),
    "user":     os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
    "password": os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
}

ROME = ZoneInfo("Europe/Rome")
TOPIC = "cassitrack/{vehicle_id}/position"


# ─────────────────────────────────────────────────────────────────
#  Parametri del modello
# ─────────────────────────────────────────────────────────────────

# ── Moto ─────────────────────────────────────────────────────────
# Tolleranza con cui una fermata si riconosce in un vertice della shape.
# 1e-5 gradi ≈ 1 m: è la stessa soglia che usa l'editor di percorso quando
# aggancia un vertice a una fermata, quindi le due cose combaciano.
COORD_TOL      = 1e-5
MAX_KMH        = 55.0     # tetto fisico del mezzo, non strumento di recupero
MIN_KMH        = 6.0      # sotto questa soglia si considera fermo

# Autorità di recupero, in frazione del passo nominale della tratta.
# È il parametro che decide se il simulatore sa essere in ritardo.
CATCHUP_MAX    = 0.18     # in ritardo: fino al +18% del passo previsto
SLACK_MAX      = 0.12     # in anticipo: fino al -12%
# Quanto in fretta il passo si adegua allo scarto: scarto (s) oltre il quale
# si usa tutta l'autorità disponibile.
CORRECTION_FULL_AT = 240.0

# ── Come sono fatte le corse ─────────────────────────────────────
# Passo caratteristico della singola corsa, riestratto ogni volta.
# Centrato appena sopra 1: gli orari MAGNI hanno un filo di margine, quindi
# una corsa "normale" tende ad arrivare puntuale o poco avanti.
PACE_MIN, PACE_MAX = 0.86, 1.22

# ── Traffico ─────────────────────────────────────────────────────
# Tarato contando che una tratta dura ~4 minuti: a 0.0016/s se ne beccava
# una ogni tre tratte, cioe' ritardo continuo e irrecuperabile. Qui un
# episodio ogni ~40 minuti di marcia, che lascia spazio anche alle corse
# pulite e agli anticipi.
CONGESTION_PROB_PER_S = 0.00042  # per secondo, per bus
CONGESTION_MIN_S      = 45.0
CONGESTION_MAX_S      = 260.0
CONGESTION_SLOWDOWN   = 0.18     # frazione della velocità che resta
RUSH_HOURS            = ((7, 9), (12, 14), (17, 19))
RUSH_MULTIPLIER       = 2.4

# ── Soste ────────────────────────────────────────────────────────
DWELL_BASE_S       = 8.0    # apertura/chiusura porte
DWELL_PER_BOARDER  = 2.4    # secondi per passeggero che sale
DWELL_MAX_S        = 75.0
# Sosta al capolinea fra una corsa e la successiva. Serve al backend:
# ScheduleAdherenceService registra l'arrivo al capolinea solo se vede il
# mezzo FERMO lì, e il v2 lo teletrasportava via nello stesso istante.
LAYOVER_MIN_S      = 60.0

# ── Occupazione ──────────────────────────────────────────────────
# Domanda relativa per fascia oraria: quante persone attendono, in scala
# arbitraria. Alimenta il pannello "Peak hour occupancy" delle Analytics.
DEMAND_BY_HOUR = {
    5: .10, 6: .35, 7: .90, 8: 1.00, 9: .55, 10: .40, 11: .45,
    12: .85, 13: .95, 14: .60, 15: .45, 16: .55, 17: .95, 18: 1.00,
    19: .60, 20: .30, 21: .18, 22: .08,
}
BOARDERS_AT_PEAK = 9.0    # saliti attesi a una fermata importante, in punta

# ── GPS ──────────────────────────────────────────────────────────
# L'errore è una DERIVA, non rumore indipendente. Un ricevitore reale sbaglia
# oggi quasi come sbagliava un secondo fa: l'errore indipendente a ogni
# campione fa "tremare" il mezzo in un modo che nessun GPS produce.
GPS_SIGMA_M       = 5.0
GPS_CORRELATION   = 0.90   # quanto dell'errore precedente sopravvive
GPS_OUTLIER_PROB  = 0.01   # riflessione su un palazzo: salto isolato
GPS_OUTLIER_M     = 30.0

# ── Anomalie ─────────────────────────────────────────────────────
# GUASTO: il mezzo si ferma ma la radio di bordo continua a trasmettere.
# Il backend lo legge come STALLED (fermo da 10 minuti mentre è in corsa),
# quindi la durata deve poter superare quella soglia o lo stato non si vede
# mai.
BREAKDOWN_PROB_PER_S = 0.000012
BREAKDOWN_MIN_S      = 11 * 60
BREAKDOWN_MAX_S      = 22 * 60

# PERDITA SEGNALE: il mezzo continua ad andare ma smette di trasmettere.
# Il backend lo legge come NO_SIGNAL dopo 5 minuti di silenzio; al ritorno
# il bus riappare più avanti sul percorso, che è ciò che succede davvero.
SIGNAL_LOSS_PROB_PER_S = 0.000015
SIGNAL_LOSS_MIN_S      = 6 * 60
SIGNAL_LOSS_MAX_S      = 14 * 60


# ─────────────────────────────────────────────────────────────────
#  Geometria
# ─────────────────────────────────────────────────────────────────

def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    dlat, dlon = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2
         + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2))
         * math.sin(dlon / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def bearing_deg(lat1, lon1, lat2, lon2):
    dlon = math.radians(lon2 - lon1)
    l1, l2 = math.radians(lat1), math.radians(lat2)
    x = math.sin(dlon) * math.cos(l2)
    y = math.cos(l1) * math.sin(l2) - math.sin(l1) * math.cos(l2) * math.cos(dlon)
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def offset_metres(lat, lon, east_m, north_m):
    """Sposta un punto di tot metri, per applicare l'errore GPS."""
    dlat = north_m / 111320.0
    dlon = east_m / (111320.0 * max(0.1, math.cos(math.radians(lat))))
    return lat + dlat, lon + dlon


def seconds_of_day(dt=None):
    now = dt or datetime.now(ROME)
    return now.hour * 3600 + now.minute * 60 + now.second


def hhmm(sec):
    sec = int(sec) % 86400
    return f"{sec // 3600:02d}:{sec % 3600 // 60:02d}"


# ─────────────────────────────────────────────────────────────────
#  Caricamento dalla base dati
# ─────────────────────────────────────────────────────────────────

def load_network(conn):
    """
    Legge tutto quello che serve: mezzi, geometrie, corse e orari.

    Si legge una volta sola all'avvio. L'orario cambia quando un gestore lo
    modifica, non durante una corsa, e ricaricarlo a ogni passo significherebbe
    interrogare il database dieci volte al secondo per scoprire che nulla è
    cambiato.
    """
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # ── Fermate ──────────────────────────────────────────────────
    cur.execute("SELECT id, name, lat, lon FROM stops")
    stops = {r["id"]: dict(r) for r in cur.fetchall()}

    # ── Geometrie: la strada disegnata, vertice per vertice ─────
    cur.execute("""
        SELECT route_id, seq, lat, lon
        FROM route_shapes
        ORDER BY route_id, seq
    """)
    shapes = {}
    for r in cur.fetchall():
        shapes.setdefault(r["route_id"], []).append((r["lat"], r["lon"]))

    # ── Mezzi ────────────────────────────────────────────────────
    cur.execute("""
        SELECT bus_id, targa, current_vehicle_id, numero_posti
        FROM buses
        WHERE disponibile = TRUE AND current_vehicle_id IS NOT NULL
        ORDER BY bus_id
    """)
    buses = cur.fetchall()

    # ── Corse con i loro orari ───────────────────────────────────
    # Da V24: la fermata sta nel pattern della linea, l'orario nella corsa.
    cur.execute("""
        SELECT t.id AS trip_id, t.bus_id, t.route_id,
               r.short_name, r.long_name,
               ss.stop_sequence, ss.arrival_seconds, rs.stop_id
        FROM trips t
        JOIN routes r        ON r.id = t.route_id
        JOIN scheduled_stops ss ON ss.trip_id = t.id
        JOIN route_stops rs  ON rs.route_id = t.route_id
                            AND rs.stop_sequence = ss.stop_sequence
        ORDER BY t.bus_id, ss.arrival_seconds, ss.stop_sequence
    """)
    calls_by_trip = {}
    trip_meta = {}
    for r in cur.fetchall():
        calls_by_trip.setdefault(r["trip_id"], []).append(
            (r["stop_sequence"], r["arrival_seconds"], r["stop_id"]))
        trip_meta[r["trip_id"]] = (r["bus_id"], r["route_id"],
                                   r["short_name"], r["long_name"])
    cur.close()

    return stops, shapes, buses, calls_by_trip, trip_meta


def bind_calls_to_shape(calls, shape, stops):
    """
    Lega ogni fermata programmata al suo vertice nella geometria.

    La scansione riparte SEMPRE da dove si era fermata, mai dall'inizio: le
    linee di Cassino sono anelli e la stessa fermata compare due volte nello
    stesso percorso, quindi le coordinate da sole non dicono quale dei due
    passaggi si intende. Cercando in avanti, il secondo passaggio trova il
    secondo vertice.

    Restituisce [(indice_vertice, arrival_seconds, stop_id)] oppure None se
    una fermata non ha riscontro nella geometria — nel qual caso la corsa non
    è simulabile e va saltata, invece di essere percorsa a metà.
    """
    out, cursor_i = [], 0
    for _seq, arrival, stop_id in calls:
        coord = stops.get(stop_id)
        if coord is None or coord["lat"] is None:
            return None
        found = -1
        for i in range(cursor_i, len(shape)):
            if (abs(shape[i][0] - coord["lat"]) < COORD_TOL
                    and abs(shape[i][1] - coord["lon"]) < COORD_TOL):
                found = i
                break
        if found < 0:
            return None
        out.append((found, arrival, stop_id))
        cursor_i = found + 1
    return out if len(out) >= 2 else None


def cumulative_metres(shape):
    """Distanza progressiva lungo la geometria, per i calcoli di passo."""
    cum = [0.0]
    for i in range(len(shape) - 1):
        cum.append(cum[-1] + haversine_m(shape[i][0], shape[i][1],
                                         shape[i + 1][0], shape[i + 1][1]))
    return cum


def stop_weight(stop_id):
    """
    Quanto è trafficata una fermata, da 0.35 a 1.0.

    Il database non dice quali fermate contano di più, e inventarlo a caso a
    ogni avvio darebbe grafici diversi ogni volta senza motivo. Si deriva
    dall'id: è arbitrario ma STABILE, quindi la stessa fermata pesa sempre
    uguale e i confronti fra due esecuzioni hanno senso.
    """
    h = 0
    for ch in stop_id:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return 0.35 + (h % 1000) / 1000.0 * 0.65


# ─────────────────────────────────────────────────────────────────
#  Il pullman
# ─────────────────────────────────────────────────────────────────

class Bus:
    """
    Un mezzo che percorre le proprie corse lungo la geometria disegnata.

    Stati:
      idle      — al capolinea, aspetta l'ora di partenza della prossima corsa
      running   — in marcia fra due fermate
      dwelling  — fermo a una fermata, porte aperte
      congested — bloccato nel traffico
      broken    — guasto: fermo, ma la radio trasmette
    """

    def __init__(self, spec):
        self.vehicle_id = spec["vehicle_id"]
        self.targa      = spec["targa"]
        self.capacity   = spec["capacity"]
        self.shapes     = spec["shapes"]
        self.trips      = spec["trips"]      # ordinate per orario di partenza

        self.trip_i = 0
        self.leg    = 0        # indice della fermata appena lasciata
        self.node   = 0        # vertice corrente nella geometria
        self.frac   = 0.0      # avanzamento fra node e node+1
        self.state  = "idle"
        self.timer  = 0.0

        self.speed_kmh = 0.0
        self.heading   = 0.0
        self.pace      = random.uniform(PACE_MIN, PACE_MAX)
        self.occupancy = 0
        self.battery   = random.uniform(12.2, 12.8)

        # Errore GPS: due componenti in metri, che derivano nel tempo.
        self.gps_e = random.gauss(0, GPS_SIGMA_M)
        self.gps_n = random.gauss(0, GPS_SIGMA_M)

        self.last_dwell   = 0.0   # sosta appena fatta, scalata dal budget tratta
        self._cum_trip    = None  # corsa a cui si riferisce _cum, vedi _start_trip_if_due
        self.silent_until = 0.0   # perdita segnale: non pubblica fino a qui
        self.last_report  = None  # ultima riga stampata, per il log

    # ── Accesso alla corsa corrente ─────────────────────────────
    @property
    def trip(self):
        return self.trips[self.trip_i] if self.trip_i < len(self.trips) else None

    @property
    def shape(self):
        t = self.trip
        return self.shapes.get(t["route_id"], []) if t else []

    def position(self):
        """Posizione vera, senza errore GPS."""
        sh = self.shape
        if not sh:
            return None
        if self.node >= len(sh) - 1:
            return sh[-1]
        a, b = sh[self.node], sh[self.node + 1]
        return (a[0] + (b[0] - a[0]) * self.frac,
                a[1] + (b[1] - a[1]) * self.frac)

    # ── Passo nominale della tratta in corso ────────────────────
    def _leg_pace_kmh(self, now):
        """
        Velocità che l'ORARIO chiede per la tratta corrente, corretta dal
        passo di questa corsa e da quanto il mezzo è fuori tempo.

        È qui che vive la scelta di fondo del simulatore. Il passo di
        riferimento non è "quanto serve per arrivare in orario da adesso" —
        quella formula, ricalcolata a ogni passo, permette di riassorbire
        qualunque ritardo e rende impossibile essere tardi. È "quanto l'orario
        prevedeva per questa tratta", una costante della tratta; sopra ci va
        una correzione LIMITATA in funzione dello scarto accumulato.
        """
        t = self.trip
        sh, cum = self.shape, self._cum
        i_from, t_from, _ = t["stops"][self.leg]
        i_to,   t_to,   _ = t["stops"][self.leg + 1]

        # La sosta appena fatta si SOTTRAE dal tempo concesso alla tratta,
        # non si somma. Un orario reale include il tempo porte aperte: se lo
        # si aggiunge sopra, ogni fermata regala ritardo strutturale e con
        # otto fermate la corsa sfora di minuti senza che sia successo nulla.
        # Era la causa principale del +8 minuti mediano nella prima taratura.
        planned_s = max(10.0, (t_to - t_from) - self.last_dwell)
        leg_m     = max(1.0, cum[i_to] - cum[i_from])
        nominal   = (leg_m / planned_s) * 3.6          # km/h previsti

        # Scarto: positivo se in ritardo. Confronta dove l'orario voleva il
        # mezzo a quest'ora con dove il mezzo è davvero, lungo la strada.
        progress = (cum[self.node] + self.frac *
                    (cum[min(self.node + 1, len(sh) - 1)] - cum[self.node]))
        share    = min(1.0, max(0.0, (progress - cum[i_from]) / leg_m))
        due_now  = t_from + planned_s * share
        lateness = now - due_now

        # Correzione con autorità limitata: un piccolo scarto si riassorbe,
        # un ingorgo vero no.
        k = max(-1.0, min(1.0, lateness / CORRECTION_FULL_AT))
        factor = 1.0 + (CATCHUP_MAX * k if k > 0 else SLACK_MAX * k)

        return max(MIN_KMH, min(MAX_KMH, nominal * self.pace * factor))

    # ── Avanzamento lungo la geometria ──────────────────────────
    def _advance(self, metres):
        """Sposta il mezzo di `metres` lungo la strada. True se ha raggiunto
        la fermata successiva."""
        sh, cum = self.shape, self._cum
        target_i = self.trip["stops"][self.leg + 1][0]

        while metres > 0 and self.node < len(sh) - 1:
            seg = cum[self.node + 1] - cum[self.node]
            if seg <= 0:
                self.node += 1
                self.frac = 0.0
                continue
            remain = seg * (1.0 - self.frac)
            if metres < remain:
                self.frac += metres / seg
                metres = 0
            else:
                metres -= remain
                self.node += 1
                self.frac = 0.0
                if self.node >= target_i:
                    self.node = target_i
                    self.frac = 0.0
                    return True
        return self.node >= target_i

    # ── Passeggeri ──────────────────────────────────────────────
    def _serve_stop(self, stop_id, now):
        """
        Saliti e scesi a una fermata. Restituisce i secondi di sosta.

        La sosta dipende da QUANTI salgono, e questo è il punto: in ora di
        punta salgono più persone, quindi le porte restano aperte più a lungo,
        quindi il mezzo accumula ritardo. Occupazione e puntualità risultano
        correlate senza che nessuna delle due sia stata imposta — che è come
        stanno le cose in una rete vera, e rende leggibili insieme i due
        pannelli delle Analytics.
        """
        hour   = int(now // 3600) % 24
        demand = DEMAND_BY_HOUR.get(hour, 0.15) * stop_weight(stop_id)

        free     = max(0, self.capacity - self.occupancy)
        expected = BOARDERS_AT_PEAK * demand
        boarding = min(free, max(0, int(random.gauss(expected, expected * 0.45 + 1))))

        # Scendono più persone dove il mezzo è pieno, e verso fine corsa.
        share_done = (self.leg + 1) / max(1, len(self.trip["stops"]) - 1)
        alighting  = min(self.occupancy,
                         int(self.occupancy * random.uniform(0.05, 0.30 + 0.45 * share_done)))

        self.occupancy = max(0, self.occupancy - alighting + boarding)
        self.last_dwell = min(DWELL_MAX_S, DWELL_BASE_S + boarding * DWELL_PER_BOARDER)
        return self.last_dwell

    # ── Selezione della corsa ───────────────────────────────────
    def _start_trip_if_due(self, now):
        """Mette il mezzo in partenza se è l'ora della sua prossima corsa."""
        # Prima si scartano le corse la cui finestra è già passata. Senza
        # questo, avviando il simulatore alle 10:00 un mezzo la cui prima
        # corsa era a mezzanotte la partiva comunque, e ogni sua fermata
        # risultava cinque ore in anticipo: non un anticipo, un orario
        # sbagliato. La regola è la stessa applicata a fine corsa.
        while self.trip is not None and self.trip["end"] < now:
            self.trip_i += 1
        t = self.trip
        if t is None:
            return False

        # Ci si posiziona all'origine della corsa e si aspetta l'orario.
        #
        # Il riposizionamento va fatto quando cambia la CORSA, non quando
        # cambia il vertice di partenza: due linee diverse possono avere
        # l'origine allo stesso indice, e in quel caso il vecchio controllo
        # (`if self.node != ...`) non scattava. Il mezzo percorreva la nuova
        # corsa con le distanze progressive della linea PRECEDENTE, quindi
        # ogni fermata risultava raggiunta con decine di minuti di anticipo:
        # non un anticipo, una misura fatta sul righello sbagliato.
        if self._cum_trip != t["trip_id"]:
            self.node = t["stops"][0][0]
            self.frac = 0.0
            self.leg  = 0
            self.last_dwell = 0.0
            self._cum = self._cums[t["route_id"]]
            self._cum_trip = t["trip_id"]
        if now >= t["stops"][0][1]:
            self.state = "running"
            self.pace  = random.uniform(PACE_MIN, PACE_MAX)
            self.timer = self._serve_stop(t["stops"][0][2], now)
            self.state = "dwelling"
            return True
        return False

    def _finish_trip(self, now):
        """
        Fine corsa: si resta AL CAPOLINEA per almeno LAYOVER_MIN_S.

        Il v2 spostava il mezzo all'origine della corsa successiva nello stesso
        istante in cui finiva la precedente. ScheduleAdherenceService registra
        l'arrivo al capolinea solo se vede il mezzo fermo lì o allontanarsene,
        quindi quell'arrivo non veniva mai misurato: l'ultima fermata di ogni
        corsa spariva dalle statistiche.
        """
        self.state = "idle"
        self.speed_kmh = 0.0
        self.timer = LAYOVER_MIN_S
        self.last_dwell = 0.0
        # Scendono tutti.
        self.occupancy = 0
        self.trip_i += 1

        # Se il mezzo e' cosi' indietro che la corsa successiva e' gia'
        # finita sulla carta, la si salta. In esercizio lo farebbe la
        # centrale operativa riassegnando il turno; senza questo, un ritardo
        # accumulato la mattina insegue corse morte per tutto il giorno e a
        # fine servizio i numeri diventano privi di senso.
        while self.trip is not None and self.trip["end"] < now:
            self.trip_i += 1

    # ── Un passo di simulazione ─────────────────────────────────
    def step(self, dt, now, forced=None):
        """
        Avanza di `dt` secondi. `now` sono i secondi dalla mezzanotte.

        `forced` può valere "breakdown" o "signal_loss" per provocare
        l'anomalia a comando, invece di aspettare che càpiti.
        """
        # ── Anomalie ────────────────────────────────────────────
        if forced == "breakdown" and self.state != "broken":
            self.state = "broken"
            self.timer = random.uniform(BREAKDOWN_MIN_S, BREAKDOWN_MAX_S)
        elif forced == "signal_loss" and self.silent_until <= now:
            self.silent_until = now + random.uniform(SIGNAL_LOSS_MIN_S, SIGNAL_LOSS_MAX_S)

        if self.state == "broken":
            self.speed_kmh = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self.state = "running"
            return

        if random.random() < BREAKDOWN_PROB_PER_S * dt:
            # Fermo ma non muto: un guasto meccanico non spegne la radio.
            # Il backend lo leggerà come STALLED, non come NO_SIGNAL.
            self.state = "broken"
            self.timer = random.uniform(BREAKDOWN_MIN_S, BREAKDOWN_MAX_S)
            self.speed_kmh = 0.0
            return

        if self.silent_until <= now and random.random() < SIGNAL_LOSS_PROB_PER_S * dt:
            # Il mezzo continua ad andare: smette solo di farsi sentire.
            self.silent_until = now + random.uniform(SIGNAL_LOSS_MIN_S, SIGNAL_LOSS_MAX_S)

        # ── Al capolinea, in attesa ─────────────────────────────
        if self.state == "idle":
            self.speed_kmh = 0.0
            if self.timer > 0:
                self.timer -= dt
                return
            self._start_trip_if_due(now)
            return

        t = self.trip
        if t is None:
            self.state = "idle"
            return

        # ── Fermo: sosta o ingorgo ──────────────────────────────
        if self.state in ("dwelling", "congested"):
            self.speed_kmh = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self.state = "running"
            return

        # ── In marcia ───────────────────────────────────────────
        hour = int(now // 3600) % 24
        rush = RUSH_MULTIPLIER if any(a <= hour < b for a, b in RUSH_HOURS) else 1.0
        if random.random() < CONGESTION_PROB_PER_S * dt * rush:
            self.state = "congested"
            self.timer = random.uniform(CONGESTION_MIN_S, CONGESTION_MAX_S)
            self.speed_kmh = 0.0
            return

        kmh = self._leg_pace_kmh(now)
        before = self.position()
        arrived = self._advance(kmh / 3.6 * dt)
        after = self.position()

        if before and after:
            moved = haversine_m(before[0], before[1], after[0], after[1])
            self.speed_kmh = moved / max(dt, 0.001) * 3.6
            if moved > 0.5:
                self.heading = bearing_deg(before[0], before[1], after[0], after[1])

        if arrived:
            self.leg += 1
            if self.leg >= len(t["stops"]) - 1:
                self._finish_trip(now)
            else:
                self.timer = self._serve_stop(t["stops"][self.leg][2], now)
                self.state = "dwelling"
                self.speed_kmh = 0.0

    # ── Il pacchetto sul filo ───────────────────────────────────
    def payload(self, now):
        """None quando il mezzo non deve trasmettere (fuori servizio o muto)."""
        if self.silent_until > now:
            return None
        pos = self.position()
        if pos is None or self.trip is None:
            return None

        # L'errore GPS deriva invece di essere riestratto da zero.
        rho = GPS_CORRELATION
        keep = math.sqrt(max(0.0, 1 - rho * rho))
        self.gps_e = self.gps_e * rho + random.gauss(0, GPS_SIGMA_M) * keep
        self.gps_n = self.gps_n * rho + random.gauss(0, GPS_SIGMA_M) * keep
        e, n = self.gps_e, self.gps_n
        if random.random() < GPS_OUTLIER_PROB:
            # Riflessione su un edificio: un singolo campione lontano, che NON
            # entra nella deriva — altrimenti il mezzo resterebbe spostato.
            e += random.gauss(0, GPS_OUTLIER_M)
            n += random.gauss(0, GPS_OUTLIER_M)
        lat, lon = offset_metres(pos[0], pos[1], e, n)

        self.battery = max(11.8, min(12.8, self.battery - random.uniform(0, 0.002)))
        ble = max(0, int(round(self.occupancy / 0.6)) + random.randint(-2, 2))

        return {
            "vehicle_id":       self.vehicle_id,
            "timestamp":        datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "lat":              round(lat, 6),
            "lon":              round(lon, 6),
            "heading_deg":      round(self.heading, 1),
            "speed_kmh":        round(self.speed_kmh, 1),
            "ble_device_count": ble,
            "passengers":       self.occupancy,
            "capacity":         self.capacity,
            "battery_voltage":  round(self.battery, 2),
            "firmware_version": "3.0.0-sim",
        }


# ─────────────────────────────────────────────────────────────────
#  Montaggio
# ─────────────────────────────────────────────────────────────────

def build_buses(conn, verbose=True):
    """Costruisce un Bus per ogni mezzo che ha corse simulabili."""
    stops, shapes, bus_rows, calls_by_trip, trip_meta = load_network(conn)
    cums = {rid: cumulative_metres(sh) for rid, sh in shapes.items()}

    by_bus = {}
    skipped = {"no_shape": 0, "unbound": 0}
    for trip_id, calls in calls_by_trip.items():
        bus_id, route_id, short, long_ = trip_meta[trip_id]
        shape = shapes.get(route_id)
        if not shape or len(shape) < 2:
            skipped["no_shape"] += 1
            continue
        bound = bind_calls_to_shape(calls, shape, stops)
        if bound is None:
            # Una fermata dell'orario non ha riscontro nella geometria: la
            # corsa si salta invece di percorrerla a metà. Succede quando il
            # percorso è stato ridisegnato senza rigenerare le shape.
            skipped["unbound"] += 1
            continue
        by_bus.setdefault(bus_id, []).append({
            "trip_id": trip_id, "route_id": route_id,
            "label": (short or route_id) + (" — " + long_ if long_ else ""),
            "stops": bound,
            "start": bound[0][1], "end": bound[-1][1],
        })

    buses = []
    for row in bus_rows:
        trips = sorted(by_bus.get(row["bus_id"], []), key=lambda t: t["start"])
        if not trips:
            continue
        b = Bus({"vehicle_id": row["current_vehicle_id"],
                 "targa": row["targa"],
                 "capacity": row["numero_posti"] or 50,
                 "shapes": shapes, "trips": trips})
        b._cums = cums
        b._cum  = cums.get(trips[0]["route_id"], [0.0])
        b._cum_trip = trips[0]["trip_id"]
        buses.append(b)

    if verbose:
        print(f"🚌 {len(buses)} mezzi con corse simulabili")
        if skipped["no_shape"]:
            print(f"   ⚠️  {skipped['no_shape']} corse saltate: la linea non ha geometria disegnata")
        if skipped["unbound"]:
            print(f"   ⚠️  {skipped['unbound']} corse saltate: una fermata non si ritrova nel tracciato")
            print(f"      (rigenera le shape con tools/build_route_shapes_osrm.py)")
    return buses


def place_at_current_time(bus, now):
    """
    Porta il mezzo dove sarebbe a quest'ora, avviando il simulatore a metà
    giornata senza aspettare le 06:00.

    Si salta alla corsa giusta e si avanza col modello vero a passi di 5
    secondi, invece di piazzare il mezzo sul punto che l'orario indica: quella
    scorciatoia lo farebbe nascere esattamente puntuale, cioè con lo stesso
    difetto del v2, solo concentrato nel primo istante.
    """
    while bus.trip is not None and bus.trip["end"] < now:
        bus.trip_i += 1
    if bus.trip is None:
        return
    t = bus.trip
    bus._cum = bus._cums.get(t["route_id"], [0.0])
    bus._cum_trip = t["trip_id"]
    bus.node, bus.frac, bus.leg = t["stops"][0][0], 0.0, 0
    if now < t["stops"][0][1]:
        bus.state, bus.timer = "idle", 0.0
        return
    bus.state = "running"
    clock = t["stops"][0][1]
    while clock < now and bus.trip is t:
        bus.step(5.0, clock)
        clock += 5.0


def main():
    p = argparse.ArgumentParser(description="CassiTrack GPS Simulator v3")
    p.add_argument("--broker", default="localhost")
    p.add_argument("--port", type=int, default=1883)
    p.add_argument("--interval", type=int, default=5,
                   help="secondi fra due pubblicazioni (default: 5)")
    p.add_argument("--db-host", default=DB_CONFIG["host"])
    p.add_argument("--db-port", type=int, default=DB_CONFIG["port"])
    p.add_argument("--mqtt-username", default=os.environ.get("MQTT_BUS_USERNAME", "cassitrack-bus"))
    p.add_argument("--mqtt-password", default=os.environ.get("MQTT_BUS_PASSWORD", "bus-password"))
    p.add_argument("--raw", action="store_true", help="stampa il JSON pubblicato")
    p.add_argument("--force-breakdown", metavar="VEHICLE_ID",
                   help="provoca subito un guasto su questo mezzo (stato STALLED)")
    p.add_argument("--force-signal-loss", metavar="VEHICLE_ID",
                   help="provoca subito una perdita di segnale (stato NO_SIGNAL)")
    args = p.parse_args()

    print("🗄️  Connessione a PostgreSQL...")
    try:
        conn = psycopg2.connect(host=args.db_host, port=args.db_port,
                                dbname=DB_CONFIG["dbname"], user=DB_CONFIG["user"],
                                password=DB_CONFIG["password"])
        conn.autocommit = True
        print("✅ PostgreSQL connesso\n")
    except Exception as e:
        print(f"❌ PostgreSQL irraggiungibile: {e}")
        print("   Verifica che docker compose sia avviato (porta 5433)")
        return

    buses = build_buses(conn)
    conn.close()
    if not buses:
        print("❌ Nessun mezzo simulabile. Servono corse con una geometria disegnata.")
        return

    now = seconds_of_day()
    for b in buses:
        place_at_current_time(b, now)
    running = sum(1 for b in buses if b.state != "idle")

    print(f"\n{'─'*66}")
    print(f"  {running}/{len(buses)} mezzi in servizio alle {hhmm(now)}")
    print(f"  Pubblicazione ogni {args.interval}s su cassitrack/<vehicle_id>/position")
    print(f"  Percorso: geometria reale (route_shapes), non rette fra fermate")
    print(f"  Ritardo:  emergente — recupero limitato al ±{int(CATCHUP_MAX*100)}% del passo")
    if args.force_breakdown:
        print(f"  ⚠️  Guasto forzato su {args.force_breakdown}")
    if args.force_signal_loss:
        print(f"  ⚠️  Perdita segnale forzata su {args.force_signal_loss}")
    print(f"{'─'*66}\n")

    client = mqtt.Client(userdata={})
    if args.mqtt_username:
        client.username_pw_set(args.mqtt_username, args.mqtt_password)
    try:
        client.connect(args.broker, args.port, keepalive=60)
    except Exception as e:
        print(f"❌ Broker MQTT irraggiungibile: {e}")
        return
    client.loop_start()
    time.sleep(1)
    print("📡 In trasmissione... (Ctrl+C per fermare)\n")

    forced_done = set()
    last = time.monotonic()
    try:
        while True:
            wall = time.monotonic()
            dt   = max(0.1, wall - last)
            last = wall
            now  = seconds_of_day()

            for b in buses:
                forced = None
                if b.vehicle_id == args.force_breakdown and "b" not in forced_done:
                    forced = "breakdown"; forced_done.add("b")
                elif b.vehicle_id == args.force_signal_loss and "s" not in forced_done:
                    forced = "signal_loss"; forced_done.add("s")

                b.step(dt, now, forced)
                pl = b.payload(now)
                if pl is None:
                    continue
                client.publish(TOPIC.format(vehicle_id=pl["vehicle_id"]),
                               json.dumps(pl), qos=1)
                if args.raw:
                    print(f"   ↗ {json.dumps(pl)}")

            # Riepilogo: lo scarto mostrato è calcolato QUI per il solo operatore.
            # Non viaggia nel payload — il ritardo lo deve misurare il backend
            # dagli arrivi, altrimenti la prova non prova niente.
            for b in buses:
                t = b.trip
                if t is None or b.state == "idle":
                    continue
                icon = {"broken": "🔧", "congested": "🚧",
                        "dwelling": "🛑", "running": "🚌"}.get(b.state, "  ")
                mute = " 📵" if b.silent_until > now else ""
                due  = t["stops"][min(b.leg + 1, len(t["stops"]) - 1)][1]
                print(f"  {icon} {b.vehicle_id:<10} {b.speed_kmh:5.1f} km/h "
                      f"{b.occupancy:3d}/{b.capacity} pax  "
                      f"[{t['label'][:26]:<26} → {hhmm(due)} "
                      f"scarto {(now - due)/60:+5.1f}m]{mute}")
            print()
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\n🛑 Simulatore fermato.")
    finally:
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
