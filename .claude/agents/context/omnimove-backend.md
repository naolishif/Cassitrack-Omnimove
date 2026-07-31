# OMNIMOVE Backend — Architecture Briefing

> Persistent context for backend tasks. Path: `omnimove-backend/`.
> Multimodal journey planner for Cassino (Italy). Java 17, Spring Boot 3.5.5, Maven.
> Group `it.unicas`, artifact `omnimove`, version `0.1.0`. Root package `it.unicas.omnimove`.

## 1. Purpose & Domain

OMNIMOVE is the **passenger-facing multimodal journey planner** for the city of Cassino and
the UNICAS Engineering Campus (Folcara). It plans journeys across four modes — **BUS, BIKE,
SCOOTER, WALK** — ranks them by three profiles (FAST / BUDGET / ECO), and enriches each option
with weather advice, a Green Index (CO2), cost, and live bus delays.

It is one half of a **two-server microservice architecture**:
- **OMNIMOVE** :8180 (this repo) — passenger journey planning.
- **CASSITRACK** :8280 — MAGNI fleet monitoring (live GPS, telemetry, NeTEx static data).

OMNIMOVE consumes CASSITRACK **only via REST/SSE** — no shared DB, no code imports (see §5).
Bike/scooter pricing is provided by a fictional "Elerent" shared-mobility operator.

## 2. Tech Stack (pom.xml + application.yml)

- **Spring Boot 3.5.5** (parent BOM), **Java 17** (note: `maven.compiler.source/target` oddly set to `25` in pom, `java.version=17`).
- Pinned patched deps: `tomcat.version=10.1.55`, `spring-security.version=6.5.9`.
- **Web**: spring-boot-starter-web (MVC) **and** spring-boot-starter-webflux (WebClient — reactive HTTP to Cassitrack, Google, Anthropic, OpenWeatherMap).
- **Persistence**: spring-boot-starter-data-jpa; **PostgreSQL** driver 42.7.11 (runtime, primary DB `omnimovedb`); **H2** (runtime, present but console disabled — Postgres is the real DB). Hibernate `ddl-auto: validate` (schema owned by Flyway).
- **Flyway**: flyway-core + **flyway-database-postgresql** (Postgres split module for Flyway 10+). `baseline-on-migrate: true`, migrations at `classpath:db/migration`.
- **Redis**: spring-boot-starter-data-redis (`StringRedisTemplate`) — rate limiting, JWT blacklist, latest-telemetry snapshots.
- **InfluxDB**: influxdb-client-java 7.1.0 — journey analytics + bus telemetry time-series.
- **Security**: spring-boot-starter-security; **JJWT 0.11.5** (api/impl/jackson), BCrypt.
- **Mail**: spring-boot-starter-mail (Gmail SMTP) — verification + password reset.
- **XML/SIRI/NeTEx**: jackson-dataformat-xml (parse NeTEx PublicationDelivery + SIRI VehicleMonitoring).
- **Claude AI**: Anthropic Messages API via WebClient (model default `claude-haiku-4-5-20251001`).
- **springdoc-openapi 2.5.0** (webmvc-ui) — docs at `/api/docs`, Swagger UI at `/api/swagger-ui` (both require auth).
- **Actuator** — only `health`,`info` exposed; health details never shown.
- **Validation** (jakarta), **Lombok**.
- **OWASP dependency-check-maven 10.0.4** bound to `verify`, fails build on CVSS >= 7 (suppressions in `owasp-suppressions.xml`).
- Server: port **8180**, context-path **`/omnimove`** (env `SERVER_SERVLET_CONTEXT_PATH`).

## 3. REST Controllers

All under context-path `/omnimove`. Base paths below are relative to that.

### AuthController — `/api/v1/auth` (public where noted)
- `POST /register` — create TRAVELLER; validates strong password, sends verification email. Rate-limited (5/IP/hr).
- `POST /login` — email+password; locks after 5 failed attempts (429 + `suggestPasswordReset`); requires verified email; issues JWT as **httpOnly cookie `omnimove_jwt`** + also in `AuthResponse.token`.
- `GET /verify?token=` — verifies email, redirects to `omnimove-login.html?verified=…`.
- `POST /resend-verification` — rate-limited 3/email/hr.
- `POST /forgot-password` — sends reset link; always same message (anti-enumeration); 3/email/hr.
- `POST /reset-password` — set new password via token (1h expiry); clears failed attempts.
- `GET /reset-page?token=` — sanitizes token, redirects to `reset-password.html#<token>`.
- `POST /logout` — blacklists current token in Redis, clears cookie.
- `GET /me` — current user profile.
- `DELETE /account` — deletes own account, blacklists token.

### AdminController — `/api/v1/admin` (`@PreAuthorize hasAnyAuthority('ADMIN','ROLE_ADMIN')`)
- `GET /users`, `POST /users`, `DELETE /users/{id}` (cannot delete self or another admin), `GET /users/stats`.
- `GET /analytics?range=1W|1M|3M|6M|1Y` — InfluxDB aggregates (kpis, modeDistribution, modeByHour, greenIndexTrend, dayOfWeek, topRoutes).
- `GET /settings/google` / `PUT /settings/google` — toggle Google Maps feature flags `google.search`, `google.stop_eta`.

### JourneyController — `/api/v1/journeys` (TRAVELLER or ADMIN)
- `GET /stops` — active stops (id, name, lat, lon), capped 500.
- `POST /search` — body `JourneyRequest` → `JourneyResponse`; requires `origin_lat`/`dest_lat`; rate-limited 30/user/hr; records a raw-search InfluxDB point.
- `GET /stops/{stopId}/arrivals?limit=` — merges CASSITRACK real-time + scheduled arrivals, dedups by trip/vehicle, caps at 10; enriches delay via `TrafficAwareETAService` when `google.stop_eta` is ON, else Cassitrack retrospective delay; rate-limited 60/user/hr. Returns `List<StopArrivalResponse>`.
- `POST /select` — records a chosen mode into `journey_log` + InfluxDB; server recomputes Green Index/CO2 (never trusts client). Validates mode ∈ {BUS,WALK,BIKE,SCOOTER}, distance 0–200km, cost 0–50€.

### TravellerController — `/api/v1/traveller` (`@PreAuthorize hasAnyAuthority('TRAVELLER','ROLE_TRAVELLER')`)
- `PUT /me` — update name/email/password (email uniqueness + current-password check; emits audit events).
- `GET /preferences` / `PUT /preferences` — `UserPreferences` (per-user row).
- `GET /stats` — ecoPoints, co2SavedKg (vs CAR baseline), trips, spent30d.
- `GET /history` — last 20 journey logs, flagged with `isFavorite`.
- `GET /favorites` / `POST /favorites/toggle` — favourite routes keyed by mode+origin+dest.

### AiController — `/api/v1/ai` (TRAVELLER or ADMIN)
- `POST /chat` — body `ChatRequest` (message, language, history) → `ChatResponse`; delegates to `AiOrchestrationService`, personalises with logged-in user's history.

## 4. Services

- **JourneyPlannerService** (core, see algorithm below).
- **TrafficAwareETAService** — recomputes per-bus delay from each live vehicle's GPS to the stop using Google Maps live-traffic (`driving`). Only DEPARTED buses (with `vehicleId`) get a recomputed delay; scheduled entries returned untouched. Called by JourneyController only when `google.stop_eta` flag is ON.
- **GoogleMapsService** — Google **Distance Matrix API** client (`https://maps.googleapis.com/maps/api/distancematrix/json`). Returns `TrafficResult(durationSeconds, durationInTrafficSeconds, distanceMetres)`. Adds `departure_time`+`traffic_model=best_guess` only for `driving`. Key missing/failure → `Optional.empty()` (callers fall back to haversine/timetable).
- **GoogleApiSettingsService** — two runtime flags `google.search` / `google.stop_eta` persisted in Postgres `app_settings`, cached in-memory (`ConcurrentHashMap`), default **ON**. Write-through on `set()`. `snapshot()` for admin UI.
- **WeatherService** — OpenWeatherMap for Cassino,IT; 10-min in-memory cache; maps OWM code → `WeatherCondition` (CLEAR/CLOUDY/RAIN/HEAVY_RAIN/STORM/SNOW/HOT/WINDY); provides per-mode warnings + overall suggestion. No key → default CLEAR.
- **GreenIndexService** — EEA CO2 factors (g/passenger-km): WALK/BIKE/SCOOTER=0, BUS=68, CAR=170. `computeGreenIndex` = `100 - co2/(car_co2)*100` clamped [0,100]; `computeCo2Grams`.
- **JourneyEventService** — writes InfluxDB points: `journey_search_query` (raw searches, on `/search`) and `journey_search` (selections, tagged mode/day_of_week; fields hour, green_index, distance_km, count).
- **AnalyticsService** — Flux queries over Influx for the admin dashboard (mode distribution, mode×hour, green-index trend, KPI summary incl. selectionRate + co2SavedKg, day-of-week) + top routes from SQL `journey_log` (`findTopRoutes`). Range map 1W..1Y.
- **NetexImportService** — pulls NeTEx `PublicationDelivery` XML from Cassitrack (`X-Api-Key` header, XXE-hardened XmlMapper), `@Transactional`. On each run: **deletes then re-imports** scheduled_stops→trips→routes→stops→buses (flush between DELETE/INSERT), then StopPlaces (coords via PassengerStopAssignment), Buses (ResourceFrame), Lines→routes, LineString→`route_shapes` (wholesale replace, optional), ServiceJourneys (TimetableFrame, fallback ServiceFrame)→trips+scheduled_stops. Strips NeTEx namespace prefixes via `localId()`. **Triggered on `ApplicationReadyEvent`** with exponential backoff (10 attempts, 5s→60s) — no admin endpoint.
- **SiriConsumerService** — legacy SIRI VehicleMonitoring polling; **disabled** (`@Scheduled` commented) — superseded by SSE.
- **TelemetrySyncService** — subscribes on `ApplicationReadyEvent` to Cassitrack **SSE stream** `/telemetry/stream` (`X-Api-Key`), filters `telemetry-update` events, parses SIRI XML → `BusTelemetryDTO`, writes to **Redis** (`bus:latest:<id>`) and **InfluxDB** measurement `vehicle_position`. Auto-reconnects every 5s (infinite retry). The `@Scheduled(60000)` REST-poll variant is commented out.
- **EmailService** — Gmail SMTP HTML emails (verification 24h, reset 1h). Failures are logged, not thrown.
- **RateLimiterService** — Redis INCR+EXPIRE sliding window, **fail-open** if Redis down. Buckets: register 5/IP/hr, resend 3/email/hr, forgot 3/email/hr, journey-search 30/user/hr, stop-arrivals 60/user/hr.
- **Security services**: `JwtUtil` (HS256, subject=email, 1h expiry), `JwtFilter` (OncePerRequest; resolves token from cookie first, then `Authorization: Bearer`; checks blacklist), `UserDetailsServiceImpl` (loads by email, authority = role), `TokenBlacklistService` (Redis `jwt_blacklist:<token>` with TTL = remaining validity), `SecurityAuditService` (dual sink: masked SLF4J `SECURITY_AUDIT` log + unmasked async DB insert).
- **AiOrchestrationService** — Claude assistant. Detects IT/EN by word-scoring; builds a live-data context block (active buses, per-stop ETAs from Cassitrack, weather, logged-in user's recent journeys + preferred mode + CO2 saved); calls Anthropic Messages API (`x-api-key`, `anthropic-version: 2023-06-01`, `max_tokens=1024`) with multi-turn history; graceful canned fallback if the API fails (still `success=true`).

## 5. CassitrackClient — how OMNIMOVE consumes CASSITRACK

`client/CassitrackClient.java` — a WebClient wrapper, base URL `cassitrack.api.base-url`
(default `http://localhost:8280/cassitrack/api/v1`). **The only path for fleet data.** All methods
catch exceptions and return empty lists so OMNIMOVE degrades gracefully when Cassitrack is offline.
- `GET /vehicles` → `VehicleDTO[]` (getActiveVehicles).
- `GET /stops/{stopId}/arrivals` → `StopArrivalDTO[]` (real-time).
- `GET /stops/{stopId}/schedule` → `StopArrivalDTO[]` (timetable).
- `isAvailable()` — probes `/vehicles`.

Other Cassitrack ingress (separate from this client):
- **NeTEx static import**: `NetexImportService` GETs `cassitrack.netex.url` (default `…/api/static/netex`) as XML, auth header **`X-Api-Key`** = `cassitrack.api.token`.
- **Live telemetry**: `TelemetrySyncService` subscribes to SSE `{{base-url}}/telemetry/stream`, header `X-Api-Key`.

Data shapes: `VehicleDTO{vehicle_id, lat, lon, speed_kmh, schedule_status, crowding_level, estimated_passengers}`;
`StopArrivalDTO{vehicle_id, trip_id, route_id, route_name, route_short_name, estimated_arrival, scheduled_arrival, schedule_status, delay_minutes, crowding_level, delay_stop_name}` (Instants). `dto/netex/*` and `dto/siri/Siri` model the XML.

## 6. Data Model

**JPA entities** (`model/`, table names in parens):
- **User** (`users`) — id, email(unique), password(WRITE_ONLY), name, role, verified, verification_token(+expiry), failed_login_attempts, reset_password_token(+expiry).
- **UserPreferences** (`user_preferences`) — PK = user_id (1:1 with User); defaultJourneyMode, avoidHighOccupancy, showWalking, preferBikeOverBus, notifyDelays, notifyTicketExpiry, notifyEcoTip, onlyBusWhenRaining(default true).
- **FavoriteRoute** (`favorite_route`) — id, userId, mode, originName, destName, createdAt; UNIQUE(user,mode,origin,dest).
- **JourneyLog** (`journey_log`) — id, userId, mode, distanceKm, costEuros, co2Grams, greenIndex, originName, destName, createdAt.
- **AppSetting** (`app_settings`) — key(`setting_key`), value(`setting_value`), updatedAt. Runtime config store.
- **SecurityAuditEvent** (`security_audit_events`) — id, eventType, email, ipAddress, additionalInfo, createdAt (unmasked; INSERT-only for app user).
- **Transit graph** (imported from NeTEx): **Route** (`routes`, id String e.g. "LINEA_1", longName, shortName, active) — **Trip** (`trips`, id String) `@ManyToOne` Route + `@ManyToOne` Bus — **ScheduledStop** (`scheduled_stops`, id, `@ManyToOne` Trip via `trip_id`, stopId, stopSequence, arrivalSeconds=secs-from-midnight) — **Stop** (`stops`, id String, name, lat, lon, active, createdAt) — **Bus** (`buses`, busId, licensePlate unique, numberSeats, placeDisablePeople, available, currentVehicleId) — **RouteShape** (`route_shapes`, composite PK (routeId,seq), lat, lon, isStop — road polyline, presentation only).

**Redis** (`StringRedisTemplate`): rate-limit counters `rl:*`, JWT blacklist `jwt_blacklist:<token>`, latest telemetry `bus:latest:<busId>` (JSON).

**InfluxDB** (org `omnimove_org`, bucket `omnimove_bucket`): measurements `journey_search_query`, `journey_search` (analytics), `vehicle_position` (telemetry: lat/lon/speed_kmh/delay/trip_id/passengers/capacity/next_stop…).

## 7. Journey Planning DTOs (exact fields, snake_case JSON via `@JsonProperty`)

**JourneyRequest**: `origin_lat, origin_lon, origin_name, dest_lat, dest_lon, dest_name, origin_is_gps, user_id, origin_stop_id, dest_stop_id, departure_time` ("HH:mm" Europe/Rome, blank=now), `messages`, `modes` (List; note `modes` has no @JsonProperty).

**JourneyResponse**: `options` (List<JourneyOption>), `messages`, `origin`, `destination`, `total_options`, `realtime_available`, `weather_summary`, `temperature_celsius`.

**JourneyOption**: `mode`, `mode_label`, `duration_minutes`, `distance_metres`, `cost_euros`, `green_index`, `co2_grams`, `eta_minutes`, `summary`, `weather_warning`, `weather_suggestion`, `legs`, `delay_minutes`, `delay_status`, `delay_real_time`, `delay_at_stop`, `delay_label`, `score_fast`, `score_budget`, `score_eco`.

**JourneyLeg**: `mode` (WALK/WAIT/BUS/BIKE/SCOOTER), `from`, `to`, `duration_minutes`, `distance_metres`, `stop_coords` (List<double[]> polyline), `instruction`.

### JourneyPlannerService algorithm (`plan()`)
1. Fetch weather + Cassitrack availability. Resolve requested modes (default BUS,BIKE,SCOOTER,WALK). Apply user prefs: drop WALK if `showWalking=false`; if `preferBikeOverBus`, defer BUS (compute it only as fallback when BIKE unavailable).
2. For each mode build a `JourneyOption`:
   - **WALK/BIKE/SCOOTER**: one Google Distance Matrix call (`walking`/`bicycling`) for real road distance+time; if Google returns empty the option is **excluded** (no haversine fallback for these). SCOOTER derives time from distance at 20 km/h. Cost from Elerent unlock+per-minute.
   - **BUS**: find nearest origin/dest stops (`findNearestStopId` = haversine top-3 then Google walking to pick closest). Look for a direct line (`findLinesConnecting`); else best single transfer (`findBestTransfer` native SQL: origin→X→dest, different routes). Walk-to-stop leg (Google, only if `origin_is_gps`). Wait time from Cassitrack live ETA for the exact line (`waitMinutesForLine`), falling back to static timetable (`arrivalSeconds`). Per-segment bus time via `busTimeBySegments`: when `google.search` ON, one Google `driving` call per consecutive stop pair using live traffic at projected boarding time; else static timetable deltas. Ring-line aware (picks the shortest origin→dest occurrence pair). Bus leg polyline sliced from `route_shapes` (`roadPathAlong`, forward-scan matching stops to vertices) or stop-to-stop fallback. Live delay only reported for "now" searches.
3. Post-filter: if raining and `onlyBusWhenRaining` (default true), remove non-bus options; otherwise sort bus-first when raining, then by duration.
4. **`computeScores`**: min-max normalise duration/cost/greenIndex across the *final* option set; weighted scores per profile (time,cost,env): FAST {.70,.10,.20}, BUDGET {.20,.70,.10}, ECO {.20,.10,.70}. Time/cost inverted (lower=better). Ties → 0.5. Frontend sorts by the active chip's score.

## 8. Security

- **JWT**: HS256 (`jwt.secret`, 1h `jwt.expiration-ms`). Subject=email. Delivered as **httpOnly cookie `omnimove_jwt`** (SameSite=Strict; `Secure` gated by `COOKIE_SECURE` env) **and** returned in body for header clients. `JwtFilter` reads cookie-first then Bearer; stateless sessions.
- **Password policy**: >=8 chars, upper+lower+digit+special (regex). BCrypt encoder.
- **Account lockout**: 5 failed logins → 429 + suggest reset; cleared on success/reset. Email must be verified before login.
- **Token revocation**: logout / delete-account blacklist the token in Redis until natural expiry.
- **CORS**: `SecurityConfig.corsSource()` — origins from `omnimove.cors.allowed-origins`, methods GET/POST/PUT/DELETE/OPTIONS, headers Authorization/Content-Type/Accept, **allowCredentials=false**. NOTE: a **second, conflicting** `CorsConfig.corsFilter()` bean exists (origins localhost:3000/8000/null, allowCredentials=true, all headers) — legacy/duplicate, be careful when touching CORS.
- **Headers**: CSP (self + jsdelivr for scripts, OSM tiles for img), HSTS 1yr, frameOptions deny, X-Content-Type-Options. CSRF disabled (stateless JWT).
- **Authorization** (`SecurityConfig`): auth endpoints + login page/static assets public; `/api/docs`,`/api/swagger-ui` require auth; `/api/v1/admin/**` + admin html → ADMIN; `/api/v1/traveller/**` → TRAVELLER; `/api/v1/journeys/**`,`/api/v1/ai/**` → any authenticated; `actuator/health|info` public, rest of actuator ADMIN. `@EnableMethodSecurity` + `@PreAuthorize` on admin/traveller controllers.
- **Audit** (`SecurityAuditService`): masked PII to `SECURITY_AUDIT` log, full PII async-inserted to `security_audit_events`. Events: registration, login success/failure, account locked/deleted, logout, email verified, password reset (+requested), weak password rejected, admin user created/deleted/listed, profile email/password/update changed, verification resent, rate-limit exceeded. App DB user has INSERT-only on the table.
- **Email flows**: verify link `…/api/v1/auth/verify?token=` (24h); reset link `…/api/v1/auth/reset-page?token=` (1h). Forgot-password is enumeration-safe.

## 9. Flyway Migrations (`db/migration`, latest = V15)

- **V1** initial schema: routes, trips, stops, scheduled_stops, users, favorite_stops, favorite_trips + indexes.
- **V2** seed admin@omnimove.it / traveller@omnimove.it (weak passwords — later fixed by V12).
- **V3** stops table fix (active, created_at, description; rename latitude/longitude → lat/lon); routes.active.
- **V4** 30 simulation users `*@sim.omnimove.it`.
- **V5** buses table + `trips.bus_id` FK.
- **V6** journey_log.
- **V7** favorite_route (UNIQUE user,mode,origin,dest).
- **V8** email verification / password reset columns; marks pre-existing users verified.
- **V9** user_preferences.
- **V10** `user_preferences.only_bus_when_raining`.
- **V11** **app_settings** + seeds `google.search=true`, `google.stop_eta=true` (runtime feature flags edited via admin `PUT /settings/google` and read by `GoogleApiSettingsService`).
- **V12** replace weak seeded passwords (admin `Admin_OmniMove2026!`, traveller `Traveller_OmniMove2026*`, sim users `SimUser_OmniMove2026!`).
- **V13** security_audit_events + `security_auditor` read-only role + INSERT-only grant to `omnimove` app user.
- **V14** route_shapes table (road polyline; lat/lon CHECKs; local copy of Cassitrack geometry).
- **V15** route_shapes seed data for Cassino lines (LINEA_1 …), each INSERT guarded by EXISTS on `routes` (routes arrive at runtime via NeTEx import, so a fresh DB skips seeding and relies on NetexImportService).

Note: routes/stops/trips/scheduled_stops/buses/route_shapes are **not authoritatively seeded** by migrations — they are (re)populated at startup from Cassitrack NeTEx. `spring.sql.init.mode: never`.

## 10. External Config, Env Vars, Docker & Conventions

**Env vars** (`.env.example`, wired in `docker-compose.yml`):
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `SPRING_REDIS_PASSWORD` (+ `SPRING_DATA_REDIS_HOST/PORT`),
`JWT_SECRET` (>=32 chars), `INFLUX_URL/INFLUX_TOKEN` (org `omnimove_org`, bucket `omnimove_bucket`),
`MAIL_USERNAME/MAIL_PASSWORD` (Gmail app password), `CASSITRACK_URL/CASSITRACK_API_TOKEN/CASSITRACK_NETEX_URL`,
`APP_BASE_URL` (email links), `CORS_ALLOWED_ORIGINS`, `COOKIE_SECURE`, `SERVER_SERVLET_CONTEXT_PATH` (default `/omnimove`),
optional `WEATHER_API_KEY`, `GOOGLE_MAPS_API_KEY`, `ANTHROPIC_API_KEY` (`ANTHROPIC_API_MODEL` optional).

**docker-compose services** (all bound to `127.0.0.1` only):
- `omnimove-postgres` (postgres 15-alpine, digest-pinned) :5432, DB `omnimovedb`, healthcheck.
- `omnimove-influxdb` (2.7-alpine) :8087→8086, auto-setup org/bucket/token.
- `omnimove-redis` (7-alpine) :6380→6379, **password-protected** (`--requirepass`).
- `omnimove` (Spring Boot, built from Dockerfile) :8180 — under **`profiles: [full]`** (`docker compose --profile full up`). Internally points at `omnimove-influxdb:8086`, `omnimove-redis:6379`.

**Conventions a backend engineer must follow**:
- CASSITRACK data **only** through `CassitrackClient` / NeTEx import / SSE — never a shared DB or direct import. Design for Cassitrack being offline (return empty, degrade).
- Schema changes = **new Flyway migration** (files are checksum-immutable; `ddl-auto=validate` so entities must match DDL exactly). Don't edit past Vn files.
- Transit tables are runtime-populated from NeTEx; don't hardcode routes/stops in migrations.
- External APIs (Google/Weather/Anthropic) must **degrade gracefully** when the key is missing — return `Optional.empty()`/defaults, never fail the request.
- Google Maps usage is gated by `google.search` / `google.stop_eta` runtime flags — respect them in new code paths.
- Never trust client-supplied Green Index / CO2 / cost — recompute server-side (see `/journeys/select`).
- Security-sensitive actions must emit a `SecurityAuditService` event (masked log + unmasked DB).
- JSON API fields are **snake_case** (`@JsonProperty`). Java is camelCase.
- WebClient (WebFlux) is used for all outbound HTTP even though the app is MVC; `.block()` is used deliberately.
- Watch the **duplicate CORS bean** (`CorsConfig` vs `SecurityConfig`) when changing CORS.
