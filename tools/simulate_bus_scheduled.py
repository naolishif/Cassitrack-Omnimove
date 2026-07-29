#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
simulate_bus_scheduled.py
=========================
Schedule-aware bus simulator for CassiTrack.

Derived from the reference simulator `simulate_bus.py` (Prof.'s
GeneratePathAndSimulateSend). His movement model is kept almost verbatim —
haversine interpolation along the path, dwell at stops, random traffic stalls,
occupancy that only changes on departure, occasional breakdowns. Two things
change:

  1. IT FOLLOWS OUR TIMETABLE.
     The original loops the route end to end forever, on its own clock. Delay
     is then meaningless: ScheduleAdherenceService compares an arrival against
     an absolute scheduled time, so a bus that simply drives round all day
     produces essentially random punctuality figures. Here each bus waits at
     the terminus and departs at the times in `scheduled_stops`, then paces
     itself to meet the scheduled arrival at every intermediate stop.

     Delays therefore become REAL: a traffic stall or a long dwell makes the
     bus genuinely late, and the punctuality dashboard means something.

  2. IT IS DATABASE-DRIVEN.
     Routes, stops, road geometry and departure times all come from Postgres
     (route_shapes / scheduled_stops / trips / buses) rather than a JSON file,
     so the simulation and the system can never drift out of step. Reshape a
     path in the route editor, re-run the importer, and the buses follow the
     new roads immediately.

The wire format is UNCHANGED from the reference: the same compact payload on
the same `cassitrack/obu/{id}/pos` topic, so ObuPositionPayload translates it
exactly as it would a real ESP32 unit.

Usage
-----
    pip install paho-mqtt psycopg2-binary

    # against the local Mosquitto (default)
    python tools/simulate_bus_scheduled.py

    # against the external TLS broker the real units use
    python tools/simulate_bus_scheduled.py --obu-broker

    # start at a given time of day, and run faster than real time
    python tools/simulate_bus_scheduled.py --start 08:00 --speed 10
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
from datetime import datetime

import paho.mqtt.client as mqtt
import psycopg2
import psycopg2.extras


# ─────────────────────────────────────────────────────────────────
#  Environment / connection defaults
# ─────────────────────────────────────────────────────────────────
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

DB_CONFIG = {
    "host":     os.environ.get("SPRING_DATASOURCE_HOST", "localhost"),
    "port":     int(os.environ.get("SPRING_DATASOURCE_PORT", "5433")),
    "dbname":   os.environ.get("SPRING_DATASOURCE_DB", "cassitrack"),
    "user":     os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
    "password": os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
}

TOPIC_TMPL = "cassitrack/obu/{bus}/pos"

# ─────────────────────────────────────────────────────────────────
#  Simulation parameters (same names/meanings as the reference)
# ─────────────────────────────────────────────────────────────────
MAX_KMH          = 50.0    # ceiling: a bus that needs more than this runs late
MIN_KMH          = 8.0     # floor: crawls rather than stopping dead when early
SPEED_JITTER     = 0.10    # ±10% noise on the pace it aims for
SIM_STEP         = 1.0     # internal integration step (s)
SEND_INTERVAL    = 20.0    # seconds between MQTT publishes per bus
SEND_JITTER      = 0.20    # ± on the publish interval, to desynchronise buses
STOP_DWELL       = 20.0    # mean dwell at an intermediate stop (s)
STOP_JITTER      = 6.0     # ± variability of that dwell
TRAFFIC_PROB     = 0.004   # chance per step of hitting congestion
TRAFFIC_MIN      = 5.0
TRAFFIC_MAX      = 25.0
OCC_MAX_DELTA    = 4       # max passenger change at a stop
BREAKDOWN_MTBF   = 86400.0 # mean time between breakdowns (s) ≈ 24 h
BREAKDOWN_DUR    = 600.0   # a breakdown silences the unit for 10 min

R_EARTH = 6371000.0
COORD_TOL = 1e-5           # ~1 m: shape vertices are authored from stop coords


# ─────────────────────────────────────────────────────────────────
#  Geography (unchanged from the reference)
# ─────────────────────────────────────────────────────────────────
def haversine(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R_EARTH * math.asin(math.sqrt(a))


def bearing(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def interp(a, b, f):
    return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)


# ─────────────────────────────────────────────────────────────────
#  Loading the network and the timetable
# ─────────────────────────────────────────────────────────────────
def load_network(conn):
    """Assemble everything each bus needs to drive its duty.

    Returns a list of bus specs:
        {vehicle_id, capacity, shape[route_id], trips:[{stops:[(idx, secs)]}]}
    where each trip's stops are (index into the route's shape, scheduled
    arrival in seconds of day) — so the simulator can pace between them.
    """
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    cur.execute("SELECT id, lat, lon FROM stops")
    stops = {r["id"]: (r["lat"], r["lon"]) for r in cur.fetchall()}

    cur.execute("SELECT route_id, seq, lat, lon FROM route_shapes "
                "ORDER BY route_id, seq")
    shapes = {}
    for r in cur.fetchall():
        shapes.setdefault(r["route_id"], []).append((r["lat"], r["lon"]))

    cur.execute("""
        SELECT bus_id, current_vehicle_id, numero_posti
        FROM buses
        WHERE current_vehicle_id IS NOT NULL AND disponibile = TRUE
        ORDER BY bus_id
    """)
    buses = cur.fetchall()

    out = []
    for b in buses:
        cur.execute("""
            SELECT t.id AS trip_id, t.route_id
            FROM trips t
            WHERE t.bus_id = %s
        """, (b["bus_id"],))
        trip_rows = cur.fetchall()
        if not trip_rows:
            continue

        trips = []
        for t in trip_rows:
            shape = shapes.get(t["route_id"])
            if not shape or len(shape) < 2:
                continue                      # no geometry imported for this route

            cur.execute("""
                SELECT stop_id, stop_sequence, arrival_seconds
                FROM scheduled_stops
                WHERE trip_id = %s
                ORDER BY stop_sequence
            """, (t["trip_id"],))
            sched = cur.fetchall()
            if len(sched) < 2:
                continue

            # Bind each scheduled stop to its vertex in the path. Scanning
            # FORWARD from the previous match is what makes ring lines work:
            # LINEA_1 calls at VBO, SFF and VGA twice, so a stop's coordinates
            # alone do not identify which visit is meant.
            pts, cursor_i, ok = [], 0, True
            for s in sched:
                coord = stops.get(s["stop_id"])
                if coord is None:
                    ok = False
                    break
                found = -1
                for i in range(cursor_i, len(shape)):
                    if (abs(shape[i][0] - coord[0]) < COORD_TOL
                            and abs(shape[i][1] - coord[1]) < COORD_TOL):
                        found = i
                        break
                if found < 0:
                    ok = False
                    break
                pts.append((found, s["arrival_seconds"], s["stop_id"]))
                cursor_i = found + 1
            if not ok or len(pts) < 2:
                continue

            trips.append({
                "trip_id": t["trip_id"],
                "route_id": t["route_id"],
                "stops": pts,
                "start": pts[0][1],
                "end": pts[-1][1],
            })

        if not trips:
            continue
        trips.sort(key=lambda x: x["start"])
        out.append({
            "vehicle_id": b["current_vehicle_id"],
            "capacity": b["numero_posti"] or 50,
            "shapes": shapes,
            "trips": trips,
        })

    cur.close()
    return out


# ─────────────────────────────────────────────────────────────────
#  The bus
# ─────────────────────────────────────────────────────────────────
class ScheduledBus:
    """A bus working a published duty.

    States:
      idle    — waiting at the origin for its next departure
      run     — between stops, pacing to meet the next scheduled arrival
      dwell   — stopped at an intermediate stop
      traffic — held up in congestion (a genuine source of delay)
      broken  — stopped and silent
    """

    def __init__(self, spec):
        self.id = spec["vehicle_id"]
        self.topic = TOPIC_TMPL.format(bus=self.id)
        self.capacity = spec["capacity"]
        self.shapes = spec["shapes"]
        self.trips = spec["trips"]

        self.trip_i = 0
        self.leg = 0            # index of the stop we have just left
        self.node = 0           # current vertex index in the shape
        self.frac = 0.0         # progress along node -> node+1
        self.state = "idle"
        self.timer = 0.0

        self.spd = 0.0
        self.hdg = 0.0
        self.occ = random.randint(0, max(1, self.capacity // 3))
        self.bat = random.uniform(3.8, 4.2)
        self.last_occ_change = -1e9

    # ── geometry helpers ────────────────────────────────────────
    @property
    def trip(self):
        return self.trips[self.trip_i] if self.trip_i < len(self.trips) else None

    @property
    def shape(self):
        t = self.trip
        return self.shapes[t["route_id"]] if t else []

    def position(self):
        sh = self.shape
        if not sh:
            return (41.4925, 13.8306)
        if self.node >= len(sh) - 1:
            return sh[-1]
        return interp(sh[self.node], sh[self.node + 1], self.frac)

    def _dist_to(self, target_idx):
        """Metres from the current position to a vertex further along."""
        sh = self.shape
        if self.node >= target_idx:
            return 0.0
        here = self.position()
        total = haversine(here[0], here[1], sh[self.node + 1][0], sh[self.node + 1][1])
        for i in range(self.node + 1, target_idx):
            total += haversine(sh[i][0], sh[i][1], sh[i + 1][0], sh[i + 1][1])
        return total

    def _advance(self, metres):
        """Move along the path; returns True on reaching the next stop vertex."""
        sh = self.shape
        target = self.trip["stops"][self.leg + 1][0]
        while metres > 1e-9:
            if self.node >= target:
                return True
            seg = haversine(sh[self.node][0], sh[self.node][1],
                            sh[self.node + 1][0], sh[self.node + 1][1])
            if seg < 1e-6:
                self.node, self.frac = self.node + 1, 0.0
                continue
            remain = seg * (1.0 - self.frac)
            if metres < remain:
                self.frac += metres / seg
                return self.node >= target
            metres -= remain
            self.node, self.frac = self.node + 1, 0.0
            if self.node >= target:
                return True
        return self.node >= target

    def _boarding(self):
        """Passengers change only on departure, as in the reference."""
        delta = random.randint(-OCC_MAX_DELTA, OCC_MAX_DELTA)
        if delta:
            self.occ = max(0, min(self.capacity, self.occ + delta))

    def _start_trip_at(self, now):
        """Park the bus at the origin of the next trip that has not finished."""
        while self.trip and now > self.trip["end"] + 60:
            self.trip_i += 1
        if not self.trip:                      # duty finished for today
            return False
        self.leg = 0
        self.node = self.trip["stops"][0][0]
        self.frac = 0.0
        return True

    # ── one simulation step ─────────────────────────────────────
    def tick(self, dt, now):
        """`now` is seconds since midnight — the same clock the timetable uses."""
        if self.state == "broken":
            self.spd = 0.0
            self.timer -= dt
            if self.timer <= 0:
                self.state = "run"
            return

        if random.random() < dt / BREAKDOWN_MTBF:
            self.state, self.timer, self.spd = "broken", BREAKDOWN_DUR, 0.0
            return

        if self.state == "idle":
            self.spd = 0.0
            if not self._start_trip_at(now):
                return
            # Depart when the timetable says, not before.
            if now >= self.trip["stops"][0][1]:
                self.state = "run"
                self._boarding()
            return

        if self.state in ("dwell", "traffic"):
            self.spd = 0.0
            self.timer -= dt
            if self.timer <= 0:
                if self.state == "dwell":
                    self._boarding()
                self.state = "run"
            return

        # ── running: pace towards the next scheduled arrival ──
        t = self.trip
        if not t:
            self.state = "idle"
            return

        if random.random() < TRAFFIC_PROB:
            self.state, self.timer, self.spd = "traffic", \
                random.uniform(TRAFFIC_MIN, TRAFFIC_MAX), 0.0
            return

        target_idx, target_time, _ = t["stops"][self.leg + 1]
        remaining_m = self._dist_to(target_idx)
        remaining_s = target_time - now

        # The heart of it: aim to arrive exactly on time. If that needs more
        # than MAX_KMH the bus cannot catch up and is genuinely late — which is
        # precisely the signal the adherence service is meant to detect.
        if remaining_s <= 1:
            need_kmh = MAX_KMH
        else:
            need_kmh = (remaining_m / remaining_s) * 3.6
        kmh = max(MIN_KMH, min(MAX_KMH, need_kmh))
        kmh *= 1 + random.uniform(-SPEED_JITTER, SPEED_JITTER)

        prev = self.position()
        arrived = self._advance(kmh / 3.6 * dt)
        cur = self.position()
        moved = haversine(prev[0], prev[1], cur[0], cur[1])
        self.spd = moved / dt * 3.6
        if moved > 0.1:
            self.hdg = bearing(prev[0], prev[1], cur[0], cur[1])

        if arrived:
            self.leg += 1
            if self.leg >= len(t["stops"]) - 1:
                # Terminus: rest here until the next duty is due.
                self.trip_i += 1
                self.state = "idle"
                self.spd = 0.0
            else:
                self.state = "dwell"
                self.timer = max(5.0, STOP_DWELL +
                                 random.uniform(-STOP_JITTER, STOP_JITTER))
                self.spd = 0.0

    # ── the wire format: identical to the reference simulator ───
    def payload(self):
        lat, lon = self.position()
        return {
            "id":   self.id,
            "ts":   int(time.time()),
            "lat":  round(lat, 6),
            "lon":  round(lon, 6),
            "spd":  round(self.spd, 1),
            "hdg":  round(self.hdg, 1),
            "occ":  self.occ,
            "sat":  random.randint(6, 12),
            "bat":  round(self.bat, 2),
            "tech": "sim",
            "rsrp": random.randint(-110, -70),
        }


# ─────────────────────────────────────────────────────────────────
#  Runner
# ─────────────────────────────────────────────────────────────────
_stop = threading.Event()


def run_bus(bus, client, args, clock):
    since, target = 0.0, random.uniform(0, SEND_INTERVAL)
    while not _stop.is_set():
        bus.tick(SIM_STEP * args.speed, clock())
        since += SIM_STEP * args.speed
        if since >= target:
            since, target = 0.0, SEND_INTERVAL * (1 + random.uniform(-SEND_JITTER, SEND_JITTER))
            if bus.state != "broken":
                client.publish(bus.topic, json.dumps(bus.payload()))
                trip = bus.trip
                print(f"[{bus.id:>6}] {bus.state:<7} {bus.spd:5.1f} km/h "
                      f"occ={bus.occ:<3} trip={trip['trip_id'] if trip else '—'}")
        _stop.wait(SIM_STEP)


def make_client(args):
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION1)
    except (AttributeError, TypeError):
        client = mqtt.Client()

    if args.obu_broker:
        host = os.environ.get("MQTT_OBU_URL", "ssl://devaidalab.unicas.it:8883")
        host = host.replace("ssl://", "").replace("tcp://", "")
        host, _, port = host.partition(":")
        port = int(port or 8883)
        client.username_pw_set(os.environ.get("MQTT_OBU_USERNAME", "esp32"),
                               os.environ.get("MQTT_OBU_PASSWORD", ""))
        client.tls_set(cert_reqs=ssl.CERT_NONE if args.insecure else ssl.CERT_REQUIRED)
        if args.insecure:
            client.tls_insecure_set(True)
    else:
        host, port = args.broker, args.port
        user = os.environ.get("MQTT_USERNAME", "")
        if user:
            client.username_pw_set(user, os.environ.get("MQTT_PASSWORD", ""))

    print(f"MQTT → {host}:{port}")
    client.connect(host, port, keepalive=60)
    return client


def main():
    ap = argparse.ArgumentParser(
        description="Schedule-aware CassiTrack bus simulator (OBU wire format).")
    ap.add_argument("--broker", default="localhost", help="local broker host")
    ap.add_argument("--port", type=int, default=1883)
    ap.add_argument("--obu-broker", action="store_true",
                    help="publish to the external TLS broker instead of the local one")
    ap.add_argument("--insecure", action="store_true",
                    help="skip TLS certificate verification (with --obu-broker)")
    ap.add_argument("--start", metavar="HH:MM",
                    help="start at this time of day instead of now (e.g. 08:00)")
    ap.add_argument("--speed", type=float, default=1.0,
                    help="simulation speed multiplier (default: real time)")
    args = ap.parse_args()

    print("Connecting to PostgreSQL…")
    try:
        conn = psycopg2.connect(**DB_CONFIG)
        conn.autocommit = True
    except Exception as e:
        sys.exit(f"Cannot connect to PostgreSQL: {e}\n"
                 f"Is docker compose up? (cassitrack-postgres on port "
                 f"{DB_CONFIG['port']})")

    specs = load_network(conn)
    if not specs:
        sys.exit("No buses with both a timetable and route geometry.\n"
                 "Run the Flyway migrations (V9–V12) first.")

    print(f"\n{len(specs)} bus(es) on duty:")
    for s in specs:
        print(f"  {s['vehicle_id']:>6}  {len(s['trips'])} trip(s), "
              f"first departure {s['trips'][0]['start'] // 3600:02d}:"
              f"{s['trips'][0]['start'] % 3600 // 60:02d}")

    # Simulated clock: seconds since midnight, optionally offset and sped up.
    if args.start:
        h, _, m = args.start.partition(":")
        sim_origin = int(h) * 3600 + int(m or 0) * 60
    else:
        n = datetime.now()
        sim_origin = n.hour * 3600 + n.minute * 60 + n.second
    wall_origin = time.time()

    def clock():
        return (sim_origin + (time.time() - wall_origin) * args.speed) % 86400

    print(f"Clock starts at {int(clock()) // 3600:02d}:{int(clock()) % 3600 // 60:02d}"
          f"  (×{args.speed} real time)\n")

    client = make_client(args)
    client.loop_start()

    threads = [threading.Thread(target=run_bus,
                                args=(ScheduledBus(s), client, args, clock),
                                daemon=True)
               for s in specs]
    for t in threads:
        t.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping…")
        _stop.set()
        for t in threads:
            t.join(timeout=2)
        client.loop_stop()
        conn.close()


if __name__ == "__main__":
    main()
