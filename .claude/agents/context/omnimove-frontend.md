# OMNIMOVE Web Frontend — Architecture Briefing

Static, no-build vanilla frontend served by Spring Boot from
`omnimove-backend/src/main/resources/static/`. Read this at the start of every frontend task.

## 1. Overview

- **No build step.** Plain HTML + CSS + JS, one triplet per page. Spring Boot serves the
  files directly; there is no bundler, framework, npm, or transpilation.
- **Backend context path:** all HTML lives under `/` but the app is mounted under
  `/omnimove` (e.g. `https://host/omnimove-login.html`). All API calls are prefixed with
  `/omnimove/api/v1` (constant `OMNIMOVE` / `API_BASE` in each JS file).
- **Pages (triplets):** `omnimove-login`, `omnimove-traveller`, `omnimove-admin`,
  `reset-password` — each has `.html` / `.css` / `.js`.
- **CDN libs (loaded per page, no local copies):**
  - Leaflet **1.9.4** (JS + CSS, SRI-pinned) — traveller only.
  - Chart.js **4.4.1** (`chart.umd.js`, SRI-pinned) — admin only.
  - OpenStreetMap raster tiles (`tile.openstreetmap.org`) — traveller map.
  - Google Fonts, **different per page** (see §6): Orbitron + Plus Jakarta Sans (traveller);
    Inter + JetBrains Mono (admin); Syne + DM Mono (login, reset-password).

## 2. Per-page reference

### omnimove-login (`.html/.css/.js`)
- **Purpose:** single-card auth hub — Sign In, Register, email-verification-sent,
  Forgot-password, and (embedded) Reset-password panels.
- **DOM:** `.container > .logo + .tagline + .card`. Card holds `#tabBar` (Sign In / Register
  tabs), a `#msg` banner, and five `.form` panels toggled by the `active` class:
  `#loginForm`, `#registerForm`, `#emailSentPanel`, `#forgotForm`, `#resetForm`.
- **Key JS (`omnimove-login.js`):**
  - IIFE `init()` reads URL params on load: `?pr=`/`?reset=` → show reset panel;
    `?verified=true|expired|invalid` → status message; `sessionStorage omnimove_flash_err`
    → flash error.
  - `switchTab`, `showPanel(id)` (drives the `ALL_PANELS` list), `showMsg/hideMsg`,
    `hideTabs/showTabs`, field-level `fieldErr/clearFieldErr/clearAllFieldErrs`.
  - `handleLogin`, `handleRegister`, `handleForgot`, `handleReset`,
    `resendVerification` / `resendVerificationForEmail`.
  - `isPasswordValid(p)` regex: min 8, ≥1 lower, ≥1 upper, ≥1 digit, ≥1 special.
  - `saveSession(data)` writes non-sensitive `{name,email,role}` to
    `sessionStorage.omnimove_user` (`USER` role normalised to `TRAVELLER`).
  - `secureRedirect(role)` → `window.location.replace()` to `REDIRECT[role]`
    (`ADMIN`→admin, else traveller). `document.write` was removed (CSP/XSS fix).
  - `_failedAttempts` counter reveals `#forgotBtn` after ≥2 failures (403 not counted).
- **APIs:** `POST /auth/login`, `/auth/register`, `/auth/forgot-password`,
  `/auth/resend-verification`, `/auth/reset-password`.

### omnimove-traveller (`.html/.css/.js`) — mobile-first, the main app
- **Purpose:** journey planner + tickets + profile (history/favourites/payment/prefs/account)
  + AI assistant. See §3 and §7.
- **DOM:** `.sidebar#menuDrawer` (brand, user block, `.sidebar-nav` with `.nav-item[data-pane]`,
  eco-score card) + `.menu-scrim` + `.main` (`.topbar` search + `.cats` chip rows,
  `.content-area` with three `.pane`s: `#pane-map`, `#pane-tickets`, `#pane-profile`).
  Map pane = `#map` + floating FABs + `.map-sidebar` bottom-sheet (`.routes-list`).
  Overlays: `#aiOverlay`, `#stopSheetOverlay`, `#deleteModal`, `#acList` (autocomplete).
- **Rendering:** Leaflet map (`L.map('map')`, OSM tiles) with custom `divIcon`s
  (pulsing user dot, SVG teardrop dest pin, "M" stop marker); results rendered as HTML
  strings into `.routes-list`.
- **APIs:** `/traveller/stats|preferences|history|favorites|favorites/toggle`,
  `/journeys/stops`, `/journeys/stops/{id}/arrivals`, `/journeys/search`, `/journeys/select`,
  `/ai/chat`, `/auth/logout`, `/auth/account`.

### omnimove-admin (`.html/.css/.js`)
- **Purpose:** admin console — analytics dashboard (FR-OM-009), user management, settings.
- **DOM:** `.topbar` (+ `#clock`), `.tabs` (`data-tab=stats|users|settings`), `.content`
  with three `.pane`s (`#pane-stats`, `#pane-users`, `#pane-settings`), plus `#modalOverlay`
  (add user), `#deleteModal`, `#toastEl`.
- **Key JS (`omnimove-admin.js`):**
  - IIFE `checkAdminAuth()` route guard: reads `sessionStorage.omnimove_user`; redirects to
    login unless `role === 'ADMIN'`.
  - Users: `loadUsers` → `renderTable`, `filterTable(q)` (client-side name/email/id + role
    filter), `deleteUser`/`confirmDeleteUser`, `addUser`, `loadStats`.
  - Analytics: `loadAnalytics(range)` fetches once and fans out to `updateKpis`,
    `updateModeChart` (doughnut), `updateModeByHourChart` (stacked bar), `updateGreenIndexChart`
    (line + moving average), `updateDayOfWeek` (bar), `updateTopRoutes` (table). Range bars
    `#rangeBar`/`#giRangeBar`/`#hourRangeBar` (1W/1M/3M/6M/1Y) re-fetch.
  - Settings: `loadGoogleSettings`/`toggleGoogleSetting` drive two `.switch` toggles
    (`#swSearch` `google.search`, `#swStopEta` `google.stop_eta`).
- **APIs:** `/admin/users` (GET/POST), `/admin/users/{id}` (DELETE), `/admin/users/stats`,
  `/admin/analytics?range=`, `/admin/settings/google` (GET/PUT), `/auth/logout`.
- **Note:** CSV/XLSX/PDF export buttons are stubs (`toast(...)` only).

### reset-password (`.html/.css/.js`)
- **Purpose:** standalone reset page (target of the email link, distinct from the login page's
  embedded reset panel).
- **DOM:** `.box > .logo + .tag + .card` with `#resetForm` (`#p1`, `#p2`, errors `#e1/#e2`,
  `#msgBox`, `#btn`).
- **Key JS (`reset-password.js`):** reads token from `window.location.hash` (`#TOKEN`);
  `?expired=true` or missing token hides the form and shows an error. `valid(p)` = same
  password regex. `doReset(e)` → `POST /auth/reset-password {token,newPassword,confirmPassword}`.

## 3. Traveller journey-planner flow (detail)

**Entry (topbar).** `#originSelect` and `#destSelect` are typable text inputs with a custom
autocomplete (`#acList`, `initAutocomplete`, `_acShow`/`_acItems`) backed by the in-memory
`STOPS` map; the selected stop id is stored in `input.dataset.id`. Origin also offers a
synthetic `GPS` option ("My Location"). `#departTime` is an optional `<input type=time>`.
`swapStops()` swaps origin/destination. Sort chips `#sortChips` (`eco|budget|fast`) and mode
chips `#modeChips` (`BUS|BIKE|SCOOTER`) set `activeSort` / `activeModes`.

**Stops load.** `loadStops()` → `GET /journeys/stops`; fills `STOPS[id]={id,name,lat,lon}`,
sets default origin/dest, renders markers (`renderStopMarkers`), fits map bounds.

**Search — building JourneyRequest.** `doSearch()`:
- resolves origin via `getOrigin()` (falls back to GPS via `tryGetGPS()`; Cassino
  `41.5020,13.8200` fallback if GPS denied), rejects origin==dest.
- POSTs to `/journeys/search` with snake_case body:
  `origin_lat, origin_lon, origin_name, origin_is_gps, dest_lat, dest_lon, dest_name,
  user_id, dest_stop_id, origin_stop_id`, plus optional `modes[]` (only if chips selected)
  and optional `departure_time`.
- 429 → "too many searches"; other errors → warning card. On success stores
  `window._currentOrigin/_currentDest/_lastSearchData` and calls `renderRoutes`.

**Rendering options.** `renderRoutes(data)`:
- updates `.weather-pill` from `data.weather_summary`; renders `data.messages[]` as
  `.search-notice` banners; empty `data.options` → "No routes found".
- `sortOptions()` orders by `data.options[i].score_eco|score_budget|score_fast`
  (falls back to `green_index` / `cost_euros` / `duration_minutes`).
- each option → `.route-card#card-{mode}` showing `mode_label`, `duration_minutes`, cost
  (`cost_euros`, 0→"Free"), `co2_grams`, `green_index/100`, optional `delay_label`/
  `weather_warning`, and a Select button calling `selectMode(...)`. Options cached in
  `window._routeOptions[mode]` (incl. `legs`).

**Selecting + starting.** `selectMode(...)` highlights the card and appends a sticky
"Start Journey" banner. `startJourney()`:
- optionally re-reads GPS, fires `POST /journeys/select` (mode, green_index, distance_km,
  cost_euros, origin_name, dest_name — non-blocking analytics),
- draws the route on the map: for **BUS** it iterates `selectedJourney.legs`, drawing each
  `leg.stop_coords` polyline (colour-cycled) + circle-marker stops for `BUS` legs and dashed
  grey lines for `WALK` legs; other modes draw a single origin→dest polyline (dashed for WALK).
- fits bounds, then replaces `.routes-list` with a "Journey In Progress" panel. For BUS it
  breaks down legs into Walk / Bus-wait / On-bus cells (summing `duration_minutes` /
  `distance_metres` over `WALK`/`WAIT`/`BUS` legs); a per-minute `setInterval` counts down
  `#etaCounter`. `endJourney()` clears layers/interval, restores stops, reloads eco stats.

**Legs contract (per option):** `legs[].mode` ∈ `BUS|WALK|WAIT`, `legs[].stop_coords`
(array of `[lat,lon]`), `legs[].duration_minutes`, `legs[].distance_metres`.

## 4. Shared client patterns

- **Auth token.** Delivered by the server as an **httpOnly cookie** (`Set-Cookie` on
  `/auth/login`, `SameSite=Strict`), sent automatically on every fetch. JS never reads it.
  - *Inconsistency to be aware of:* `omnimove-traveller.js`'s `apiFetch` still also tries
    `localStorage.getItem('omnimove_token')` and attaches a `Bearer` header if present;
    `logout()`/`deleteAccount()` in the traveller page likewise read/clear that key. In the
    current cookie-based flow that key is never set, so those branches are effectively no-ops.
    Treat the cookie as the real auth mechanism; the localStorage code is legacy.
- **Display identity.** `sessionStorage.omnimove_user = {name,email,role}` (set by login's
  `saveSession`). Cleared on tab close.
- **Route guards.** Traveller: redirect to login if `omnimove_user` has no name/email.
  Admin: `checkAdminAuth()` requires `role==='ADMIN'`. No explicit global 401-interceptor —
  individual fetches check `r.ok`/`r.status` and show toasts/messages; a missing cookie
  makes protected calls fail and (admin) 403 is surfaced via toast.
- **`apiFetch(path, options)`** — shared helper in traveller + admin JS: prepends `API_BASE`,
  sets JSON `Content-Type`, spreads caller options/headers.
- **Registration → verification → reset flow:** register → `#emailSentPanel` with resend;
  email link hits backend `/auth/verify` which redirects to
  `omnimove-login.html?verified=...`; forgot-password → email → link routes through
  `/auth/reset-page` (redirects with `?pr=TOKEN`) to the login reset panel, or to the
  standalone `reset-password.html#TOKEN`.
- **XSS defense:** every API string interpolated into `innerHTML` is passed through
  `escHtml()` (and `escAttr()` for attribute contexts). Present in traveller + admin JS.
- **UX states:** loading (spinner/emoji cards injected into `.routes-list`), empty
  (`.empty-state` / "No routes/trips/favourites"), error (red warning card / toast).
  Toasts: `showToast(msg,isError)` (traveller), `toast(msg,err)` (admin).

## 5. API contract as consumed by the client

All under `/omnimove/api/v1`. JSON field names the JS actually reads:

- **Auth (`AuthController`):**
  - `POST /auth/login {email,password}` → `{token, name, email, role, message,
    suggest_password_reset}`. 401 bad creds, 403 unverified, 429 locked.
  - `POST /auth/register {name,email,password,confirmPassword}`
  - `POST /auth/forgot-password {email}` · `POST /auth/resend-verification {email}` → `{message}`
  - `POST /auth/reset-password {token,newPassword,confirmPassword}` → `{message}`
  - `POST /auth/logout` · `DELETE /auth/account`
  - (server-side redirect endpoints, not fetched: `GET /auth/verify`, `GET /auth/reset-page`)
- **Journeys (`JourneyController`):**
  - `GET /journeys/stops` → `[{id,name,lat,lon}]`
  - `POST /journeys/search` (JourneyRequest, snake_case, see §3) → JourneyResponse
    `{weather_summary, messages[], options[]}`; each option:
    `{mode, mode_label, duration_minutes, cost_euros, co2_grams, green_index, distance_metres,
    score_eco, score_budget, score_fast, delay_label, delay_status, weather_warning, legs[]}`.
  - `GET /journeys/stops/{id}/arrivals?limit=10` → `[{route_short_name, route_name,
    scheduled_arrival, estimated_arrival, schedule_status, crowding_level, real_time,
    departed, delay_minutes, delay_stop_name}]`.
  - `POST /journeys/select {mode,green_index,distance_km,cost_euros,origin_name,dest_name}`
- **Traveller (`TravellerController`):**
  - `GET /traveller/stats` → `{ecoPoints, co2SavedKg, trips, spent30d}`
  - `GET/PUT /traveller/preferences` → `{defaultJourneyMode, avoidHighOccupancy, showWalking,
    preferBikeOverBus, onlyBusWhenRaining, notifyDelays, notifyTicketExpiry, notifyEcoTip}`
  - `GET /traveller/history` → `[{mode, createdAt, costEuros, originName, destName,
    greenIndex, isFavorite}]`
  - `GET /traveller/favorites` → `[{mode, originName, destName, avgCost, usedCount, greenIndex}]`
  - `POST /traveller/favorites/toggle {mode,originName,destName}` → `{favorited}`
- **Admin (`AdminController`):** `GET/POST /admin/users`, `DELETE /admin/users/{id}`,
  `GET /admin/users/stats` → `{total,admins,travellers}`,
  `GET /admin/analytics?range=` → `{kpis:{totalSearches,totalSelections,co2SavedKg,
  avgGreenIndex}, modeDistribution, modeByHour, greenIndexTrend[{time,value}], dayOfWeek,
  topRoutes[{origin,dest,uses,avgGreenIndex}]}`,
  `GET/PUT /admin/settings/google` → `{"google.search":bool,"google.stop_eta":bool}`.
- **AI (`AiController`):** `POST /ai/chat {message,language,history[{role,content}]}` →
  `{answer, suggestions[]}`.

> Keep these field names in sync with backend DTOs when either side changes.

## 6. CSS / design tokens & naming

Each page defines its own `:root` custom properties — **there is no shared stylesheet**.

- **Traveller (light, mobile-first):** `--primary #10b981` (green) / `--blue #3b82f6` /
  `--purple #7c3aed` / `--amber` / `--red`, `--bg-white #fff`, `--bg-app #f8fafc`,
  `--text-dark #0f172a` / `--text-mid` / `--text-soft`, `--border`/`--border-mid`,
  `--ai-gradient`, `--sidebar-w 240px`, `--shadow-*`, `--radius-* (12/20/28)`.
  Fonts: **Orbitron** (brand/logo), **Plus Jakarta Sans** (body).
- **Admin (dark):** `--bg-deep #0c1117`/`--bg-panel`/`--bg-card`, `--accent-green #34d399`,
  `--accent-cyan #38bdf8`, `--accent-amber`/`--accent-red`/`--accent-blue`,
  `--text-primary/secondary/dim`. Fonts: **Inter** (main), **JetBrains Mono** (`--font-mono`).
- **Login / reset-password (dark):** `--bg #07090F`, `--panel`/`--panel2`, `--accent #3B82F6`,
  `--cyan`/`--green #22C55E`/`--red #EF4444`, `--text #E2E8F0`, `--dim`.
  Fonts: **Syne** (`--display`), **DM Mono** (`--mono`).
- **Naming conventions:** lowercase-hyphen classes (`.route-card`, `.stat-pill`,
  `.nav-item`, `.range-btn`, `.ptab-pane`). State via `.active`/`.open`/`.on`/`.starred`/
  `.show`. camelCase IDs for scripted elements (`#originSelect`, `#kpiSearches`,
  `#userTbody`). Data attributes drive behaviour: `data-pane`, `data-tab`/`data-ptab`,
  `data-sort`, `data-mode`, `data-range`, `data-key`, `data-id`, `data-stop-id`.
  Heavy use of inline `style="..."` for one-offs (esp. injected HTML strings).

## 7. Responsive / mobile-first (traveller)

- `omnimove-traveller` is the **mobile-first** page; a separate `omnimove-mobile` agent owns
  deeper device-specific concerns.
- Single breakpoint `@media (max-width: 768px)` used throughout (~25 blocks). Desktop shows
  the persistent `.sidebar`; on mobile it becomes a slide-in drawer (`#menuDrawer.open` +
  `#menuScrim`), toggled by `openMenu`/`closeMenu`/`closeMenuMap`, with `.hamburger` /
  `.map-menu-fab` triggers and a `.mobile-back` `←` bar (`backToMap`, redefined several times;
  the last definition — reopen-menu-over-map — wins).
- Map results use a **draggable bottom sheet** (`.map-sidebar` + `#sheetHandle`, IIFE "Step 10"):
  three snap states peek/half/full via touch/mouse drag, calling `map.invalidateSize()`.
- Panes act as full-screen mobile subpages via `document.body.dataset.pane`. `map.invalidateSize()`
  is called on resize, pane switches, and a 500 ms post-load timer. Zoom control moves to
  top-right on mobile. Login/reset CSS also have `max-width:480/768px` tweaks (16px inputs to
  avoid iOS zoom, 44px touch targets).

## 8. Adding a new page (conventions)

1. Create `omnimove-<page>.html/.css/.js` in `.../resources/static/`. No build; Spring serves
   it directly.
2. In HTML `<head>`: link the page's own Google Fonts + its CSS; load any CDN lib
   (Leaflet/Chart.js) with the same SRI hashes; put `<script src="omnimove-<page>.js">` last
   before `</body>`.
3. In the JS: define `const API_BASE = '/omnimove/api/v1';` and copy the `apiFetch` helper.
   Add a route guard reading `sessionStorage.omnimove_user` (redirect to
   `omnimove-login.html` if missing / wrong `role`).
4. Copy `escHtml`/`escAttr` and pass every API-derived string through them before `innerHTML`.
5. Reuse token names: `sessionStorage.omnimove_user`, sort keys `eco|budget|fast`, mode enums
   `BUS|BIKE|SCOOTER|WALK`. Define page-local `:root` tokens (pick the matching palette).
6. Provide loading / empty (`.empty-state`) / error states and a toast helper. Rely on the
   httpOnly cookie for auth; do not store JWTs in localStorage.
7. If the page redirects post-login, add its role→file mapping to `REDIRECT` in
   `omnimove-login.js`.
