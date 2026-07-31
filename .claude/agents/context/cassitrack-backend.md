# CASSITRACK Backend — Architecture Briefing

> Persistent reference for the CASSITRACK backend. Read at the start of every backend task.
> Path: `cassitrack-backend/` — Java 17 / Spring Boot 3.5.5 / Maven.
> `groupId=it.unicas`, `artifactId=cassitrack`, `version=0.1.0-SNAPSHOT`.
> Base package: `it.unicas.cassitrack`. App entrypoint: `CassitrackApplication` (`@EnableScheduling`, `@EnableAsync`).

---

## 1. Purpose & Domain

Real-time bus fleet monitoring for the city of **Cassino**, operator **MAGNI Autoservizi**
(University of Cassino / Southern Lazio, 2025–2026). ESP32/OBU on-board units publish GPS
telemetry over MQTT; the backend enriches raw fixes with trip/route/stop/delay context, caches
live state, persists time-series, and exposes real-time positions + ETAs via REST, SIRI, NeTEx
and an SSE stream. It is the data provider consumed by **OMNIMOVE** (a sibling app, served under
`/omnimove/` on port 8180) and by the built-in fleet-manager / analytics / admin dashboards.

Key principle (load-bearing): **the bus reports only what it can physically measure** (GNSS fix,
BLE count, hardware telemetry). Everything operational — trip, route, current stop, next stop,
delay, crowding — is **derived server-side** and must never be trusted from the payload.

---

## 2. Tech Stack (versions from `pom.xml` / `application.yml`)

| Concern | Tech | Version |
|---|---|---|
| Framework | Spring Boot (parent) | 3.5.5 |
| Language | Java | 17 |
| Web/REST | spring-boot-starter-web (Tomcat pinned) | 10.1.55 |
| Relational DB | PostgreSQL + PostGIS (JDBC driver 42.7.11) | postgis 16-3.4 |
| Time-series | InfluxDB (influxdb-client-java 7.1.0) | 2.7 |
| Cache / sessions | Redis (spring-boot-starter-data-redis) | 7.2-alpine |
| Messaging | MQTT — spring-integration-mqtt + Eclipse Paho v3 | paho 1.2.5 / Mosquitto 2.0 |
| Realtime push | spring-boot-starter-websocket (starter present); actual push is **SSE** | — |
| Security | spring-boot-starter-security (pinned) + JJWT (api/impl/jackson) | sec 6.5.9 / jjwt 0.11.5 |
| Migrations | Flyway core + flyway-database-postgresql | (BOM) |
| Standards | SIRI + NeTEx via jackson-dataformat-xml | (BOM) |
| API docs | springdoc-openapi-starter-webmvc-ui | 2.5.0 |
| AI | spring-boot-starter-webflux → `WebClient` to Anthropic Claude (no SDK) | — |
| Boilerplate | Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`) | (optional) |
| Security scan | OWASP dependency-check-maven (fails build on CVSS ≥ 7) | 10.0.4 |

Server: port **8280**, context-path **`/cassitrack`** (Nginx routes `/cassitrack/` → 8280,
`/omnimove/` → 8180). Actuator exposes only `health,info`. Static SPA pages live in
`src/main/resources/static/` (login, fleetmanager, analytics, admin — each with `.html/.css/.js`).

---

## 3. REST Controllers (base path → key endpoints)

All under context-path `/cassitrack`. `@RestController` classes in `controller/`.

### `VehicleController` — `/api/v1/vehicles` (PUBLIC GET; primary OMNIMOVE integration)
- `GET /` → all active vehicles (`List<VehicleStatusDTO>`); "active" = seen ≤ 300s.
- `GET /{id}` → single vehicle, 404 if not in cache.
- `GET /fleet-size` → `{total: busRepository.count()}`.
- `GET /count` → `{active, total}` from `VehicleStateCache`.

### `StopController` — `/api/v1/stops`
- `GET /{stopId}/arrivals` → live predicted arrivals (`ETAService`).
- `GET /{stopId}/schedule` → today's remaining static-timetable arrivals.
- `GET /` , `POST /`, `PUT /{id}`, `DELETE /{id}` → stop CRUD (writes = FLEET_MANAGER). Uses record `StopRequest`.

### `RouteController` — `/api/v1/routes`
- `GET /` → active routes with ordered stops + road polyline (`RouteGeometry`/`PathPoint`); public map geometry.
- `GET /manage` → raw `Route` list.
- `POST /`, `PUT /{id}`, `DELETE /{id}` → route CRUD (writes = FLEET_MANAGER). Record `RouteRequest`.

### `BusController` — `/api/v1/buses` (US-01 registry; class-level `@PreAuthorize` FLEET_MANAGER)
- `GET /` (search/status/routeId filters), `GET /route-options`, `GET /{id}`.
- `POST /`, `PUT /{id}`, `PUT /{id}/visibility?visible=`, `DELETE /{id}`. Delegates to `BusService`; local `@ExceptionHandler`s produce readable JSON. **Registry** ≠ live telemetry (that's `/vehicles`).

### `AuthController` — `/api/v1/auth`
- `POST /register` → public self-registration.
- `POST /login` → returns `LoginResponse` (token in body) + sets httpOnly `cassitrack_jwt` cookie.
- `POST /logout` → blacklists token, clears cookie.

### `UserController` — `/api/v1/users` (class-level `@PreAuthorize` ADMIN)
- `GET /`, `POST /` (uses `RegisterRequest`), `PUT /{id}`, `DELETE /{id}`.

### `AnalyticsController` — `/api/v1/analytics` (class-level `@PreAuthorize` FLEET_MANAGER)
- `GET /summary`, `/adherence`, `/busiest-hours`, `/passengers-by-route`, `/delay-by-route`,
  `/co2`, `/operating-hours`, `/routes`, `/routes-map`. Filter params (`startTime`, `endTime`,
  `routeIds`, `busId`, `groupBy`) are regex-validated (Flux-injection guard, OWASP A03).

### `AiController` — `/api/v1/ai` (FLEET_MANAGER or ADMIN)
- `POST /chat` → natural-language Q&A over live data (`ChatRequest`→`ChatResponse`).

### `SiriController` — `/api/v1/siri`
- `GET /vehicle-monitoring` (XML, optional `route_id`) → SIRI VehicleMonitoring from Redis cache.

### `NetexController` — `/api/static`
- `GET /netex` (XML) → full NeTEx `PublicationDelivery` (stops, lines, timetable, vehicles, shapes).
  Guarded by `X-Api-Key` (constant-time compare vs `sse.api-token`).

### `DataExportController` — `/api/v1/telemetry`
- `GET /latest?route_id=` → `List<BusTelemetryDTO>` from Influx (marked DEPRECATED).
- `GET /stream` (SSE, `text/event-stream`) → SIRI XML pushed every 5s + 3s heartbeat.
  Guarded by `X-Api-Key`; capped at 50 concurrent emitters. This is the OMNIMOVE feed.

---

## 4. Services (`service/`) & responsibilities

- **`ETAService`** — predicts arrivals at a stop from active vehicles + scheduled timetable → `StopArrivalDTO`.
- **`CrowdingService`** — *static util class* (no injection). Single source of truth for crowding:
  `effectivePassengers` (direct count or `bleDeviceCount × 0.6`), SIRI OccupancyStatus + LOW/MEDIUM/HIGH/VERY_HIGH from fill ratio. Used by `SiriMapper`.
- **`GreenIndexService`** — Green Index 0–100 from EEA CO₂ factors (bus 68, car 170 gCO₂/pkm).
- **`ScheduleAdherenceService`** — computes stop arrival, delay minutes, punctuality status via an
  "approach/recession" state machine (80 m arrival gate, 18 m recession margin). `statusFromDelay`
  thresholds: EARLY < −1, ON_TIME ≤ 3, SLIGHTLY_LATE ≤ 10, else SIGNIFICANTLY_LATE. Writes delay history to Influx.
- **`RouteMatchingService`** — matches GPS to nearest stop, resolves stop names, next-stop-after-sequence. Records `NamedStop`, `StopOnTrip`.
- **`TripResolutionService`** — answers "which trip is this vehicle running now?" (bus_id + clock →
  candidate trips → GPS tie-break). Per-vehicle in-memory cache; re-resolves when service window elapses.
- **`AnalyticsService`** — hybrid: live from Redis, historical aggregations from InfluxDB (Flux queries).
- **`InfluxService`** — reads latest telemetry (Flux) → `BusTelemetryDTO` (for deprecated `/latest`).
- **`VehicleService`** — maps cached `VehiclePosition` → `VehicleStatusDTO` (resolves `map_visible` fresh per request).
- **`VehicleStateCache`** — **Redis-backed** live-state store (see §5); `getActive()` filters by 300s.
- **`BusService`** — bus registry CRUD + in-memory search/filter (US-01); keeps `disponibile` in sync with `status`.
- **`UserService`** — user CRUD, BCrypt hashing, audit hooks; `UserDTO::from` mapping.
- **`AiOrchestrationService`** — collects live context, calls Claude via `WebClient`, falls back to
  local processing when the API key/credits are unavailable (graceful degradation).
- **Security services**: `SecurityAuditService`, `TokenBlacklistService`, `LoginAttemptService` (see §7).

---

## 5. Data Model

### JPA entities (`model/`) — PostgreSQL
- **`Route`** (`routes`, id VARCHAR PK) — shortName, longName, active, color.
- **`Stop`** (`stops`, id VARCHAR PK) — name, lat, lon, description, active, createdAt. PostGIS
  `location` GEOGRAPHY column auto-maintained by DB trigger (`trg_stop_location`).
- **`Bus`** (`buses`, `bus_id` IDENTITY PK) — targa (unique), numeroPosti, wheelchairAccessible,
  disponibile (legacy), `currentVehicleId` (radio identity = MQTT id, unique), `routeId`,
  `status` (ACTIVE/INACTIVE/MAINTENANCE, CHECK constraint), `mapVisible`.
- **`Trip`** (`trips`, id VARCHAR PK) — `@ManyToOne` → Route, `@ManyToOne` → Bus (both LAZY).
- **`ScheduledStop`** (`scheduled_stops`, BIGSERIAL PK) — `@ManyToOne` → Trip (`trip_id`), stopId,
  stopSequence, arrivalSeconds. UNIQUE(trip_id, stop_sequence). **Source of truth for adherence/ETA.**
- **`RouteShape`** (`route_shapes`, composite PK `(route_id, seq)` via `@IdClass`) — lat, lon, isStop.
  Presentation-only road polyline (GTFS `shapes.txt`-style); routes without shapes fall back to stop-hops.
- **`User`** (`users`, BIGSERIAL PK) — taxId, name, surname, email (unique), passwordHash
  (`@JsonProperty WRITE_ONLY`), role, telephone. Bean-validation annotations.
- **`SecurityAuditEvent`** (`security_audit_events`, BIGSERIAL PK) — eventType, email, ipAddress,
  additionalInfo, createdAt (`@PrePersist`). App user has INSERT-only; SELECT reserved for `security_auditor` role.

**Relationships:** Route 1—* Trip *—1 Bus; Trip 1—* ScheduledStop *→ Stop (by id); Route 1—* RouteShape.
Repositories are Spring Data `JpaRepository` (except `VehiclePositionRepository` = `CrudRepository`).
Custom JPQL lives mainly in `ScheduledStopRepository` (upcoming-by-stop, representative-sequence,
operating-hours, routes-with-stops) and `TripRepository` (fetch-join route+bus by ids).

### Redis
- **`VehiclePosition`** is a `@RedisHash("vehicle_positions")` with `@Id vehicleId` — the **live
  current state** of each vehicle (key `vehicle_positions:{vehicleId}`). Holds GPS, derived trip/route,
  last/next stop, delay + anchor fields, approach state-machine fields, ScheduleStatus. NOT a JPA entity.
- `TokenBlacklistService` → `jwt_blacklist:{token}` (StringRedisTemplate, TTL = remaining validity).
- `LoginAttemptService` → `login_failed:{email}` counter (15-min window, max 5).

### InfluxDB
- Bucket `vehicle_telemetry`, org `unicas`. Measurement **`vehicle_position`** written by
  `MqttMessageHandler.writeToInflux` (tags: vehicle_id, bus_id, trip_id, route_id; fields: lat, lon,
  speed_kmh, heading_deg, wheelchair_accessible, ble_device_count, battery_voltage, passengers,
  capacity, delay, last_stop_registered, next_stop; precision SECONDS). Writes the **fully-resolved**
  state, not raw payload. Analytics read historical data back via Flux.

---

## 6. MQTT Ingestion Pipeline

**Config** (`config/MqttConfig.java`): Paho client factory + Spring-Integration inbound adapter
subscribing (QoS 1) to `cassitrack/+/position`, `cassitrack/+/ble`, and `cassitrack/obu/+/pos`,
feeding `mqttInputChannel` (a `DirectChannel`). An **optional second inbound connection** (the "OBU
broker", e.g. `ssl://devaidalab.unicas.it:8883`) is `@ConditionalOnProperty("mqtt.obu.enabled")`;
it feeds the SAME channel. TLS is auto-attached for `ssl://` URLs. OBU client-id gets a random suffix
only when left at the default (avoids broker id collisions). An outbound channel/handler also exists.

**Payload DTOs** (`dto/`):
- `MqttPositionPayload` — verbose internal schema (snake_case `@JsonProperty`): vehicle_id, timestamp,
  lat, lon, speed_kmh, heading_deg, ble_device_count, passengers, capacity, battery_voltage, firmware_version.
- `ObuPositionPayload` — compact ESP32 wire schema (id, ts, lat, lon, spd, hdg, occ, sat, bat, tech, rsrp);
  `.toMqttPositionPayload()` adapts it (ts=0 → now()), `@JsonIgnoreProperties(ignoreUnknown=true)`.

**Handler flow** (`mqtt/MqttMessageHandler` — `@ServiceActivator(inputChannel="mqttInputChannel")`):
1. **Parse** — `/obu/` in topic → parse `ObuPositionPayload` then adapt; else parse `MqttPositionPayload`.
2. **Validate** — vehicle_id regex, lat/lon in Cassino bbox (41.40–41.60, 13.70–14.00), timestamp age ≤ 300s. Bad → `securityAuditService.mqttInvalidPayload` and drop.
3. **vehicle_id → Bus** via `busRepository.findByCurrentVehicleId` (busId, wheelchairAccessible, numeroPosti).
4. **Bus + clock → active Trip** (`TripResolutionService.resolve`) → sets tripId, routeId, routeName.
5. **Adherence** (`ScheduleAdherenceService.processBusAdherence`) → last stop registered + delay.
6. **Stop identities** — `RouteMatchingService` resolves last-stop name + next stop after sequence.
7. **Cache** — `vehicleStateCache.update(vehicleId, entity)` → Redis.
8. **Time-series** — `writeToInflux` (Influx failures are caught and logged, non-fatal).

The order (3→4→5→6) is load-bearing: each step depends on the previous.

**To WebSocket/dashboard:** live state lives in Redis. The dashboard/OMNIMOVE read via
`GET /api/v1/vehicles` (JSON), `GET /api/v1/siri/vehicle-monitoring` (XML), or the push
**SSE** stream `GET /api/v1/telemetry/stream` — `DataExportController` builds SIRI XML from the
cache (`SiriMapper.toSiriFromCache`) every 5s (`@Scheduled`), heartbeat every 3s. NOTE: the
`spring-websocket` starter and `/ws/vehicles` permit rule exist, but there is **no STOMP config
class** — realtime delivery is currently SSE, not STOMP.

---

## 7. Security

- **Config** `config/SecurityConfig` — `@EnableWebSecurity` + `@EnableMethodSecurity`. Stateless
  (no HTTP session), CSRF disabled (JWT), CORS from `cassitrack.cors.allowed-origins`, BCrypt encoder,
  `DaoAuthenticationProvider`. Strict security headers: CSP (no `unsafe-inline`), HSTS, frame deny,
  Permissions-Policy, Cross-Origin-Resource-Policy (all added as OWASP/ZAP fixes, tagged V-xx in comments).
- **Public**: login page + its css/js, `POST /api/v1/auth/login`, GET on `/api/v1/vehicles/**`,
  `/stops/**`, `/routes/**`, `/siri/**`, `/journeys/**`, `/telemetry/latest`, `/telemetry/stream`, `/ws/**`, `/api/static/**`.
- **Role-gated**: `/api/v1/analytics/**` + `/api/v1/buses/**` + fleetmanager page → FLEET_MANAGER;
  `/api/v1/users/**` + `/api/v1/auth/register` + admin page → ADMIN; `/api/v1/ai/**` → FLEET_MANAGER **or** ADMIN.
  POST/PUT/DELETE on vehicles/stops/routes/journeys/buses → FLEET_MANAGER. Everything else authenticated.
  Swagger UI (`/api/docs/**`, `/api/swagger-ui/**`) requires auth. Authorities checked both with and without `ROLE_` prefix.
- **JWT flow**: `security/JwtUtil` — HS256, secret from `jwt.secret`, 1h expiry (`jwt.expiration-ms`).
  `security/JwtAuthenticationFilter` (before `UsernamePasswordAuthenticationFilter`) reads token from
  **httpOnly cookie `cassitrack_jwt` first**, then `Authorization: Bearer` header; validates + checks
  blacklist → sets `SecurityContext`. `UserDetailsServiceImpl` loads users by email.
- **Token blacklist**: `TokenBlacklistService` (Redis `jwt_blacklist:*`, TTL = remaining validity) —
  populated on logout.
- **Login attempts**: `LoginAttemptService` (Redis, 5 failures / 15-min lock) → 429 + `ACCOUNT_LOCKED` audit.
- **Audit**: `SecurityAuditService` (`@Slf4j(topic="SECURITY_AUDIT")`) dual-writes — a **masked** log
  line (PII masked) and an **async unmasked** DB row (`SecurityAuditEvent`). Events: REGISTRATION,
  LOGIN_SUCCESS/FAILURE, ACCOUNT_LOCKED, LOGOUT, ADMIN_USER_CREATED/UPDATED/DELETED, RATE_LIMIT_EXCEEDED, MQTT_INVALID_PAYLOAD.
- **SSE/NeTEx** endpoints are guarded separately by `X-Api-Key` header (constant-time compare vs `sse.api-token`).

---

## 8. Flyway Migrations (`db/migration/`) — latest = **V14**

`ddl-auto: validate`, `baseline-on-migrate: true`. Never edit an applied migration (checksum mismatch).

| V | Summary |
|---|---|
| V1 | Initial schema — PostGIS ext; `routes`, `stops` (+ location trigger/GIST index), `buses`, `trips`, `scheduled_stops`, `users`. |
| V2 | Seed demo master data (routes etc.). |
| V3 | Generate trips + timetable (PL/pgSQL). |
| V4 | Real MAGNI lines for Cassino (Linea A/B/C); wipes prior fake seed. |
| V5 | Refresh timetable with official times (3 rings from Piazza San Benedetto); adds `travel_seconds_from_prev`. |
| V6 | Drop `travel_seconds_from_prev` (redundant; derivable from arrival deltas). |
| V7 | US-01 bus registry fields: `route_id` (FK ON DELETE SET NULL), `status` (CHECK), `map_visible`. |
| V8 | `security_audit_events` table + grants (INSERT-only app user, `security_auditor` SELECT). |
| V9 | `route_shapes` table (road geometry polyline). |
| V10 | Route-shape data for Cassino lines (generated from `percorsiCassino.json`). |
| V11 | Set `buses.current_vehicle_id` to OBU radio ids (BUS1, BUS2, BUS2L, BUS3) so telemetry resolves. |
| V12 | Assign Liceo half-run trips to BUS2L (fixes NO_TRIP after V11 split). |
| V13 | Extend LINEA_2 / LINEA_2_LIC to a full day of departures. |
| V14 | Clear `current_vehicle_id` of MAGNI-002 (no on-board unit fitted). |

---

## 9. External Config / Env (`.env.example` + `docker-compose.yml`)

Env vars (from `.env.example`): `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (Postgres on **5433**),
`SPRING_REDIS_HOST/PORT/PASSWORD`, `JWT_SECRET` (≥32 chars), `INFLUX_URL/TOKEN/ORG/BUCKET`,
`SSE_API_TOKEN` (must match OMNIMOVE's `CASSITRACK_API_TOKEN`), `MQTT_BROKER_URL/USERNAME/PASSWORD`,
`CORS_ALLOWED_ORIGINS`, `WEATHER_API_KEY` (OpenWeatherMap, optional), `ANTHROPIC_API_KEY` (optional),
OBU: `MQTT_OBU_ENABLED/URL/CLIENT_ID/USERNAME/PASSWORD/TOPIC`. Also forwarded in compose:
`SERVER_SERVLET_CONTEXT_PATH` (`/cassitrack`), `COOKIE_SECURE`, `CSP_CONNECT_EXTRA`.

`docker-compose.yml` services (all bound to **127.0.0.1** only; images pinned by digest; secrets from
`.env`): `postgres` (postgis 16-3.4, 5433), `influxdb` (2.7, 8086), `redis` (7.2-alpine, 6379,
`--requirepass`), `mosquitto` (2.0, 1883, auth via `mosquitto/config/{mosquitto.conf,passwd,acl}`),
`cassitrack` (Spring Boot, 8280, `profiles: [full]` — infra-only by default so the app can run from
IntelliJ). All infra services have healthchecks; the app `depends_on` them being healthy.

Public server IP referenced in comments: `193.205.60.151` (8280 = cassitrack, 8180 = omnimove).

---

## 10. Conventions & Patterns to Follow

- **DTO boundary**: controllers return DTOs (`VehicleStatusDTO`, `StopArrivalDTO`, `BusDTO`, `UserDTO`,
  `BusTelemetryDTO`, `ChatResponse`), not entities (except a few management/list endpoints returning raw
  `Route`/`Stop`). DTO fields use `@JsonProperty` snake_case for external consumers (OMNIMOVE). Request
  bodies use dedicated DTOs / Java `record`s (`StopRequest`, `RouteRequest`, `RegisterRequest`, `LoginRequest`).
- **Lombok everywhere**: `@Data`/`@Getter/@Setter`, `@Builder`, `@NoArgs/@AllArgsConstructor`,
  `@RequiredArgsConstructor` for constructor injection, `@Slf4j` logging. Prefer constructor injection.
- **Mapping**: static mapper utilities — `SiriMapper` (Redis cache → SIRI), `dto/netex/*` DTOs assembled
  inline in `NetexController`, `UserDTO.from(...)`. `CrowdingService` is a static-only util (single source of truth for crowding vocab).
- **SIRI/NeTEx**: XML via `jackson-dataformat-xml` (`XmlMapper` / `produces=APPLICATION_XML_VALUE`).
  NeTEx builds a full `PublicationDelivery` frame tree (Site/Service/Timetable/Resource); IDs namespaced
  `CASSITRACK:<Type>:<id>`. SIRI OccupancyStatus + delay come from `CrowdingService`/`ScheduleAdherenceService` — keep them the single source of truth.
- **Server-derived truth**: never trust trip/route/stop/delay from the MQTT payload — derive them. The
  handler-step order in `MqttMessageHandler` is intentional and must be preserved.
- **`scheduled_stops` is authoritative** for timetable/ETA/adherence; `route_shapes` is presentation-only.
- **`buses.current_vehicle_id`** is the radio identity that MUST equal the id the hardware transmits
  (see V11/V14) — it's the join key for telemetry → bus.
- **Time**: business logic uses `ZoneId.of("Europe/Rome")`; Instants/epoch-seconds elsewhere.
- **Security-config comments** are annotated with V-xx / OWASP / ZAP fix IDs — respect them when editing
  the filter chain (rule order is first-match-wins). Validate all Influx/Flux query params (A03).
- **Graceful degradation**: Influx write failures, missing Anthropic key, missing weather key all
  degrade quietly rather than failing the request. Match this style for new external integrations.
- **Migrations are append-only**: add a new `V{n}__*.sql`; never modify an applied one.
