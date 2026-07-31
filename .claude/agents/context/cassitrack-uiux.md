# CASSITRACK — UI/UX Design Briefing

Persistent design reference for the CASSITRACK operator dashboards. Derived **strictly** from the
static CSS/HTML in `cassitrack-backend/src/main/resources/static/`:
`cassitrack-login`, `cassitrack-fleetmanager`, `cassitrack-analytics`, `cassitrack-admin`.
Read this at the start of every CASSITRACK UI/UX task so new work stays coherent.

> Source-of-truth note: `cassitrack-fleetmanager.css` (1146 lines) is the richest file and the de-facto
> pattern library. The other three pages reuse the same tokens but are simpler and, in places, diverge
> (see "Consistency notes"). There are **two** analytics stylings: the standalone `cassitrack-analytics.css`
> page (bar/status lists, no real charts) and the analytics *view* embedded in `cassitrack-fleetmanager.css`
> (KPI cards + Chart.js). Prefer the fleetmanager version as the modern baseline.

---

## 1. Overall aesthetic & goal

Dark **control-room / mission-control** look: near-black backgrounds, thin cool-blue hairline borders,
mono-typed micro-labels, glowing status dots, and saturated semantic colors used sparingly against a
desaturated field. The goal is a **dense, glanceable operator dashboard** — many small data cells,
uppercase mono labels, tight spacing, and color reserved almost exclusively to encode *status*
(on-time / late / early). Motion is subtle and functional: blinking live-dot, animated loading bar,
typing dots, width transitions on progress bars. `overflow:hidden` on `html,body` in the app shells
(fleetmanager, admin) reinforces a fixed, non-scrolling "cockpit" frame with internal scroll regions.

---

## 2. Design tokens (CSS custom properties)

Defined identically in every page's `:root` (login/admin omit a few). Canonical set (fleetmanager):

| Token | Value | Role |
|-------|-------|------|
| `--bg` | `#07090F` | App background (near-black) |
| `--panel` | `#0F1623` | Primary surface (header, sidebar, cards, tables) |
| `--panel2` | `#141E2E` | Recessed surface (inputs, selects, table headers) |
| `--border` | `#1A2744` | Hairline borders / dividers everywhere |
| `--accent` | `#3B82F6` | Primary brand blue (links, active state, buttons, KPI values) |
| `--cyan` | `#06B6D4` | Secondary accent / "EARLY" status |
| `--green` | `#22C55E` | Success / "ON_TIME" / online |
| `--amber` | `#F59E0B` | Warning / "SLIGHTLY_LATE" / "NO_TRIP" |
| `--red` | `#EF4444` | Danger / "SIGNIFICANTLY_LATE" / logout / offline |
| `--purple` | `#8B5CF6` | Extra categorical (declared; rarely used) |
| `--grey` | `#4B5563` | Neutral / "UNKNOWN" (fleetmanager only) |
| `--text` | `#E2E8F0` | Body/foreground text |
| `--dim` | `#4B5563` (admin: `#64748B`) | Muted labels, placeholders, secondary text |
| `--light` | `#F0F4FF` | Brightest emphasis text (fleetmanager only) |
| `--mono` | `'DM Mono', monospace` | Data/label font |
| `--display` | `'Syne', sans-serif` | Display/UI font |

**Not tokenized (hardcoded, worth normalizing):** map bg `#020617`; KPI/chart-card gradient
`linear-gradient(180deg,#081120,#09162A)`; bright numerics `#F8FAFC` / `#FEF...`; admin thead `#0B1220`.
Semantic status colors are also **re-hardcoded** as hex (e.g. `#22C55E`, `#F59E0B22`) in the many
`[data-status="…"]` attribute-selector rules rather than referencing tokens.

**Spacing:** no numeric scale token. Ad-hoc but consistent rhythm: micro `4–6px`, tight `7–10px`,
standard section `12–14px` and `18–24px`, page gutters `20–28px`.
**Radii:** small `4–8px` (chips, inputs, buttons), medium `10–14px` (cards, tables, modals),
large `18–20px` (admin panels, big chart-cards), pill `999px` / `20px` (status pills, route pills, toggles).
**Shadows:** used only for depth pop — popups `0 8px 32px rgba(0,0,0,.6)`, dropdowns `0 8px 24px rgba(0,0,0,.5)`,
plus **glows** `0 0 6–14px <color>` on live dots, map markers, and chart bars/lines.
**Transitions:** ubiquitous `.15s–.25s ease` on `border-color` / `background` / `color` / `opacity` /
`transform`; bar fills use slower `.6s–.8s ease` for animated growth.

---

## 3. Typography

Two Google fonts, loaded via one `<link>` on every page:
`Syne:wght@600;700;800` + `DM Mono:wght@400;500`.

- **Syne (`--display`)** = default `font-family` on `html,body`. Used for the brand logo
  (`.logo` 17–32px, weight 800, negative letter-spacing), page/chart/section titles, card values,
  and any human-readable heading. Weights in use: 600/700/800.
- **DM Mono (`--mono`)** = the "instrument" font. Every micro-label, KPI/stat number, table cell,
  chip, timer/countdown, input, and status readout. Uppercase + wide `letter-spacing (1–2px)` on labels.
  Weights: 400/500 (occasionally rendered 700 via `font-weight` for pills/badges).

**Role → size guide (representative):** logo 17–32px/800 · page-title 26px/800 · chart-title 15px/700 ·
KPI value 24px/800 · stat-val 18–38px/700–800 · countdown 22px · body/table 11–13px mono ·
labels 8–11px mono uppercase. Display=identity & hierarchy; Mono=data & density.

---

## 4. Component inventory (reusable patterns + classes)

- **App shell:** `.app` (flex column, `100vh`) → `header` (`--panel`, bottom hairline) + view regions.
  Header holds `.logo` (with blue `<span>`), nav, and a right-aligned `.header-right` live cluster.
- **Top nav (fleetmanager):** `.top-nav` > `.top-btn` (mono, `--dim`, ghost); `.top-btn.active` =
  blue-tinted bg + accent border/text; `.logout-btn` variant = red hover. Admin uses `.admin-badge` instead of nav.
- **Live status cluster:** `.dot` + `.dot-green`(glow + `blink`/`pulse` keyframe) / `.dot-red`, with
  `.htext` mono timestamp/status. `@keyframes blink`/`pulse` toggle opacity 1↔.3.
- **Chips / pills / badges (several parallel systems):**
  `.chip` + `.chip-blue/.chip-cyan/.chip-green` (header tags); `.chip[data-status]` status variant;
  `.dm-pill` + `.dm-pill-active/-inactive/-maintenance` (data-mgmt table); `.route-pill` (999px, removable, `.rpx` ×);
  `.badge` (analytics table); `.bm-badge.on/.off`; `.role.admin/.manager/.driver` (admin, pill). All share the
  tinted-bg + matching-border + colored-text formula (`rgba(color,.1)` bg / `rgba(color,.3–.4)` border).
- **Cards:** generic `.card` (login), `.panel` (admin, r18), `.stat`/`.analytics-card`/`.stat-card` (small metric tiles),
  `.kpi-card` (gradient bg, hover lift `translateY(-2px)`), `.chart-card` (gradient, r20, holds `.chart-header`+`.chart-title`+`.chart-subtitle`),
  `.vcard` (vehicle list item: hover/`.selected` states, left `.vcard-stripe` color-coded by `data-status`, `.vcard-grid` 2-col),
  `.eta-card`, `.jcard` (journey), `.bd-card` (bus detail), `.msg-bubble` (chat).
- **Chart-card header pattern:** `.chart-card > .chart-header (space-between) > {.chart-title, .chart-subtitle}`.
  Canvas lives in `.chart-wrap` (`height:280px`, `overflow-x:auto`) > `canvas.chart-canvas` with sibling `.chart-empty` (hidden by default).
- **Tables:** three near-duplicate table systems — `.dm-table` (data-mgmt: sticky mono uppercase `thead`, hover-row, right/center align helpers `.dm-num/.dm-right/.dm-center`, `.dm-plate`, `.dm-muted`),
  `.bm-table` (stops/routes legacy), and bare `table/th/td` (analytics + admin, `min-width:1100px` in admin). All: mono cells, hairline row borders, `rgba(59,130,246,.04–.05)` hover.
- **Buttons:** `.btn` (login/admin primary block), `.btn-primary/-secondary/-danger` (admin),
  `.top-btn`, `.preset-btn`, `.apply-btn`, `.jp-btn`, `.send-btn`, `.dm-btn` + `-primary/-ghost/-danger`,
  `.bm-btn-ghost`, `.dm-row-btn`. Convention: accent-fill primary (`opacity:.85` hover), ghost = subtle
  white-alpha bg + `--dim` text going `--text` on hover, danger = red-tinted.
- **Forms/inputs:** `.input`, `.stop-select`, `.jp-input`, `.chat-input`, `.filter-select`, `.filter-date`,
  `.dm-input`, `.bm-input`, `.search-input`, `.modal-input` — all `--panel2` bg + `--border`, mono text,
  `outline:none`, and a **shared focus signal: `border-color:var(--accent)`**. Range slider `.fm-filter-range`
  (`accent-color:var(--accent)`), custom toggle `.dm-switch` (green when checked), styled checkboxes via `accent-color`.
- **Nav/tabs:** `.tabs`>`.tab`(`.active` accent underline); `.dm-subtabs`>`.dm-subtab`.
- **Overlays:** `.dm-drawer` (right slide-in 380px, `.dm-drawer-backdrop`), `.dm-confirm` (delete dialog),
  admin `.modal-overlay`/`.modal`. All `position:fixed`, dark scrim, `--panel` surface.
- **Loading/empty:** `#loading` splash (`.load-logo`,`.load-bar`,`.load-fill` grow anim, fades `.gone`);
  `.empty`/`.empty-icon`, `.chart-empty`, `.chart-msg`, `.dm-empty`, `.bm-empty`, `.loading` (pulsing).
- **Footer:** `.footer` / `.dm-footer` mono `--dim` micro-credit line.

---

## 5. Layout system

- **Fleetmanager** = fixed `.app` column. Views are `.main-view` (hidden) → `.active-view` (shown).
  - Fleet Monitor: `#fleet-monitor.active-view{display:grid; grid-template-columns:minmax(0,1fr) 360px}`
    — full-bleed `#map` (flex:1) + fixed-width right `.sidebar` (`z-index:1000`, internal tabbed scroll).
  - Analytics view: filter bar (`.filter-bar`, wrapping flex) atop `.analytics-dashboard`
    (`grid-template-columns:320px 1fr`, `gap:20px`, `padding:24px`) → scrolling `.analytics-left` / `.analytics-right` columns.
    Inner grids: `.kpi-grid` (1-col compact), `.analytics-row`/`.charts-grid` (`1fr 1fr`), `.bottom-grid`.
- **Analytics page** = centered `.main` (`max-width:1200px`), `.stats-grid`
  (`repeat(auto-fit,minmax(170px,1fr))`), `.charts-grid` (`1fr 1fr`).
- **Admin** = `.app` column, sticky `header`, scrolling `.content`, single `.panel` with `.topbar` + wrapped table.
- **Login** = centered flex, `.container max-width:400px`.
- **Responsiveness (only two breakpoints exist):**
  `@media(max-width:1200px)` collapses `.analytics-dashboard` and `.bottom-grid` to single column (fleetmanager);
  `@media(max-width:800px)` collapses `.charts-grid` to `1fr` (analytics page).
  There is **no** breakpoint for the fleet-monitor map+360px sidebar grid, nav, or tables → not mobile-adapted.

---

## 6. Data-visualization styling

- **Leaflet map:** container `#map` on dark base `#020617`. Popups fully re-themed via `!important`:
  `.leaflet-popup-content-wrapper` = `--panel` bg + `--border` + r8 + deep shadow + `--text`; tip = `--panel`;
  content margin zeroed. Custom bus marker = `.bus-icon-wrap` (`.bus-icon-body` teardrop 36px rotated -45°,
  white border, drop-shadow) + `.bus-icon-label`, both **color-coded by `data-status`**. Vehicle/stop popups
  = `.vpop*` / `.stop-popup*` with the same status attribute-selector color table.
- **Chart.js:** rendered into `<canvas class="chart-canvas">` inside `.chart-wrap` (`height:280px`), housed in
  gradient `.chart-card`. Chart colors are set in JS (not CSS) but must match tokens/status palette. Empty state =
  sibling `.chart-empty` (CSS default `display:none`; JS toggles). Custom thin blue scrollbars applied to chart wraps.
- **CSS "fake" charts (placeholders / analytics page):** `.fake-donut` (conic-gradient green/amber/red split with
  `::before` hole), `.fake-line-chart`/`.line-point` (glowing blue dots), `.bar`/`.bar-fill` (blue gradient + glow),
  `.route-fill` (blue→cyan gradient), `.status-bar`, `.green-bar-fill`. `.legend-dot.green/.amber/.red` legends.
- **Loading/empty vocabulary:** `.loading` (pulsing mono text placeholder rows), `.empty`+`.empty-icon`,
  `.chart-msg`, table `colspan` "Loading..." rows. Consistent muted mono treatment.

---

## 7. Accessibility observations (current gaps)

- **Focus states:** inputs get only `border-color:var(--accent)` on `:focus` (subtle, ~low contrast on dark);
  **no visible `:focus-visible` outline** on buttons, tabs, `.top-btn`, pills, or interactive `div`s. Global
  `outline:none` on inputs removes the default without a strong replacement. Keyboard users get weak affordance.
- **Semantics:** many interactive elements are non-focusable `div`/`span` (`.tab`, `.vcard`, `.route-pill`,
  `.jp-quick-btn`, `.sugg`, `.bd-back`, dropdown items) → not keyboard-operable and missing ARIA roles.
  Login/some forms do use real `<button>`/`<input>`/`<label for>`; fleet filters correctly pair `<label for>`.
- **Contrast:** `--dim` (`#4B5563`) mono micro-text at 8–10px on `--bg`/`--panel` is **below WCAG AA** for
  small text; admin's lighter `#64748B` is better. Status colors on tinted `rgba(...,.1)` chips are generally OK
  but small (9px) reduces legibility. White-on-accent buttons are fine.
- **Color-only encoding:** status is conveyed almost entirely by hue (stripe/dot/pill) with no shape/icon
  redundancy in most cards → problematic for color-vision-deficient operators. `data-status` text often present, which helps.
- **Motion:** blink/pulse/typing/loading animations have **no `prefers-reduced-motion` guard**.
- **Misc:** no visible skip links; drawers/modals lack documented focus-trap in the static markup; `title`
  attributes used on some filter controls (minor a11y positive).

---

## 8. Consistency notes & recommendations

**Coherent (keep doing):** identical `:root` token block on every page; universal focus = accent border;
mono uppercase micro-labels; tinted-bg+border+colored-text formula for every chip/pill/badge; hairline
`--border` dividers; accent-primary / ghost / danger button trichotomy; `--panel`/`--panel2` two-surface model.

**Divergences to reconcile in future work:**
1. **Duplicated token blocks** across 4 files drift (`--dim` differs in admin; `--grey`/`--light`/`--purple`/`--amber`
   missing from some). → Extract one shared `tokens.css` and import everywhere.
2. **Redundant component systems:** three table stylings (`.dm-table` / `.bm-table` / bare `table`), several
   pill systems (`.chip` / `.dm-pill` / `.route-pill` / `.badge` / `.bm-badge` / `.role`), two chart-card
   definitions. → Standardize on the fleetmanager `.dm-*` + `.chart-card` set; retire legacy `.bm-*` (comments
   in CSS already flag this migration as in-progress) and the placeholder analytics page.
3. **Hardcoded hex** for status + gradients bypasses tokens. → Add `--status-ontime/-late/-early/...` and
   `--surface-gradient` tokens; replace literals in `[data-status]` rules.
4. **Spacing/radius are ad-hoc.** → Introduce a token scale (`--space-1..6`, `--radius-sm/md/lg/pill`) to lock rhythm.

**When building new CASSITRACK UI:** use Syne for headings/values + DM Mono for every label & number;
build on `--panel`/`--panel2` surfaces with `--border` hairlines; encode status only with the 6-value palette
(ON_TIME green / SLIGHTLY_LATE amber / SIGNIFICANTLY_LATE red / EARLY cyan / UNKNOWN grey / NO_TRIP amber)
**and add a non-color cue**; give buttons/tabs a real visible focus ring; guard animations with
`prefers-reduced-motion`; keep it dense, glanceable, and dark.
