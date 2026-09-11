#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
obu_inventory.py — Chi trasmette davvero, e chi il registro si aspetta
======================================================================

PERCHE' ESISTE
Le unita' ESP32 pubblicano su cassitrack/obu/{id}/pos, e CassiTrack risolve
quell'id con una uguaglianza esatta su buses.current_vehicle_id:

    Optional<Bus> bus = busRepository.findByCurrentVehicleId(pos.getVehicleId());

Se non trova la riga, TripResolutionService.resolve() esce subito
(`if (busId == null) return Optional.empty()`) e il mezzo resta NO TRIP per
sempre, pur trasmettendo posizione e velocita' regolarmente.

Oggi i due insiemi non coincidono: il registro dice BUS1..BUS37, le antenne
dicono BUS2LIC, BUS04, BUSAGR. Questo script mette le due liste una accanto
all'altra invece di indovinarle.

E' DI SOLA LETTURA. Non scrive sul database, non pubblica su MQTT, non tocca
nessun file. Serve a decidere, non a correggere.

COSA GUARDA OLTRE AI NOMI
Anche i campi del payload: BUS04 in interfaccia mostra passeggeri "—", quindi
il disallineamento dei nomi potrebbe non essere l'unico problema. Per ogni id
si riporta quali chiavi sono sempre presenti e quali mancano, cosi' si vede
subito se un'unita' e' muta su "occ" o su "bat".

USO
    pip install paho-mqtt psycopg2-binary
    python obu_inventory.py                  # ascolta 120 s, poi confronta
    python obu_inventory.py --seconds 600    # finestra piu' lunga
    python obu_inventory.py --no-db          # solo broker, senza registro
    python obu_inventory.py --insecure       # salta la verifica del cert TLS

Una finestra lunga conta: un'unita' spenta o in avaria non trasmette, e
assumerla inesistente sarebbe un errore. Con --send-interval 60 lato ESP32,
due minuti bastano appena; per un inventario serio meglio 10 minuti.
"""

import argparse
import json
import os
import ssl
import sys
import time
from collections import defaultdict

import paho.mqtt.client as mqtt


# ─────────────────────────────────────────────────────────────────
#  Configurazione: stesse variabili degli altri tool
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

_URL = os.environ.get("MQTT_OBU_URL", "ssl://devaidalab.unicas.it:8883")
_HOSTPORT = _URL.replace("ssl://", "").replace("tcp://", "")
_HOST, _, _PORT = _HOSTPORT.partition(":")

BROKER = _HOST
PORT = int(_PORT or 8883)
USER = os.environ.get("MQTT_OBU_USERNAME", "esp32")
PASSWORD = os.environ.get("MQTT_OBU_PASSWORD", "")
TOPIC = os.environ.get("MQTT_OBU_TOPIC", "cassitrack/obu/+/pos")

# I campi che il manifesto dichiara per il payload OBU. Serve per dire quali
# mancano, non per rifiutare nulla.
EXPECTED_FIELDS = ["id", "ts", "lat", "lon", "spd", "hdg",
                   "occ", "sat", "bat", "tech", "rsrp"]


def db_config():
    """
    Parametri Postgres, letti preferendo SPRING_DATASOURCE_URL.

    Il .env del progetto definisce la URL JDBC intera e non host/porta
    separati, quindi leggere solo le variabili singole funzionerebbe per caso,
    grazie ai default. Qui la URL, se c'e', vince.
    """
    url = os.environ.get("SPRING_DATASOURCE_URL", "")
    host, port, dbname = "localhost", 5433, "cassitrack"
    if url.startswith("jdbc:postgresql://"):
        rest = url[len("jdbc:postgresql://"):]
        hostport, _, db = rest.partition("/")
        h, _, p = hostport.partition(":")
        host = h or host
        port = int(p) if p.isdigit() else port
        dbname = (db.split("?")[0] or dbname)
    return {
        "host": os.environ.get("SPRING_DATASOURCE_HOST", host),
        "port": int(os.environ.get("SPRING_DATASOURCE_PORT", port)),
        "dbname": os.environ.get("SPRING_DATASOURCE_DB", dbname),
        "user": os.environ.get("SPRING_DATASOURCE_USERNAME", "cassitrack"),
        "password": os.environ.get("SPRING_DATASOURCE_PASSWORD", "cassitrack_dev"),
    }


# ─────────────────────────────────────────────────────────────────
#  Raccolta dal broker
# ─────────────────────────────────────────────────────────────────

seen = defaultdict(lambda: {
    "messages": 0,
    "topic_id": None,     # l'id nel TOPIC
    "payload_id": None,   # l'id DENTRO il JSON: possono divergere
    "fields_always": None,
    "fields_ever": set(),
    "last": None,
    "first_seen": None,
    "last_seen": None,
})


def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        print(f"[MQTT] connesso, sottoscrivo {TOPIC}\n")
        client.subscribe(TOPIC)
    else:
        # rc=5 e' "not authorised": quasi sempre MQTT_OBU_PASSWORD mancante.
        print(f"[MQTT] connessione RIFIUTATA (rc={rc}). "
              f"{'Credenziali errate o mancanti.' if rc == 5 else ''}")
        sys.exit(1)


def on_message(client, userdata, msg):
    # L'id nel topic: cassitrack/obu/<QUI>/pos
    parts = msg.topic.split("/")
    topic_id = parts[2] if len(parts) > 2 else "?"

    try:
        data = json.loads(msg.payload.decode("utf-8", errors="replace"))
    except Exception:
        data = {}

    key = topic_id
    e = seen[key]
    now = time.time()
    e["messages"] += 1
    e["topic_id"] = topic_id
    e["payload_id"] = data.get("id")
    e["last"] = data
    e["last_seen"] = now
    if e["first_seen"] is None:
        e["first_seen"] = now

    present = set(k for k in data.keys())
    e["fields_ever"] |= present
    # "sempre presenti" = intersezione di tutti i messaggi visti finora
    e["fields_always"] = present if e["fields_always"] is None \
        else (e["fields_always"] & present)


def collect(seconds, insecure):
    try:
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION1)
    except (AttributeError, TypeError):
        client = mqtt.Client()
    client.username_pw_set(USER, PASSWORD)
    client.tls_set(cert_reqs=ssl.CERT_NONE if insecure else ssl.CERT_REQUIRED)
    if insecure:
        client.tls_insecure_set(True)
    client.on_connect = on_connect
    client.on_message = on_message

    print(f"[MQTT] connessione a {BROKER}:{PORT} ...")
    client.connect(BROKER, PORT, keepalive=60)
    client.loop_start()

    try:
        for remaining in range(seconds, 0, -1):
            print(f"\r  ascolto... {remaining:4d}s  "
                  f"({len(seen)} unita', "
                  f"{sum(v['messages'] for v in seen.values())} messaggi)",
                  end="", flush=True)
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n  interrotto, procedo con quanto raccolto.")
    finally:
        client.loop_stop()
        client.disconnect()
    print("\n")


# ─────────────────────────────────────────────────────────────────
#  Il registro
# ─────────────────────────────────────────────────────────────────

def load_registry():
    import psycopg2
    cfg = db_config()
    print(f"[DB] {cfg['user']}@{cfg['host']}:{cfg['port']}/{cfg['dbname']}")
    rows = []
    with psycopg2.connect(**cfg) as conn, conn.cursor() as cur:
        cur.execute("""
            SELECT bus_id, current_vehicle_id, targa, numero_posti, status
              FROM buses
             ORDER BY bus_id
        """)
        rows = cur.fetchall()
    return rows


# ─────────────────────────────────────────────────────────────────
#  Il confronto
# ─────────────────────────────────────────────────────────────────

def report(registry):
    broadcast_ids = sorted(seen.keys())

    print("=" * 72)
    print(f"UNITA' CHE TRASMETTONO  ({len(broadcast_ids)})")
    print("=" * 72)
    if not broadcast_ids:
        print("  Nessun messaggio ricevuto nella finestra di ascolto.")
        print("  Se il broker e' quello giusto, o le unita' sono spente")
        print("  oppure la finestra e' piu' corta del loro intervallo di invio.")
        return

    for bid in broadcast_ids:
        e = seen[bid]
        missing = [f for f in EXPECTED_FIELDS if f not in (e["fields_always"] or set())]
        last = e["last"] or {}
        pos = (f"{last.get('lat')}, {last.get('lon')}"
               if last.get("lat") is not None else "nessuna posizione")
        print(f"\n  {bid}")
        print(f"      messaggi ..... {e['messages']}")
        print(f"      id nel JSON .. {e['payload_id']}"
              + ("   ⚠ DIVERSO dal topic" if e['payload_id'] != bid else ""))
        print(f"      ultima pos ... {pos}   spd={last.get('spd')} occ={last.get('occ')}")
        if missing:
            print(f"      campi MAI presenti: {', '.join(missing)}")

    if registry is None:
        print("\n(registro non interrogato: --no-db)")
        return

    reg_ids = {r[1] for r in registry if r[1]}
    bset = set(broadcast_ids)

    orphans = sorted(bset - reg_ids)     # trasmettono ma nessuno le conosce
    silent = sorted(reg_ids - bset)      # censite ma mute
    matched = sorted(bset & reg_ids)

    print("\n" + "=" * 72)
    print("CONFRONTO CON IL REGISTRO")
    print("=" * 72)
    print(f"\n  Corrispondenze esatte ({len(matched)}): "
          f"{', '.join(matched) if matched else '—'}")

    print(f"\n  ⚠ TRASMETTONO MA NON SONO NEL REGISTRO ({len(orphans)})")
    print("    Sono queste le unita' che restano NO TRIP.")
    for o in orphans:
        print(f"      {o}")

    print(f"\n  Nel registro ma silenziose ({len(silent)})")
    print("    Righe libere: e' fra queste che vanno collocate le orfane.")
    for r in registry:
        if r[1] in silent:
            print(f"      bus_id={r[0]:<4} {r[1]:<12} targa={r[2]:<12} "
                  f"posti={r[3]:<4} status={r[4]}")

    if orphans:
        print("\n" + "=" * 72)
        print("PROSSIMO PASSO")
        print("=" * 72)
        print("""
  Per ogni unita' orfana serve sapere SU QUALE MEZZO FISICO sta montata,
  informazione che ne' il broker ne' il database possiedono. Assegnarla a una
  riga a caso le darebbe targa e capienza di un altro pullman: un errore che
  in interfaccia sembra corretto, ed e' il motivo per cui questo script si
  ferma qui invece di generare l'UPDATE da solo.

  Le tracce GPS qui sopra aiutano: la posizione dice quale linea sta facendo,
  e da li' si risale al mezzo.
""")


def main():
    ap = argparse.ArgumentParser(
        description="Inventario delle unita' OBU: chi trasmette vs chi e' censito")
    ap.add_argument("--seconds", type=int, default=120,
                    help="durata dell'ascolto in secondi (default: 120)")
    ap.add_argument("--insecure", action="store_true",
                    help="salta la verifica del certificato TLS")
    ap.add_argument("--no-db", action="store_true",
                    help="non interrogare il registro, solo il broker")
    args = ap.parse_args()

    if not PASSWORD:
        print("⚠ MQTT_OBU_PASSWORD non impostata: il broker rifiutera' "
              "la connessione (rc=5).\n")

    collect(args.seconds, args.insecure)

    registry = None
    if not args.no_db:
        try:
            registry = load_registry()
        except Exception as ex:
            print(f"[DB] non raggiungibile ({ex}); mostro solo il broker.\n")

    report(registry)


if __name__ == "__main__":
    main()
