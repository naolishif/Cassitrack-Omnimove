#!/usr/bin/env python3
"""
CASSITRACK — Road geometry from a routing engine (route_shapes)

WHY THIS EXISTS
---------------
A route drawn stop-to-stop is a straight line: the bus visibly leaves it on
every bend. tools/crea_path.html solves that by hand — you click every vertex,
143 of them for LINEA 1. This script does the same job automatically: it asks a
routing engine for the real road geometry between consecutive stops and writes
the resulting migration.

Same output as import_route_shapes.py (a V*__*.sql inserting route_shapes), so
nothing else in the project needs to change.

HOW IT WORKS
------------
  1. reads the lines and their stop sequences from PostgreSQL
     (the representative run of each route, exactly like the backend does)
  2. asks OSRM for the driving path through those stops
  3. writes route_shapes: the road vertices, with is_stop=TRUE on the ones
     that are scheduled stops

THE ONE SUBTLETY THAT MATTERS
-----------------------------
A stop vertex is written with the stop's coordinates FROM THE DATABASE, never
with the point OSRM snapped to the road. simulate_bus_scheduled.py binds each
scheduled stop to a shape vertex by comparing coordinates within ~1 m
(COORD_TOL) and DROPS THE WHOLE TRIP when it finds none — a stop nudged even a
few metres onto the carriageway would silently stop that bus from running.

USAGE
-----
    pip install psycopg2-binary requests
    python tools/build_route_shapes_osrm.py                  # all routes
    python tools/build_route_shapes_osrm.py --routes LINEA_16,LINEA_17
    python tools/build_route_shapes_osrm.py --dry-run        # just report

Database settings are read from cassitrack-backend/.env, like the simulator.
The public OSRM demo server is used by default: it is rate-limited and meant
for light use, which is why requests are spaced out. Point --osrm at your own
instance for heavy runs.
"""

import argparse
import math
import os
import sys
import time
from datetime import datetime

try:
    import psycopg2
    import psycopg2.extras
except ImportError:
    sys.exit("Manca psycopg2. Installa con:  pip install psycopg2-binary")

try:
    import requests
except ImportError:
    sys.exit("Manca requests. Installa con:  pip install requests")


# ── configuration ────────────────────────────────────────────────────────

def load_env(path):
    """Minimal .env reader (same approach as gps_simulator2.py)."""
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            k = k.strip()
            v = v.split("#", 1)[0].strip()
            if k and k not in os.environ:
                os.environ[k] = v


HERE = os.path.dirname(os.path.abspath(__file__))
load_env(os.path.join(HERE, "..", "cassitrack-backend", ".env"))

DB = {
    "host":     os.environ.get("SPRING_DATASOURCE_HOST", "localhost"),
    "port":     int(os.environ.get("SPRING_DATASOURCE_PORT", "5433")),
    "dbname":   os.environ.get("SPRING_DATASOURCE_DB", "cassitrack"),
    "user":     os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
    "password": os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
}

OSRM_DEFAULT = "https://router.project-osrm.org"
REQUEST_PAUSE = 1.2      # seconds between calls — the demo server is shared
MAX_WAYPOINTS = 25       # OSRM rejects very long waypoint lists; split beyond this


# ── geometry helpers ─────────────────────────────────────────────────────

def metres(a_lat, a_lon, b_lat, b_lon):
    R = 6371000.0
    f = math.radians
    dlat, dlon = f(b_lat - a_lat), f(b_lon - a_lon)
    s = (math.sin(dlat / 2) ** 2
         + math.cos(f(a_lat)) * math.cos(f(b_lat)) * math.sin(dlon / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(s), math.sqrt(1 - s))


def simplify(points, tol_m=8.0):
    """
    Drop vertices that add nothing to the shape (Douglas-Peucker, iterative).

    A city route comes back from OSRM with hundreds of points, many of them
    millimetres apart on a straight stretch. Thinning them keeps the drawing
    identical while making the NeTEx document and every map load much lighter.
    Endpoints are always kept.
    """
    if len(points) < 3:
        return points[:]

    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]

    while stack:
        start, end = stack.pop()
        if end <= start + 1:
            continue
        # Work in local metres, not in degrees: at this latitude one degree of
        # longitude is ~83 km against ~111 km for latitude, so projecting on raw
        # lat/lon would skew which vertex is really the farthest from the chord.
        lat0 = points[start][0]
        kx = math.cos(math.radians(lat0)) * 111320.0   # metres per degree of lon
        ky = 111320.0                                   # metres per degree of lat

        def xy(p):
            return ((p[1] - points[start][1]) * kx,
                    (p[0] - points[start][0]) * ky)

        ax, ay = 0.0, 0.0
        bx, by = xy(points[end])
        worst, worst_i = 0.0, -1
        for i in range(start + 1, end):
            px, py = xy(points[i])
            dx, dy = bx - ax, by - ay
            if dx == 0 and dy == 0:
                d = math.hypot(px - ax, py - ay)
            else:
                t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
                t = max(0.0, min(1.0, t))
                d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
            if d > worst:
                worst, worst_i = d, i
        if worst > tol_m:
            keep[worst_i] = True
            stack.append((start, worst_i))
            stack.append((worst_i, end))

    return [p for p, k in zip(points, keep) if k]


# ── database ─────────────────────────────────────────────────────────────

def fetch_routes(conn, only=None):
    """
    Stop sequence of every active route, taken from its representative run —
    the same rule the backend uses (findRepresentativeSequence: lowest trip id).
    """
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute("SELECT id, short_name, long_name FROM routes WHERE active = TRUE ORDER BY id")
    routes = cur.fetchall()

    out = []
    for r in routes:
        if only and r["id"] not in only:
            continue
        cur.execute("""
            SELECT ss.stop_id, ss.stop_sequence, s.name, s.lat, s.lon
            FROM scheduled_stops ss
            JOIN stops s ON s.id = ss.stop_id
            WHERE ss.trip_id = (SELECT MIN(t.id) FROM trips t WHERE t.route_id = %s)
            ORDER BY ss.stop_sequence
        """, (r["id"],))
        stops = cur.fetchall()
        if len(stops) >= 2:
            out.append({"id": r["id"],
                        "name": r["short_name"] or r["id"],
                        "stops": stops})
        else:
            print(f"  · {r['id']}: nessuna corsa da cui leggere le fermate — saltata")
    return out


# ── routing ──────────────────────────────────────────────────────────────

def osrm_leg(base, a, b):
    """
    Road geometry between two stops, as [(lat, lon), ...].
    Falls back to the straight segment if the engine cannot route it.
    """
    url = (f"{base}/route/v1/driving/"
           f"{a['lon']},{a['lat']};{b['lon']},{b['lat']}"
           f"?overview=full&geometries=geojson")
    try:
        resp = requests.get(url, timeout=25)
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") != "Ok" or not data.get("routes"):
            return None
        coords = data["routes"][0]["geometry"]["coordinates"]   # [lon, lat]
        return [(c[1], c[0]) for c in coords]
    except Exception as exc:
        print(f"      ! routing fallito ({exc.__class__.__name__}): tratto dritto")
        return None


def build_shape(base, stops, tol_m, pause):
    """
    Full geometry of one route: every leg routed on the roads, with the stop
    coordinates preserved EXACTLY as they are in the database (see module docstring).

    Returns [(lat, lon, is_stop), ...] and a per-leg report.
    """
    shape = []
    report = []

    for i, (a, b) in enumerate(zip(stops, stops[1:])):
        leg = osrm_leg(base, a, b)
        straight = metres(a["lat"], a["lon"], b["lat"], b["lon"])

        if leg is None:
            leg = [(a["lat"], a["lon"]), (b["lat"], b["lon"])]
            routed = straight
            ok = False
        else:
            routed = sum(metres(*leg[j], *leg[j + 1]) for j in range(len(leg) - 1))
            ok = True
            leg = simplify(leg, tol_m)

        # first stop of the route
        if i == 0:
            shape.append((a["lat"], a["lon"], True))

        # intermediate road vertices: drop the ones that coincide with the
        # stops themselves, those are added with the database coordinates
        for lat, lon in leg[1:-1]:
            if metres(lat, lon, a["lat"], a["lon"]) < 2:  continue
            if metres(lat, lon, b["lat"], b["lon"]) < 2:  continue
            shape.append((lat, lon, False))

        shape.append((b["lat"], b["lon"], True))

        report.append({"from": a["name"], "to": b["name"], "ok": ok,
                       "straight_m": straight, "routed_m": routed,
                       "vertices": len(leg)})
        time.sleep(pause)

    return shape, report


# ── SQL emission ─────────────────────────────────────────────────────────

def emit_sql(shapes, version, out_path, osrm_base):
    lines = []
    w = lines.append
    stamp = datetime.now().strftime("%Y-%m-%d %H:%M")

    w("-- " + "─" * 64)
    w("-- CASSITRACK")
    w(f"-- V{version}__route_shapes_routed.sql")
    w("--")
    w("-- Road geometry of the lines, following the actual streets.")
    w(f"-- GENERATED by tools/build_route_shapes_osrm.py on {stamp}")
    w(f"-- Routing engine: {osrm_base}")
    w("--")
    w("-- Do not hand-edit: re-run the script, or reshape the path in the")
    w("-- route editor (Data Management > Routes).")
    w("--")
    w("-- Stop vertices carry the coordinates held in `stops`, NOT the point")
    w("-- the router snapped to the carriageway: simulate_bus_scheduled.py")
    w("-- binds each scheduled call to a vertex within ~1 m and would drop the")
    w("-- whole trip if they drifted apart.")
    w("--")
    w("-- Geometry only. Routes, stops and every timetable row are untouched.")
    w("-- " + "─" * 64)
    w("")

    for rid, shape in shapes:
        w(f"-- ── {rid}: {len(shape)} punti, {sum(1 for p in shape if p[2])} fermate ──")
        w(f"DELETE FROM route_shapes WHERE route_id = '{rid}';")
        w("INSERT INTO route_shapes (route_id, seq, lat, lon, is_stop) VALUES")
        rows = []
        for seq, (lat, lon, is_stop) in enumerate(shape):
            rows.append(f"    ('{rid}',{seq},{lat:.6f},{lon:.6f},{'TRUE' if is_stop else 'FALSE'})")
        w(",\n".join(rows) + ";")
        w("")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# ── main ─────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="Genera route_shapes seguendo le strade reali.")
    ap.add_argument("--routes", help="solo queste linee, separate da virgola (es. LINEA_16,LINEA_17)")
    ap.add_argument("--osrm", default=OSRM_DEFAULT, help=f"server di routing (default: {OSRM_DEFAULT})")
    ap.add_argument("--version", default="18", help="numero della migrazione da generare (default: 18)")
    ap.add_argument("--tolerance", type=float, default=8.0,
                    help="semplificazione in metri: piu' alto = meno punti (default: 8)")
    ap.add_argument("--pause", type=float, default=REQUEST_PAUSE,
                    help=f"pausa fra le richieste (default: {REQUEST_PAUSE}s)")
    ap.add_argument("--dry-run", action="store_true", help="mostra il risultato senza scrivere l'SQL")
    args = ap.parse_args()

    only = set(x.strip() for x in args.routes.split(",")) if args.routes else None

    print("Connessione a PostgreSQL...")
    try:
        conn = psycopg2.connect(**DB)
    except Exception as exc:
        sys.exit(f"Connessione fallita: {exc}\nControlla cassitrack-backend/.env")
    print(f"  ok  {DB['user']}@{DB['host']}:{DB['port']}/{DB['dbname']}\n")

    routes = fetch_routes(conn, only)
    if not routes:
        sys.exit("Nessuna linea da elaborare.")
    print(f"Linee da elaborare: {len(routes)}\n")

    shapes = []
    total_legs = failed_legs = 0

    for r in routes:
        print(f"► {r['id']} ({r['name']}) — {len(r['stops'])} fermate")
        shape, report = build_shape(args.osrm, r["stops"], args.tolerance, args.pause)
        for leg in report:
            total_legs += 1
            if not leg["ok"]:
                failed_legs += 1
            ratio = leg["routed_m"] / leg["straight_m"] if leg["straight_m"] > 1 else 1.0
            flag = "  " if leg["ok"] else " !"
            print(f"   {flag} {leg['from'][:22]:22} → {leg['to'][:22]:22} "
                  f"{leg['routed_m']:7.0f} m  (x{ratio:.2f} sulla retta)  {leg['vertices']:4} punti")
        print(f"   = {len(shape)} vertici totali\n")
        shapes.append((r["id"], shape))

    conn.close()

    print("─" * 70)
    print(f"Tratti instradati: {total_legs - failed_legs}/{total_legs}"
          + (f"   ({failed_legs} in linea retta)" if failed_legs else ""))

    if args.dry_run:
        print("\n--dry-run: nessun file scritto.")
        return

    out = os.path.join(HERE, "..", "cassitrack-backend", "src", "main", "resources",
                       "db", "migration", f"V{args.version}__route_shapes_routed.sql")
    out = os.path.normpath(out)
    emit_sql(shapes, args.version, out, args.osrm)
    print(f"\nScritto: {out}")
    print("Riavvia il backend: Flyway applichera' la migrazione.")


if __name__ == "__main__":
    main()
