# Integrating shared mobility (bikes / scooters / e-scooters) into OMNIMOVE via API

> Course handout — version 2026-08-03. No code changes: this is design only. Equivalent
> Italian version: `omnimove_integration_it.md`.

---

## 1. Goal and context

OMNIMOVE is the multimodal journey planner for Cassino: given an origin and a destination,
`JourneyPlannerService` builds up to four options — **BUS, BIKE, SCOOTER, WALK** — ranks them
under three profiles (FAST / BUDGET / ECO) and enriches them with weather, Green Index (CO2),
cost and live bus delays from CASSITRACK.

Today BIKE and SCOOTER are **estimated, not real**:

- `planBike()` / `planScooter()` ask Google Maps only for a `bicycling` route, then compute the
  cost from static tariffs in `application.yml` (`elerent.bike.unlock`,
  `elerent.bike.per-minute`, `elerent.scooter.unlock`, `elerent.scooter.per-minute`);
- the scooter derives its travel time from distance at a fixed speed
  (`SPEED_SCOOTER = 20.0` km/h);
- **nothing checks that a vehicle actually exists near the user**, that it is charged, or that
  it is not already reserved.

Goal: connect a real sharing operator via API so that the option becomes *"Elerent e-scooter
120 m away, battery 68%, 14 km of range, €1.00 + €0.25/min, tap to unlock"* instead of a
theoretical estimate. The standard to use is **GBFS**.

---

## 2. GBFS in 5 minutes

**GBFS** (*General Bikeshare Feed Specification*, https://gbfs.org) is the *de facto* standard
for **real-time** shared-mobility data: bikes, e-bikes, e-scooters, mopeds, free-floating car
sharing. It is the micromobility equivalent of GTFS-Realtime, maintained by MobilityData
(spec: https://github.com/MobilityData/gbfs). What matters to us is that it is **read-only,
public and almost always unauthenticated**, and that it is **JSON over HTTPS**: it is consumed
with a plain `WebClient`, exactly as we already do with CASSITRACK. The catalogue of public
feeds lives in MobilityData's `systems.csv`: **around 34 Italian feeds** as of 2026-08-03.

### 2.1 File structure

The entry point is always `gbfs.json` (the *discovery file*): it lists all the others.

| File | Content | Do we need it? |
|---|---|---|
| `gbfs.json` | index of the feeds, per language | **yes, the entry point** |
| `system_information.json` | operator, timezone, contacts | yes (metadata) |
| `vehicle_types.json` | `form_factor`, `propulsion_type`, max range | **yes, to map BIKE vs SCOOTER** |
| `free_bike_status.json` | **free-floating vehicles: id, lat, lon, battery, range, availability, deep links** | **yes, this is the core** |
| `station_information.json` / `station_status.json` | physical stations and free docks | yes, if the operator is station-based (e.g. BikeMi) |
| `system_pricing_plans.json` | unlock fee, €/min, currency | **yes, replaces the hardcoded tariffs** |
| `geofencing_zones.json` | GeoJSON of forbidden / no-parking areas | yes, so we never suggest an illegal drop-off |
| `system_hours.json`, `system_alerts.json` | service hours, alerts | optional |

### 2.2 TTL and refresh

Every file carries three mandatory top-level fields:

```json
{ "last_updated": 1754216400, "ttl": 60, "version": "2.3", "data": { } }
```

`last_updated` = epoch of the operator's last update; `ttl` = **seconds the cache stays valid**
(`0` = do not cache); `version` = spec version. Good-citizen rule, often a contractual
condition: **never poll faster than the `ttl`**. Typically 60 s for `free_bike_status`, hours
or days for the static files.

### 2.3 Versions

**1.x** legacy; **2.2 / 2.3** the most widespread standard in Italy today, the one we assume;
**3.x** the newest, which renames `free_bike_status.json` to **`vehicle_status.json`** and makes
the `data` structure multilingual. Practical defence: read `version`, support 2.x natively and
3.x through a dedicated mapper; read file names **from the discovery document**, never build
them by string concatenation; never assume an optional field is present.

---

## 3. Worked example: consuming Dott Rome step by step

**Dott** (electric scooters) publishes a public, **unauthenticated** GBFS feed for Rome: it
works right now, from a terminal, with no sign-up.

### Step 1 — discovery

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json | jq .
```

```json
{
  "last_updated": 1754216400,
  "ttl": 60,
  "version": "2.2",
  "data": {
    "en": {
      "feeds": [
        { "name": "system_information",   "url": "https://gbfs.api.ridedott.com/public/v2/rome/system_information.json" },
        { "name": "free_bike_status",     "url": "https://gbfs.api.ridedott.com/public/v2/rome/free_bike_status.json" },
        { "name": "vehicle_types",        "url": "https://gbfs.api.ridedott.com/public/v2/rome/vehicle_types.json" },
        { "name": "geofencing_zones",     "url": "https://gbfs.api.ridedott.com/public/v2/rome/geofencing_zones.json" },
        { "name": "system_pricing_plans", "url": "https://gbfs.api.ridedott.com/public/v2/rome/system_pricing_plans.json" }
      ]
    }
  }
}
```

Feed names are **data**, not conventions: the client builds a `name -> url` map.

### Step 2 — the available vehicles

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/free_bike_status.json | jq '.data.bikes | length'
```

A real record (captured on 2026-08-03):

```json
{
  "bike_id": "7b7eeef5-ac9a-4905-96f4-67e7e092d429",
  "lat": 41.883018, "lon": 12.523322,
  "current_range_meters": 3536,
  "current_fuel_percent": 0.22,
  "is_disabled": false, "is_reserved": false,
  "vehicle_type_id": "dott_scooter",
  "rental_uris": { "android": "https://dott.app.link/...", "ios": "https://dott.app.link/..." }
}
```

| Field | Use in OMNIMOVE |
|---|---|
| `bike_id` | opaque, **rotating** identifier (it changes at the end of a rental): never persist it as a domain key |
| `lat` / `lon` | user→vehicle distance (haversine), then a walking leg via Google |
| `current_range_meters` | feasibility filter: if `< trip distance`, the vehicle is not enough |
| `current_fuel_percent` | 0.0–1.0 → "battery 22%" in the UI |
| `is_disabled`, `is_reserved` | **both must be `false`** for the vehicle to be offered |
| `vehicle_type_id` | join with `vehicle_types.json` → `form_factor` → OMNIMOVE mode |
| `rental_uris` | deep link into the operator's app: the final call to action |

### Step 3 — from vehicle type to mode

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/vehicle_types.json | jq '.data.vehicle_types'
```

```json
[ { "vehicle_type_id": "dott_scooter", "form_factor": "scooter",
    "propulsion_type": "electric", "max_range_meters": 30000, "name": "Dott e-scooter" } ]
```

The mapping we adopt: `bicycle` (human or `electric_assist`) → **BIKE**; `scooter`,
`scooter_standing`, `scooter_seated` → **SCOOTER**; `moped`, `car`, `other` → **ignored**
(out of scope for the current planner).

### Step 4 — real tariffs instead of hardcoded ones

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/system_pricing_plans.json | jq '.data.plans[0]'
```

```json
{ "plan_id": "rome-payg", "name": "Pay as you go", "currency": "EUR",
  "price": 1.00, "is_taxable": false,
  "per_min_pricing": [ { "start": 0, "rate": 0.25, "interval": 1 } ] }
```

`price` = unlock fee, `per_min_pricing[].rate` = €/minute: exactly the pair OMNIMOVE currently
hardcodes in `application.yml`. With the feed it becomes operator-supplied data.

### Step 5 — vehicles within 300 m of a point

```bash
curl -s https://gbfs.api.ridedott.com/public/v2/rome/free_bike_status.json \
 | jq '[.data.bikes[] | select(.is_disabled==false and .is_reserved==false)
        | select(((.lat-41.8830)*111320|fabs) < 300 and ((.lon-12.5233)*82500|fabs) < 300)]
       | length'
```

This is the shell equivalent of what `SharedMobilityService.findNearest(...)` will do.

### Other public Italian feeds (useful for testing)

| Operator | City | Discovery URL |
|---|---|---|
| Dott | Rome | `https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json` |
| Bird | Rome / Milan / Florence | `https://mds.bird.co/gbfs/v2/public/rome/gbfs.json` |
| Lime | Naples / Bari | `https://data.lime.bike/api/partners/v2/gbfs/naples/gbfs.json` |
| Zeus | Anzio (Lazio) | `https://zeus.city/api/v1/mds/gbfs/anzio/gbfs.json` |
| BikeMi | Milan (station-based) | `https://gbfs.urbansharing.com/bikemi.com/gbfs.json` |

Free aggregator (128 Italian networks, its own non-GBFS format): **Citybikes**,
`https://api.citybik.es/v2` — handy for a quick overview, but it is not the standard.

---

## 4. Integration design for OMNIMOVE

**Guiding principle: replicate the `CassitrackClient` pattern.** In OMNIMOVE all fleet data
goes through a single client (`it.unicas.omnimove.client.CassitrackClient`), a `WebClient`
wrapper with a configured base URL that **degrades silently**: every method catches the
exception and returns an empty list, so the planner keeps working with CASSITRACK down. Shared
mobility must honour the same contract: **one integration point, no error ever propagated to
the traveller**.

> This section is **design only**: none of the classes described here has been written.

### 4.1 Proposed classes

```
it.unicas.omnimove
├── client/GbfsClient.java              <-- NEW, twin of CassitrackClient
├── dto/gbfs/
│   ├── GbfsEnvelopeDTO.java            last_updated, ttl, version, data<T>
│   ├── GbfsDiscoveryDTO.java           data.<lang>.feeds[] -> name/url
│   ├── GbfsVehicleDTO.java             bike_id, lat, lon, current_range_meters,
│   │                                   current_fuel_percent, is_disabled, is_reserved,
│   │                                   vehicle_type_id, rental_uris
│   ├── GbfsVehicleTypeDTO.java         vehicle_type_id, form_factor, propulsion_type
│   ├── GbfsStationInfoDTO.java  /  GbfsStationStatusDTO.java
│   ├── GbfsPricingPlanDTO.java         plan_id, currency, price, per_min_pricing[]
│   └── GbfsGeofencingZoneDTO.java      GeoJSON FeatureCollection (phase 2)
├── dto/SharedVehicleResponse.java      boundary DTO (never the raw GBFS DTO)
└── service/
    ├── SharedMobilityService.java          filtering, mapping, nearest, pricing
    ├── GbfsRefreshService.java             scheduled polling that respects the ttl
    └── SharedMobilitySettingsService.java  feature flag (twin of GoogleApiSettingsService)
```

Conventions already in force to follow: package `it.unicas.omnimove.*`, **constructor
injection** (`@RequiredArgsConstructor`), Lombok, **snake_case** JSON via `@JsonProperty`, thin
controllers with logic in services, **never expose entities**, `@Value` for configuration.

### 4.2 `GbfsClient` — proposed shape

```
@Component
public class GbfsClient {
    Map<String,String> discoverFeeds(String providerId);           // name -> url, long cache
    List<GbfsVehicleDTO>     fetchFreeVehicles(String providerId); // free_bike_status | vehicle_status
    List<GbfsVehicleTypeDTO> fetchVehicleTypes(String providerId);
    List<GbfsPricingPlanDTO> fetchPricingPlans(String providerId);
    boolean isAvailable(String providerId);                        // probes the discovery file
}
```

Non-functional requirements: **short timeout** (2–3 s), because the planner is synchronous and
cannot hang on a slow feed; **no exception escapes upward** (empty list + `log.warn`, like
`CassitrackClient.getActiveVehicles()`); **version handling** (if `version` starts with `3.`,
read `vehicle_status`); **one `WebClient` per provider**, base URL from the discovery document.

### 4.3 `SharedMobilityService` — domain logic

1. **Availability**: discard `is_disabled == true` or `is_reserved == true`.
2. **Type → mode mapping** per the table in §3 step 3.
3. **Nearest vehicle**: haversine over the candidates, top-N, then (optionally) one Google
   `walking` call for the real pedestrian distance — **the same scheme as
   `JourneyPlannerService.findNearestStopId()`**, which does haversine top-3 then Google.
4. **Range**: discard vehicles whose `current_range_meters` is below the trip distance.
5. **Pricing**: use `system_pricing_plans` when the feed exposes it, **otherwise** fall back to
   the `elerent.*` values in `application.yml`. The fallback is mandatory: it is what makes the
   integration backward-compatible.

### 4.4 Cache and refresh

`GbfsRefreshService`: `@Scheduled(fixedDelay = 30_000)` on the fast feeds while respecting the
declared `ttl`; discovery, `vehicle_types` and `pricing_plans` hourly; bootstrap on
`ApplicationReadyEvent` with exponential backoff, as `NetexImportService` already does.

**Redis** (already in the stack, `StringRedisTemplate`) as the shared cache, in the same key
style as the telemetry keys `bus:latest:<id>`: `gbfs:vehicles:<providerId>` with TTL = the
feed's `ttl`; `gbfs:types:<providerId>` and `gbfs:pricing:<providerId>` TTL 1 h;
`gbfs:discovery:<providerId>` TTL 24 h.

**No new table, no Flyway migration** for the vehicles: they are volatile data with rotating
identifiers. Persisting them would be technical noise and questionable on privacy grounds.

### 4.5 Hook points in the planner

In `JourneyPlannerService`, `planBike()` and `planScooter()` become:

1. ask `SharedMobilityService` for the nearest available vehicle to the origin;
2. **if there is none within the configured radius → return `null`**, i.e. drop the option:
   consistent with the existing policy (today the option disappears when Google returns no real
   route);
3. prepend a **`WALK` leg** origin → vehicle before the BIKE/SCOOTER leg (`JourneyLeg` already
   supports `mode`, `from`, `to`, `duration_minutes`, `distance_metres`, `instruction`);
4. compute the cost from the feed's tariffs;
5. expose `rental_uris` in the `instruction` field or — better — through a new
   `@JsonProperty("deep_link")` on `JourneyLeg`: it is a DTO, **not** an entity, so **no
   migration**.

Green Index is unchanged: `GreenIndexService` already assigns 0 g CO2/km to BIKE and SCOOTER.

### 4.6 REST endpoint (optional, for the map)

`GET /api/v1/journeys/vehicles/nearby?lat=&lon=&radius_m=&mode=` in `JourneyController`:
authenticated (TRAVELLER or ADMIN) like everything under `/api/v1/journeys/**`; validation with
`spring-boot-starter-validation` (lat −90..90, lon −180..180, `radius_m` 50..2000); **rate
limited** through `RateLimiterService`, new bucket `vehicles-nearby` at 60/user/hour in line
with `stop-arrivals`; response `List<SharedVehicleResponse>`, **never** the raw GBFS DTOs.

### 4.7 Configuration — nothing hardcoded

In `application.yml`, in the style of the existing `cassitrack:` block:

```yaml
sharedmobility:
  enabled: ${GBFS_ENABLED:false}
  search-radius-metres: ${GBFS_SEARCH_RADIUS_M:400}
  refresh-seconds: ${GBFS_REFRESH_SECONDS:30}
  providers:
    elerent:
      discovery-url: ${GBFS_ELERENT_URL:}          # empty = provider disabled
      api-key:       ${GBFS_ELERENT_API_KEY:}      # only if the operator requires one
    dott-demo:
      discovery-url: ${GBFS_DOTT_URL:https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json}
```

and in `.env.example`: `GBFS_ENABLED=false`, `GBFS_ELERENT_URL=`, `GBFS_ELERENT_API_KEY=`,
`GBFS_SEARCH_RADIUS_M=400`. **Missing key = feature off, not an error**: the rule already
applied to Google Maps, weather and Anthropic throughout the project.

For a runtime toggle from the admin dashboard, follow the `app_settings` pattern: a new
**`V16__sharedmobility_settings.sql`** migration (never edit the already-applied V1–V15)
inserting `sharedmobility.enabled` with `ON CONFLICT DO NOTHING`, plus a
`SharedMobilitySettingsService` twin of `GoogleApiSettingsService` (`ConcurrentHashMap` cache,
write-through, safe default). A sibling service is preferable to widening the Google one's
whitelist, which is deliberately limited to two keys.

---

## 5. Elerent: who they are and what to ask for

**Elerent** (https://elerent.com) is an Italian sharing operator (e-scooters, e-bikes, electric
mopeds) present in about **40 Italian cities, including Cassino**. It is the natural partner
for the project: it physically operates in the territory OMNIMOVE serves and is already named
in the code as the source of the bike/scooter tariffs.

**Status as of 2026-08-03: Elerent publishes no API or GBFS feed.** It must be requested
formally.

### 5.1 Checklist to submit to Elerent

1. **A GBFS 2.3+ feed** (or 3.x) over HTTPS containing at least: `gbfs.json`,
   `system_information.json`, `vehicle_types.json`, `free_bike_status.json` (or
   `vehicle_status.json` in 3.x), `geofencing_zones.json`, `system_pricing_plans.json`.
2. **Refresh ≤ 60 seconds** on `free_bike_status`, with a consistent declared `ttl`.
3. **Coverage of Cassino at minimum** — ideally one feed per city, or a single feed with
   `geofencing_zones` separating the operating areas.
4. **Rental deep links** (`rental_uris.android` / `.ios` / `.web`) to take the user from the
   OMNIMOVE screen to the unlock flow in the Elerent app.
5. **Access**: public and unauthenticated preferred (like Dott, Bird, Lime); failing that, a
   **read-only API key** — our configuration already provides for it.
6. **Terms of use**: acceptable rate limit, attribution obligations, data licence (ideally ODbL
   or CC-BY), technical contact for outages.
7. **A test / staging environment** with dummy data, to develop without touching production.

Useful negotiating point: **a GBFS feed brings the operator traffic**, it does not take it away
— booking and payment stay inside the Elerent app, OMNIMOVE acts as a shop window.

### 5.2 The alternative: MDS (and why it is not for us)

**MDS** (*Mobility Data Specification*,
https://github.com/openmobilityfoundation/mobility-data-specification) is the standard for
**trip-level** data (complete routes, vehicle state events). It is far richer than GBFS, but it
is designed for **oversight by city authorities**, not for passenger apps; access is typically
restricted and contractual with the public body; and it contains privacy-sensitive data. So
**GBFS is the right ask for OMNIMOVE**; MDS stays a possibility only if the City of Cassino
becomes a project partner.

### 5.3 Fallback if Elerent does not respond in time

1. **A simulated Elerent-style feed** generated locally (§6) — the option chosen for September.
2. **Point at a public operator in another city** (Dott Rome, Zeus Anzio), stating openly in the
   demo that the coordinates are not Cassino's: it proves the client works against real feeds.
3. **Stay on the current estimates** (`elerent.*` in `application.yml`): the code does not
   regress, it simply does not gain real availability.

---

## 6. September 2026 demo plan — simulating an Elerent feed

Goal: **show the integration working before any agreement exists**, with Cassino data, without
ever implying the feed is official.

### 6.1 Static files served locally

A `tools/gbfs-sim/` folder with `gbfs.json`, `system_information.json`, `vehicle_types.json`,
`system_pricing_plans.json` and `free_bike_status.json` (the only one continuously regenerated).

`gbfs.json` with URLs pointing at the local server:

```json
{ "last_updated": 1756900000, "ttl": 60, "version": "2.3",
  "data": { "it": { "feeds": [
    { "name": "system_information",   "url": "http://localhost:8090/system_information.json" },
    { "name": "vehicle_types",        "url": "http://localhost:8090/vehicle_types.json" },
    { "name": "free_bike_status",     "url": "http://localhost:8090/free_bike_status.json" },
    { "name": "system_pricing_plans", "url": "http://localhost:8090/system_pricing_plans.json" }
  ] } } }
```

`free_bike_status.json` with Cassino coordinates (indicative, to be refined against real spots):

```json
{ "last_updated": 1756900000, "ttl": 60, "version": "2.3",
  "data": { "bikes": [
    { "bike_id": "sim-elr-0001", "lat": 41.493833, "lon": 13.828778,
      "current_range_meters": 18400, "current_fuel_percent": 0.68,
      "is_disabled": false, "is_reserved": false,
      "vehicle_type_id": "elerent_scooter",
      "rental_uris": { "web": "https://elerent.com/" } },
    { "bike_id": "sim-elr-0002", "lat": 41.490600, "lon": 13.832000,
      "current_range_meters": 6100, "current_fuel_percent": 0.24,
      "is_disabled": false, "is_reserved": false,
      "vehicle_type_id": "elerent_scooter",
      "rental_uris": { "web": "https://elerent.com/" } },
    { "bike_id": "sim-elr-0101", "lat": 41.475700, "lon": 13.814500,
      "current_range_meters": 0, "current_fuel_percent": 0.92,
      "is_disabled": false, "is_reserved": false,
      "vehicle_type_id": "elerent_bike",
      "rental_uris": { "web": "https://elerent.com/" } }
  ] } }
```

Useful demo anchors: **Cassino railway station** (~41.4938, 13.8288), **town centre / Piazza
Miranda** (~41.4906, 13.8320), **UNICAS Folcara campus** (~41.4757, 13.8145).

```bash
cd tools/gbfs-sim && python3 -m http.server 8090
# then in .env:  GBFS_ENABLED=true   GBFS_ELERENT_URL=http://localhost:8090/gbfs.json
curl -s http://localhost:8090/gbfs.json | jq '.data.it.feeds[].name'
curl -s http://localhost:8090/free_bike_status.json | jq '.data.bikes | length'
```

Same verification as §3, different source — which is precisely the point.

### 6.2 Moving vehicles: reuse the `gps_simulator2.py` pattern

The repo root already contains `gps_simulator2.py`, the CASSITRACK bus GPS simulator: it loads
vehicles and routes from PostgreSQL, **interpolates positions between real points**, publishes
at a regular interval (`--interval`) and has a minimal `.env` loader. That is the skeleton we
need.

A twin script `tools/elerent_gbfs_sim.py` should: start from N plausible parking positions in
Cassino (station, centre, campus, squares); on each tick (10–30 s) move a few vehicles by tens
of metres, decrease `current_fuel_percent`, recompute `current_range_meters` and flip some
`is_reserved` values; **rewrite `free_bike_status.json` atomically** (temp file + `os.replace`,
so the backend never reads a truncated JSON); update `last_updated` on every write while
keeping `ttl: 60`.

Benefit: the UI shows vehicles moving and batteries draining, while the backend meanwhile
exercises **the real GBFS code path**, not a fake mode.

### 6.3 Demo script (5 minutes)

1. Show that the **real Dott Rome feed** answers (§3, steps 1–2): the standard exists and is
   public.
2. Show the **simulated Elerent Cassino feed** with the exact same structure.
3. Plan Station → Folcara Campus: the SCOOTER option now says *"e-scooter 120 m away, battery
   68%"* with the walking leg to the vehicle.
4. Stop the simulator or mark every vehicle `is_disabled: true` → the SCOOTER option
   **disappears from the results** instead of raising an error: controlled degradation, the same
   philosophy the planner uses when CASSITRACK is offline.
5. Close by explaining that swapping the simulator for Elerent means changing **a single
   environment variable**, `GBFS_ELERENT_URL`.

### 6.4 Honesty rules for the demo

Always label simulated data, in the UI and in the slides, as **"demo feed — not official
Elerent data"**; do not use Elerent logos or trademarks as if the service were already
integrated; give simulated `bike_id`s a recognisable prefix (`sim-elr-…`).

---

## 7. References

**Standards**
- GBFS — official site: https://gbfs.org
- GBFS — specification and `systems.csv` catalogue: https://github.com/MobilityData/gbfs
- MDS: https://github.com/openmobilityfoundation/mobility-data-specification

**Public Italian feeds (verified on 2026-08-03)**
- Dott Rome: https://gbfs.api.ridedott.com/public/v2/rome/gbfs.json
- Bird Rome (also Milan, Florence): https://mds.bird.co/gbfs/v2/public/rome/gbfs.json
- Lime Naples (also Bari): https://data.lime.bike/api/partners/v2/gbfs/naples/gbfs.json
- Zeus Anzio: https://zeus.city/api/v1/mds/gbfs/anzio/gbfs.json
- BikeMi Milan (station-based): https://gbfs.urbansharing.com/bikemi.com/gbfs.json
- Citybikes API (aggregator, 128 Italian networks): https://api.citybik.es/v2

**Target operator**
- Elerent: https://elerent.com — active in ~40 Italian cities, Cassino included; no public API
  as of 2026-08-03.

**OMNIMOVE code referenced**
- `omnimove-backend/src/main/java/it/unicas/omnimove/client/CassitrackClient.java` — the client pattern to replicate
- `omnimove-backend/src/main/java/it/unicas/omnimove/service/JourneyPlannerService.java` — `planBike()`, `planScooter()`, `findNearestStopId()`
- `omnimove-backend/src/main/java/it/unicas/omnimove/service/GoogleApiSettingsService.java` — feature flags over `app_settings`
- `omnimove-backend/src/main/resources/application.yml` — the `elerent:` block with the current tariffs
- `gps_simulator2.py` (repo root) — the simulator pattern to reuse
