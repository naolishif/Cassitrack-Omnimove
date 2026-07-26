#!/usr/bin/env python3
"""
CASSITRACK — GPS Bus Simulator (Database-Driven)
University of Cassino and Southern Lazio

Loads the 4 real buses and their scheduled routes directly from PostgreSQL.
Each bus follows its correct trip based on the current time of day,
interpolating between real stop coordinates.

Usage:
    python gps_simulator2.py
    python gps_simulator2.py --broker localhost --port 1883 --interval 10

Dependencies:
    pip install paho-mqtt psycopg2-binary
    pip install tzdata
"""

import json
import math
import os
import random
import time
import argparse
from datetime import datetime, timezone
import paho.mqtt.client as mqtt
import psycopg2
import psycopg2.extras


def _load_env_file(path):
    """Minimal .env loader (no python-dotenv dependency needed).

    Reads KEY=VALUE lines from `path` and sets them into os.environ,
    WITHOUT overriding any variable already set in the real environment
    (so `--db-*` CLI flags / real env vars still win).
    """
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.split("#", 1)[0].strip()  # drop inline comments
            if key and key not in os.environ:
                os.environ[key] = value


# Auto-load cassitrack-backend/.env (same secrets docker-compose uses to
# initialize Postgres) so this script's credentials always match the running
# container without needing to export env vars by hand every time.
_load_env_file(os.path.join(os.path.dirname(os.path.abspath(__file__)), "cassitrack-backend", ".env"))

# ── Database connection — reads from env vars, falls back to local dev defaults ─
DB_CONFIG = {
    "host":     os.environ.get("SPRING_DATASOURCE_HOST", "localhost"),
    "port":     int(os.environ.get("SPRING_DATASOURCE_PORT", "5433")),
    "dbname":   os.environ.get("SPRING_DATASOURCE_DB",   "cassitrack"),
    "user":     os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
    "password": os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
}

# ── Simulation constants ──────────────────────────────────────────
STEPS_BETWEEN_STOPS  = 12      # interpolation points between each pair of stops
GPS_NOISE_METRES     = 6       # ±6m GPS jitter
MAX_SPEED_KMH        = 50
MIN_SPEED_KMH        = 12
BUS_CAPACITY         = {       # from buses.numero_posti
    "MAGNI-001": 85,
    "MAGNI-002": 85,
    "MAGNI-003": 52,
    "MAGNI-004": 52,
}


# ─────────────────────────────────────────────────────────────────
# Database helpers
# ─────────────────────────────────────────────────────────────────

def load_buses(conn):
    """Load the 4 buses from the DB."""
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("""
            SELECT bus_id, targa, current_vehicle_id,
                   numero_posti, wheelchair_accessible
            FROM buses
            WHERE disponibile = TRUE
            ORDER BY bus_id
        """)
        return cur.fetchall()


def load_stops(conn):
    """Load all stops as a dict: stop_id → {lat, lon, name}."""
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("SELECT id, name, lat, lon FROM stops")
        return {row["id"]: dict(row) for row in cur.fetchall()}


def find_active_trip(conn, bus_id, now_seconds):
    """
    Find the trip that this bus is running right now.

    Logic: find the trip whose first stop departure is the latest
    one that is still <= now_seconds (i.e. the trip already started
    and hasn't finished yet based on last stop arrival).
    """
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("""
            SELECT
                t.id          AS trip_id,
                t.route_id,
                r.short_name  AS route_name,
                r.long_name   AS route_long_name,
                r.color       AS route_color,
                MIN(ss.arrival_seconds) AS departure_seconds,
                MAX(ss.arrival_seconds) AS last_arrival_seconds
            FROM trips t
            JOIN routes r ON r.id = t.route_id
            JOIN scheduled_stops ss ON ss.trip_id = t.id
            WHERE t.bus_id = %s
            GROUP BY t.id, t.route_id, r.short_name, r.long_name, r.color
            HAVING MIN(ss.arrival_seconds) <= %s
               AND MAX(ss.arrival_seconds) >= %s
            ORDER BY MIN(ss.arrival_seconds) DESC
            LIMIT 1
        """, (bus_id, now_seconds, now_seconds))
        return cur.fetchone()


def load_trip_stops(conn, trip_id):
    """
    Load the ordered stops for a trip, with coordinates.
    Returns list of dicts: {stop_id, name, lat, lon, arrival_seconds, stop_sequence}
    """
    with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
        cur.execute("""
            SELECT
                ss.stop_sequence,
                ss.arrival_seconds,
                ss.stop_id,
                s.name,
                s.lat,
                s.lon
            FROM scheduled_stops ss
            JOIN stops s ON s.id = ss.stop_id
            WHERE ss.trip_id = %s
            ORDER BY ss.stop_sequence ASC
        """, (trip_id,))
        return cur.fetchall()


# ─────────────────────────────────────────────────────────────────
# Geometry helpers
# ─────────────────────────────────────────────────────────────────

def haversine_metres(lat1, lon1, lat2, lon2):
    R = 6371000
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2
         + math.cos(math.radians(lat1))
         * math.cos(math.radians(lat2))
         * math.sin(dlon / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def compute_heading(lat1, lon1, lat2, lon2):
    dlon = math.radians(lon2 - lon1)
    lat1r, lat2r = math.radians(lat1), math.radians(lat2)
    x = math.sin(dlon) * math.cos(lat2r)
    y = (math.cos(lat1r) * math.sin(lat2r)
         - math.sin(lat1r) * math.cos(lat2r) * math.cos(dlon))
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def add_gps_noise(lat, lon, metres=GPS_NOISE_METRES):
    dlat = random.uniform(-metres, metres) / 111320
    dlon = random.uniform(-metres, metres) / (111320 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def interpolate_waypoints(trip_stops, steps):
    """
    Build a dense list of waypoints from the trip's ordered stops.
    Each waypoint carries: lat, lon, nearest_stop_name, arrival_seconds (interpolated).
    """
    waypoints = []
    stops = list(trip_stops)

    for i in range(len(stops) - 1):
        a, b = stops[i], stops[i + 1]
        for step in range(steps):
            t = step / steps
            waypoints.append({
                "lat":              a["lat"] + (b["lat"] - a["lat"]) * t,
                "lon":              a["lon"] + (b["lon"] - a["lon"]) * t,
                "nearest_stop":     a["name"],
                "nearest_stop_id":  a["stop_id"],
                "arrival_seconds":  int(a["arrival_seconds"]
                                        + (b["arrival_seconds"] - a["arrival_seconds"]) * t),
                "at_stop":          step == 0,
            })

    # Final stop
    last = stops[-1]
    waypoints.append({
        "lat":             last["lat"],
        "lon":             last["lon"],
        "nearest_stop":    last["name"],
        "nearest_stop_id": last["stop_id"],
        "arrival_seconds": last["arrival_seconds"],
        "at_stop":         True,
    })
    return waypoints


def find_waypoint_index_for_time(waypoints, now_seconds):
    """
    Find the waypoint index that best matches the current time.
    This places the bus at the right position on the route right now.
    """
    for i, wp in enumerate(waypoints):
        if wp["arrival_seconds"] >= now_seconds:
            return max(0, i - 1)
    return len(waypoints) - 1


# ─────────────────────────────────────────────────────────────────
# Bus simulator class
# ─────────────────────────────────────────────────────────────────

class BusSimulator:
    def __init__(self, bus_row, conn):
        self.bus_id      = bus_row["bus_id"]
        self.vehicle_id  = bus_row["current_vehicle_id"]   # e.g. MAGNI-001
        self.targa       = bus_row["targa"]
        self.capacity    = bus_row["numero_posti"]
        self.wheelchair  = bus_row["wheelchair_accessible"]
        self.conn        = conn

        # Simulated passenger state.
        # Clamp so small-capacity buses don't break randint: e.g. a 10-seat bus
        # gives int(10*0.4)=4, which is below the old fixed minimum of 5, making
        # randint(5, 4) raise "empty range". Keep low <= high for any capacity.
        _pax_high = max(1, int(self.capacity * 0.4))
        self.passengers      = random.randint(min(5, _pax_high), _pax_high)
        self.speed_kmh       = random.uniform(25, 40)
        self.battery_voltage = random.uniform(12.2, 12.8)

        # Trip / route state
        self.trip_id        = None
        self.route_id       = None
        self.route_name     = None
        self.route_color    = None
        self.waypoints      = []
        self.wp_index       = 0
        self.active         = False

        self._load_current_trip()

    def _load_current_trip(self):
        """Load the trip active right now for this bus."""
        now_seconds = self._now_seconds()
        trip = find_active_trip(self.conn, self.bus_id, now_seconds)

        if trip is None:
            print(f"  ⚠️  {self.vehicle_id}: no active trip at {self._now_str()} "
                  f"(service runs 06:00–22:00)")
            self.active = False
            return

        trip_stops = load_trip_stops(self.conn, trip["trip_id"])
        self.trip_id     = trip["trip_id"]
        self.route_id    = trip["route_id"]
        self.route_name  = trip["route_name"]
        self.route_color = trip["route_color"] or "1976D2"
        self.waypoints   = interpolate_waypoints(trip_stops, STEPS_BETWEEN_STOPS)
        self.wp_index    = find_waypoint_index_for_time(self.waypoints, now_seconds)
        self.active      = True

        wp = self.waypoints[self.wp_index]
        print(f"  ✅ {self.vehicle_id} ({self.targa}) → trip {self.trip_id} "
              f"| route {self.route_name} | near: {wp['nearest_stop']}")

    def _now_seconds(self):
        """Seconds since midnight (Rome time)."""
        from datetime import date
        import zoneinfo
        rome = zoneinfo.ZoneInfo("Europe/Rome")
        now  = datetime.now(rome)
        return now.hour * 3600 + now.minute * 60 + now.second

    def _now_str(self):
        from zoneinfo import ZoneInfo
        rome = ZoneInfo("Europe/Rome")
        return datetime.now(rome).strftime("%H:%M:%S")

    def _reload_if_trip_ended(self):
        """Check if the current trip is over, based on real elapsed time, and reload."""
        if not self.active:
            self._load_current_trip()
            return

        now_seconds  = self._now_seconds()
        last_arrival = self.waypoints[-1]["arrival_seconds"]

        if now_seconds > last_arrival:
            print(f"  🔄 {self.vehicle_id}: trip {self.trip_id} finished, loading next...")
            self._load_current_trip()

    def _simulate_passengers(self, at_stop):
        """Realistic passenger boarding/alighting at stops."""
        if at_stop:
            alighting = random.randint(0, min(8, self.passengers))
            boarding  = random.randint(0, min(10, self.capacity - self.passengers + alighting))
            self.passengers = max(0, self.passengers - alighting + boarding)
        return self.passengers

    def next_payload(self):
        """Advance simulation one step and return the MQTT payload."""
        self._reload_if_trip_ended()

        if not self.active:
            return None

        now_seconds = self._now_seconds()
        self.wp_index = find_waypoint_index_for_time(self.waypoints, now_seconds)

        wp      = self.waypoints[self.wp_index]
        next_wp = self.waypoints[min(self.wp_index + 1, len(self.waypoints) - 1)]

        lat, lon = add_gps_noise(wp["lat"], wp["lon"])
        heading  = compute_heading(wp["lat"], wp["lon"], next_wp["lat"], next_wp["lon"])

        at_stop = wp["at_stop"]
        self._last_at_stop      = at_stop
        self._last_nearest_stop = wp["nearest_stop"]

        # Speed: slow down approaching stop, speed up between stops
        if at_stop:
            self.speed_kmh = 0
        else:
            target = random.uniform(28, 45)
            self.speed_kmh += (target - self.speed_kmh) * 0.3
            self.speed_kmh  = max(MIN_SPEED_KMH, min(MAX_SPEED_KMH, self.speed_kmh))
            self.speed_kmh += random.uniform(-2, 2)

        # Battery slow drain
        self.battery_voltage -= random.uniform(0, 0.002)
        self.battery_voltage  = max(11.8, min(12.8, self.battery_voltage))

        # Passengers
        occupancy = self._simulate_passengers(at_stop)


        # BLE scanner: the on-board unit counts nearby Bluetooth devices.
        # Not every passenger carries a discoverable device, and some carry
        # more than one — VehicleService calibrates back with a 0.6 factor.
        ble_devices = max(0, int(round(occupancy / 0.6)) + random.randint(-2, 2))

        return {
            # ── Identity ──────────────────────────────────────────
            "vehicle_id":        self.vehicle_id,
            "timestamp":         datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),

            # ── Position (raw GNSS output) ────────────────────────
            "lat":               round(lat, 6),
            "lon":               round(lon, 6),
            "heading_deg":       round(heading, 1),
            "speed_kmh":         round(self.speed_kmh, 1),

            # ── On-board sensors ──────────────────────────────────
            "ble_device_count":  ble_devices,
            "passengers":        occupancy,
            "capacity":          self.capacity,
            "battery_voltage":   round(self.battery_voltage, 2),
            "firmware_version":  "2.0.0-sim",
        }


# ─────────────────────────────────────────────────────────────────
# MQTT callbacks
# ─────────────────────────────────────────────────────────────────

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        print(f"✅ Connected to MQTT broker at "
              f"{userdata['broker']}:{userdata['port']}\n")
    else:
        print(f"❌ MQTT connection failed (rc={rc})")


# ─────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="CassiTrack GPS Simulator")
    parser.add_argument("--broker",         default="localhost",                        help="MQTT broker host")
    parser.add_argument("--port",           type=int, default=1883,                    help="MQTT broker port")
    parser.add_argument("--interval",       type=int, default=10,                      help="Publish interval in seconds (default: 10)")
    parser.add_argument("--db-host",        default=DB_CONFIG["host"])
    parser.add_argument("--db-port",        type=int, default=DB_CONFIG["port"])
    parser.add_argument("--mqtt-username",  default=os.environ.get("MQTT_BUS_USERNAME", "cassitrack-bus"), help="MQTT broker username (set MQTT_USERNAME env var or leave blank for no auth)")
    parser.add_argument("--mqtt-password",  default=os.environ.get("MQTT_BUS_PASSWORD", "bus-password"), help="MQTT broker password (set MQTT_PASSWORD env var or leave blank for no auth)")
    parser.add_argument("--raw",            action="store_true",                       help="Print the exact JSON payload published on MQTT")
    parser.add_argument("--raw-only",       action="store_true",                       help="Print ONLY the raw payloads, no summary line")
    args = parser.parse_args()
    if args.raw_only:
        args.raw = True

    # ── Connect to PostgreSQL ─────────────────────────────────────
    print("🗄️  Connecting to PostgreSQL...")
    try:
        conn = psycopg2.connect(
            host=args.db_host,
            port=args.db_port,
            dbname=DB_CONFIG["dbname"],
            user=DB_CONFIG["user"],
            password=DB_CONFIG["password"],
        )
        conn.autocommit = True
        print("✅ PostgreSQL connected\n")
    except Exception as e:
        print(f"❌ Cannot connect to PostgreSQL: {e}")
        print("   Make sure docker compose is running (cassitrack-postgres on port 5433)")
        return

    # ── Load buses from DB ────────────────────────────────────────
    buses_data = load_buses(conn)
    if not buses_data:
        print("❌ No buses found in database. Run Flyway migrations first.")
        return

    print(f"🚌 Found {len(buses_data)} buses in database. Initializing simulators...\n")
    simulators = []
    for bus_row in buses_data:
        sim = BusSimulator(bus_row, conn)
        simulators.append(sim)

    active = [s for s in simulators if s.active]
    print(f"\n{'─'*60}")
    print(f"  {len(active)}/{len(simulators)} buses active at current time")
    print(f"  Publish interval: {args.interval}s")
    print(f"  MQTT topic pattern: cassitrack/<vehicle_id>/position")
    print(f"  Payload fields:     vehicle_id, timestamp, lat, lon, heading_deg,")
    print(f"                      speed_kmh, ble_device_count, passengers,")
    print(f"                      capacity, battery_voltage, firmware_version")
    if args.raw:
        print(f"  Raw payload dump:   ON")
    print(f"{'─'*60}\n")

    # ── Connect to MQTT ───────────────────────────────────────────
    client = mqtt.Client(userdata={"broker": args.broker, "port": args.port})
    client.on_connect = on_connect
    if args.mqtt_username:
        client.username_pw_set(args.mqtt_username, args.mqtt_password)
    try:
        client.connect(args.broker, args.port, keepalive=60)
    except Exception as e:
        print(f"❌ Cannot connect to MQTT broker: {e}")
        print("   Make sure cassitrack-mosquitto is running on port 1883")
        conn.close()
        return

    client.loop_start()
    time.sleep(1)

    print("📡 Publishing... (Ctrl+C to stop)\n")

    try:
        while True:
            for sim in simulators:
                payload = sim.next_payload()
                if payload is None:
                    print(f"  💤 {sim.vehicle_id}: outside service hours, skipping")
                    continue

                topic = f"cassitrack/{payload['vehicle_id']}/position"
                wire  = json.dumps(payload)
                client.publish(topic, wire, qos=1)

                if not args.raw_only:
                    # route / trip / stop are NOT transmitted any more — printed
                    # here only so the operator can see what the simulated bus is
                    # doing. Anything in [brackets] never leaves this process.
                    status = "\U0001F6D1 STOP" if sim._last_at_stop else "\U0001F68C      "
                    print(
                        f"  {status} {payload['vehicle_id']} | "
                        f"({payload['lat']:.5f}, {payload['lon']:.5f}) | "
                        f"{payload['speed_kmh']:5.1f} km/h | "
                        f"{payload['passengers']:3d}/{payload['capacity']} pax | "
                        f"ble: {payload['ble_device_count']:3d} | "
                        f"[sim: {sim.route_name} near {sim._last_nearest_stop}]"
                    )

                if args.raw:
                    # Exactly the bytes that go on the wire — nothing added,
                    # nothing derived. This is all CassiTrack receives.
                    print(f"     \u2197 TX {topic}")
                    print(f"        {wire}")

            print()
            time.sleep(args.interval)

    except KeyboardInterrupt:
        print("\n🛑 Simulator stopped.")
    finally:
        client.loop_stop()
        client.disconnect()
        conn.close()


if __name__ == "__main__":
    main()
