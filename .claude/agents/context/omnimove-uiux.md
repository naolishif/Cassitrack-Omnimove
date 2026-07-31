# OMNIMOVE — UI/UX Design Briefing

Persistent design reference for OMNIMOVE, a consumer, mobile-first multimodal journey
planner for Cassino (UNICAS 2025/2026). Derived **only** from the static frontend at
`omnimove-backend/src/main/resources/static` — the actual `.css`/`.html` of
`omnimove-traveller`, `omnimove-login`, `reset-password`, `omnimove-admin`.
Read this at the start of every UI/UX task. Share with the `omnimove-mobile` agent.

> **IMPORTANT — there are THREE distinct visual themes, not one.** The prompt assumed a
> single Orbitron/Plus Jakarta Sans system; the code disagrees. See §2/§3.

---

## 1. Overall aesthetic & goal

- **Traveller app** (`omnimove-traveller`) — the true consumer surface. **Light, friendly,
  fast, mobile-first**, built around a full-screen **Leaflet map** with a companion routes
  panel. Rounded surfaces, soft shadows, generous emerald-green accent, playful emoji icons.
  Aesthetic = "clean, optimistic, eco-forward transit app."
- **Login / reset-password** — a **dark, techy "terminal" aesthetic** (near-black bg, mono
  labels, blue accent). Onboarding/auth only.
- **Admin** (`omnimove-admin`) — a separate **dark ops/analytics console** (Inter +
  JetBrains Mono, cyan accent, dense tables/charts). Not consumer-facing; keep it isolated.

The consumer north star is the **traveller** theme. New consumer/mobile UI should match it.

---

## 2. Design tokens (exact `:root` custom properties)

### 2a. Traveller (light) — `omnimove-traveller.css` — THE consumer system
| Token | Value | Role |
|---|---|---|
| `--primary` | `#10b981` | brand emerald (primary CTAs, active, times) |
| `--primary-light` | `#d1fae5` | tinted bg (active nav, ok badges) |
| `--primary-dark` | `#059669` | hover/darker text on tint |
| `--text-dark` | `#0f172a` | headings/body, dark buttons, scrims |
| `--text-mid` | `#475569` | secondary text |
| `--text-soft` | `#94a3b8` | tertiary/placeholder/labels |
| `--bg-app` | `#f8fafc` | app/inset surface (inputs, metric boxes) |
| `--bg-white` | `#ffffff` | cards, sidebar, panels |
| `--border` | `#f1f5f9` | hairline dividers |
| `--border-mid` | `#e2e8f0` | card/input borders |
| `--blue` | `#3b82f6` | mode/secondary accent (scooter-ish, early) |
| `--amber` | `#f59e0b` | warning / favourite star / late |
| `--red` | `#ef4444` | error / delay / destructive |
| `--purple` | `#7c3aed` | tertiary action (btn-purple) |
| `--ai-gradient` | `linear-gradient(135deg,#10b981,#3b82f6)` | AI/eco/hero fills |
| `--sidebar-w` | `240px` | desktop sidebar width |
| `--shadow-sm` | `0 1px 3px rgba(0,0,0,.04)` | resting cards |
| `--shadow-md` | `0 4px 16px rgba(0,0,0,.06)` | hover lift |
| `--shadow-lg` | `0 12px 40px rgba(0,0,0,.1)` | overlays/panels |
| `--radius-sm` | `12px` | buttons, nav items, chips-in-cards |
| `--radius-md` | `20px` | cards, sheets, chips |
| `--radius-lg` | `28px` | hero/active-ticket cards, AI panel |

**Spacing:** no scale token — ad-hoc px (common rhythm: 6/8/10/12/14/16/18/20/24/28px).
**Transitions:** inline, mostly `0.15s` (interactions), `0.2s`–`0.28s` (toggles/drawer).
**Radii for pills:** `50px` / `20px` used directly for chips, badges, toggles.
Semantic status greens/reds also appear as **raw hex** (e.g. `#fef9c3`/`#92400e` amber
notice, `#dbeafe`/`#1e40af` blue "early", `#fee2e2` red) — not tokenized.

### 2b. Login / reset (dark) — `omnimove-login.css`, `reset-password.css` (identical set)
`--bg:#07090F` · `--panel:#0F1623` · `--panel2:#141E2E` · `--border:#1A2744` ·
`--accent:#3B82F6` · `--cyan:#06B6D4` · `--green:#22C55E` · `--red:#EF4444` ·
`--text:#E2E8F0` · `--dim:#4B5563` · `--mono:'DM Mono'` · `--display:'Syne'`.
Radii 8–12px, no shadow tokens.

### 2c. Admin (dark console) — `omnimove-admin.css` (own set, do not reuse for consumer)
`--bg-deep:#0c1117` · `--bg-panel:#111820` · `--bg-card:#161e28` · `--accent-green:#34d399`
· `--second-green:#10b981` · `--accent-cyan:#38bdf8` · `--accent-amber:#fbbf24` ·
`--accent-red:#f87171` · `--accent-blue:#60a5fa` · `--text-primary:#e2e8f0`. Sharp radii (2–5px),
Inter + JetBrains Mono, uppercase letter-spaced buttons.

**Note:** `--primary`/`--second-green` `#10b981` is the only value shared across all three
themes — the emerald brand green is the connective tissue.

---

## 3. Typography (fonts actually loaded)

| Surface | Display font | Body/label font | Loaded via |
|---|---|---|---|
| **Traveller** | `Orbitron` (400,700) | `Plus Jakarta Sans` (300–800) | Google Fonts |
| **Login / reset** | `Syne` (600–800) | `DM Mono` (400,500) | Google Fonts |
| **Admin** | `Inter` | `JetBrains Mono` (mono) | `--font-*` (Inter not linked → system-ui fallback) |

Traveller role mapping:
- **Orbitron** — display only, reserved for the `.brand-logo` wordmark (`OMNI` + emerald
  `MOVE` span, 19px/700, letter-spacing 1px). Nothing else uses it.
- **Plus Jakarta Sans** — everything else. Weights in use: 500 (AI msg body), 600 (nav,
  inputs), 700 (buttons, chips), 800 (headings, prices, times, ETAs, section titles).
- Size ladder (px): 8–9 micro-labels · 10–11 captions/meta · 12–13 body/buttons ·
  14–16 sub-headings · 18–22 titles/prices/ETAs · 26 eco points · 32 hero avatar glyph.
- Numeric emphasis pattern: big **800** numerals in `--primary` (route time 22px,
  arrival ETA 20px, price-tag 20px, eco-points 26px).

**Consistency flag:** the three themes share **no fonts**. Any unified consumer redesign
should standardize on Orbitron (display) + Plus Jakarta Sans (body).

---

## 4. Component inventory (real class names)

**Shell / nav (traveller)**
- `.sidebar` (240px) → `.sidebar-brand`, `.sidebar-user` (`.avatar` 40px, 14px radius),
  `.sidebar-nav` with `.nav-section-label` + `.nav-item` (`.active` = `--primary-light`
  bg / `--primary-dark` text) + `.nav-icon` + `.nav-badge` (pill). Footer `.eco-card`
  (gradient) with `.eco-points`.
- `.topbar` → `.topbar-search` (pill, `.search-seg` origin/dest/time + `.search-btn`),
  `.cats` chip rows (`.cat-chip`, `.active` = dark fill). Two chip groups: `#sortChips`
  (Eco/Budget/Fast) and `#modeChips` (Bus/Bike/Scooter).

**Journey / route cards**
- `.route-card` (map panel): `.route-top` (`.route-name` + `.route-time` big emerald),
  `.status-row` of `.status-badge` (`.s-delay/.s-occ/.s-ok` + `.delay-*` variants),
  `.metrics-row` (3-col `.metric-box` label/value), full-width `.action-btn` (`.btn-dark`
  `.btn-blue` `.btn-green` `.btn-purple`).
- `.route-hist-card` (history/favourites): `.route-hist-icon` mode tile (`.ri-bus`
  `.ri-bike` `.ri-scooter` `.ri-walk`, each its own pastel bg), info, `.route-hist-green`
  (green-index pill), `.fav-star` (amber when `.starred`).

**Arrivals bottom sheet** — `.stop-sheet-overlay` → `.stop-sheet` (radius `24px 24px 0 0`,
`.stop-sheet-handle` grabber, `env(safe-area-inset-bottom)` padding) → `.arrival-card`
(left border colour-codes status: `.late` amber, `.very-late` red, `.early` blue,
`.scheduled` grey) with `.arrival-route-short`, `.arrival-eta` (`.amber`/`.red`),
`.arrival-badge`, `.stop-check-btn`.

**Tickets** — `.active-ticket-card` (gradient hero + `.qr-btn` glassmorphic), `.type-selector`
of `.type-chip`, `.ticket-list` grid of `.ticket-option` (`h3`/`p`/`.price-tag`).

**Profile** — `.profile-hero-card` (gradient) + `.profile-avatar-lg` + `.profile-stats`
(4 `.pstat`), `.profile-tabs`/`.profile-tab`, `.ptab-pane`. Settings use `.pref-row` +
`.toggle` (`.on` slides knob) + `.pref-select`; `.payment-card` + `.pay-icon`.

**AI** — `.ai-fab` (gradient FAB) opens `.ai-overlay`/`.ai-panel` (top radius 28px, bottom
sheet) with `.ai-msg.bot`/`.ai-msg.user` bubbles, `.ai-input`/`.ai-send`.

**States** — `.empty-state` (emoji + copy), `.search-notice` (amber banner),
inline "Plan your trip" 🧭 placeholder, `.ac-list`/`.ac-item` autocomplete dropdown.

**Auth (login/reset)** — `.card` → `.tabs`/`.tab` (Sign In / Register), `.form.active`,
`.label`/`.input` (`.input-err`), `.field-err.visible`, `.btn`/`.btn-ghost`/`.btn-danger`,
`.msg.ok`/`.msg.err`, `.email-sent-box` (verification). Reset uses `.box`/`.field`/`.ferr`.

---

## 5. Layout system

- **Desktop traveller:** `body{display:flex}` → fixed `.sidebar` (240px) + `.main`
  (`.topbar` + `.content-area` of `.pane`s). Panes toggle via `.pane.active`
  (`#pane-map` / `#pane-tickets` / `#pane-profile`).
- **Map ↔ results coupling:** desktop = `#map` (flex:1) beside a 340px `.map-sidebar`
  (`.routes-list`). Mobile = map goes `position:absolute; inset:0` and `.map-sidebar`
  becomes a **draggable bottom sheet** (`height:40vh`, top radius 18px, `.sheet-handle`
  grabber, `touch-action:none` on sheet / `pan-y` on list).
- **Single breakpoint: `max-width:768px`** (plus a `480px` tweak in login). Everything
  mobile is layered through ~26 incremental `@media (max-width:768px)` "Step" blocks —
  heavy use of `!important` and iterative overrides (search bar was reworked many times
  into a stacked origin/destination grid). Fragile; refactor before extending.
- **Mobile chrome:** `.sidebar` becomes an off-canvas drawer (`translateX(-100%)`,
  z-index 2000) + `.menu-scrim`; a floating `.map-menu-fab` (top-left) opens it; option
  panes swap search for a `.mobile-back` ← + `.mobile-title`.
- **Safe areas:** `env(safe-area-inset-top)` (topbar padding) and
  `env(safe-area-inset-bottom)` (stop sheet) are honoured. Good.
- **Touch targets:** mobile `.hamburger`/`.mobile-back`/`.search-btn` set to **44×44**
  (login sets `min-height:44px` on btns/tabs, `input font-size:16px` to kill iOS zoom).
  BUT the final "slim search" steps force the origin/destination bar to **32px height** —
  below the 44px guideline. `.cat-chip`, `.toggle` (42×24), `.fav-star`, close ✕ (30px),
  and inline text buttons are also under 44px.

---

## 6. Journey-results UX

- **Options** render as `.route-card`s in `.routes-list`, sorted by the active `#sortChips`
  intent (🌱 Eco / 💸 Budget / ⚡ Fast) and filtered by `#modeChips`. Card shows name,
  a large emerald **total time**, status badges, and a 3-metric grid.
- **Legs/segments:** no dedicated per-leg list component in the static markup — legs are
  summarized via `.metric-box` values, `.status-badge`/`.delay-*` chips, and mode tiles
  (`.route-hist-icon` variants). Live stop detail lives in the arrivals sheet.
- **Times / ETA / delay:** `.route-time`, `.arrival-eta` (+ `.amber`/`.red`), scheduled-vs-
  live via `.arrival-times` and `.delay-chip` (`.d-ontime`/`.d-late`/`.d-unknown`,
  `.d-live`/`.d-hist`). Delay taxonomy: `on_time / early / slightly_late /
  significantly_late / unknown`, each a coloured badge.
- **Green-index:** surfaced as `.route-hist-green` pill, `.eco-card`/eco-points, and
  profile `statEcoPoints`/`statCo2Saved`. Green = core value signal, always emerald.
- **Empty/loading/error:** `.empty-state` (emoji+copy), inline 🧭 "Plan your trip"
  placeholder, `.search-notice` amber banner, sheet subtitle "Loading…", auth `.msg.ok/.err`
  and `.field-err`. No skeleton loaders / spinners in CSS.
- **Weather-aware:** `.weather-pill` in the routes header; preference "Hide bike/scooter/walk
  when raining" ties weather to results.
- **Onboarding/auth:** tabbed Sign In / Register; email verification via `#emailSentPanel`
  ("Check your email" + resend); Forgot-password link appears only after ≥2 failed logins
  (`#forgotBtn`); reset flow via `?reset=TOKEN` panel or standalone `reset-password.html`.
  Inline field validation with error states throughout.

---

## 7. Accessibility observations (current gaps)

- **No focus-visible styles** in traveller: inputs use `outline:none` with only a border
  colour change on `.input:focus` in auth; traveller topbar/AI inputs remove outline and
  add **no** replacement focus ring. Keyboard users get little/no focus indication.
- **Touch targets below 44px:** slim 32px search bar, `.cat-chip`, 30px close buttons,
  `.toggle` (24px tall), inline text `.action-btn`s. Login/reset handle this better (44px).
- **Icon-only controls lack labels in places:** many `aria-label`s exist (hamburger,
  back, menu-close, sheet close) — good — but emoji-as-icon buttons (swap ⇄, QR, star)
  and decorative emoji have no `aria-hidden`/text alternatives.
- **Contrast risks:** `--text-soft #94a3b8` on white ≈ 2.9:1 (fails AA for small text) — used
  widely for captions/placeholders. Gradient white text on emerald and amber-on-cream
  (`#92400e` on `#fef9c3`) are borderline; verify per use.
- **Toggles/tabs** are `<div onclick>` (not buttons/`role`/`aria-checked`) in traveller —
  not keyboard-operable. Admin's `.switch` correctly uses `aria-checked`.
- **Motion:** no `prefers-reduced-motion` handling for pulse/transition animations.
- **Semantics:** heavy `<div>` + inline `onclick`; nav items and chips aren't real buttons.

---

## 8. Consistency notes & recommendations

**Keep (the good, coherent patterns):**
- Emerald `#10b981` as the single brand accent; big 800-weight emerald numerals for the
  key metric (time/ETA/price/eco).
- Card language: white surface, `--border-mid` hairline, `--shadow-sm` → `--shadow-md`
  on hover, `translateY(-1px)` lift, radius `--radius-md` (20px).
- Gradient (`--ai-gradient`) reserved for "hero / AI / eco" moments only.
- Bottom-sheet + safe-area pattern for mobile detail; drawer + scrim for nav.
- Status colour semantics: green=ok, amber=warn/late, red=delay/error, blue=early/mode.

**Fix / watch when extending:**
1. **Tokenize what's raw.** Add semantic tokens for the status hexes (`#fef9c3/#92400e`,
   `#dbeafe/#1e40af`, `#fee2e2`) and a spacing scale; today they're inline literals.
2. **Kill the `!important` override cascade.** The mobile search bar was iterated ~10
   times; consolidate the 26 "Step" media blocks into one clean mobile stylesheet.
3. **Reconcile fonts.** Traveller=Orbitron/Jakarta, auth=Syne/DM Mono, admin=Inter/JetBrains.
   For a coherent consumer product, auth should adopt the traveller display/body pair.
4. **A11y baseline:** add visible `:focus-visible` rings, raise touch targets to ≥44px,
   convert `<div onclick>` toggles/chips/nav to `<button>` with `aria-*`, add
   `prefers-reduced-motion`, and re-check `--text-soft` contrast.
5. **Reduce inline styles.** Large chunks of traveller HTML carry inline `style="..."`
   (inputs, modals, buttons) that bypass the token system — migrate to classes.

**For the omnimove-mobile agent:** treat the **traveller light theme** as the canonical
consumer design system (tokens in §2a, type in §3, components in §4). The map-first layout,
draggable results sheet, safe-area handling, emerald metrics, and status colour semantics
are the load-bearing conventions to preserve. Do NOT pull admin/login dark tokens into
consumer surfaces.
