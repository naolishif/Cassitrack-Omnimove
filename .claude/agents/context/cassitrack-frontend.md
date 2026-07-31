# CASSITRACK Frontend — Architecture Briefing

Persistent reference for the CASSITRACK dashboard frontend. Read this before any
frontend task. Everything below is drawn from the actual code under
`cassitrack-backend/src/main/resources/static/`.

## 1. Overview

- **No-build, vanilla HTML/CSS/JS.** No framework, no bundler, no npm. Each page
  is one `.html` + one `.css` + one `.js` triplet, served statically by Spring
  Boot from `src/main/resources/static/`.
- **Context path:** the app lives under the `/cassitrack/` prefix (Nginx + Spring
  context-path). All API calls use the constant `API = '/cassitrack/api/v1'`
  (login.js names it `CASSITRACK`). Page-to-page links are **relative**
  (`cassitrack-fleetmanager.html`, no leading slash) so they resolve under the
  context path.
- **CDN libraries** (only where needed, pinned + SRI hashes):
  - Leaflet 1.9.4 (`leaflet.css` + `leaflet.js`) — fleetmanager only.
  - Chart.js 4.4.4 (`chart.umd.js`) — fleetmanager only (analytics/admin use
    hand-rolled CSS bar charts, not Chart.js).
  - Google Fonts: **Syne** (600/700/800, display) + **DM Mono** (400/500, mono).
  - Map tiles: CARTO dark basemap `dark_all` (not a JS dependency).
- **Pages:** `cassitrack-login`, `cassitrack-fleetmanager`,
  `cassitrack-analytics`, `cassitrack-admin`. A `cassitrack-driver.html` is
  referenced in the role-routing table but **does not exist** in this folder.

## 2. Pages

### login (`cassitrack-login.*`)
- **Purpose:** authenticate, then redirect by role.
- **DOM:** `.container > .logo + .card`; card holds `#msg` status line and
  `#loginForm` (`#loginEmail`, `#loginPassword`, `#loginBtn`).
- **JS:** `handleLogin(e)` POSTs to `${CASSITRACK}/auth/login` with
  `{email, password}`. On success reads `data.token` + `data.role`, then
  `window.location.replace(roleRoutes[role])` after a 1s delay. `showMsg(text,
  type)` toggles `.msg.ok` / `.msg.err`.
- **`roleRoutes`:** `FLEET_MANAGER → cassitrack-fleetmanager.html`,
  `ADMIN → cassitrack-admin.html`, `DRIVER → cassitrack-driver.html`.
- **Auth model note:** despite `data.token` being read, the token is **not**
  stored client-side — the server sets an httpOnly cookie (see §3). The form
  handler is bound via `addEventListener` (no inline `onsubmit`) for CSP.

### fleetmanager (`cassitrack-fleetmanager.*`)
The largest page (~1900 lines JS). Single HTML with three top-nav views toggled
by `.main-view` / `.active-view`; nav buttons `#topBtnFleetMonitor`,
`#topBtnAnalytics`, `#topBtnDataMgmt`, `#topBtnLogout`. `switchTopView(viewId,
btn)` swaps the active view (and calls `map.invalidateSize()` for the map view).

**View A — Fleet Monitor (`#fleet-monitor`)**
- Full-screen Leaflet `#map` + right `.sidebar`.
- Filters: `#routeFilter` (route), `#serviceFilter` (all/in/out),
  `#delayFilter` range (min delay). Live counters `#anActiveRatio`,
  `#anPassengers`. Vehicle list container `#vehicle-list`.
- **Map init** (`window load`): `L.map('map', {center:[41.497,13.822], zoom:14})`
  + CARTO dark tile layer. Fetches `/vehicles/fleet-size`, then `/routes` to draw
  each route: a colored `L.polyline` (solid if `route.path` road geometry exists,
  else dashed stop-to-stop), an invisible wide `hit` polyline for clicks
  (`toggleRouteFilter`), and one `L.circleMarker` per stop (accumulated in
  `stopMap`, popup lists serving lines). Route colors: golden-angle
  `hsl((i*137.5)%360,70%,55%)`.
- **Polling:** `fetchVehicles()` GETs `/vehicles` every `REFRESH = 15000`ms.
  `updateMap(vehicles)` upserts `L.marker` per `vehicle_id` (custom `busIcon`
  divIcon, status via `data-status`), popup via `popupV(v)`.
  `updateFleet(all)` rebuilds `.vcard` list (or focus detail via
  `buildBusDetail`) and the header counters. `selV(id)` selects/focuses a bus
  (flyTo + open popup); `updateRouteVisibility()` decides which routes/stops draw.
- **Bus map visibility:** single source of truth `buses.map_visible` (delivered
  as `map_visible` on each vehicle). Toggled via `👁` (`toggleBusVisibility`) or
  the Data Management switch (`dmToggleVisibility`), both `PUT
  /buses/{busId}/visibility?visible=<bool>`; optimistic with revert.

**View B — Analytics (`#analytics-view`)**
- Filter bar: period presets (`.preset-btn[data-preset]`: today/yesterday/week/
  month/lastmonth/custom), custom `#filterFrom`/`#filterTo`, route multi-select
  dropdown (`#routeDropdown` + `#routeCheckList` checkboxes + `#selectedRoutePills`),
  `#filterBus`, `#filterGroupBy` (hour/day), `#applyFiltersBtn`.
- State object `activeFilters`. `buildFilterParams()` → query string
  (`startTime`, `endTime`, `routeIds` CSV, `busId`, `groupBy`).
  `presetToRange(preset)` computes ISO start/stop.
- **Chart.js:** two bar charts via `renderBarChart(canvasId, instanceRef, data,
  tooltipUnit)` — `#delayChart` (`loadDelayByRoute`, `/analytics/delay-by-route`)
  and `#routesChart` (`loadPassengersByRoute`, `/analytics/passengers-by-route`).
  Data shape is `{routeKey: {slotLabel: value}}`; labels from
  `buildChartLabels()` using `scheduleFirstHour`/`scheduleLastHour`
  (`/analytics/operating-hours`). Empty states: sibling `#<canvasId>Empty` div.
- **KPIs:** `#kpiDelay`, `#kpiPassengers`, `#kpiCo2` (`/analytics/co2`),
  `#kpiOnTimePct`/`#kpiOnTime` + CSS conic-gradient `.fake-donut`
  (`updateFleetDonut` from `/vehicles`; on-time % from `/analytics/adherence`).
- Route dropdown data comes from `/analytics/routes` (`loadRoutes`, note: this
  name shadows the CRUD `loadRoutes` — see §7 caveat).

**View C — Data Management (`#data-management`)**
- Sub-tabs `#dmTabBuses` / `#dmTabStops` / `#dmTabRoutes` → `switchDmPanel`.
- **Buses panel (US-01 registry):** toolbar (`#dmSearch` debounced 250ms,
  `#dmFilterStatus`, `#dmFilterRoute`, `#dmResetBtn`, `#dmNewBtn`), table
  `#dmTableBody`, count `#dmCount`. `dmLoadBuses()` GETs `/buses?search&status&
  routeId`; `dmRenderTable` renders rows with registry `status` pill
  (`dmStatusPill`) vs. live pill (`dmLivePill`, read from `vehicleData`).
  Right-side drawer (`#dmDrawer`) for create/edit (`dmSave` → POST/PUT `/buses`),
  delete confirm modal (`#dmConfirmBackdrop`, `dmDoDelete` → DELETE `/buses/{id}`).
  Route options from `/buses/route-options` (`dmLoadRoutes`).
- **Stops panel:** `loadStops` (`/stops`), inline `#stForm`, `saveStop`
  (POST/PUT `/stops`), `deleteStop` (DELETE), table `#stTableBody`.
- **Routes panel:** `loadRoutes` [CRUD one] (`/routes/manage`), `#rtForm`,
  `saveRoute` (POST/PUT `/routes`, color sent hex without `#`), `deleteRoute`
  (DELETE), table `#rtTableBody`.

### analytics (`cassitrack-analytics.*`)
- **Purpose:** read-only fleet analytics dashboard (separate simpler page from
  the fleetmanager Analytics view). Gated to FLEET_MANAGER/ADMIN server-side.
- **DOM:** header with `#statusDot`/`#statusText`/`#refreshBtn`/`#logoutBtn`;
  `#statsGrid` (6 stat cards), `#adherenceChart`, `#hoursChart` (CSS bars),
  `#vehicleTable`.
- **JS:** `loadAll()` = `Promise.all([loadSummary, loadAdherence,
  loadBusiestHours])`, auto-refresh every **30000ms**.
  - `loadSummary` → `/analytics/summary`.
  - `loadAdherence` → `/analytics/adherence` (status bars + vehicle table).
  - `loadBusiestHours` → `/analytics/busiest-hours` (hourly CSS bars).
- Charts here are hand-built inline-styled `<div>` bars, **not** Chart.js.

### admin (`cassitrack-admin.*`)
- **Purpose:** user CRUD. Reached only by ADMIN role.
- **DOM:** `#userModal` (add/edit form: `#modalTaxId/Name/Surname/Email/
  Telephone/Password/Role`), `#deleteModal`, `#searchInput`, users table body
  `#usersTable`, toolbar `#addUserBtn/#editBtn/#deleteBtn/#logoutBtn`.
- **JS:** `loadUsers()` GET `/users` (called on page load). `saveUser()` POST
  `/users` (create) or PUT `/users/{id}` (edit); password only sent if typed, as
  `passwordHash`. `isPasswordSecure()` enforces 8+ chars, upper/lower/digit/
  special. `confirmDeleteUser()` DELETE `/users/{id}`. `selectRow` enables
  edit/delete. Client-side `#searchInput` filters rows by hiding non-matches.

## 3. Shared client patterns

- **JWT / auth:** login server sets an **httpOnly cookie**; the frontend never
  reads/writes the token and never sets an `Authorization` header. Every fetch is
  same-origin, so the cookie is sent automatically. (Legacy comments throughout
  document removed `localStorage.cassitrack_token` code — do NOT reintroduce it.)
- **No client-side route guard.** Access control is server-side (`SecurityConfig`
  gates each `.html` by role, returning 403 before the JS runs). Pages do not
  bounce unauthenticated users themselves.
- **Logout:** identical `logoutUser()` on every authed page — `POST
  /cassitrack/api/v1/auth/logout` (blacklists the JWT, clears cookie) then
  `window.location.href = 'cassitrack-login.html'`.
- **`escHtml(s)`** — duplicated verbatim in fleetmanager.js, analytics.js,
  admin.js. Escapes `& < > " '` before any `innerHTML` insertion (XSS defense).
- **CSP-driven conventions (important):**
  - No inline `onclick=`/`onsubmit=` — all handlers bound via
    `addEventListener`; dynamically-generated elements use **event delegation**
    on a stable parent + `data-*` attributes (e.g. `data-route-id`,
    `data-edit-id`, `data-del-id`, `data-vis-id`, `data-act`).
  - No inline `style="..."` for **dynamic** values in fleetmanager: markup carries
    `data-fg` / `data-bg` / `data-width-pct`, and `applyDynStyles(root)` applies
    them via CSSOM after insertion (and on Leaflet `popupopen`). Fixed-domain
    values (schedule status) use `data-status` + static CSS attribute selectors.
  - **Exception:** analytics.js and admin.js DO still use inline `style="..."`
    strings (e.g. `style="color:${col}"`). CSP hardening was applied to
    fleetmanager first; follow the fleetmanager pattern for new work.
- **Loading / empty / error states:**
  - Fleetmanager splash `#loading` (`.gone` after 1500ms).
  - Tables/lists print `Loading…` then either data, a filtered-empty message, or
    a failure message (`Could not load…` / `Failed to load…`).
  - Header status dot pattern: `.dot.dot-green` (ok) vs `.dot.dot-red` +
    "Backend offline" on fetch failure.
  - Charts hide the canvas and show `#<id>Empty` when there is no data.

## 4. Live-update mechanism

**There is no WebSocket / SSE / STOMP anywhere in the frontend** (verified: zero
matches for `WebSocket`, `SockJS`, `EventSource`, `/ws`). "Live" updates are
**HTTP polling**:

- Fleetmanager: `setInterval(fetchVehicles, 15000)` → GET `/vehicles`.
  `updateMap` moves/creates markers, `updateFleet` refreshes the sidebar, header
  shows "N buses active" and a `#hTime` "next in Xs" countdown.
- Analytics page: `setInterval(loadAll, 30000)`.
- The optimistic visibility toggles reconcile against the next poll (server
  payload replaces local `vehicleData` wholesale, so local state can't drift).

If real-time push is ever added, this is the section to update.

## 5. API contract as consumed by the client

Base: `/cassitrack/api/v1`. Field names below are the **exact JSON keys the JS
reads** — keep backend DTOs in sync with these.

| Method | Endpoint | Consumed by | JSON fields read |
|---|---|---|---|
| POST | `/auth/login` | login | resp `token`, `role` |
| POST | `/auth/logout` | all authed pages | — |
| GET | `/vehicles` | fleetmanager, analytics | `vehicle_id`, `lat`, `lon`, `route_id`, `route_name`, `trip_id`, `schedule_status`, `delay_minutes`, `delay_stop_name`, `speed_kmh`, `estimated_passengers`, `occupancy_pct`, `crowding_level`, `last_stop_name`, `next_stop_name`, `upcoming_stop_name`, `eta_seconds`, `numero_posti`, `wheelchair_accessible`, `map_visible`, `bus_id`/`busId` |
| GET | `/vehicles/fleet-size` | fleetmanager | `total` |
| GET | `/routes` | fleetmanager (map) | `id`, `name`, `longName`, `path[].{lat,lon}`, `stops[].{id,name,lat,lon}` |
| GET | `/routes/manage` | fleetmanager DM | `id`, `shortName`, `longName`, `color`, `active` |
| POST/PUT/DELETE | `/routes`, `/routes/{id}` | fleetmanager DM | body `{id, shortName, longName, color, active}` |
| GET | `/stops` | fleetmanager DM | `id`, `name`, `lat`, `lon`, `active`, `description` |
| POST/PUT/DELETE | `/stops`, `/stops/{id}` | fleetmanager DM | body `{id, name, lat, lon, description, active}` |
| GET | `/buses?search&status&routeId` | fleetmanager DM | `busId`, `targa`, `numeroPosti`, `routeId`, `routeName`, `status`, `currentVehicleId`, `wheelchairAccessible`, `mapVisible` |
| GET | `/buses/{id}` | fleetmanager DM | same as above |
| GET | `/buses/route-options` | fleetmanager DM | `id`, `label` |
| POST/PUT | `/buses`, `/buses/{id}` | fleetmanager DM | body `{targa, numeroPosti, routeId, status, currentVehicleId, wheelchairAccessible, mapVisible}` |
| PUT | `/buses/{id}/visibility?visible=<bool>` | fleetmanager | — |
| DELETE | `/buses/{id}` | fleetmanager DM | — |
| GET | `/analytics/summary` | analytics page | `active_buses_now`, `on_time_percentage`, `on_time_count`, `late_count`, `early_count`, `buses_today`, `position_reports_today` |
| GET | `/analytics/adherence` | fleetmanager + analytics | `total_active`, `status_counts.{ON_TIME,EARLY,...}`, `vehicles[].{vehicle_id,status,speed_kmh,delay_minutes,crowding}` |
| GET | `/analytics/busiest-hours` | analytics page | `hourly_activity[].{hour,count}`, `peak_hour` |
| GET | `/analytics/routes` | fleetmanager analytics | `id`, `shortName`, `longName` |
| GET | `/analytics/operating-hours` | fleetmanager analytics | `_global.{firstHour,lastHour}` |
| GET | `/analytics/passengers-by-route?…` | fleetmanager analytics | `{routeKey:{slot:value}}` |
| GET | `/analytics/delay-by-route?…` | fleetmanager analytics | `{routeKey:{slot:value}}` |
| GET | `/analytics/co2?…` | fleetmanager analytics | `co2_saved_kg`, `passenger_km` |
| GET | `/users` | admin | `id`, `taxId`, `name`, `surname`, `email`, `telephone`, `role` |
| POST/PUT/DELETE | `/users`, `/users/{id}` | admin | body `{taxId, name, surname, email, telephone, role, passwordHash?}` |

**Naming caveat:** live `/vehicles` uses **snake_case** keys; the `/buses`
registry and `/routes/manage` use **camelCase**. Do not confuse them.

Analytics query params: `startTime`, `endTime` (ISO), `routeIds` (CSV), `busId`,
`groupBy` (`hour`|`day`).

## 6. CSS / design tokens

Dark theme, defined as CSS custom properties in `:root` of each stylesheet.
Fleetmanager/login token set:

```
--bg:#07090F  --panel:#0F1623  --panel2:#141E2E  --border:#1A2744
--accent:#3B82F6  --cyan:#06B6D4  --green:#22C55E  --red:#EF4444
--dim:#4B5563   --text:#E2E8F0
--mono:'DM Mono',monospace   --display:'Syne',sans-serif
```

Analytics adds `--amber:#F59E0B` and `--purple:#8B5CF6` (used for the extra stat
cards). Status color scale (JS constants `SC`/`STATUS_COL`): ON_TIME `#22C55E`,
SLIGHTLY_LATE / NO_TRIP `#F59E0B`, SIGNIFICANTLY_LATE `#EF4444`, EARLY `#06B6D4`,
UNKNOWN `#4B5563`. Chart palette `CHART_COLORS =
['#3B82F6','#06B6D4','#8B5CF6','#22C55E','#F59E0B','#EF4444']`.

Typography: **Syne** for display/UI, **DM Mono** for labels, values, numbers.
Uppercase mono micro-labels (`.label`, `.analytics-label`, letter-spacing) are
the recurring stylistic motif.

**Class/ID conventions:**
- IDs are `camelCase` for interactive elements (`#loginBtn`, `#applyFiltersBtn`).
- Classes are `kebab-case`, prefixed by feature area:
  - `dm-*` = Data Management buses registry (new US-01 UI).
  - `bm-*` = legacy CRUD forms/tables reused by Stops & Routes panels.
  - `vpop-*` / `vcard-*` / `bd-*` = vehicle popup / sidebar card / bus-detail.
  - `kpi-*`, `chart-*`, `filter-*`, `preset-*`, `route-*` = analytics view.
  - `stat-card`, `status-*`, `bar-*` = analytics page.
- Logo markup is always `CASSI<span>TRACK</span>` (span colored `--accent`).

## 7. Adding a new page consistently

1. Create the triplet `cassitrack-<name>.{html,css,js}` in `static/`. Keep the
   1:1 page↔files rule; no shared JS module exists.
2. In `<head>`: viewport meta, the Google Fonts `<link>` (Syne + DM Mono), then
   `<link href="cassitrack-<name>.css">`. Add Leaflet/Chart.js CDN tags (pinned
   versions + SRI, `crossorigin="anonymous"`) **only if** the page uses them.
   Script tag goes at end of `<body>`: `<script src="cassitrack-<name>.js">`.
3. Copy the `:root` token block and the `escHtml()` helper (they are duplicated
   per page by design). Always `escHtml()` any API string before `innerHTML`.
4. Define `const API = '/cassitrack/api/v1';` and call it same-origin — **never**
   add an `Authorization` header or touch `localStorage` for auth; the httpOnly
   cookie handles it. Add a `logoutUser()` that POSTs `/auth/logout` then
   redirects to `cassitrack-login.html`.
5. Wire all events with `addEventListener`; for runtime-generated rows use
   delegation + `data-*` (CSP forbids inline handlers). Prefer the fleetmanager
   `data-fg/data-bg/data-width-pct` + `applyDynStyles` pattern over inline
   `style=""` for dynamic values.
6. Provide loading / empty / error states (spinner or "Loading…", explicit
   failure text, and a `.dot-green`/`.dot-red` status indicator if it polls).
7. If the page is role-restricted, add the server-side gate in `SecurityConfig`
   and add the role→page mapping to `roleRoutes` in `cassitrack-login.js`. (Note
   `DRIVER → cassitrack-driver.html` is mapped but the file is missing.)
8. **Pitfall:** in fleetmanager.js the name `loadRoutes` is defined twice
   (routes CRUD vs. analytics dropdown); function hoisting makes the later
   definition win. Don't rely on that name — use unique function names in new code.
