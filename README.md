# CASSITRACK + OMNIMOVE
### Real-Time Smart Mobility Platform for Cassino
**University of Cassino and Southern Lazio (UNICAS) — 2025/2026**
**I-CiTies Challenge 2026 — Brescia, September 2026**

---

## What Is This?

Two Spring Boot systems that together cover the urban bus network of Cassino:
one watches the fleet, the other plans journeys on top of it.

**CASSITRACK** is the fleet-monitoring backend. It ingests on-board telemetry over
MQTT, resolves each position to a trip, a route and a stop, computes schedule
adherence, and publishes the result as a REST/SSE API plus two interoperability
feeds (NeTEx for the static network, SIRI for the live one).

**OMNIMOVE** is the traveller-facing multimodal journey planner. It mirrors
CASSITRACK's network over the NeTEx feed, consumes its live telemetry over SSE,
and ranks Bus / Bike / Scooter / Walk options by time, cost, CO₂ or a personal
preference profile.

The two never share a database. OMNIMOVE talks to CASSITRACK **only over HTTP**,
which is what makes them separately deployable.

| System | What it does | Port | Base path |
|---|---|---|---|
| **CASSITRACK** | Fleet monitoring — live positions, trip resolution, delay, analytics, data management | `8280` | `/cassitrack` |
| **OMNIMOVE** | Journey planning — multimodal search, Green Index, traveller app, admin console | `8180` | `/omnimove` |

---

## The Network It Runs On

The data is the real Cassino urban network, not a demo line:

| | |
|---|---|
| Lines | **18**, each uniquely numbered and colour-coded; every non-loop line also has its return as a line of its own (`<id>_R`) |
| Stops | **45**, with real coordinates (a handful interpolated between surveyed ones) |
| Trips | Departures **every 30 minutes, 06:00 → 23:00, both directions** |
| Vehicles | **37** registered buses; four of them are bound to physical ESP32/OBU units (`BUS1`…`BUS4`) |
| Geometry | All 18 lines have a road-routed polyline in `route_shapes` (OSRM-generated, editable on the map), so buses follow streets instead of cutting across blocks |

The stop pattern belongs to the **line** (`route_stops`), the times belong to the
**trip** (`scheduled_stops`) — the same JourneyPattern / ServiceJourney split
Transmodel and NeTEx make. See `V27__route_stop_patterns.sql`.

---

## System Architecture

```
ESP32 / OBU units                    gps_simulator3.py  ·  tools/simulate_bus*.py
        │  MQTT/TLS 8883                      │  MQTT 1883
        │  cassitrack/obu/{id}/pos            │  cassitrack/{vehicle_id}/position
        └──────────────┬──────────────────────┘
                       ▼
        Eclipse Mosquitto (auth + per-topic ACL)
                       ▼
  ┌─────────────────────────────────────────────────────────┐
  │  CASSITRACK — Spring Boot, port 8280                    │
  │    MqttMessageHandler   vehicle → trip → stop → delay   │
  │    PostgreSQL+PostGIS   routes, stops, trips, users     │
  │    InfluxDB             telemetry time series           │
  │    Redis                live vehicle state              │
  │    Analytics · Reports (CSV/XLSX/PDF) · Data management  │
  └───────┬──────────────┬───────────────┬──────────────────┘
          │ NeTEx        │ SSE           │ SIRI VM
          │ /api/static  │ /telemetry    │ /api/v1/siri
          ▼              ▼               ▼
  ┌─────────────────────────────────────────────────────────┐
  │  OMNIMOVE — Spring Boot, port 8180                      │
  │    StaticDataSyncService   mirrors the network          │
  │    TelemetrySyncService    live buses → Redis + Influx  │
  │    JourneyPlannerService   Dijkstra + transfers         │
  │    GreenIndexService · PreferenceWeights · Elerent      │
  │    GDPR: consents · retention · export · research tiers │
  └───────┬─────────────────────────────────────────────────┘
          │
          ├── Google Maps Distance Matrix   (traffic-aware ETA)
          ├── Elerent / RideAtom            (bike & scooter availability)
          ├── OpenWeatherMap                (weather-aware ranking)
          └── Regolo (Seeweb, EU)           (AI assistant, OpenAI-compatible)

  Static frontends are served by the backends themselves, from src/main/resources/static.
```

---

## Features

### CASSITRACK — fleet side

| Feature | Notes |
|---|---|
| Live bus tracking | MQTT ingestion, position validated (bounding box + max age) before it is trusted |
| Two MQTT inputs | Local broker for simulators; optional TLS broker for the real OBU/ESP32 compact schema |
| Trip resolution | Vehicle → bus → current trip → route, from the registry and the clock |
| Schedule adherence | `EARLY` / `ON_TIME` / `SLIGHTLY_LATE` (>3 min) / `SIGNIFICANTLY_LATE` (>10 min) |
| Next-stop matching | Haversine + approach/recession gates, so a stop is "reached" once and not oscillating |
| Live map | Fleet-manager dashboard with lines drawn from `route_shapes` in each line's colour |
| Analytics | Active lines, average delay, delay by route, busiest hours, passengers, CO₂, network view |
| Report export | The same dataset as CSV, XLSX or PDF — no extra dependencies, written by hand |
| Data management | CRUD for buses, stops, routes and trips, plus a map route-shape editor with preview |
| Admin panel | Users, roles, login/activity audit |
| NeTEx feed | Full static network as a NeTEx `PublicationDelivery` document |
| Version counters | Cheap change-detection endpoint backed by DB triggers, so consumers poll it instead of the whole document |
| SIRI Vehicle Monitoring | XML feed of live vehicle activity, optionally filtered by route |
| SSE telemetry stream | Push feed consumed by OMNIMOVE |
| AI assistant | Fleet-side chat over Anthropic Claude |
| Security | JWT, role-based access, security audit log, token blacklist, login-attempt throttling |

### OMNIMOVE — traveller side

| Feature | Notes |
|---|---|
| Multimodal search | Bus, Bike, Scooter, Walk, with walking legs to and from the stops |
| Transfers | Dijkstra over the stop graph — a journey with one change is found when no direct line exists |
| Traffic-aware ETA | Google Maps Distance Matrix from the bus's own GPS position; falls back to nearest stop, then to a 25 km/h estimate |
| Rankings | Fastest, Cheapest, Greenest, or **Custom** from the traveller's preference profile |
| Preference profile | Four onboarding answers become weights (time / cost / eco / reliability) — derived, never stored as numbers |
| Green Index | 0–100 per option, from EEA-style CO₂ factors (bus 68 g/pax·km against a 170 g/km car baseline) |
| Weather awareness | Rain and wind demote bike and scooter; "bus only when raining" is a per-account setting |
| Elerent integration | Live bike/scooter availability and operating zones from the RideAtom API, read-only; realistic mock when no key is configured |
| Live bus positions | Shown on the traveller map and during an active journey |
| Timetable browser | Departures by line and terminus |
| Favourites | Favourite stops and favourite journeys, both re-runnable in one tap |
| Journey history | Recorded per account, with stats |
| AI assistant | Multi-turn, personalised, bilingual; may fill and run a search, but may only *start* a journey when the traveller said so in as many words |
| Accounts | Email/password with verification, password reset, Sign in with Google, optional reCAPTCHA v2 on login |
| Admin console | Users, analytics, retention runs, UI/security/Google feature switches, analytics export |
| Messages | Travellers can write to the operators; replies land in the app |
| Bilingual UI | English and Italian, ~235 translated strings |
| GDPR layer | Privacy notice, cookie policy, consent ledger, personal-data export, nightly retention, three-tier research pipeline (off by default) |
| Rate limiting | Per-account hourly caps on journey search and stop arrivals — a cost ceiling as much as an abuse one |

---

## Standards and Interoperability

| Standard | Where | Endpoint |
|---|---|---|
| **NeTEx** | Static network export (stops, lines, patterns, trips, calendars, geometry) | `GET /cassitrack/api/static/netex` |
| **SIRI** Vehicle Monitoring | Live vehicle activity | `GET /cassitrack/api/v1/siri/vehicle-monitoring` |
| **MQTT** | Telemetry ingestion — full schema locally, compact OBU schema over TLS | `cassitrack/{id}/position`, `cassitrack/obu/{id}/pos` |
| **SSE** | Live telemetry push to OMNIMOVE (`X-Api-Key`) | `GET /cassitrack/api/v1/telemetry/stream` |
| **OpenAPI 3** | Both backends | `/api/swagger-ui` (authentication required) |

`netex.xml` in the repository root is a captured sample of the NeTEx output;
`validate_siri.py` and `tools/netex_element_order.py` help check both documents
against the official XSDs.

---

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) — running
- **Java 17 JDK** ([Temurin](https://adoptium.net/)) — both `pom.xml` files target 17, and so do the Dockerfiles
- Python 3.10+ for the simulators and the tools in `tools/`
- IntelliJ IDEA (or any IDE that runs a Spring Boot main class)
- Git

---

## Repository Layout

```
cassitrack_&_omnimove/
├── cassitrack-backend/                 # Fleet monitoring — port 8280, context /cassitrack
│   ├── docker-compose.yml              # postgres(5433) influxdb(8086) redis(6379) mosquitto(1883)
│   ├── Dockerfile                      # multi-stage build, non-root runtime
│   ├── mosquitto/config/               # broker conf + ACL (passwd is generated, never committed)
│   ├── .env.example                    # copy to .env
│   └── src/main/
│       ├── java/it/unicas/cassitrack/
│       │   ├── config/                 # MQTT (local + OBU), Influx, Security, Web
│       │   ├── controller/             # vehicles, buses, trips, routes, stops, analytics,
│       │   │                           #   reports, users, auth, ai, siri, netex, telemetry
│       │   ├── service/                # trip resolution, adherence, ETA, analytics,
│       │   │                           #   route patterns/edit, report export, audit
│       │   ├── mqtt/MqttMessageHandler.java
│       │   ├── dto/netex/  dto/siri/   # feed document models
│       │   ├── model/  repository/  security/
│       └── resources/
│           ├── application.yml
│           ├── db/migration/           # Flyway V1 … V28
│           └── static/                 # login, fleet manager, admin (HTML/CSS/JS)
│
├── omnimove-backend/                   # Journey planner — port 8180, context /omnimove
│   ├── docker-compose.yml              # omnimove-postgres(5432) influxdb(8087) redis(6380)
│   ├── Dockerfile
│   ├── .env.example
│   └── src/main/
│       ├── java/it/unicas/omnimove/
│       │   ├── client/                 # CassitrackClient, RideAtom/Elerent, mock provider
│       │   ├── controller/             # journeys, traveller, admin, auth, privacy, ai
│       │   ├── service/                # planner, green index, preferences, sync (static +
│       │   │                           #   telemetry), weather, Google Maps, consent,
│       │   │                           #   retention, research pipeline, mail, rate limiting
│       │   ├── security/  model/  repository/  util/
│       └── resources/
│           ├── application.yml
│           ├── db/migration/           # Flyway V1 … V35
│           └── static/                 # traveller app, admin console, login, privacy,
│                                       #   cookie policy, self-hosted fonts + Leaflet + Chart.js
│
├── gps_simulator2.py                   # schedule-driven simulator (position computed from the clock)
├── gps_simulator3.py                   # motion simulator — delay, early running, breakdowns emerge
├── tools/
│   ├── simulate_bus.py                 # reference simulator (free-running)
│   ├── simulate_bus_scheduled.py       # timetable-following variant
│   ├── build_route_shapes_osrm.py      # generate route geometry from a routing engine
│   ├── import_route_shapes.py          # import geometry drawn with crea_path.html
│   ├── crea_path.html                  # manual route-drawing editor
│   ├── bridge.py                       # MQTT/TLS → WebSocket bridge for browsers
│   ├── netex_element_order.py          # resolve the child order NeTEx XSDs impose
│   └── vendor_fonts.py                 # self-host Google Fonts (no user IP leaves the server)
│
├── docs/privacy/                       # DPIA (draft) + privacy change summary
├── elerent_en.md / elerent_it.md       # Elerent / ATOM Mobility integration notes
├── netex.xml                           # sample NeTEx output
├── validate_siri.py                    # validate a SIRI document against the XSD
└── requirements.txt                    # Python deps for simulators and tools
```

---

## How to Run

### Step 1 — Configure the environment

Each backend reads its secrets from a `.env` file that is **never committed**.

```bash
cp cassitrack-backend/.env.example cassitrack-backend/.env
cp omnimove-backend/.env.example   omnimove-backend/.env
```

Then fill both in. The values that must match across the two files:

| CASSITRACK | OMNIMOVE | Why |
|---|---|---|
| `SSE_API_TOKEN` | `CASSITRACK_API_TOKEN` | OMNIMOVE authenticates to the SSE and NeTEx feeds with it |
| — | `CASSITRACK_URL`, `CASSITRACK_NETEX_URL` | must point at the running CASSITRACK, including the `/cassitrack` context path |

Generate strong secrets with `openssl rand -base64 48`. `JWT_SECRET` must be at
least 32 characters in both.

Everything else degrades gracefully when left **empty**: no `GOOGLE_MAPS_API_KEY`
means haversine ETAs, no `AI_API_KEY` means canned assistant answers, no
`ELERENT_PUBLIC_KEY` means the mock bike fleet, no `GOOGLE_OAUTH_CLIENT_ID`
means the login page simply does not draw the Google button, no
`RECAPTCHA_*` keys means the login check stays off whatever the admin switch says.

Keep the *variables* present even when their value is blank — a few placeholders
in `application.yml` have no fallback and the app refuses to start if the
variable is missing altogether. Copying `.env.example` wholesale is the safe move.

### Step 2 — Start the infrastructure

Start Docker Desktop first, then:

```bash
docker compose -f cassitrack-backend/docker-compose.yml up -d postgres influxdb redis mosquitto
```

```bash
docker compose -f omnimove-backend/docker-compose.yml up -d omnimove-postgres omnimove-influxdb omnimove-redis
```

All ports are bound to `127.0.0.1` — nothing is reachable from the LAN.

| Service | Host port | Notes |
|---|---|---|
| CASSITRACK PostgreSQL + PostGIS | 5433 | db `cassitrack` |
| CASSITRACK InfluxDB | 8086 | org `unicas`, bucket `vehicle_telemetry` |
| CASSITRACK Redis | 6379 | password required |
| Mosquitto | 1883 | anonymous access disabled, per-topic ACL |
| OMNIMOVE PostgreSQL | 5432 | db `omnimovedb` |
| OMNIMOVE InfluxDB | 8087 | org `omnimove_org`, bucket `omnimove_bucket` |
| OMNIMOVE Redis | 6380 | password required |

### Step 3 — Create the MQTT users

The broker refuses anonymous clients, and the password file is deliberately not
in Git, so each developer generates their own:

```bash
docker exec -it cassitrack-mosquitto mosquitto_passwd -c /mosquitto/config/passwd cassitrack-backend
```

```bash
docker exec -it cassitrack-mosquitto mosquitto_passwd /mosquitto/config/passwd cassitrack-bus
```

Put `cassitrack-backend`'s credentials in `MQTT_USERNAME` / `MQTT_PASSWORD`, give
the simulator the `cassitrack-bus` ones, then `docker compose restart mosquitto`.

### Step 4 — Run the backends

In IntelliJ, add the `.env` contents as environment variables on each run
configuration (Run → Edit Configurations → Modify options → Environment
variables), then start, in this order:

1. **CassitrackApplication** → http://localhost:8280/cassitrack/
2. **OmnimoveApplication** → http://localhost:8180/omnimove/

Flyway runs the migrations on first start. OMNIMOVE imports the network from
CASSITRACK's NeTEx feed at startup and re-imports within a minute of any change,
so **CASSITRACK must be up first**.

To run the backends in containers instead of the IDE, each compose file keeps
its app behind a `full` profile:

```bash
docker compose -f cassitrack-backend/docker-compose.yml --profile full up -d
```

```bash
docker compose -f omnimove-backend/docker-compose.yml --profile full up -d
```

### Step 5 — Feed it some buses

```bash
python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
```

```bash
python gps_simulator3.py
```

`gps_simulator3.py` integrates real motion along the routed geometry, so delay,
early running, breakdowns and signal loss happen on their own rather than being
switched on. `gps_simulator2.py` is the older schedule-driven one: always
punctual, useful when you want a quiet fleet.

Both read the fleet and the timetable straight from PostgreSQL, picking up the
database credentials from `cassitrack-backend/.env` on their own. The MQTT
credentials are the *bus* ones — `MQTT_BUS_USERNAME` / `MQTT_BUS_PASSWORD` in the
environment, or `--mqtt-username` / `--mqtt-password` on the command line.

### Step 6 — Open the apps

| App | URL | Who |
|---|---|---|
| CASSITRACK login | http://localhost:8280/cassitrack/ | fleet manager, admin |
| Fleet manager dashboard | `cassitrack-fleetmanager.html` (after login) | `FLEET_MANAGER` |
| CASSITRACK admin panel | `cassitrack-admin.html` (after login) | `ADMIN` |
| OMNIMOVE login | http://localhost:8180/omnimove/ | traveller, admin |
| Traveller app | `omnimove-traveller.html` (after login) | `TRAVELLER` |
| OMNIMOVE admin console | `omnimove-admin.html` (after login) | `ADMIN` |

There is no separate static file server: both backends serve their own
frontends, and the pages are gated by the same rules as the APIs behind them.

---

## API Reference

Interactive docs (authentication required — no free reconnaissance):

- CASSITRACK: http://localhost:8280/cassitrack/api/swagger-ui
- OMNIMOVE: http://localhost:8180/omnimove/api/swagger-ui

### CASSITRACK — `http://localhost:8280/cassitrack`

| Method | Endpoint | Access |
|---|---|---|
| GET | `/api/v1/vehicles` · `/{id}` · `/count` · `/fleet-size` | public |
| GET | `/api/v1/stops` · `/{id}/arrivals` · `/{id}/schedule` | public |
| GET | `/api/v1/routes` · `/{id}/shape` | public |
| GET | `/api/v1/siri/vehicle-monitoring` | public |
| GET | `/api/v1/telemetry/latest` (deprecated) | public |
| GET | `/api/v1/telemetry/stream` (SSE) | `X-Api-Key` |
| GET | `/api/static/netex` · `/api/static/version` | `X-Api-Key` |
| GET/POST/PUT/DELETE | `/api/v1/buses` · `/api/v1/trips` · `/api/v1/routes` · `/api/v1/stops` | `FLEET_MANAGER` |
| GET | `/api/v1/analytics/**` (summary, adherence, busiest-hours, delay-by-route, co2, network, …) | `FLEET_MANAGER` |
| POST | `/api/v1/reports/export` (CSV / XLSX / PDF) | authenticated (driven from the fleet-manager UI) |
| POST | `/api/v1/ai/chat` | `FLEET_MANAGER`, `ADMIN` |
| GET/POST/PUT/DELETE | `/api/v1/users/**` | `ADMIN` |
| POST | `/api/v1/auth/login` · `/logout` | public / authenticated |

### OMNIMOVE — `http://localhost:8180/omnimove`

| Method | Endpoint | Access |
|---|---|---|
| POST | `/api/v1/journeys/search` | traveller / admin |
| POST | `/api/v1/journeys/select` | traveller / admin |
| GET | `/api/v1/journeys/stops` · `/stops/{id}/arrivals` | traveller / admin |
| GET | `/api/v1/journeys/live-buses` | traveller / admin |
| GET | `/api/v1/journeys/routes/{id}/stops` · `/shape` · `/routes/shapes` | traveller / admin |
| GET | `/api/v1/journeys/timetable/routes` · `/{routeId}` | traveller / admin |
| GET | `/api/v1/journeys/bikes` · `/bikes/zones` | traveller / admin |
| GET | `/api/v1/journeys/weather` · `/geocode/reverse` | traveller / admin |
| GET/PUT | `/api/v1/traveller/me` · `/preferences` · `/ui-settings` | traveller |
| GET | `/api/v1/traveller/history` · `/stats` | traveller |
| GET/POST/DELETE | `/api/v1/traveller/favorite-stops` · `/favorites` | traveller |
| POST/GET | `/api/v1/traveller/messages` | traveller |
| POST | `/api/v1/ai/chat` | traveller / admin |
| POST/GET | `/api/v1/privacy/consents` · `/export` | public (record) / authenticated |
| POST | `/api/v1/auth/register` · `/login` · `/google` · `/forgot-password` · `/reset-password` | public |
| GET | `/api/v1/auth/me` · `/verify` | authenticated |
| DELETE | `/api/v1/auth/account` | authenticated |
| GET/PUT/DELETE | `/api/v1/admin/**` (users, analytics, retention, settings) | `ADMIN` |

### Example — journey search

```bash
curl -X POST http://localhost:8180/omnimove/api/v1/journeys/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"origin_lat":41.493833,"origin_lon":13.828778,"origin_name":"Piazza San Benedetto","dest_lat":41.47583,"dest_lon":13.82894,"dest_name":"Universita Folcara"}'
```

### Example — live fleet

```bash
curl http://localhost:8280/cassitrack/api/v1/vehicles
```

### Example — static network version

```bash
curl -H "X-Api-Key: $SSE_API_TOKEN" http://localhost:8280/cassitrack/api/static/version
```

---

## Journey Planner — Modes and Fares

| Mode | Provider | Fare | CO₂ (g/pax·km) |
|---|---|---|---|
| 🚌 Bus | Cassino urban network | €1.00 per boarding (a change costs two) | 68 |
| 🚲 Bike | Elerent | €1.00 unlock + €0.29/min | 0 |
| 🛴 E-Scooter | Elerent | €1.00 unlock + €0.25/min (+ €5.00 refundable hold) | 0 |
| 🚶 Walk | — | free | 0 |

Green Index = `100 − (CO₂ / CO₂ of the same trip by car) × 100`, with the car
baseline at 170 g/km. The traveller app shows the full derivation per option.

Elerent fares live in configuration (`elerent.*` in OMNIMOVE's
`application.yml`), not in the planner, so a price change is a config change.

---

## Privacy and Data Protection

The GDPR work is real and is documented in `docs/privacy/`:

- **Published texts** — `privacy.html` and `cookie-policy.html`, versioned by
  `PRIVACY_POLICY_VERSION`; bumping it re-asks for consent.
- **Consent ledger** — every consent recorded with its policy version.
- **Data export** — a traveller can download their own data from the app.
- **Retention** — a nightly job enforces the periods the notice states
  (journeys 365 d, security logs 365 d, consents 730 d, unverified accounts 24 h)
  and records every run, successes and failures alike. **On by default.**
- **Research pipeline** — a three-tier lifecycle (operational → pseudonymous →
  aggregate with k-anonymity ≥ 10). **Off by default**, and it should stay off
  until the DPIA has been signed off by the University's DPO.
- **No third-country asset loads** — fonts, Leaflet and Chart.js are all
  self-hosted, so no user IP reaches Google's CDNs.
- **AI provider** — the default assistant provider is Regolo (Seeweb, Italy):
  EU data residency and zero retention, which matters because the assistant is
  handed the traveller's journey history.

`docs/privacy/DPIA-omnimove-cassitrack.md` is an explicitly labelled **technical
draft**: it is not a valid DPIA until completed, reviewed by the DPO and
approved by the University.

---

## Security Notes

- Secrets live in `.env` only, never in `application.yml` or `docker-compose.yml`.
- All infrastructure ports bind to `127.0.0.1`.
- Container images are pinned by digest, not by mutable tag.
- Mosquitto: anonymous access disabled, per-topic ACL — a bus publisher can
  write its own topic and nothing else.
- Swagger UI requires authentication on both backends.
- JWT with a server-side blacklist on logout; login-attempt throttling; a
  dedicated security audit log on both sides.
- Incoming positions are validated (Cassino bounding box, max age 300 s) before
  they are stored or trusted.
- `mvn verify` runs OWASP Dependency-Check; suppressions are in
  `owasp-suppressions.xml` in each module.

> **Outstanding:** early commits contained secrets in tracked `env` files. The
> history still needs `git filter-repo` treatment and every exposed secret needs
> rotating — see the notice in `.gitignore`.

---

## Simulators and Tools

| Script | What it is for |
|---|---|
| `gps_simulator3.py` | The one to use. Real motion along `route_shapes`; delay and early running are physical outcomes, not parameters. `--force-breakdown <vehicle-id>`, `--force-signal-loss <vehicle-id>` and `--raw` are there for targeted tests. |
| `gps_simulator2.py` | Position computed from the clock — always on time, no traffic, straight lines between stops. |
| `tools/simulate_bus.py` | Reference simulator: free-running, does not follow the timetable. |
| `tools/simulate_bus_scheduled.py` | The reference movement model made to depart on the timetable. |
| `tools/crea_path.html` | Draw a line's geometry by hand on a map, export `percorsiX.json`. |
| `tools/import_route_shapes.py` | Turn that JSON into a `route_shapes` migration. |
| `tools/build_route_shapes_osrm.py` | Same output, generated automatically from a routing engine. |
| `tools/bridge.py` | Bridge the TLS MQTT broker to a WebSocket a browser can read. |
| `tools/vendor_fonts.py` | Re-download and re-vendor the self-hosted fonts. |
| `validate_siri.py` | Validate a SIRI document against the official XSD (`pip install lxml`). |
| `tools/netex_element_order.py` | Resolve the child order a NeTEx type requires across its inheritance chain. |

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `Connection refused` on 5432/5433 | Docker not running | Start Docker Desktop, then bring the compose stacks up |
| Backend exits at startup complaining about a missing property | `.env` not loaded into the run configuration | Environment variables set in a shell are invisible to IntelliJ — put them in Run → Edit Configurations |
| Flyway `validate` failure on start | A migration file was edited after it had been applied | Reset the database (`docker compose down -v`) or fix the checksum row in `flyway_schema_history` |
| Hibernate schema validation error | Database predates the current migrations | `docker compose down -v`, then start again so Flyway rebuilds from V1 |
| No buses on the map | Simulator not running, or MQTT credentials wrong | Run `python gps_simulator3.py`; check the broker accepted the `cassitrack-bus` user |
| Broker container stuck "unhealthy" | `passwd` file missing | Generate the MQTT users (Step 3) and restart Mosquitto |
| OMNIMOVE shows no lines or stops | The NeTEx import never ran | Start CASSITRACK first; check `CASSITRACK_NETEX_URL` includes `/cassitrack`, and that `CASSITRACK_API_TOKEN` equals CASSITRACK's `SSE_API_TOKEN` |
| OMNIMOVE gets no live buses | SSE stream not reachable or token mismatch | Same token pair; check `CASSITRACK_URL` |
| OMNIMOVE cannot write to InfluxDB | Wrong token | Generate one in the InfluxDB UI on port 8087 and set `INFLUX_TOKEN` in `omnimove-backend/.env` |
| ETAs look like straight-line estimates | `GOOGLE_MAPS_API_KEY` unset or Distance Matrix API not enabled | Set the key on **OmnimoveApplication**; the fallback chain is by design |
| The AI assistant only gives canned answers | `AI_API_KEY` unset (OMNIMOVE) or `ANTHROPIC_API_KEY` unset (CASSITRACK) | Set the relevant key; the fallback is deliberate, not a failure |
| No "Sign in with Google" button | `GOOGLE_OAUTH_CLIENT_ID` unset, or the origin is not registered | Register the origin (no path) in Google Cloud Console and set the ID |
| reCAPTCHA switch is on but nothing appears | Keys not configured | Without both keys the check stays off on purpose — a widget nobody can solve would lock everyone out |
| Browser blocked by CORS or CSP | Origin not listed | Add it to `CORS_ALLOWED_ORIGINS` (both apps) and `CSP_CONNECT_EXTRA` (CASSITRACK) |

---

## Useful Commands

```bash
docker compose -f cassitrack-backend/docker-compose.yml logs -f mosquitto
```

```bash
docker exec -it cassitrack-postgres psql -U cassitrack -d cassitrack -c "SELECT vehicle_id, lat, lon, speed_kmh, received_at FROM vehicle_positions ORDER BY received_at DESC LIMIT 10;"
```

```bash
docker compose -f cassitrack-backend/docker-compose.yml down -v
```

---

## What Is Next

- Real OBU hardware on more vehicles (`BUS4` still needs reflashing from `BUS2L`)
- Elerent production API key, replacing the mock availability provider
- A service calendar: trips currently run every day, with no weekday/Saturday distinction
- Official timetable data from the network operator, replacing the interpolated intermediate times
- DPIA completion and DPO sign-off before the research pipeline is enabled anywhere real

---

## Credits

**Scientific direction — University of Cassino and Southern Lazio**
Prof. Mario Molinara (software development coordinator, scientific director of
the AIDA Laboratory) · Prof. Mauro D'Apuzzo (sustainable mobility) ·
Prof. Luigi Ferrigno (Technology Transfer Office, which sponsored the activity) ·
Prof. Francesco Iacoviello (President of CASI, which provided the network
infrastructure and expertise)

**Commissioning body and partners**
Municipality of Cassino (commissioning body) · Elerent (bike and scooter
position data) · Seeweb (the AI that powers the assistant)

**Development team**
Massimiliano Carello · Giacomo Alberto Napolitano · Naoli Shiferaw Biru ·
Ferran Montero · Mar González Montesinos

**Context:** I-CiTies Challenge 2026, Brescia, September 2026.

---

## Repository

```
https://github.com/naolishif/cassitrack
```
