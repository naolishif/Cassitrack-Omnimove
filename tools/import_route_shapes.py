#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
import_route_shapes.py
======================
Imports the road geometry from a `percorsiX.json` file (produced by the
CassiTrack route editor, `crea_path.html`) into the `route_shapes` table.

WHY
---
A route in our schema is a sequence of stops. The maps therefore drew each
line as straight hops between stops, while a bus following the real streets
visibly wandered off it. `route_shapes` (see V9) stores the true polyline;
this script fills it.

WHAT IT DOES *NOT* DO
---------------------
It imports geometry only. It never creates or renames routes or stops: our
route ids (LINEA_1, LINEA_2, LINEA_2_LIC, LINEA_3) and stop ids (PSB, CRS, …)
stay exactly as they are. The JSON's own bus ids (BUS1, BUS2, …) are mapped
onto ours through ROUTE_ID_MAP below and are otherwise ignored.

By default the script writes a Flyway migration rather than touching the
database, so the import is reproducible on every environment (dev, server,
a teammate's laptop) instead of being a one-off manual load.

Usage
-----
    # 1. Generate the migration (default; writes V10__route_shapes_cassino.sql)
    python tools/import_route_shapes.py tools/percorsiCassino.json

    # 2. Or load straight into a running database
    python tools/import_route_shapes.py tools/percorsiCassino.json --db

    # Inspect the mapping without writing anything
    python tools/import_route_shapes.py tools/percorsiCassino.json --dry-run

Dependencies: none for the default mode. `--db` needs psycopg2-binary.
"""

import argparse
import json
import os
import sys
from datetime import date

# ─────────────────────────────────────────────────────────────────
#  Mapping: editor bus id  ->  our route id
#
#  Verified against V5__refresh_timetable_magni.sql: the colours and the stop
#  sequences match one-for-one (BUS2L is the "mezza corsa" from the Liceo,
#  i.e. LINEA_2_LIC). Edit here if the editor file ever gains new lines.
# ─────────────────────────────────────────────────────────────────
ROUTE_ID_MAP = {
    "BUS1":  "LINEA_1",
    "BUS2":  "LINEA_2",
    "BUS2L": "LINEA_2_LIC",
    "BUS3":  "LINEA_3",
}

# Our stops, from V5__refresh_timetable_magni.sql. Used only to sanity-check
# that the JSON's stop coordinates really are our stops before importing.
OUR_STOPS = {
    "PSB": (41.493833, 13.828778), "CRS": (41.49046,  13.83673),
    "VLE": (41.48911,  13.83955),  "VGA": (41.48592,  13.83505),
    "SFF": (41.48546,  13.83204),  "VBO": (41.48451,  13.82781),
    "VSA": (41.48221,  13.82569),  "UNI": (41.47583,  13.82894),
    "RET": (41.47179,  13.82752),  "RLD": (41.46939,  13.82897),
    "AUS": (41.4787,   13.82294),  "COL": (41.4816,   13.82408),
    "IMA": (41.4875,   13.839611), "EDN": (41.490639, 13.836639),
    "LIC": (41.467472, 13.829139), "GIA": (41.492,    13.831472),
    "ING": (41.487361, 13.825472), "OSR": (41.483083, 13.825111),
    "XXS": (41.494111, 13.83175),  "OSS": (41.505639, 13.842639),
}

COORD_TOLERANCE = 1e-5          # ~1 m — coordinates are copied, not re-surveyed

# Both backends keep their own database and their own copy of the geometry
# (OmniMove's is refreshed by the NeTEx import; this seeds it). The generated
# SQL is identical for both — the tables are deliberately the same shape.
MIGRATION_DIRS = {
    "cassitrack": os.path.join(
        "cassitrack-backend", "src", "main", "resources", "db", "migration"),
    "omnimove": os.path.join(
        "omnimove-backend", "src", "main", "resources", "db", "migration"),
}


# ─────────────────────────────────────────────────────────────────
#  Loading and validation
# ─────────────────────────────────────────────────────────────────
def match_stop(lat, lon):
    """Return our stop id sitting at these coordinates, or None."""
    for sid, (slat, slon) in OUR_STOPS.items():
        if abs(slat - lat) < COORD_TOLERANCE and abs(slon - lon) < COORD_TOLERANCE:
            return sid
    return None


def load(path):
    """Read the editor JSON and map it onto our route ids.

    Returns a list of (route_id, source_id, points, stop_ids) and prints a
    report. Unmapped buses are skipped loudly rather than guessed at.
    """
    with open(path, encoding="utf-8") as f:
        data = json.load(f)

    routes, problems = [], []

    for bus in data.get("buses", []):
        src = bus.get("id")
        route_id = ROUTE_ID_MAP.get(src)
        if not route_id:
            problems.append(
                f"  ! '{src}' has no entry in ROUTE_ID_MAP — skipped. "
                f"Add one if this line should be imported.")
            continue

        points = bus.get("points") or []
        if len(points) < 2:
            problems.append(f"  ! '{src}' has fewer than 2 points — skipped.")
            continue

        # Which of our stops do this line's stops correspond to?
        stop_ids, unknown = [], 0
        for s in bus.get("stops", []):
            sid = match_stop(s["lat"], s["lon"])
            if sid:
                stop_ids.append(sid)
            else:
                unknown += 1
        if unknown:
            problems.append(
                f"  ! '{src}' -> {route_id}: {unknown} stop(s) do not match any "
                f"stop in our database. Geometry still imports, but check the file.")

        routes.append((route_id, src, points, stop_ids))

    print(f"Read {path}")
    print(f"  file: name={data.get('name')!r} city={data.get('city')!r}\n")
    print(f"  {'source':8} {'-> our route id':16} {'points':>7} {'stops':>6}   stop ids")
    for route_id, src, points, stop_ids in routes:
        n_stop_pts = sum(1 for p in points if p.get("stop"))
        print(f"  {src:8} -> {route_id:16} {len(points):>7} {n_stop_pts:>6}   "
              f"{','.join(stop_ids)}")

    if problems:
        print("\nWarnings:")
        print("\n".join(problems))

    return routes


# ─────────────────────────────────────────────────────────────────
#  Output: Flyway migration
# ─────────────────────────────────────────────────────────────────
def next_version(migration_dir):
    """Lowest unused V<n>__ number in the migration folder."""
    used = set()
    if os.path.isdir(migration_dir):
        for name in os.listdir(migration_dir):
            if name.startswith("V") and "__" in name:
                head = name[1:name.index("__")]
                if head.isdigit():
                    used.add(int(head))
    return max(used) + 1 if used else 1


def to_sql(routes, source_name):
    """Render the shape rows as an idempotent Flyway migration."""
    out = [
        "-- ────────────────────────────────────────────────────────────────",
        "-- CASSITRACK",
        "-- Road geometry for the Cassino lines (route_shapes data).",
        "--",
        f"-- GENERATED by tools/import_route_shapes.py from '{source_name}'",
        f"-- on {date.today().isoformat()}. Do not hand-edit: reshape the path in",
        "-- the route editor (crea_path.html), re-export the JSON and re-run the",
        "-- importer to produce a new migration.",
        "--",
        "-- Geometry only. Route ids, stop ids and every timetable row are",
        "-- untouched; scheduled_stops remains the source of truth for adherence.",
        "-- ────────────────────────────────────────────────────────────────",
        "",
    ]

    for route_id, src, points, _ in routes:
        out.append(f"-- ── {route_id}  (editor id: {src}, {len(points)} points) ──")
        # Re-runnable: drop this route's old path before inserting the new one.
        out.append(f"DELETE FROM route_shapes WHERE route_id = '{route_id}';")
        out.append("INSERT INTO route_shapes (route_id, seq, lat, lon, is_stop) VALUES")
        rows = [
            f"    ('{route_id}', {i}, {p['lat']:.6f}, {p['lon']:.6f}, "
            f"{'TRUE' if p.get('stop') else 'FALSE'})"
            for i, p in enumerate(points)
        ]
        out.append(",\n".join(rows) + ";")
        out.append("")

    return "\n".join(out)


# ─────────────────────────────────────────────────────────────────
#  Output: direct database load
# ─────────────────────────────────────────────────────────────────
def to_db(routes):
    try:
        import psycopg2
    except ImportError:
        sys.exit("--db needs psycopg2:  pip install psycopg2-binary")

    cfg = dict(
        host=os.environ.get("SPRING_DATASOURCE_HOST", "localhost"),
        port=int(os.environ.get("SPRING_DATASOURCE_PORT", "5433")),
        dbname=os.environ.get("SPRING_DATASOURCE_DB", "cassitrack"),
        user=os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
        password=os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
    )
    conn = psycopg2.connect(**cfg)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            for route_id, _src, points, _ in routes:
                cur.execute("SELECT 1 FROM routes WHERE id = %s", (route_id,))
                if cur.fetchone() is None:
                    raise SystemExit(
                        f"Route '{route_id}' does not exist in the database. "
                        f"Run the Flyway migrations first.")
                cur.execute("DELETE FROM route_shapes WHERE route_id = %s", (route_id,))
                cur.executemany(
                    "INSERT INTO route_shapes (route_id, seq, lat, lon, is_stop) "
                    "VALUES (%s, %s, %s, %s, %s)",
                    [(route_id, i, p["lat"], p["lon"], bool(p.get("stop")))
                     for i, p in enumerate(points)],
                )
                print(f"  {route_id}: {len(points)} points written")
        conn.commit()
        print("\nCommitted.")
    except Exception:
        conn.rollback()
        print("\nRolled back — nothing was written.")
        raise
    finally:
        conn.close()


# ─────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(
        description="Import route geometry from a CassiTrack editor JSON into route_shapes.")
    ap.add_argument("json_file", help="e.g. tools/percorsiCassino.json")
    ap.add_argument("--db", action="store_true",
                    help="write straight to PostgreSQL instead of generating a migration")
    ap.add_argument("--dry-run", action="store_true",
                    help="only show the mapping; write nothing")
    ap.add_argument("--target", choices=["cassitrack", "omnimove", "both"],
                    default="both",
                    help="which backend(s) to generate a migration for (default: both)")
    ap.add_argument("--out", help="explicit path of the migration file "
                                  "(implies a single target)")
    args = ap.parse_args()

    routes = load(args.json_file)
    if not routes:
        sys.exit("\nNothing to import.")

    total = sum(len(p) for _, _, p, _ in routes)
    print(f"\n{len(routes)} route(s), {total} points total.")

    if args.dry_run:
        print("\n--dry-run: nothing written.")
        return

    if args.db:
        print()
        to_db(routes)
        return

    sql = to_sql(routes, os.path.basename(args.json_file))

    if args.out:
        targets = [args.out]
    else:
        names = (["cassitrack", "omnimove"] if args.target == "both"
                 else [args.target])
        # Each backend numbers its migrations independently, so the version is
        # resolved per directory rather than shared.
        targets = [
            os.path.join(MIGRATION_DIRS[n],
                         f"V{next_version(MIGRATION_DIRS[n])}__route_shapes_cassino.sql")
            for n in names
        ]

    print()
    for out in targets:
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            f.write(sql)
        print(f"Wrote {out}")
    print("\nApply by restarting the backend(s) — Flyway runs on startup.")


if __name__ == "__main__":
    main()
