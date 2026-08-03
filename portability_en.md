# Portability of CASSITRACK and OMNIMOVE to other cities

> Portability analysis — August 2026. Twin document: `portability_it.md`.
> Related documents: `cassitrack_integration_en.md`, `omnimove_integration_en.md`, `payments_integration_en.md`.

## 1. Question and verdict

**Could the system, as it is today, work in another city?**

| System | As-is | Config only | With data reload | With code changes |
|---|---|---|---|---|
| CASSITRACK | ❌ | ❌ | ❌ (necessary but not sufficient) | ✅ **3–5 days** (one-off fork) |
| OMNIMOVE | ❌ | ❌ | ❌ (necessary but not sufficient) | ✅ **2–3 days** (one-off fork) |

**Neither system has an architecture problem.** The domain model is city-agnostic (WGS84 coordinates, GTFS-like vocabulary, no geographic constraint in the schema). The "Cassino-ness" is concentrated in **hardcoded constants, branding strings and seed data** — not in the design. OMNIMOVE is in better shape because it already made the right call: transit data is **not seeded in migrations but imported at runtime** from CASSITRACK's NeTEx feed.

Shared structural constraint: both are **single-tenant / single-city**. No `City`/`Agency`/`Tenant` entity anywhere in the schema: two cities = two full deployments (separate DB, Redis, Influx, broker).

---

## 2. CASSITRACK

### 2.1 Critical blockers

**B1 — Hardcoded geofence in MQTT ingestion (critical, silent).**
`cassitrack-backend/.../mqtt/MqttMessageHandler.java:56-59`:

```java
private static final double LAT_MIN = 41.40;  LAT_MAX = 41.60;
private static final double LON_MIN = 13.70;  LON_MAX = 14.00;
```

Enforced in `isValid()` (lines 165-166): any telemetry outside the Cassino box (~20×25 km) is **silently discarded** (just an audit line saying "validation failed"). A Milan bus would never reach the DB, Influx, or the map. This is the single worst blocker — and the highest-leverage fix: externalize 4 constants via `@Value`, or compute the box from the stops in the DB plus a margin.

**B2 — The timetable exists only as hand-written SQL.**
`trips` and `scheduled_stops` have no write controller and no UI (the Data Management UI has only Buses/Stops/Routes tabs — `cassitrack-fleetmanager.html:351-353`). The only way to author a timetable is the PL/pgSQL generator `pg_temp.gen_line(...)` in `V5__refresh_timetable_magni.sql:52-60`, called with literal arrays. Cascading consequences without a timetable:
- a route with fewer than 2 scheduled stops **does not appear** in `GET /api/v1/routes` (`RouteController.java:69-77`);
- every bus resolves to `NO_TRIP` → no route, delay, ETA, or adherence; the analytics page is empty.

**B3 — Flyway migrations intertwine schema and Cassino seed data.**
V2 (routes, stops, MAGNI-00x buses, `@cassitrack.it` users), V4/V5 (open with `DELETE FROM` and reseed the real Cassino lines and 20 stops), V10 (555 lines of Cassino road geometry), V11–V14 (fleet-specific patches). They cannot be skipped — Flyway runs the whole chain. Clean fix: split `db/schema` from `db/seed-<city>` and drive `spring.flyway.locations` from env (`application.yml:30`).

### 2.2 Minor blockers

| # | What | Where |
|---|---|---|
| B4 | Route geometry has no runtime path (pipeline: `tools/crea_path.html` → JSON → `tools/import_route_shapes.py` → hand-committed migration). Mitigated: without geometry the map falls back to dashed stop-to-stop polylines | `V10:5-10`, `RouteController.java:59-63` |
| B5 | Map center hardcoded `[41.497, 13.822]`, no `fitBounds` | `cassitrack-fleetmanager.js:64-65` |
| B7 | Timezone `Europe/Rome` hardcoded in 5 places — and **inconsistently**: `AnalyticsService.java:208,391` uses `systemDefault()` (a latent bug already today) | `ETAService.java:46-47`, `ScheduleAdherenceService.java:32`, `TripResolutionService.java:42`, `StopController.java:42`, `AiOrchestrationService.java:78` |
| B8 | "MAGNI / Cassino / Linea 16" branding in ~14 places (UI, OpenAPI, AI prompt) | `application.yml:115`, `AiOrchestrationService.java:200-205,321-324`, `cassitrack-analytics.html:26`, etc. |
| B9 | MQTT topics not parameterized (`cassitrack/+/position`), SIRI `ProducerRef` `"CASSITRACK"` hardcoded, NeTEx codespace `"CASSITRACK:"` in 11 places. **Note**: NeTEx/SIRI are export-only — there is no importer, so the standards support does not help onboarding today | `application.yml:82-83`, `Siri.java:58`, `NetexController.java` |

### 2.3 What is already portable

All infrastructure is env-driven (Postgres, Redis, Influx, MQTT brokers, JWT, SSE token, CORS, Anthropic key, context path). Stop/route/bus CRUD accepts any coordinate in the world. The schema is PostGIS SRID 4326 and `route_shapes` is explicitly modelled on GTFS `shapes.txt`. Operational thresholds (3/10-min adherence, 80 m approach gate, crowding) are generic.

### 2.4 New-city onboarding (steps)

1. **Data** (60–70% of the effort): stops with coordinates → routes → **timetable** (new migration using the `gen_line` pattern, respecting the headway > round-trip-time rule of `V13:27-40`) → geometry (optional) → buses with `current_vehicle_id` = the OBUs' radio ids → user reset.
2. **Migration surgery**: keep V1/V7/V8/V9 (schema), replace V2–V6 and V10–V14 (seed).
3. **Code**: externalize the bbox (B1), map center (B5), timezone (B7), strings (B8), codespace/topics (B9).
4. **Ops**: dedicated DB, Influx bucket, Redis, broker credentials, CORS (all already env-driven).

---

## 3. OMNIMOVE

### 3.1 What is already portable (the good part)

- **No transit data in migrations**: `NetexImportService.importDataFromCassitrack()` deletes and re-imports everything (stops, routes, trips, timetables, buses, shapes) from the feed at runtime. Migrations create schema only (+2 demo users).
- Google Distance Matrix parameterized per request (raw lat/lon, no regional bias).
- CASSITRACK endpoint fully configurable: `CASSITRACK_URL`, `CASSITRACK_API_TOKEN`, `CASSITRACK_NETEX_URL`.
- The frontend has no preset destinations: autocomplete and markers derive from `GET /journeys/stops`, with `fitBounds` over the loaded stops.
- Micromobility tariffs as properties (`elerent.*`); `GreenIndexService` (EEA factors) and `TrafficAwareETAService` need no change at all.

### 3.2 Blockers — the most insidious ones **fail silently**

**A1 — Cassino-coordinate fallback in the planner (the single most important fix in the project).**
`JourneyPlannerService.java:593-605`: if a stop id is null/unknown, `getStopLat/Lon` returns `41.4925 / 13.8306` (Cassino city centre). In another city this would produce **plausible-looking but meaningless journeys, with no error**. It must return `Optional`/throw, not a magic constant.

**A2 — The map "goes home".** `omnimove-traveller.js:979`: ending a journey does a hard `setView` back to Cassino — a real bug even today. (The `:237` at startup is just a flash, corrected immediately by `fitBounds`.)

**A3 — GPS denied ⇒ user teleported to Via Folcara.** `omnimove-traveller.js:482-484`: the promise *resolves* with fabricated Cassino coordinates instead of failing — the user is never told.

**Weather by fixed city name.** `WeatherService.java:36-40,90`: queries OpenWeatherMap with `weather.api.city: Cassino` (a property that is **missing from `.env.example`**), not with the journey's coordinates. Ambiguous names (Cambridge, Springfield) resolve unpredictably.

**Operator and tariffs welded into the code.** "Elerent" and `elerent.it` in the planner labels (`JourneyPlannerService.java:437-473`); `COST_BUS = 1.00` (line 62) and `SPEED_SCOOTER = 20.0` km/h (line 61) as constants rather than properties; `€` hardcoded in the UI; mock ticket shop with "Bus Line 16 / Urban zone A" (`omnimove-traveller.html:150-256`).

**AI prompt scoped to Cassino.** `AiOrchestrationService.java:298-309` ("assistant for Cassino… Folcara campus… steer back to Cassino transport") plus offline fallbacks naming "Bus 16".

**Timezone and locale.** `Europe/Rome` in 4 places (`JourneyPlannerService.java:518,874`, `AiOrchestrationService.java:178`): outside CET every wait time would be wrong (`arrivalSeconds` is interpreted against Rome midnight). `it-IT`/`en-GB` locale literals in the JS.

**Single-feed coupling.** One `CassitrackClient` with a `baseUrl` fixed at construction; the import **wipes the entire dataset** before re-importing; the feed dialect is CASSITRACK-specific (`NAMESPACE:Type:LOCALID` ids, Italian extension fields `targa`/`numeroPosti`) — a standards-compliant NeTEx/GTFS feed needs an adapter. Also, `SiriConsumerService.java:26` hardcodes `localhost:8280` (currently disabled, a latent blocker).

**Scale assumptions** (not Cassino-specific, but they bite in bigger cities): `findNearestStopId` does `findAll()` + haversine over every stop + 3 blocking Google calls; the AI context makes one HTTP call **per stop per chat message**; a hard `.limit(500)` cap on stops; unclustered markers. Beyond ~200 stops this needs rework (+1–2 weeks).

### 3.3 New-city onboarding (steps)

0. **Upstream prerequisite** (the real long pole, outside OMNIMOVE): a CASSITRACK instance for the new city, already live and publishing NeTEx in the same dialect.
1. Fresh infrastructure (a **new** DB, not a clone of the Cassino DB — the only case where V15, correctly guarded with `WHERE EXISTS`, could hurt), new secrets.
2. Configuration: point `CASSITRACK_*` to the new instance; `weather.api.city/country` (and add them to `.env.example`); `elerent.*` tariffs → the local operator.
3. Code (by severity): fallback A1 → `setView` A2 → GPS A3 → `COST_BUS`/`SPEED_SCOOTER` to properties → `mobility.operator.name/url` → `omnimove.city.name/timezone` (weather, AI, the 4 timezone sites) → rebranding → mock ticket shop → `SiriConsumerService` → configurable locale.
4. Data: delete the ~40 simulated users of `V4__sim_users.sql` (shared known password `test1234`) and rotate the V2 demo credentials. On boot the app imports NeTEx by itself (`OmnimoveApplication.java:43-76`, 10 retries).
5. Verify: map centred locally, weather naming the right city, AI not mentioning Cassino, wait times matching the local timetable in the local timezone.

---

## 4. Combined effort estimates

| Scenario | CASSITRACK | OMNIMOVE |
|---|---|---|
| One-off fork, similar-size Italian city | **3–5 days** (60–70% of it data production: timetable SQL + geometry) | **2–3 days** |
| Non-Italian / non-CET city | included above + timezone | **+2–3 days** (timezone, locale, remaining Italian strings) |
| Larger city (>500 stops) | — | **+1–2 weeks** (spatial index, caps, clustering, AI context) |
| Making them genuinely configurable (still single-tenant) | **1.5–2.5 weeks** | **~1 week** |
| Onboarding without SQL (GTFS/NeTEx importer + timetable API) | **+3–5 weeks** — turns "days per city" into "an afternoon per city" | **+1–2 weeks** (adapter for standard feeds) |
| True multi-tenant (multiple cities per deployment) | **+3–4 weeks** | **+3–4 weeks** |

---

## 5. The top 5 highest-leverage moves

1. **Externalize CASSITRACK's MQTT bounding box** (4 lines that silently void the entire product outside a 20×25 km box).
2. **Remove OMNIMOVE's silent Cassino-coordinate fallbacks** (`JourneyPlannerService.java:593-605`, `omnimove-traveller.js:482-484,979`).
3. **Split schema from seed** in CASSITRACK's Flyway chain (`db/schema` + `db/seed-<city>` via `spring.flyway.locations`).
4. **A single city config**: `app.city`, `app.timezone`, `mobility.operator.*` consumed by map, weather, AI and branding in both systems.
5. **A GTFS importer** for CASSITRACK (the schema is already GTFS-shaped): the strategic move that makes onboarding a city an afternoon's work.

## 6. Lessons for the course

- **"Portable by architecture" ≠ "portable in practice"**: both systems have the right design but dozens of hardcoded constants. The discipline is to *never write* a geographic/company literal outside configuration.
- **Silent failures are the worst class of bug**: a geofence that discards without error and a fallback that fabricates coordinates produce systems that *look* like they work. An explicit crash beats a plausible-but-false datum.
- **Seed vs import**: OMNIMOVE (runtime import) ports in 2–3 days; CASSITRACK (migration seed) takes 3–5 plus hand-written SQL. The entire difference lies in that one choice.
- **Standards only help if you import them**: exporting NeTEx does not ease onboarding; importing it would transform it.
