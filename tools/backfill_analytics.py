#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
backfill_analytics.py — Storico fittizio per la tab Analytics
==============================================================

PERCHE' SCRIVE SU INFLUX E NON SU POSTGRES
Tutti i pannelli della tab Analytics leggono una sola sorgente: la misurazione
`vehicle_position` in InfluxDB. AnalyticsService non interroga Postgres per i
grafici — ci va solo per i nomi delle linee. Quindi lo storico si crea li', con
gli stessi tag e gli stessi campi che scriverebbe MqttMessageHandler.

COSA GENERA
Traffico coerente con l'orario vero: legge trips e scheduled_stops, e produce
punti solo per i mezzi che sarebbero stati davvero in servizio, sulla linea
giusta e nella fascia oraria giusta. Cosi' i grafici per linea e per ora
reggono il confronto con la tab Trips invece di raccontare un'altra rete.

Un campione al minuto per mezzo in servizio. I pannelli aggregano per ora,
quindi campionare piu' fitto allunga la scrittura senza cambiare un pixel.

I GIORNI NON SONO TUTTI UGUALI
Feriali pieni, sabato ridotto, domenica minima, e una giornata storta con
ritardi diffusi. Serve a far vedere qualcosa: con sette giorni identici i
grafici sono piatti e le soglie di colore non si accendono mai.

Nota onesta: la tabella `trips` non ha un calendario di servizio — dopo V24 le
corse valgono per ogni giorno, e il NeTEx esporta un unico DayType "Everyday".
La riduzione del fine settimana e' quindi una FINZIONE di questo script, decisa
sottocampionando le corse in modo deterministico. Se un giorno arrivera' un
calendario vero, questa parte va buttata, non adattata.

USO
    pip install psycopg2-binary
    python backfill_analytics.py --dry-run     # genera e conta, non scrive
    python backfill_analytics.py               # dal 1 settembre a stanotte

    python backfill_analytics.py --from 2026-09-01 --to 2026-09-07
    python backfill_analytics.py --step 300    # un campione ogni 5 minuti

Per default si ferma a MEZZANOTTE DI OGGI: la giornata corrente e' quella in cui
gira il simulatore vero, e sovrapporre le due sorgenti renderebbe illeggibile
qualunque verifica.

PER DISFARE
    curl -XPOST "$INFLUX_URL/api/v2/delete?org=unicas&bucket=vehicle_telemetry" \
      -H "Authorization: Token $INFLUX_TOKEN" -H 'Content-Type: application/json' \
      -d '{"start":"2026-09-01T00:00:00Z","stop":"2026-09-07T00:00:00Z",
           "predicate":"_measurement=\"vehicle_position\""}'
"""

import argparse
import hashlib
import json
import math
import os
import random
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import psycopg2
import psycopg2.extras


# ─────────────────────────────────────────────────────────────────
#  Configurazione: le stesse variabili degli altri strumenti
# ─────────────────────────────────────────────────────────────────

def _load_env_file(path):
    """Lettore .env minimale; non sovrascrive mai l'ambiente reale."""
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


def db_config():
    """
    Parametri Postgres, preferendo SPRING_DATASOURCE_URL.

    Il .env definisce la URL JDBC intera e non host/porta separati: leggere solo
    le variabili singole funzionerebbe per caso, grazie ai default.
    """
    url = os.environ.get("SPRING_DATASOURCE_URL", "")
    host, port, dbname = "localhost", 5433, "cassitrack"
    if url.startswith("jdbc:postgresql://"):
        rest = url[len("jdbc:postgresql://"):]
        hostport, _, db = rest.partition("/")
        h, _, p = hostport.partition(":")
        host = h or host
        port = int(p) if p.isdigit() else port
        dbname = db.split("?")[0] or dbname
    return {
        "host":     os.environ.get("SPRING_DATASOURCE_HOST", host),
        "port":     int(os.environ.get("SPRING_DATASOURCE_PORT", port)),
        "dbname":   os.environ.get("SPRING_DATASOURCE_DB", dbname),
        "user":     os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
        "password": os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
    }


INFLUX_URL    = os.environ.get("INFLUX_URL", "http://localhost:8086").rstrip("/")
INFLUX_TOKEN  = os.environ.get("INFLUX_TOKEN", "")
INFLUX_ORG    = os.environ.get("INFLUX_ORG", "unicas")
INFLUX_BUCKET = os.environ.get("INFLUX_BUCKET", "vehicle_telemetry")

ROME = ZoneInfo("Europe/Rome")


# ─────────────────────────────────────────────────────────────────
#  Il carattere delle giornate
# ─────────────────────────────────────────────────────────────────

# Domanda relativa per fascia oraria, in scala arbitraria. Stessa curva del
# simulatore in tempo reale, cosi' lo storico e il presente si somigliano.
DEMAND_BY_HOUR = {
    5: .10, 6: .35, 7: .90, 8: 1.00, 9: .55, 10: .40, 11: .45,
    12: .85, 13: .95, 14: .60, 15: .45, 16: .55, 17: .95, 18: 1.00,
    19: .60, 20: .30, 21: .18, 22: .08,
}

RUSH_HOURS = ((7, 9), (12, 14), (17, 19))

# Quota di corse effettivamente esercitate, per giorno della settimana.
# 0 = lunedi. Il sabato dimezza, la domenica taglia molto: e' il profilo tipico
# di una rete urbana di provincia.
SERVICE_FRACTION = {0: 1.00, 1: 1.00, 2: 1.00, 3: 1.00, 4: 1.00,
                    5: 0.55, 6: 0.30}

# Quanto e' carico il servizio rispetto a un feriale medio.
DEMAND_FACTOR = {0: 1.00, 1: 1.00, 2: 1.00, 3: 1.00, 4: 1.05,
                 5: 0.60, 6: 0.35}


def day_profile(d, bad_day):
    """
    Come si comporta la rete in questa data.

    `bad_day` moltiplica i ritardi: e' la giornata storta chiesta a monte, non
    un guasto del modello. Un pomeriggio di pioggia o un cantiere producono
    esattamente questo — tutte le linee in ritardo insieme, non una sola.
    """
    dow = d.weekday()
    delay_mult = 2.6 if d == bad_day else 1.0
    return {
        "service": SERVICE_FRACTION[dow],
        "demand":  DEMAND_FACTOR[dow],
        "delay":   delay_mult,
        "label":   ("guasta" if d == bad_day
                    else ("domenica" if dow == 6 else
                          "sabato" if dow == 5 else "feriale")),
    }


def runs_today(trip_id, d, service_fraction):
    """
    Se questa corsa e' esercitata in questa data.

    Deterministico: la stessa corsa nello stesso giorno decide sempre allo
    stesso modo, quindi rilanciare lo script su un intervallo gia' generato
    produce gli stessi punti invece di un secondo servizio parallelo.
    """
    if service_fraction >= 1.0:
        return True
    h = hashlib.md5(f"{trip_id}|{d.isoformat()}".encode()).hexdigest()
    return (int(h[:8], 16) % 1000) < service_fraction * 1000


def in_rush(hour):
    return any(a <= hour < b for a, b in RUSH_HOURS)


# ─────────────────────────────────────────────────────────────────
#  Lettura della rete
# ─────────────────────────────────────────────────────────────────

def load_network(conn):
    """Fermate, mezzi e corse con i loro orari. Stessa forma del simulatore v3."""
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    cur.execute("SELECT id, name, lat, lon FROM stops")
    stops = {r["id"]: dict(r) for r in cur.fetchall()}

    cur.execute("""
        SELECT bus_id, current_vehicle_id, numero_posti, wheelchair_accessible
        FROM buses
        WHERE current_vehicle_id IS NOT NULL
        ORDER BY bus_id
    """)
    buses = {r["bus_id"]: dict(r) for r in cur.fetchall()}

    # Da V24/V27: la fermata sta nel pattern della linea, l'orario nella corsa.
    cur.execute("""
        SELECT t.id AS trip_id, t.bus_id, t.route_id,
               ss.stop_sequence, ss.arrival_seconds, rs.stop_id
        FROM trips t
        JOIN scheduled_stops ss ON ss.trip_id = t.id
        JOIN route_stops rs     ON rs.route_id = t.route_id
                               AND rs.stop_sequence = ss.stop_sequence
        ORDER BY t.id, ss.stop_sequence
    """)
    calls, meta = {}, {}
    for r in cur.fetchall():
        calls.setdefault(r["trip_id"], []).append(
            (r["arrival_seconds"], r["stop_id"]))
        meta[r["trip_id"]] = (r["bus_id"], r["route_id"])
    cur.close()

    # Una corsa con una sola fermata non descrive un percorso: non c'e' nulla
    # da interpolare fra due punti che coincidono.
    calls = {k: v for k, v in calls.items() if len(v) >= 2}
    return stops, buses, calls, meta


# ─────────────────────────────────────────────────────────────────
#  Il modello
# ─────────────────────────────────────────────────────────────────

def position_at(calls, stops, sched_sec):
    """
    Dove si trova il mezzo all'orario di tabella `sched_sec`.

    Interpolazione lineare fra la fermata precedente e la successiva. Non segue
    la geometria stradale come fa gps_simulator3, e va bene: le Analytics non
    disegnano il percorso storico, contano i punti e mediano i campi. Le
    coordinate servono solo perche' il campo `lat` e' quello che il pannello
    "busiest hours" conta.
    """
    prev = calls[0]
    for cur in calls[1:]:
        if sched_sec <= cur[0]:
            a, b = stops.get(prev[1]), stops.get(cur[1])
            if not a or not b or a["lat"] is None or b["lat"] is None:
                return None
            span = max(1, cur[0] - prev[0])
            f = min(1.0, max(0.0, (sched_sec - prev[0]) / span))
            return (a["lat"] + (b["lat"] - a["lat"]) * f,
                    a["lon"] + (b["lon"] - a["lon"]) * f,
                    span, f)
        prev = cur
    last = stops.get(calls[-1][1])
    return (last["lat"], last["lon"], 1, 1.0) if last and last["lat"] else None


def trip_delay_curve(rng, profile):
    """
    Il ritardo di una corsa: quanto vale a fine corsa e come ci arriva.

    Estratto una volta per corsa, non a ogni campione: il ritardo e' una
    proprieta' della corsa che si accumula, non rumore indipendente. Campionarlo
    a ogni minuto darebbe un mezzo che oscilla fra puntuale e in ritardo dieci
    volte fra due fermate, che non somiglia a niente di reale.

    La distribuzione punta a una rete che funziona: circa un decimo in anticipo,
    due terzi in orario, il resto in ritardo con una coda corta. La giornata
    storta sposta tutto verso destra tramite profile["delay"].
    """
    u = rng.random()
    if u < 0.10:
        final = -rng.uniform(0.5, 2.5)          # in anticipo
    elif u < 0.72:
        final = rng.uniform(-0.5, 2.0)          # in orario
    elif u < 0.92:
        final = rng.uniform(2.0, 5.0)           # ritardo lieve
    else:
        final = rng.uniform(5.0, 14.0)          # ritardo vero
    final *= profile["delay"]
    # Come ci arriva: parte quasi puntuale e accumula lungo il percorso.
    return final, rng.uniform(0.15, 0.45)


def passengers_at(rng, hour, capacity, route_factor, demand_factor):
    """Passeggeri a bordo: domanda oraria, popolarita' della linea, rumore."""
    base = DEMAND_BY_HOUR.get(hour, 0.05) * route_factor * demand_factor
    val = capacity * base * rng.uniform(0.55, 1.05)
    return max(0, min(capacity, int(round(val))))


def haversine_m(a_lat, a_lon, b_lat, b_lon):
    R = 6_371_000
    dlat = math.radians(b_lat - a_lat)
    dlon = math.radians(b_lon - a_lon)
    s = (math.sin(dlat / 2) ** 2
         + math.cos(math.radians(a_lat)) * math.cos(math.radians(b_lat))
         * math.sin(dlon / 2) ** 2)
    return R * 2 * math.atan2(math.sqrt(s), math.sqrt(1 - s))


def bearing_deg(a_lat, a_lon, b_lat, b_lon):
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dl = math.radians(b_lon - a_lon)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


# ─────────────────────────────────────────────────────────────────
#  Protocollo di riga
# ─────────────────────────────────────────────────────────────────

def esc_tag(s):
    """Nei tag, virgole spazi e uguali vanno protetti."""
    return (str(s).replace("\\", "\\\\").replace(",", "\\,")
            .replace(" ", "\\ ").replace("=", "\\="))


def line_protocol(tags, fields, epoch_s):
    """
    Una riga nel formato di InfluxDB.

    I TIPI DEVONO COMBACIARE con quelli scritti da MqttMessageHandler: la stessa
    misurazione non ammette un campo che a volte e' intero e a volte decimale, e
    il punto in conflitto viene rifiutato. passengers, capacity e delay nascono
    da Integer in Java, quindi qui vogliono il suffisso "i"; lat, lon, speed_kmh
    e heading_deg nascono da Double e restano decimali.
    """
    t = ",".join(f"{k}={esc_tag(v)}" for k, v in tags.items())
    parts = []
    for k, v in fields.items():
        if isinstance(v, bool):
            parts.append(f"{k}={'true' if v else 'false'}")
        elif isinstance(v, int):
            parts.append(f"{k}={v}i")
        else:
            parts.append(f"{k}={v:.6f}")
    return f"vehicle_position,{t} {','.join(parts)} {epoch_s}"


def write_batch(lines, dry_run):
    if dry_run or not lines:
        return
    url = (f"{INFLUX_URL}/api/v2/write?org={INFLUX_ORG}"
           f"&bucket={INFLUX_BUCKET}&precision=s")
    req = urllib.request.Request(
        url, data="\n".join(lines).encode("utf-8"), method="POST",
        headers={"Authorization": f"Token {INFLUX_TOKEN}",
                 "Content-Type": "text/plain; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            if r.status not in (200, 204):
                raise RuntimeError(f"HTTP {r.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:400]
        raise SystemExit(f"\n❌ InfluxDB ha rifiutato la scrittura: {e.code}\n   {body}")


# ─────────────────────────────────────────────────────────────────
#  Generazione
# ─────────────────────────────────────────────────────────────────

def generate(stops, buses, calls, meta, day, profile, step, emit):
    """Produce i punti di una giornata, passandoli a `emit` una riga per volta."""
    midnight = datetime(day.year, day.month, day.day, tzinfo=ROME)
    made = 0

    for trip_id, trip_calls in calls.items():
        if not runs_today(trip_id, day, profile["service"]):
            continue
        bus_id, route_id = meta[trip_id]
        bus = buses.get(bus_id)
        if bus is None:
            continue                     # corsa assegnata a un mezzo non censito

        # Seme per corsa e per giorno: stesso input, stesso output.
        rng = random.Random(f"{trip_id}|{day.isoformat()}")
        final_delay, ramp = trip_delay_curve(rng, profile)
        route_factor = 0.55 + (int(hashlib.md5(route_id.encode()).hexdigest()[:4], 16) % 90) / 100.0

        start, end = trip_calls[0][0], trip_calls[-1][0]
        capacity = bus["numero_posti"] or 50
        wheelchair = bool(bus["wheelchair_accessible"])
        vehicle_id = bus["current_vehicle_id"]

        prev_pos = None
        prev_wall = None
        sched = start
        while sched <= end:
            pos = position_at(trip_calls, stops, sched)
            if pos is None:
                break
            lat, lon, span, f_seg = pos

            # Il ritardo cresce lungo la corsa: quasi nullo alla partenza, pieno
            # all'arrivo. `ramp` decide quanto presto si manifesta.
            f_trip = (sched - start) / max(1, end - start)
            delay_min = final_delay * (ramp + (1 - ramp) * f_trip)

            wall = midnight + timedelta(seconds=sched + delay_min * 60)
            hour = wall.hour

            if in_rush(hour):
                delay_min *= 1.25

            pax = passengers_at(rng, hour, capacity, route_factor, profile["demand"])

            if prev_pos is not None:
                dt = max(1.0, (wall - prev_wall).total_seconds())
                metres = haversine_m(prev_pos[0], prev_pos[1], lat, lon)
                speed = min(60.0, metres / dt * 3.6)
                head = bearing_deg(prev_pos[0], prev_pos[1], lat, lon) if metres > 1 else 0.0
            else:
                speed, head = 0.0, 0.0

            emit(line_protocol(
                {"vehicle_id": vehicle_id,
                 "bus_id": str(bus_id),
                 "trip_id": trip_id,
                 "route_id": route_id},
                {"lat": float(lat), "lon": float(lon),
                 "speed_kmh": float(speed), "heading_deg": float(head),
                 "wheelchair_accessible": wheelchair,
                 "passengers": int(pax), "capacity": int(capacity),
                 "delay": int(round(delay_min))},
                int(wall.astimezone(timezone.utc).timestamp())))
            made += 1

            prev_pos, prev_wall = (lat, lon), wall
            sched += step

    return made


def main():
    today = datetime.now(ROME).date()
    ap = argparse.ArgumentParser(
        description="Storico fittizio di vehicle_position per la tab Analytics")
    ap.add_argument("--from", dest="d_from", default="2026-09-01",
                    help="primo giorno incluso (default: 2026-09-01)")
    ap.add_argument("--to", dest="d_to", default=today.isoformat(),
                    help="primo giorno ESCLUSO (default: oggi, per non "
                         "sovrapporsi al simulatore in tempo reale)")
    ap.add_argument("--step", type=int, default=60,
                    help="secondi fra un campione e l'altro (default: 60)")
    ap.add_argument("--bad-day", default=None,
                    help="giornata con ritardi diffusi (default: il terzo "
                         "giorno feriale dell'intervallo)")
    ap.add_argument("--dry-run", action="store_true",
                    help="genera e conta senza scrivere su InfluxDB")
    ap.add_argument("--batch", type=int, default=5000)
    args = ap.parse_args()

    d_from = date.fromisoformat(args.d_from)
    d_to   = date.fromisoformat(args.d_to)
    if d_from >= d_to:
        sys.exit("L'intervallo e' vuoto: --from deve precedere --to.")

    days = [d_from + timedelta(days=i) for i in range((d_to - d_from).days)]
    if args.bad_day:
        bad = date.fromisoformat(args.bad_day)
    else:
        feriali = [d for d in days if d.weekday() < 5]
        bad = feriali[2] if len(feriali) > 2 else (feriali[-1] if feriali else days[0])

    if not args.dry_run and not INFLUX_TOKEN:
        sys.exit("INFLUX_TOKEN non impostata: la scrittura verrebbe rifiutata.")

    print(f"🗄️  PostgreSQL {db_config()['host']}:{db_config()['port']}"
          f"/{db_config()['dbname']} ...")
    conn = psycopg2.connect(**db_config())
    stops, buses, calls, meta = load_network(conn)
    conn.close()
    print(f"✅ {len(stops)} fermate, {len(buses)} mezzi, {len(calls)} corse\n")

    if not calls:
        sys.exit("Nessuna corsa con almeno due fermate: non c'e' nulla da generare.")

    print(f"{'giorno':<12}{'tipo':<10}{'punti':>10}")
    print("─" * 32)

    buf, total = [], 0

    def emit(line):
        nonlocal buf, total
        buf.append(line)
        total += 1
        if len(buf) >= args.batch:
            write_batch(buf, args.dry_run)
            buf = []

    for d in days:
        prof = day_profile(d, bad)
        n = generate(stops, buses, calls, meta, d, prof, args.step, emit)
        print(f"{d.isoformat():<12}{prof['label']:<10}{n:>10,}")

    write_batch(buf, args.dry_run)

    print("─" * 32)
    print(f"{'totale':<22}{total:>10,}")
    if args.dry_run:
        print("\n(--dry-run: non e' stato scritto nulla)")
    else:
        print(f"\n✅ scritti su {INFLUX_URL} bucket {INFLUX_BUCKET}")
        print("   Ricarica la tab Analytics e allarga l'intervallo a 7 giorni.")


if __name__ == "__main__":
    main()
