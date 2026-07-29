#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
simulate_bus.py
===============
Simula in modo continuo e realistico gli autobus definiti in un file
percorsoX.json (creato dall'interfaccia web CassiTrack) e ne invia la
posizione via MQTT (TLS) al broker cassitrack.

Caratteristiche della simulazione:
  * ogni bus percorre il proprio tracciato avanti e indietro (capolinea);
  * velocita' tipica da bus urbano (~25 km/h), con piccola variabilita';
  * la posizione avanza per interpolazione lineare lungo i segmenti tra i punti
    fissi del tracciato, con passo = velocita' * dt;
  * ad ogni capolinea il bus sosta 3 minuti (riconfigurabile) e poi inverte la
    marcia per tornare all'altro capolinea, all'infinito;
  * l'invio MQTT e' limitato ad al piu' un messaggio al minuto per bus
    (intervallo riconfigurabile), pur simulando il moto a passi fini;
  * ogni bus invia a intervalli sfasati e con jitter, cosi' i bus non
    trasmettono tutti nello stesso istante (randomicita' del singolo bus);
  * ogni bus puo' subire una rottura casuale (~1 ogni 24 h) che lo blocca e ne
    interrompe l'invio per 10 minuti;
  * sosta di ~30 s (con jitter) alle fermate intermedie;
  * talvolta resta bloccato nel traffico (velocita' 0 per alcuni secondi);
  * il numero di passeggeri "occ" cambia SOLO alla ripartenza da una fermata;
  * distanze/velocita'/interpolazione posizione calcolate con haversine.

Uso:
    pip install paho-mqtt
    python simulate_bus.py percorso1.json
    python simulate_bus.py percorso1.json --insecure   # salta verifica cert TLS
    python simulate_bus.py percorso1.json --send-interval 30 --terminal-dwell 120

Basato sulla riga di riferimento:
    mosquitto_pub -h devaidalab.unicas.it -p 8883 --capath /etc/ssl/certs \
      -u esp32 -P '****' -t 'cassitrack/obu/BUS12/pos' \
      -m '{"id":"BUS12","ts":0,"lat":...,"lon":...,"spd":0,"hdg":0,...}'
"""

import argparse
import json
import math
import os
import random
import ssl
import sys
import threading
import time

import paho.mqtt.client as mqtt

# ------------------------------------------------------------------ #
#  Configurazione broker (dalla riga mosquitto_pub fornita)          #
# ------------------------------------------------------------------ #
# ── CassiTrack integration: credentials from the environment ──────────
# ONLY CHANGE from the original: the broker settings below were hardcoded,
# password included. This file lives in the repository, so the secret is read
# from cassitrack-backend/.env (gitignored) instead of being committed.
# Behaviour is otherwise identical to the reference simulator.
def _load_env_file(path):
    """Minimal .env reader; never overrides a real environment variable."""
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


_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_load_env_file(os.path.join(_ROOT, "cassitrack-backend", ".env"))

_URL = os.environ.get("MQTT_OBU_URL", "ssl://devaidalab.unicas.it:8883")
_HOSTPORT = _URL.replace("ssl://", "").replace("tcp://", "")
_HOST, _, _PORT = _HOSTPORT.partition(":")

BROKER     = _HOST
PORT       = int(_PORT or 8883)
USER       = os.environ.get("MQTT_OBU_USERNAME", "esp32")
PASSWORD   = os.environ.get("MQTT_OBU_PASSWORD", "")
TOPIC_TMPL = "cassitrack/obu/{bus}/pos"     # {bus} = BUS#  (es. BUS12)

# ------------------------------------------------------------------ #
#  Parametri della simulazione                                       #
# ------------------------------------------------------------------ #
CRUISE_KMH     = 25.0     # velocita' di crociera tipica di un bus urbano
SPEED_JITTER   = 0.15     # +/- 15% di variabilita' sulla velocita'
SEND_INTERVAL  = 60.0     # intervallo minimo tra due invii MQTT per bus (s) = 1/min
SIM_STEP       = 1.0      # passo di integrazione interno della simulazione (s)
TERMINAL_DWELL = 180.0    # sosta ai capolinea prima dell'inversione (s) = 3 min
STOP_DWELL     = 30.0     # sosta media alle fermate intermedie (secondi)
STOP_JITTER    = 8.0      # variabilita' della sosta (secondi)
TRAFFIC_PROB   = 0.02     # probabilita' per passo di incappare nel traffico
TRAFFIC_MIN    = 5.0      # durata minima del blocco nel traffico (s)
TRAFFIC_MAX    = 25.0     # durata massima del blocco nel traffico (s)
CAPACITY       = 50       # capienza massima del bus (per "occ")
OCC_MAX_DELTA  = 4        # variazione massima di passeggeri a ogni fermata
OCC_MIN_INTERVAL = 60.0   # tempo minimo tra due variazioni di "occ" (s)
SEND_JITTER    = 0.20     # variabilita' casuale (+/-) sull'intervallo di invio
BREAKDOWN_MTBF = 86400.0  # tempo medio tra due rotture per bus (s) ~ 24 h
BREAKDOWN_DUR  = 600.0    # durata di una rottura: nessun invio (s) = 10 min

R_EARTH = 6371000.0       # raggio terrestre medio (m)


# ------------------------------------------------------------------ #
#  Funzioni geografiche                                              #
# ------------------------------------------------------------------ #
def haversine(lat1, lon1, lat2, lon2):
    """Distanza in metri tra due coordinate (formula dell'emisenoverso)."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_EARTH * math.asin(math.sqrt(a))


def bearing(lat1, lon1, lat2, lon2):
    """Rotta (heading) in gradi 0-360 dal punto 1 al punto 2."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def interp(a, b, f):
    """Interpolazione lineare tra i punti a e b (frazione f in [0,1])."""
    return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)


# ------------------------------------------------------------------ #
#  Modello del singolo autobus                                       #
# ------------------------------------------------------------------ #
class Bus:
    def __init__(self, spec):
        self.id = spec["id"]                       # es. "BUS3"
        self.topic = TOPIC_TMPL.format(bus=self.id)
        # tracciato: lista di (lat, lon, e' una fermata?)
        self.points = [(p["lat"], p["lon"], bool(p.get("stop", False)))
                       for p in spec["points"]]

        # stato del movimento
        self.node = 0            # indice del nodo di partenza del segmento
        self.frac = 0.0          # avanzamento sul segmento corrente [0,1]
        self.direction = 1       # +1 = avanti, -1 = ritorno (capolinea)
        self.state = "run"       # "run" | "dwell" (fermata) | "traffic"
        self.timer = 0.0         # secondi rimanenti di sosta/blocco

        # telemetria
        self.spd = 0.0                              # km/h
        self.hdg = 0.0                              # gradi
        self.occ = random.randint(0, CAPACITY // 2) # passeggeri a bordo
        self.bat = random.uniform(3.8, 4.2)         # tensione batteria (V)

        # orologio di simulazione, per limitare la frequenza dei cambi di "occ"
        self.t = 0.0                                # secondi simulati trascorsi
        self.last_occ_change = 0.0                  # istante dell'ultimo cambio occ

    # --- posizione corrente (lat, lon) lungo il segmento ---
    def position(self):
        nb = self.node + self.direction
        if nb < 0 or nb >= len(self.points):
            return self.points[self.node][0], self.points[self.node][1]
        a = self.points[self.node]
        b = self.points[nb]
        return interp((a[0], a[1]), (b[0], b[1]), self.frac)

    # --- stato del nodo appena raggiunto: capolinea, fermata o nulla ---
    def _node_status(self):
        if self.node == 0 or self.node == len(self.points) - 1:
            return "terminal"                       # capolinea di linea
        if self.points[self.node][2]:
            return "dwell"                          # fermata intermedia
        return None

    # --- avanza di `dist` metri lungo il tracciato ---
    def advance(self, dist):
        """Muove il bus lungo i segmenti fra i punti fissi interpolando
        linearmente (dist = velocita' * dt). NON inverte la marcia da solo:
        restituisce lo stato del nodo raggiunto ("terminal", "dwell") oppure
        None se resta all'interno di un segmento."""
        while dist > 1e-9:
            nb = self.node + self.direction
            if nb < 0 or nb >= len(self.points):
                return "terminal"                   # gia' fermo al capolinea
            a = self.points[self.node]
            b = self.points[nb]
            seg = haversine(a[0], a[1], b[0], b[1])
            if seg < 1e-6:                          # segmento nullo: salta al nodo
                self.node, self.frac = nb, 0.0
                st = self._node_status()
                if st:
                    return st
                continue
            remain = seg * (1.0 - self.frac)        # metri residui sul segmento
            if dist < remain:
                self.frac += dist / seg             # posizione interpolata sul segmento
                return None
            dist -= remain
            self.node, self.frac = nb, 0.0          # arrivato esattamente al nodo nb
            st = self._node_status()
            if st:
                return st
        return None

    # --- variazione passeggeri alla ripartenza da fermata/capolinea ---
    def _boarding(self):
        # I passeggeri cambiano SOLO alle fermate, in modo graduale (piccoli
        # incrementi) e non piu' di una volta al minuto: se e' passato meno di
        # OCC_MIN_INTERVAL dall'ultima variazione, non cambia nulla.
        if self.t - self.last_occ_change < OCC_MIN_INTERVAL:
            return
        delta = random.randint(-OCC_MAX_DELTA, OCC_MAX_DELTA)
        if delta == 0:
            return
        self.occ = max(0, min(CAPACITY, self.occ + delta))
        self.last_occ_change = self.t

    # --- aggiornamento di un passo di simulazione (dt secondi) ---
    def tick(self, dt):
        self.t += dt                               # avanza l'orologio di simulazione

        # --- rottura/guasto: il bus si ferma e smette di trasmettere ---
        if self.state == "broken":
            self.spd = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self.state = "run"                 # riparazione: riprende la marcia
            return
        # rottura casuale: in media una ogni BREAKDOWN_MTBF, dura BREAKDOWN_DUR
        if random.random() < dt / BREAKDOWN_MTBF:
            self.state = "broken"
            self.timer = BREAKDOWN_DUR
            self.spd = 0.0
            return

        if self.state == "dwell":                  # sosta a fermata intermedia
            self.spd = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self._boarding()                   # solo ora cambia occ
                self.state = "run"

        elif self.state == "terminal":             # sosta al capolinea (3 min)
            self.spd = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self.direction *= -1               # inversione: verso l'altro capolinea
                self._boarding()
                self.state = "run"

        elif self.state == "traffic":
            self.spd = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self.state = "run"

        else:  # "run"
            if random.random() < TRAFFIC_PROB:     # ingorgo casuale
                self.state = "traffic"
                self.timer = random.uniform(TRAFFIC_MIN, TRAFFIC_MAX)
                self.spd = 0.0
                return

            v = (CRUISE_KMH / 3.6) * (1 + random.uniform(-SPEED_JITTER, SPEED_JITTER))  # m/s
            prev = self.position()
            status = self.advance(v * dt)
            cur = self.position()
            moved = haversine(prev[0], prev[1], cur[0], cur[1])
            self.spd = moved / dt * 3.6            # km/h effettivi
            if moved > 0.1:
                self.hdg = bearing(prev[0], prev[1], cur[0], cur[1])

            if status == "terminal":               # capolinea -> sosta lunga + inversione
                self.state = "terminal"
                self.timer = TERMINAL_DWELL
                self.spd = 0.0
            elif status == "dwell":                # fermata intermedia -> sosta breve
                self.state = "dwell"
                self.timer = max(5.0, STOP_DWELL + random.uniform(-STOP_JITTER, STOP_JITTER))
                self.spd = 0.0

    # --- payload JSON (stessa struttura della riga mosquitto_pub, + occ) ---
    def payload(self):
        lat, lon = self.position()
        return {
            "id":   self.id,
            "ts":   int(time.time()),
            "lat":  round(lat, 6),
            "lon":  round(lon, 6),
            "spd":  round(self.spd, 1),
            "hdg":  round(self.hdg, 1),
            "occ":  self.occ,                       # passeggeri a bordo
            "sat":  random.randint(6, 12),
            "bat":  round(self.bat, 2),
            "tech": "sim",
            "rsrp": random.randint(-110, -70),
        }


# ------------------------------------------------------------------ #
#  Loop di simulazione (un thread per bus, client MQTT condiviso)     #
# ------------------------------------------------------------------ #
_stop = threading.Event()


def _next_interval():
    """Intervallo di invio con jitter casuale, per de-sincronizzare i bus."""
    return SEND_INTERVAL * (1.0 + random.uniform(-SEND_JITTER, SEND_JITTER))


def run_bus(bus, client):
    """Simula il bus a passi fini (SIM_STEP) e pubblica su MQTT circa una volta
    ogni SEND_INTERVAL secondi, ma con fase iniziale casuale e jitter: cosi' i
    bus NON trasmettono tutti nello stesso istante. Durante una rottura
    ("broken") il bus non invia nulla, simulando un'interruzione."""
    def publish():
        msg = json.dumps(bus.payload())
        client.publish(bus.topic, msg)
        print(f"[{bus.id:>6}] {bus.state:<8} spd={bus.spd:5.1f} km/h "
              f"occ={bus.occ:<2} -> {bus.topic}")

    since_publish = 0.0
    # primo invio sfasato a caso nell'intervallo: i bus non partono sincronizzati
    target = random.uniform(0.0, SEND_INTERVAL)
    while not _stop.is_set():
        bus.tick(SIM_STEP)
        since_publish += SIM_STEP
        if since_publish >= target:
            since_publish = 0.0
            target = _next_interval()
            if bus.state != "broken":              # durante una rottura non trasmette
                publish()
        _stop.wait(SIM_STEP)


def make_client(insecure):
    # compatibile sia con paho-mqtt 1.x sia 2.x
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION1)
    except (AttributeError, TypeError):
        client = mqtt.Client()
    client.username_pw_set(USER, PASSWORD)
    if insecure:
        client.tls_set(cert_reqs=ssl.CERT_NONE)     # NON verifica il certificato
        client.tls_insecure_set(True)
    else:
        client.tls_set(cert_reqs=ssl.CERT_REQUIRED) # usa la CA di sistema (equiv. --capath)
    return client


def main():
    ap = argparse.ArgumentParser(description="Simulatore bus CassiTrack -> MQTT")
    ap.add_argument("file", help="file JSON delle linee (es. percorso1.json)")
    ap.add_argument("--insecure", action="store_true",
                    help="salta la verifica del certificato TLS")
    ap.add_argument("--send-interval", type=float, default=60.0,
                    metavar="SEC",
                    help="secondi tra due invii MQTT per ciascun bus (default: 60)")
    ap.add_argument("--terminal-dwell", type=float, default=180.0,
                    metavar="SEC",
                    help="sosta ai capolinea prima dell'inversione, in secondi (default: 180)")
    ap.add_argument("--cruise-kmh", type=float, default=25.0,
                    metavar="KMH",
                    help="velocita' di crociera urbana in km/h (default: 25)")
    ap.add_argument("--sim-step", type=float, default=1.0,
                    metavar="SEC",
                    help="passo interno di simulazione in secondi (default: 1)")
    args = ap.parse_args()

    global SEND_INTERVAL, TERMINAL_DWELL, CRUISE_KMH, SIM_STEP
    SEND_INTERVAL  = args.send_interval
    TERMINAL_DWELL = args.terminal_dwell
    CRUISE_KMH     = args.cruise_kmh
    SIM_STEP       = max(0.05, args.sim_step)

    with open(args.file, "r", encoding="utf-8") as f:
        data = json.load(f)
    buses = [Bus(b) for b in data.get("buses", []) if len(b.get("points", [])) >= 2]
    if not buses:
        print("Nessun bus valido nel file (servono almeno 2 punti per linea).")
        sys.exit(1)

    client = make_client(args.insecure)
    print(f"Connessione a {BROKER}:{PORT} ...")
    client.connect(BROKER, PORT, keepalive=60)
    client.loop_start()

    print(f"Avvio simulazione di {len(buses)} bus. Premi Ctrl+C per fermare.")
    print(f"  invio MQTT ....... 1 ogni {SEND_INTERVAL:.0f} s per bus")
    print(f"  velocita' ........ {CRUISE_KMH:.0f} km/h (+/-{SPEED_JITTER*100:.0f}%)")
    print(f"  sosta capolinea .. {TERMINAL_DWELL:.0f} s, poi inversione di marcia")
    print(f"  passo simulazione. {SIM_STEP:.2f} s\n")
    threads = [threading.Thread(target=run_bus, args=(b, client), daemon=True) for b in buses]
    for t in threads:
        t.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nArresto in corso ...")
    finally:
        _stop.set()
        for t in threads:
            t.join(timeout=2)
        client.loop_stop()
        client.disconnect()
        print("Simulazione terminata.")


if __name__ == "__main__":
    main()