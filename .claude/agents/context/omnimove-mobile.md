# OMNIMOVE Mobile Experience — Persistent Briefing

Read this at the start of every mobile-related task. It reflects the **actual code** in
`omnimove-backend/src/main/resources/static/` (files: `omnimove-traveller.html/.css/.js`,
`omnimove-login.*`, `reset-password.*`). Last verified against the current tree.

---

## 1. Delivery model

- **There is NO native app and NO cross-platform framework.** No React Native, Flutter,
  Capacitor, Cordova, or Ionic anywhere. The mobile experience IS the mobile-first
  responsive web app served as static files by the Spring Boot backend.
- Stack is **vanilla HTML/CSS/JS + Leaflet 1.9.4** (loaded from `cdn.jsdelivr.net` with SRI
  hashes). Fonts come from Google Fonts (`Orbitron`, `Plus Jakarta Sans`). Map tiles from
  OpenStreetMap (`{s}.tile.openstreetmap.org`).
- **PWA status: NONE.** There is **no `manifest.json`/web app manifest**, **no service
  worker** (`sw.js`/`service-worker.js` do not exist), and **no `<link rel="manifest">`** or
  SW registration in any file. The app is a plain online-only web page. There is no offline
  support, no install-to-home-screen, no app icons, no splash screen.

## 2. Viewport & meta tags (actual)

- `omnimove-traveller.html` line 5: `<meta name="viewport" content="width=device-width, initial-scale=1.0" />`
- `omnimove-login.html` line 5: `<meta name="viewport" content="width=device-width,initial-scale=1.0"/>`
- `reset-password.html` line 5: `<meta name="viewport" content="width=device-width,initial-scale=1"/>`
- **Missing entirely:** no `theme-color`, no `apple-mobile-web-app-capable`, no
  `apple-mobile-web-app-status-bar-style`, no `apple-touch-icon`, no `format-detection`, no
  `maximum-scale`/`user-scalable` (pinch-zoom is left enabled, which is fine for a map app).
- `charset` is UTF-8. `lang="en"`.

## 3. Responsive layout (omnimove-traveller)

The desktop layout is a flexbox shell: fixed 240px `.sidebar` + `.main` (topbar +
`.content-area` holding three `.pane`s: `#pane-map`, `#pane-tickets`, `#pane-profile`).
`body` is `height:100vh; overflow:hidden; display:flex`.

**Single breakpoint drives all mobile behavior: `@media (max-width: 768px)`.** The CSS
contains ~25 stacked, incrementally-added mobile blocks (labeled "Step 2b" … "Step 26"),
which is why there is heavy `!important` usage and some later rules override earlier ones.
Login CSS also has a `480px` breakpoint; traveller CSS effectively only uses `768px`.

Key mobile transforms (all in `omnimove-traveller.css`):
- **Sidebar → off-canvas drawer.** `.sidebar` becomes `position:fixed; width:250px;
  transform:translateX(-100%)`; `.sidebar.open` slides in. `z-index:2000`, scrim
  `.menu-scrim.open` at `z-index:1900`. A `.menu-close` (✕) appears inside the drawer.
- **Map fills the pane.** `#pane-map{position:relative}`, `#map{position:absolute;
  inset:0}`. A floating `.map-menu-fab` (☰, top-left, z 500) and `.ai-fab` (moved to
  bottom-right, z 450) sit over the map. Leaflet zoom control is moved to `topright` in JS
  (`map.zoomControl.setPosition('topright')`).
- **Journey options = bottom sheet.** `.map-sidebar` (the "Smart Routes" list) becomes an
  absolute bottom sheet: `left:0;right:0;bottom:0;height:40vh; border-radius:18px 18px 0 0`,
  `z-index:400`, with a visible `.sheet-handle` grabber. It is **draggable** (see §4). The
  stop-arrivals sheet (`#stopSheetOverlay`/`.stop-sheet`) is a separate, fixed bottom sheet
  (`max-height:78vh`, z 3000).
- **Stacked search.** The topbar search (`.topbar-search`) is re-laid-out into a
  Google-Maps-style CSS grid (`grid-template-areas: "origin swap" / "dest swap" /
  "search search"`) so origin sits over destination with a round swap button and a
  full-width Search row.
- **Sub-pages go full-screen.** On `body[data-pane="tickets"]`/`[data-pane="profile"]` the
  search UI is hidden and replaced by a `.mobile-back` (←) + `.mobile-title` bar. Profile
  hero card compacts, `.profile-tabs` are hidden (each menu item is its own screen), tabs
  scroll horizontally where shown.
- **Safe-area handling is minimal:** only two uses of `env(safe-area-inset-*)`:
  `.stop-sheet` has `padding-bottom: env(safe-area-inset-bottom,0px)`, and the topbar has
  `padding-top: calc(14px + env(safe-area-inset-top,0px))`. The Smart-Routes `.map-sidebar`
  bottom sheet and the `.ai-fab`/`.map-menu-fab` do **not** account for safe areas (notch/
  home-indicator overlap risk on iPhones).

## 4. Touch & gestures

- **Leaflet touch:** map created with `L.map('map', { zoomControl:true })` — default touch
  handling (pinch-zoom, drag-pan, tap) is left ON; no explicit `tap`, `touchZoom`,
  `dragging`, or `bounceAtZoomLimits` options set. This works for standard map interaction.
- **Bottom-sheet drag** (IIFE "Step 10" in JS): custom drag on `#sheetHandle` using both
  `mousedown/mousemove/mouseup` and `touchstart/touchmove/touchend` (touch listeners are
  `{passive:false}` and call `preventDefault`). Snaps to 3 states — peek `110px`, half
  `~45%`, full `~94%` of the map pane height — and calls `map.invalidateSize()` after.
  `.map-sidebar` has `touch-action:none`; the inner `.routes-list` has `touch-action:pan-y`
  so the list still scrolls.
- **Touch target sizes:** deliberately sized on mobile — `.hamburger`/`.map-menu-fab`
  `40–44px`, `.search-btn { min-height:44px }` (Step 2b), `.mobile-back` `40px`. Good. But
  many controls stay small: `.cat-chip` (~`7px 14px`), `.toggle` (42×24), fav `.fav-star`
  (18px glyph), Leaflet popup `.stop-check-btn`. The sidebar quick-logout `→` arrow is
  hidden on mobile (Step 4b) to avoid a tiny tap target.
- **iOS input zoom:** Step 2b sets `.search-seg input/select { font-size:16px }` to prevent
  auto-zoom on focus — BUT later steps (7, 8, 11, 14–16) shrink these back to `11–12px`
  with `!important`, so **iOS Safari WILL zoom the viewport when the origin/destination
  inputs are focused.** (Known regression from the layered "Step" overrides.)
- **Hover-only affordances that break on touch (ISSUES):** several interactions rely on
  `:hover` with no tap/`:active`/`:focus` equivalent — `.route-card:hover`,
  `.ticket-option:hover`, `.cat-chip:hover` (border/color hint), `.route-hist-card:hover`,
  `.fav-star:hover` (amber preview), `.nav-item:hover`, and JS inline
  `onmouseover/onmouseout` handlers (sidebar logout arrow, AI suggestion chips
  `renderSuggestions`). On touch these hints are invisible or "stick" after tap. None block
  functionality but degrade discoverability.

## 5. Geolocation

- Uses `navigator.geolocation.getCurrentPosition`. Two call sites:
  - `tryGetGPS()` — resolves `{name:'My Location', lat, lon, isGPS:true}`, drops a pulsing
    blue `userIcon` marker via `placeUserMarker`. Options `{timeout:8000, maximumAge:60000}`.
  - `startJourney()` — a second, silent GPS read `{timeout:6000, maximumAge:30000}` to draw
    the dashed GPS→origin-stop leg.
- **Origin defaults to GPS.** `getOrigin()` treats an empty origin field / id `'GPS'` as
  "My Location"; `doSearch()` calls `tryGetGPS()` on demand and shows a
  `📡 Getting your location...` toast. Selecting "My Location" in the autocomplete triggers
  GPS and recenters the map (`map.setView(...,16)`).
- **Permission handling / fallback:** there is **no explicit permission-denied UX**. On any
  error (denied, timeout, unavailable) `tryGetGPS`'s error callback **silently falls back to
  a hardcoded demo coordinate** `41.5020, 13.8200` labeled `'Via Folcara (approx)'` and
  still resolves success. So a user who denies location is silently routed from a fixed
  Cassino-area point with no notice. The `startJourney` GPS read fails gracefully (just skips
  the dashed leg). No `navigator.permissions` query, no HTTPS/secure-context guard.

## 6. Mobile performance

- **Payload / dependencies:** small hand-written JS (~1445 lines) and CSS (~1460 lines), no
  bundler, no framework runtime. External deps loaded per page: Leaflet JS+CSS (jsDelivr
  CDN, SRI-pinned), Google Fonts (two families, many weights via one CSS request), OSM
  tiles. **All third-party assets are CDN/remote** → no offline, and initial paint depends on
  Google Fonts + jsDelivr + OSM being reachable on mobile networks. No self-hosting, no
  `font-display` control beyond Google's `&display=swap`, no image sprites (icons are emoji/
  inline SVG `divIcon`, which is cheap).
- **DOM churn / live updates:** route lists, history, favourites, arrivals, and the whole
  "journey in progress" panel are rebuilt via `innerHTML = ...` string concatenation
  (`renderRoutes`, `renderArrivals`, `renderHistory`, `startJourney`). Fine at these list
  sizes but full-subtree replacement on each update; no diffing/virtualization.
- **Timers:** `startJourney` runs a `setInterval(..., 60000)` ETA countdown
  (`window._etaInterval`) updating one text node per minute — cheap. `endJourney` clears it.
  Toasts self-remove after 3s. No high-frequency polling loop; arrivals/routes fetch on
  demand only.
- **Map redraw:** markers are removed/re-added wholesale (`renderStopMarkers` loops
  `map.removeLayer` then re-adds; journey layers tracked on `window._*` and cleared).
  `map.invalidateSize()` is called on resize, after pane switches (`setTimeout 50`), and
  after each sheet drag-snap — necessary but each is a full map reflow. On low-end phones the
  repeated `invalidateSize` during/after drags plus wholesale marker re-adds are the main
  cost. Bus routes draw one polyline + a `circleMarker` per stop coordinate, which can add up
  for long lines.
- `#map { isolation:isolate }` is set to contain Leaflet's internal z-indices under the
  drawer.

## 7. On-screen keyboard & scroll/overflow

- **Inputs:** origin/destination are `type="text"` with a custom `.ac-list` autocomplete
  (fixed-position, `getBoundingClientRect`-anchored under the field; hidden on `scroll`/
  `resize`/`blur`). Departure is `type="time"` (native mobile time picker). The AI chat
  `#aiInput` and reset/login fields are standard text/password/email inputs.
- **Keyboard-avoidance:** none. There is no `visualViewport` handling and no scroll-into-view
  logic. Because `body` is `overflow:hidden; height:100vh` and panes manage their own
  scroll, when the soft keyboard opens over the bottom-anchored search/sheet the layout does
  not reflow to the visual viewport — the `.ac-list` (anchored by absolute
  `getBoundingClientRect` coords, and dismissed on `scroll`) can end up mis-positioned or
  hidden behind the keyboard. The `100vh` shell also suffers the classic mobile-browser
  URL-bar height jump (no `100dvh`/`svh`).
- **Scroll/overflow:** scrolling is delegated to inner containers — `.routes-list`,
  `.tickets-inner`, `.profile-scroll`, `.stop-sheet-list`, `.ai-messages`, `.sidebar-nav`,
  and `.profile-tabs` (`overflow-x:auto; -webkit-overflow-scrolling:touch`). Custom 3px
  webkit scrollbars throughout. AI chat and stop sheet auto-scroll to bottom via
  `scrollTop = scrollHeight`.

## 8. Gaps & opportunities (actionable, stay in the vanilla stack)

1. **Add a PWA baseline.** Ship a `manifest.json` (name, icons, `theme-color`,
   `display:standalone`, `start_url`) + `<link rel="manifest">` + `theme-color`/
   `apple-mobile-web-app-*` meta. This alone enables add-to-home-screen and a proper mobile
   chrome. No framework needed.
2. **Add a service worker** for app-shell + static-asset caching (HTML/CSS/JS, and ideally a
   self-hosted Leaflet + fonts). Even a cache-first shell removes the hard dependency on
   jsDelivr/Google Fonts on flaky mobile networks. Consider caching OSM tiles for last-viewed
   area. Add an install-prompt (`beforeinstallprompt`) affordance.
3. **Fix the iOS input-zoom regression.** Restore `font-size:16px` on the focused
   origin/destination inputs (override the later Step 11/14–16 `!important` shrink), or use
   `maximum-scale` carefully. Currently focusing search zooms the page on iOS.
4. **Geolocation UX.** Replace the silent hardcoded `41.5020,13.8200` fallback with an
   explicit "Location unavailable — pick a stop" state; distinguish denied vs. timeout;
   optionally use `navigator.permissions.query({name:'geolocation'})` to pre-empt the prompt.
5. **Touch/hover parity.** Add `:active`/`:focus-visible` states mirroring the `:hover`
   hints on cards/chips/stars; drop or duplicate the inline `onmouseover` handlers
   (AI suggestion chips, logout arrow) with touch-safe styling.
6. **Safe-area polish.** Add `env(safe-area-inset-bottom)` padding to the Smart-Routes bottom
   sheet and to `.ai-fab`/`.map-menu-fab` offsets so they clear the iPhone home indicator.
7. **Keyboard handling.** Adopt `100dvh`/`100svh` for the `body` shell and use
   `visualViewport` (or `scrollIntoView`) so the search sheet + `.ac-list` stay above the
   soft keyboard.
8. **Consolidate the "Step 2–26" CSS.** The 25 stacked mobile overrides with heavy
   `!important` are brittle and self-conflicting (see the input-zoom regression). Refactoring
   into one coherent mobile block would prevent future overrides fighting each other.
9. **Performance on low-end devices.** Reuse markers instead of full remove/re-add in
   `renderStopMarkers`; debounce/batch `map.invalidateSize()` calls; consider a Leaflet
   marker layer group / canvas renderer (`preferCanvas:true`) for bus-route dots on long
   lines.

---

### Quick reference — real identifiers

- Panes: `#pane-map`, `#pane-tickets`, `#pane-profile`. Body flags: `data-pane`, `data-tab`.
- Mobile chrome: `#menuDrawer`, `#menuScrim`, `.map-menu-fab`, `.mobile-back`,
  `.mobile-title`, `.menu-close`, `.ai-fab`.
- Bottom sheets: `.map-sidebar` + `#sheetHandle` (routes); `#stopSheetOverlay`/`#stopSheet`
  (arrivals). AI: `#aiOverlay`/`.ai-panel`.
- Search: `#originSelect`, `#destSelect`, `#departTime`, `#sortChips`, `#modeChips`, `#acList`.
- Key JS fns: `openMenu`/`closeMenu`/`closeMenuMap`/`backToMap`, `doSearch`, `getOrigin`,
  `tryGetGPS`, `placeUserMarker`, `startJourney`/`endJourney`, `renderRoutes`,
  `renderArrivals`, `showStopArrivals`, `initAutocomplete`, sheet-drag IIFE ("Step 10").
- Only breakpoint: `@media (max-width: 768px)`.
