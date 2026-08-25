// XSS defense: escape any API-supplied string before inserting into innerHTML
function escHtml(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

//  FRONTEND ROUTE GUARD
// V-04 FIX: Token is in httpOnly cookie (sent automatically). User data is in sessionStorage.
const LOGIN_PAGE = 'omnimove-login.html';
const _user = JSON.parse(sessionStorage.getItem('omnimove_user') || '{}');
if (!_user.name && !_user.email) {
    // Come back here once signed in
    window.location.replace(OmniSession.loginUrlWithReturn(LOGIN_PAGE));
}
// The guard above never re-runs on a bfcache restore — this covers Back
OmniSession.bindSessionGuard(LOGIN_PAGE);

document.getElementById('sidebarName').textContent  = _user.name || _user.username || 'Utente';
document.getElementById('sidebarEmail').textContent = _user.email || '';
document.getElementById('profileName').textContent  = _user.name  || _user.username || 'Utente';
document.getElementById('profileEmail').textContent = _user.email || '';
document.getElementById('paymentEmail').textContent = _user.email || '';

// Apply saved language on load
applyTranslations();
// Sync the search button's data-label attribute (CSS ::after uses attr(data-label))
function syncSearchBtn() {
    const btn = document.getElementById('searchBtn');
    if (btn) btn.setAttribute('data-label', t('search_btn'));
}
syncSearchBtn();

// Named and anonymous are separate strings: "Ciao there" is what you get from dropping a
// placeholder name into a sentence only ever written for English.
function renderAiGreeting() {
    const name = _user.name || _user.username;
    document.getElementById('aiGreeting').textContent =
        name ? tf('ai_greeting', { name }) : t('ai_greeting_anon');
}
renderAiGreeting();

const API_BASE = '/omnimove/api/v1';

async function loadEcoStats() {
    try {
        const r = await apiFetch('/traveller/stats');
        if (!r.ok) throw new Error('stats ' + r.status);
        const s = await r.json();

        document.getElementById('sidebarEcoPoints').textContent = s.ecoPoints.toLocaleString() + ' pts';
        document.getElementById('sidebarEcoSub').textContent = tf('eco_saved_month', { kg: s.co2SavedKg });

        document.getElementById('statEcoPoints').textContent = s.ecoPoints.toLocaleString();
        document.getElementById('statCo2Saved').textContent  = s.co2SavedKg + ' kg';
        document.getElementById('statTrips').textContent     = s.trips;
        document.getElementById('statSpent').textContent     = '€' + s.spent30d;
    } catch (e) {
        console.warn('Could not load eco stats:', e);
    }
}

function togglePref(el) {
    el.classList.toggle('on');
}

async function loadPreferences() {
    try {
        const r = await apiFetch('/traveller/preferences');
        if (!r.ok) throw new Error('prefs ' + r.status);
        const p = await r.json();

        // Select
        const sel = document.getElementById('prefDefaultMode');
        if (sel) sel.value = p.defaultJourneyMode || 'ECO';

        // Toggle helper
        const set = (id, val) => {
            const el = document.getElementById(id);
            if (el) el.classList.toggle('on', !!val);
        };

        set('prefAvoidOccupancy', p.avoidHighOccupancy);
        set('prefShowWalking',    p.showWalking);
        set('prefBikeOverBus',    p.preferBikeOverBus);
        set('prefOnlyBusRain',    p.onlyBusWhenRaining);
        set('prefNotifyDelays',   p.notifyDelays);
        set('prefNotifyTicket',   p.notifyTicketExpiry);
        set('prefNotifyEcoTip',   p.notifyEcoTip);

        // Applica subito il sort di default ai chip nella topbar
        const sortMap = { ECO: 'eco', BUDGET: 'budget', FAST: 'fast' };
        const sortVal = sortMap[p.defaultJourneyMode] || 'eco';
        document.querySelectorAll('#sortChips .cat-chip').forEach(c => {
            c.classList.toggle('active', c.dataset.sort === sortVal);
        });
        activeSort = sortVal;

    } catch (e) {
        console.warn('Could not load preferences:', e);
    }
}

async function savePreferences() {
    const isOn = id => document.getElementById(id)?.classList.contains('on') ?? false;

    const body = {
        defaultJourneyMode: document.getElementById('prefDefaultMode')?.value || 'ECO',
        avoidHighOccupancy: isOn('prefAvoidOccupancy'),
        showWalking:        isOn('prefShowWalking'),
        preferBikeOverBus:  isOn('prefBikeOverBus'),
        onlyBusWhenRaining: isOn('prefOnlyBusRain'),
        notifyDelays:       isOn('prefNotifyDelays'),
        notifyTicketExpiry: isOn('prefNotifyTicket'),
        notifyEcoTip:       isOn('prefNotifyEcoTip')
    };

    try {
        const r = await apiFetch('/traveller/preferences', {
            method: 'PUT',
            body: JSON.stringify(body)
        });
        if (!r.ok) throw new Error('save prefs ' + r.status);

        // Aggiorna subito il sort attivo nella topbar
        const sortMap = { ECO: 'eco', BUDGET: 'budget', FAST: 'fast' };
        const sortVal = sortMap[body.defaultJourneyMode] || 'eco';
        document.querySelectorAll('#sortChips .cat-chip').forEach(c => {
            c.classList.toggle('active', c.dataset.sort === sortVal);
        });
        activeSort = sortVal;

        showToast(t('toast_prefs_saved'));
    } catch (e) {
        console.warn('Could not save preferences:', e);
        showToast(t('toast_prefs_error'), true);
    }
}

function timeAgoLabel(dateStr) {
    const d = new Date(dateStr);
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const yest = new Date(now); yest.setDate(now.getDate() - 1);
    const isYest = d.toDateString() === yest.toDateString();

    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');

    if (sameDay) return { date: t('lbl_today'), sub: `${t('lbl_today')}, ${hh}:${mm}` };
    if (isYest)  return { date: t('lbl_yesterday'), sub: `${t('lbl_yesterday')}, ${hh}:${mm}` };
    const opts = { weekday: 'short', day: 'numeric', month: 'short' };
    const label = d.toLocaleDateString('en-GB', opts);
    return { date: label, sub: `${label}, ${hh}:${mm}` };
}

const MODE_ICON = { BUS: ['ri-bus', '🚌'], BIKE: ['ri-bike', '🚲'], SCOOTER: ['ri-scooter', '🛴'], WALK: ['ri-walk', '🚶'] };

function renderHistory(items) {
    const container = document.getElementById('history-list');
    if (!items || items.length === 0) {
        container.innerHTML = `<div class="empty-state">${t('no_trips')}</div>`;
        return;
    }

    container.innerHTML = items.map(j => {
        const [iconClass, emoji] = MODE_ICON[j.mode] || ['ri-bus', '🚌'];
        const { date, sub } = timeAgoLabel(j.createdAt);
        const cost = (j.costEuros ?? 0).toFixed(2);
        const origin = j.originName || t('my_location');
        const dest = j.destName || '—';

        return `
        <div class="route-hist-card">
            <div class="route-hist-icon ${iconClass}">${emoji}</div>
            <div class="route-hist-info">
                <div class="route-hist-name">${origin} → ${dest}</div>
                <div class="route-hist-sub">${sub} · €${cost}</div>
            </div>
            <div class="route-hist-meta">
                <div class="route-hist-date">${date}</div>
                <div class="route-hist-green">🌱 ${j.greenIndex}</div>
            </div>
            <span class="fav-star ${j.isFavorite ? 'starred' : ''}"
                  data-mode="${j.mode}" data-origin="${escAttr(origin)}" data-dest="${escAttr(dest)}"
                  title="Add to favourites">${j.isFavorite ? '★' : '☆'}</span>
        </div>`;
    }).join('');
}

async function loadHistory() {
    try {
        const r = await apiFetch('/traveller/history');
        if (!r.ok) throw new Error('history ' + r.status);
        const items = await r.json();
        renderHistory(items);
    } catch (e) {
        console.warn('Could not load history:', e);
    }
}

function renderFavorites(items) {
    const container = document.getElementById('favs-list');
    if (!items || items.length === 0) {
        container.innerHTML = `<div class="empty-state">${t('no_favs')}</div>`;
        return;
    }

    container.innerHTML = items.map(f => {
        const [iconClass, emoji] = MODE_ICON[f.mode] || ['ri-bus', '🚌'];
        return `
        <div class="route-hist-card">
            <div class="route-hist-icon ${iconClass}">${emoji}</div>
            <div class="route-hist-info">
                <div class="route-hist-name">${f.originName} → ${f.destName}</div>
                <div class="route-hist-sub">${f.mode} · ~€${f.avgCost.toFixed(2)} · Used ${f.usedCount}×</div>
            </div>
            <div class="route-hist-meta">
                <div class="route-hist-green">🌱 ${f.greenIndex}</div>
            </div>
            <span class="fav-star starred" data-mode="${f.mode}" data-origin="${escAttr(f.originName)}" data-dest="${escAttr(f.destName)}">★</span>
        </div>`;
    }).join('');
}

async function loadFavorites() {
    try {
        const r = await apiFetch('/traveller/favorites');
        if (!r.ok) throw new Error('favorites ' + r.status);
        const items = await r.json();
        renderFavorites(items);
    } catch (e) {
        console.warn('Could not load favourites:', e);
    }
}

loadEcoStats();
loadHistory();
loadPreferences();

// Re-render dynamic content when language switches
window._onLangChange = () => {
    renderAiGreeting();
    // "My Location" is a label, not the value — dataset.id is what identifies the field,
    // so the text has to be re-rendered in the new language
    ['originSelect', 'destSelect'].forEach(id => {
        const el = document.getElementById(id);
        if (el && el.dataset.id === 'GPS') el.value = t('my_location');
    });
    loadEcoStats();
    loadHistory();
    loadFavorites();
    updateWeatherPill();
    syncSearchBtn();
    updateTimeDisplay();
};

// ── Weather pill on page load ──────────────────────────────────────
async function updateWeatherPill() {
    try {
        const r = await apiFetch('/journeys/weather');
        if (!r.ok) return;
        const w = await r.json();
        const temp = w.temperature + '°C';
        const key  = 'weather_' + w.condition;
        const raw  = t(key);
        const pill = document.querySelector('.weather-pill');
        if (pill) pill.textContent = raw.replace('{t}', temp);
    } catch (e) { /* keep placeholder */ }
}
updateWeatherPill();

// ── Time Picker ─────────────────────────────────────────────────────
const _TP_ITEM_H = 40; // must match CSS .tp-drum-item height
const _TP_REPS   = 7;  // repetitions for seamless infinite scroll
const _TP_MID    = Math.floor(_TP_REPS / 2);

let _pickerHour = null;
let _pickerMin  = null;
let _pickerMode = 'depart'; // 'depart' | 'arrive'

/** Returns a Date for the user's chosen departure time (or now if nothing chosen). */
function _getPickerTripStart() {
    if (_pickerHour === null) return new Date();
    const d = new Date();
    d.setHours(_pickerHour, _pickerMin, 0, 0);
    // If the chosen time is already past today, shift to tomorrow
    if (d < new Date()) d.setDate(d.getDate() + 1);
    return d;
}
/** Format a Date as "HH:MM". */
function _fmtHHMM(date) {
    return date.getHours().toString().padStart(2,'0') + ':' + date.getMinutes().toString().padStart(2,'0');
}

function updateTimeDisplay() {
    let label, pillLabel;
    if (_pickerHour === null) {
        label     = t('time_now');
        pillLabel = t('time_now');
    } else {
        const hh = String(_pickerHour).padStart(2, '0');
        const mm = String(_pickerMin).padStart(2, '0');
        const modeLabel = _pickerMode === 'arrive' ? t('time_arrive') : t('time_depart');
        label     = `${modeLabel} ${hh}:${mm}`;
        pillLabel = `${hh}:${mm}`;
    }
    // Sidebar pill: compact — just the time (or "Now"), highlighted when a time is set
    const sidebarEl   = document.getElementById('sidebarTimeDisplay');
    const sidebarPill = document.getElementById('sidebarTimePill');
    if (sidebarEl) sidebarEl.textContent = pillLabel;
    if (sidebarPill) {
        sidebarPill.classList.toggle('sidebar-time-pill--active', _pickerHour !== null);
    }
}
updateTimeDisplay();

function _buildDrum(drumId, range, selected) {
    const drum = document.getElementById(drumId);
    drum.innerHTML = '';
    // 2 invisible pads at top — lets value 0 snap-center at scrollTop 0
    for (let i = 0; i < 2; i++) {
        const p = document.createElement('div');
        p.className = 'tp-drum-item tp-pad';
        drum.appendChild(p);
    }
    // _TP_REPS full cycles of 0…range-1
    for (let rep = 0; rep < _TP_REPS; rep++) {
        for (let v = 0; v < range; v++) {
            const el = document.createElement('div');
            el.className = 'tp-drum-item';
            el.textContent = String(v).padStart(2, '0');
            drum.appendChild(el);
        }
    }
    // 2 invisible pads at bottom
    for (let i = 0; i < 2; i++) {
        const p = document.createElement('div');
        p.className = 'tp-drum-item tp-pad';
        drum.appendChild(p);
    }
    // Position at middle rep, selected value (instant — no smooth-scroll)
    drum.scrollTop = (_TP_MID * range + selected) * _TP_ITEM_H;
}

// Attach loop-back listeners ONCE per drum (idempotent)
let _tpListenersAttached = false;
function _attachDrumLoops() {
    if (_tpListenersAttached) return;
    _tpListenersAttached = true;
    _attachLoop('drumHours', 24);
    _attachLoop('drumMins',  60);
}
function _attachLoop(drumId, range) {
    const drum = document.getElementById(drumId);
    const reset = () => {
        const idx = Math.round(drum.scrollTop / _TP_ITEM_H);
        if (idx < range || idx >= (_TP_REPS - 1) * range) {
            const val = ((idx % range) + range) % range;
            drum.scrollTop = (_TP_MID * range + val) * _TP_ITEM_H;
        }
    };
    // scrollend fires after snap animation settles — perfect for loop reset
    if ('onscrollend' in document) {
        drum.addEventListener('scrollend', reset);
    } else {
        let timer;
        drum.addEventListener('scroll', () => { clearTimeout(timer); timer = setTimeout(reset, 120); }, { passive: true });
    }
}

function openTimePicker() {
    const now = new Date();
    const selH = _pickerHour !== null ? _pickerHour : now.getHours();
    const selM = _pickerMin  !== null ? _pickerMin  : now.getMinutes();

    // Build DOM while hidden, then show — scroll must be set AFTER display:flex takes effect
    _buildDrum('drumHours', 24, selH);
    _buildDrum('drumMins',  60, selM);
    _attachDrumLoops();

    document.getElementById('tpDepart').classList.toggle('active', _pickerMode === 'depart');
    document.getElementById('tpArrive').classList.toggle('active', _pickerMode === 'arrive');
    document.querySelectorAll('#timePickerOverlay [data-i18n]').forEach(el => {
        el.textContent = t(el.dataset.i18n);
    });

    // Show overlay first — otherwise scrollTop on display:none elements is ignored
    document.getElementById('timePickerOverlay').classList.add('open');

    // Re-apply scroll positions after the browser has rendered the overlay
    requestAnimationFrame(() => {
        document.getElementById('drumHours').scrollTop = (_TP_MID * 24 + selH) * _TP_ITEM_H;
        document.getElementById('drumMins').scrollTop  = (_TP_MID * 60 + selM) * _TP_ITEM_H;
    });
}

function closeTimePicker(e) {
    if (e && e.target !== document.getElementById('timePickerOverlay')) return;
    document.getElementById('timePickerOverlay').classList.remove('open');
}

function setTimeMode(mode) {
    _pickerMode = mode;
    document.getElementById('tpDepart').classList.toggle('active', mode === 'depart');
    document.getElementById('tpArrive').classList.toggle('active', mode === 'arrive');
}

function _getDrumValue(drumId, range) {
    const drum = document.getElementById(drumId);
    const idx = Math.round(drum.scrollTop / _TP_ITEM_H);
    return ((idx % range) + range) % range;
}

function confirmTimePicker() {
    _pickerHour = _getDrumValue('drumHours', 24);
    _pickerMin  = _getDrumValue('drumMins',  60);
    document.getElementById('timePickerOverlay').classList.remove('open');
    updateTimeDisplay();
    // Re-run search automatically if origin + dest are already set
    const destId = document.getElementById('destSelect')?.dataset?.id;
    if (destId && STOPS[destId] && getOrigin()) doSearch();
}

function resetTimeToNow() {
    _pickerHour = null;
    _pickerMin  = null;
    _pickerMode = 'depart';
    document.getElementById('timePickerOverlay').classList.remove('open');
    updateTimeDisplay();
    // Re-sync the Depart toggle to active
    document.getElementById('tpDepart')?.classList.add('active');
    document.getElementById('tpArrive')?.classList.remove('active');
    // Re-run search if origin + dest are already set
    const destId = document.getElementById('destSelect')?.dataset?.id;
    if (destId && STOPS[destId] && getOrigin()) doSearch();
}

async function apiFetch(path, options = {}) {
    // V-04: auth rides on the httpOnly cookie, no Authorization header to build
    const res = await fetch(API_BASE + path, {
        ...options,
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            ...(options.headers || {})
        }
    });
    // Session revoked or expired server-side → stop pretending we are logged in
    return OmniSession.handleUnauthorized(res, LOGIN_PAGE);
}

// ── Map init ──────────────────────────────────────────────────────
const map = L.map('map', { zoomControl: true }).setView([41.4901, 13.8303], 15);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap'
}).addTo(map);

// ── Icons ─────────────────────────────────────────────────────────

// "you are here" must never read as one more bus stop. Stops are green circles
// carrying an M; this is a blue dot with a pulsing halo and no letter — different colour,
// different shape, different behaviour. Styling lives in .me-* classes so the white ring
// and dark outline keep it legible on light streets and on dark tiles alike.
const userIcon = L.divIcon({
    html: '<div class="me-marker"><span class="me-halo"></span><span class="me-dot"></span></div>',
    className: 'me-icon',
    iconSize: [26, 26],
    iconAnchor: [13, 13]
});

// Destination pin — SVG teardrop
function makeDestIcon(color) {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="42" viewBox="0 0 28 42">
        <circle cx="14" cy="14" r="12" fill="${color}" stroke="white" stroke-width="2.5"
            style="filter:drop-shadow(0 3px 6px rgba(0,0,0,0.35))"/>
        <circle cx="14" cy="14" r="5" fill="white" opacity="0.9"/>
        <line x1="14" y1="26" x2="14" y2="41" stroke="${color}" stroke-width="3"
            stroke-linecap="round" style="filter:drop-shadow(0 2px 3px rgba(0,0,0,0.2))"/>
    </svg>`;
    return L.divIcon({
        html: svg,
        className: '',
        iconSize: [28, 42],
        iconAnchor: [14, 41]
    });
}

// ── Stop markers (bus stops) ──────────────────────────────────────
let STOPS = {};  // popolato dinamicamente da loadStops()

window._stopMarkers = [];

const STOP_ICON = L.divIcon({
    html: '<div style="background:#10b981;color:white;width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 8px rgba(0,0,0,0.2);font-size:11px;font-weight:800">M</div>',
    className: '', iconSize: [28, 28]
});

function renderStopMarkers() {
    window._stopMarkers.forEach(m => map.removeLayer(m));
    window._stopMarkers = [];
    Object.values(STOPS).forEach(stop => {
        // One click straight to the arrivals sheet. The popup that used to sit in between
        // only repeated the stop name, which the sheet shows as its own title anyway —
        // no data passes through the DOM, so there is nothing left to escape here.
        const marker = L.marker([stop.lat, stop.lon], { icon: STOP_ICON })
            .addTo(map)
            .on('click', () => showStopArrivals(stop.id, stop.name));
        window._stopMarkers.push(marker);
    });
}

// ── The line network, drawn on the main map from the first frame ───
// Opening the app used to show bare stop markers: the traveller had to tap a
// stop, then a line inside the arrivals sheet, before a single route appeared.
// The network is the map's baseline content now — every line in its own colour,
// no taps required — and tapping one only promotes it to the foreground.

let NETWORK_ROUTES = [];        // [{route_id, short_name, long_name, points}]
window._networkLayers = {};     // routeId → polyline on the main map
let _focusedRouteId  = null;
let _networkHidden   = false;   // true while a journey preview owns the map

// Urban lines share whole streets. Drawn at equal width they would hide one
// another, so each successive line is thinner than the one before: on a shared
// stretch every colour keeps a visible band at the edges.
function networkWeight(index) {
    return Math.max(3, 8 - index * 1.8);
}

async function loadNetworkLines() {
    try {
        const r = await apiFetch('/journeys/routes/shapes');
        if (!r.ok) throw new Error('shapes ' + r.status);
        const routes = await r.json();
        if (!Array.isArray(routes) || !routes.length) return;
        NETWORK_ROUTES = routes.filter(rt => Array.isArray(rt.points) && rt.points.length > 1);
        registerRouteColors(routes);
        drawNetworkLines();
        renderNetworkLegend();
    } catch (e) {
        console.error('loadNetworkLines failed:', e);
        showToast(t('toast_network_error'), true);
    }
}

// Every line the feed knows about, including any without geometry: the arrivals
// sheet and the interchange badges name lines that may never be drawn on the map.
function registerRouteColors(routes) {
    ROUTE_COLORS.byId = {};
    ROUTE_COLORS.byShortName = {};
    ROUTE_COLORS.textByShortName = {};
    routes.forEach(r => {
        if (!r.color) return;
        const hex = '#' + r.color;
        ROUTE_COLORS.byId[r.route_id] = hex;
        // First line to claim a short name keeps it. Two routes can share one
        // ("11" is both the Liceo and the ITIS run) and a badge showing only the
        // number cannot tell them apart, so it has to settle on one colour.
        if (!ROUTE_COLORS.byShortName[r.short_name]) {
            ROUTE_COLORS.byShortName[r.short_name] = hex;
            if (r.text_color) ROUTE_COLORS.textByShortName[r.short_name] = '#' + r.text_color;
        }
    });
}

function drawNetworkLines() {
    Object.values(window._networkLayers).forEach(l => { try { map.removeLayer(l); } catch(_) {} });
    window._networkLayers = {};

    NETWORK_ROUTES.forEach((route, i) => {
        const color = routeColorById(route.route_id, route.short_name);
        const line  = L.polyline(route.points.map(p => [p[0], p[1]]), {
            color,
            weight:   networkWeight(i),
            opacity:  0.85,
            lineCap:  'round',
            lineJoin: 'round'
        }).addTo(map);

        const label = route.long_name
            ? `<b>${escHtml(route.short_name)}</b> · ${escHtml(route.long_name)}`
            : `<b>${escHtml(route.short_name)}</b>`;
        line.bindTooltip(label, { sticky: true, direction: 'top' });
        line.on('click', () => drawBusRoute(route.route_id, route.short_name, color));

        window._networkLayers[route.route_id] = line;
    });
}

// Highlight one line without removing the rest: dimming instead of hiding keeps
// the traveller's sense of where this route sits inside the network.
function focusNetworkLine(routeId) {
    _focusedRouteId = routeId;
    NETWORK_ROUTES.forEach((route, i) => {
        const layer = window._networkLayers[route.route_id];
        if (!layer) return;
        const isFocus = route.route_id === routeId;
        layer.setStyle({
            opacity: isFocus ? 1 : 0.15,
            weight:  isFocus ? Math.max(6, networkWeight(i)) : networkWeight(i)
        });
        if (isFocus) layer.bringToFront();
    });
    document.querySelectorAll('.nl-chip').forEach(c =>
        c.classList.toggle('nl-chip--active', c.dataset.routeId === routeId));
}

function clearNetworkFocus() {
    _focusedRouteId = null;
    // Re-asserting the original order matters as much as the style: bringToFront
    // during focus left the highlighted line on top, covering the thinner ones.
    NETWORK_ROUTES.forEach((route, i) => {
        const layer = window._networkLayers[route.route_id];
        if (!layer) return;
        layer.setStyle({ opacity: 0.85, weight: networkWeight(i) });
        layer.bringToFront();
    });
    document.querySelectorAll('.nl-chip').forEach(c => c.classList.remove('nl-chip--active'));
}

// A planned journey draws its own legs; the full network underneath would turn
// the map into noise, so it steps aside until the journey is cleared.
function setNetworkLinesVisible(visible) {
    _networkHidden = !visible;
    Object.values(window._networkLayers).forEach(l => {
        try { visible ? l.addTo(map) : map.removeLayer(l); } catch(_) {}
    });
    const legend = document.getElementById('networkLegend');
    if (legend) legend.classList.toggle('network-legend--hidden', !visible);
}

// A Leaflet control rather than a div in the pane: the map sits next to the
// Smart Routes sidebar on desktop and fills the pane on mobile, and only the
// control keeps the legend pinned inside the map itself in both layouts.
let _legendControl = null;

function renderNetworkLegend() {
    if (!_legendControl) {
        const Legend = L.Control.extend({
            options: { position: 'topright' },
            onAdd: function () {
                const div = L.DomUtil.create('div', 'network-legend');
                div.id = 'networkLegend';
                // Chip taps belong to the chips, not to the map under them
                L.DomEvent.disableClickPropagation(div);
                L.DomEvent.disableScrollPropagation(div);
                return div;
            }
        });
        _legendControl = new Legend().addTo(map);
    }
    const legend = document.getElementById('networkLegend');
    if (!legend) return;
    legend.innerHTML =
        `<span class="nl-title">${escHtml(t('legend_lines'))}</span>` +
        NETWORK_ROUTES.map(r =>
            `<button type="button" class="nl-chip" data-route-id="${escHtml(r.route_id)}"` +
            ` style="--nl-color:${routeColorById(r.route_id, r.short_name)};` +
            `--nl-text:${routeTextColor(r.short_name, routeColorById(r.route_id, r.short_name))}"` +
            ` title="${escHtml(r.long_name || r.short_name)}">` +
            `<span class="nl-swatch"></span>${escHtml(r.short_name)}</button>`
        ).join('') +
        `<button type="button" class="nl-chip nl-chip--reset" onclick="resetNetworkView()">` +
        `${escHtml(t('legend_all'))}</button>`;
}

document.addEventListener('click', e => {
    const chip = e.target.closest('.nl-chip[data-route-id]');
    if (!chip) return;
    const route = NETWORK_ROUTES.find(r => r.route_id === chip.dataset.routeId);
    if (!route) return;
    // A second tap on the active chip clears the highlight instead of re-opening it
    if (_focusedRouteId === route.route_id) { resetNetworkView(); return; }
    drawBusRoute(route.route_id, route.short_name, routeColorById(route.route_id, route.short_name));
});

// Back to the whole network: no line highlighted, no stop list open.
function resetNetworkView() {
    clearBusRoute();
    // Fresh bounds object: Polyline.getBounds() hands back the layer's own
    // instance, so extending it in place would corrupt the layer.
    const bounds = L.latLngBounds([]);
    Object.values(window._networkLayers).forEach(l => {
        const b = l.getBounds();
        if (b && b.isValid()) bounds.extend(b);
    });
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [50, 50] });
}

// ── Stop arrivals bottom sheet ─────────────────────────────────────

const STATUS_BG = {
    ON_TIME:'background:#d1fae5;color:#065f46',
    EARLY:'background:#dbeafe;color:#1e40af',
    SLIGHTLY_LATE:'background:#fef9c3;color:#92400e',
    SIGNIFICANTLY_LATE:'background:#fee2e2;color:#991b1b',
    SCHEDULED:'background:#f1f5f9;color:#475569',
};
const STATUS_LABEL = {
    ON_TIME:'On Time', EARLY:'Early', SLIGHTLY_LATE:'Slightly Late',
    SIGNIFICANTLY_LATE:'Late', SCHEDULED:'Scheduled',
};
const CARD_CLASS = {
    SLIGHTLY_LATE:'late', SIGNIFICANTLY_LATE:'very-late',
    EARLY:'early', SCHEDULED:'scheduled',
};
const CROWDING_BG = {
    LOW:'background:#d1fae5;color:#065f46',
    MEDIUM:'background:#fef9c3;color:#92400e',
    HIGH:'background:#fed7aa;color:#9a3412',
    VERY_HIGH:'background:#fee2e2;color:#991b1b',
};
function getCrowdingLabel(level) {
    const map = { LOW:'crowd_low', MEDIUM:'crowd_medium', HIGH:'crowd_high', VERY_HIGH:'crowd_very_high' };
    return t(map[level] || level);
}

let _sheetCurrentStopId = null;   // stop whose arrivals are currently showing

async function showStopArrivals(stopId, stopName) {
    _sheetCurrentStopId = stopId;
    const overlay  = document.getElementById('stopSheetOverlay');
    const title    = document.getElementById('stopSheetTitle');
    const subtitle = document.getElementById('stopSheetSubtitle');
    const list     = document.getElementById('stopSheetList');

    title.textContent    = stopName;
    subtitle.textContent = t('loading_buses');
    list.innerHTML       = '';
    overlay.classList.add('open');

    try {
        const r = await apiFetch(
            '/journeys/stops/' + encodeURIComponent(stopId) + '/arrivals?limit=10');
        if (!r.ok) throw new Error(r.status);
        const arrivals = await r.json();
        const routeCount = new Set(arrivals.map(a => a.route_short_name || a.route_name)).size;
        subtitle.textContent = arrivals.length
            ? `${routeCount} ${routeCount !== 1 ? t('lbl_lines') : t('lbl_line')} · ${t('lbl_next_departures')}`
            : t('no_buses');
        renderArrivals(list, arrivals);
    } catch(e) {
        subtitle.textContent = t('err_arrivals');
        list.innerHTML = `<p style="color:var(--text-soft);text-align:center;padding:24px 0">${t('err_service')}</p>`;
    }
}

function closeStopSheet() {
    document.getElementById('stopSheetOverlay').classList.remove('open');
    _sheetCurrentStopId = null;
}

function handleSheetBackdrop(e) {
    if (e.target === document.getElementById('stopSheetOverlay')) closeStopSheet();
}

document.addEventListener('keydown', e => { if (e.key === 'Escape') closeStopSheet(); });

// ── Route shape drawing from next-bus cards ────────────────────────
let _busRouteLayers  = [];   // polyline + circle markers for the drawn route
let _busRouteBanner  = null; // the dismissible banner element

document.addEventListener('click', e => {
    const row = e.target.closest('.tmb-route-tappable');
    if (!row) return;
    const routeId  = row.dataset.routeId;
    const name     = row.dataset.routeShort;
    const color    = row.dataset.routeColor;
    const routeDir = row.dataset.routeDir || '';
    if (routeId) drawBusRoute(routeId, name, color, routeDir);
});

let _slpPreviousStopId   = null;
let _slpPreviousStopName = null;

// The stop the traveller came from, marked in red along the line and in the list.
// Distinct from _sheetCurrentStopId, which only lives as long as the sheet is open.
let _slpHighlightStopId = null;

// State for the invert-route feature
let _slpShapePoints = null;
let _slpStopList    = null;
let _slpShortName   = null;
let _slpColor       = null;
let _slpRouteDir    = null;
let _slpReversed    = false;

async function drawBusRoute(routeId, shortName, color, routeDir = '') {
    // Drop whatever line was highlighted before, then promote this one.
    clearRouteHighlight();

    // Remember context so back button can reopen the arrivals sheet
    _slpPreviousStopId   = _sheetCurrentStopId;
    _slpPreviousStopName = _slpPreviousStopId
        ? (document.getElementById('stopSheetTitle')?.textContent || null) : null;
    _slpHighlightStopId  = _slpPreviousStopId;   // the stop to mark along the line
    _slpReversed         = false;

    // Close the arrivals sheet — the stop list panel takes its place
    closeStopSheet();

    // Dim the rest of the network rather than erasing it
    focusNetworkLine(routeId);

    // Generic stop markers step aside for this line's own dots, which carry the
    // stop name and open the same arrivals sheet on tap.
    (window._stopMarkers || []).forEach(m => { try { map.removeLayer(m); } catch(_) {} });

    try {
        // Geometry is already in memory from the network load; only the ordered
        // stop list still needs a round trip.
        const cached   = NETWORK_ROUTES.find(r => r.route_id === routeId);
        // Swallowed here rather than left dangling: the shape request below is
        // awaited first, and a rejection nobody is listening to yet is an
        // unhandled rejection.
        const stopsReq = apiFetch('/journeys/routes/' + encodeURIComponent(routeId) + '/stops')
                            .catch(() => null);

        let points = cached ? cached.points : null;
        if (!points) {
            const shapeRes = await apiFetch('/journeys/routes/' + encodeURIComponent(routeId) + '/shape');
            if (!shapeRes.ok) { showToast(t('toast_shape_unavailable'), true); clearBusRoute(); return; }
            points = await shapeRes.json();   // [[lat, lon, isStop], ...]
        }

        const stopsRes = await stopsReq;
        const stopList = stopsRes && stopsRes.ok ? await stopsRes.json() : [];

        if (!points.length) { showToast(t('toast_shape_empty'), true); clearBusRoute(); return; }

        // A line with no cached geometry (never returned by /routes/shapes) still
        // has to be drawn, otherwise focusing it would highlight nothing.
        if (!cached) {
            const line = L.polyline(points.map(p => [p[0], p[1]]),
                { color, weight: 6, opacity: 0.95 }).addTo(map);
            _busRouteLayers.push(line);
        }

        renderRouteStopDots(stopList, points, color);

        // Opened from the map or the legend there is no direction to inherit from
        // an arrivals card, so the terminus stands in for it.
        const dir = routeDir || (stopList.length ? stopList[stopList.length - 1].name : '');

        // Store for invert-route toggle
        _slpShapePoints = points;
        _slpStopList    = stopList;
        _slpShortName   = shortName;
        _slpColor       = color;
        _slpRouteDir    = dir;

        // Stop list is a sheet now, not a full-screen page: the route stays
        // visible on the main map right next to the list of its stops.
        showStopListPanel(shortName, color, stopList, points, dir, false);

        fitRouteBounds(L.latLngBounds(points.map(p => [p[0], p[1]])));

    } catch(e) {
        showToast(t('toast_shape_error'), true);
        clearBusRoute();
    }
}

// White dots on every stop of the highlighted line. They resolve their name and
// id from the ordered stop list; the shape's isStop vertices are the fallback for
// a route with geometry but no timetable rows.
function renderRouteStopDots(stopList, points, color) {
    const dots = (stopList && stopList.length)
        ? stopList.map(s => ({ id: s.stop_id, name: s.name, lat: s.lat, lon: s.lon }))
        : points.filter(p => p[2]).map(p => ({ id: null, name: null, lat: p[0], lon: p[1] }));

    dots.forEach(s => {
        const isCurrent = _slpHighlightStopId && s.id === _slpHighlightStopId;
        const dot = L.circleMarker([s.lat, s.lon], {
            radius:      isCurrent ? 9 : 6,
            color:       isCurrent ? '#ef4444' : color,
            fillColor:   '#ffffff',
            fillOpacity: 1,
            weight:      isCurrent ? 3 : 2,
            interactive: true
        }).addTo(map);

        if (s.name) dot.bindTooltip(`<b>${escHtml(s.name)}</b>`, { direction: 'top', offset: [0, -6] });
        if (s.id)   dot.on('click', () => showStopArrivals(s.id, s.name || s.id));
        _busRouteLayers.push(dot);
    });
}

// The stop list sheet covers part of the map; without compensating for it the
// route would be centred behind the sheet and read as half missing.
function fitRouteBounds(bounds) {
    if (!bounds || !bounds.isValid()) return;
    const panel  = document.getElementById('stopListPanel');
    const open   = panel && panel.classList.contains('open');
    const rect   = open ? panel.getBoundingClientRect() : null;
    const mobile = window.matchMedia('(max-width: 768px)').matches;

    if (!open)   { map.fitBounds(bounds, { padding: [50, 50] }); return; }
    if (mobile)  { map.fitBounds(bounds, { paddingTopLeft: [30, 30], paddingBottomRight: [30, rect.height + 20] }); return; }
    map.fitBounds(bounds, { paddingTopLeft: [rect.width + 40, 40], paddingBottomRight: [40, 40] });
}

// Everything drawn on top of the base network for one highlighted line.
function clearRouteHighlight() {
    _busRouteLayers.forEach(l => { try { map.removeLayer(l); } catch(_) {} });
    _busRouteLayers = [];
    clearNetworkFocus();
    if (!_networkHidden && window._stopMarkers)
        window._stopMarkers.forEach(m => { try { m.addTo(map); } catch(_) {} });
}

function closeStopListPanel() {
    const panel = document.getElementById('stopListPanel');
    if (panel) panel.classList.remove('open');
    clearRouteHighlight();
    // Reopen the next-buses sheet if we came from one
    if (_slpPreviousStopId && _slpPreviousStopName) {
        showStopArrivals(_slpPreviousStopId, _slpPreviousStopName);
    }
    _slpPreviousStopId = _slpPreviousStopName = _slpHighlightStopId = null;
}

function clearBusRoute() {
    clearRouteHighlight();
    const panel = document.getElementById('stopListPanel');
    if (panel) panel.classList.remove('open');
    _slpPreviousStopId = _slpPreviousStopName = _slpHighlightStopId = null;
}

function showBusRouteBanner(shortName, color) {
    if (_busRouteBanner) _busRouteBanner.remove();
    const banner = document.createElement('div');
    banner.id = 'busRouteBanner';
    banner.innerHTML =
        `<span class="brb-dot" style="background:${color}"></span>` +
        `<span class="brb-label">Route <strong>${escHtml(shortName)}</strong></span>` +
        `<button class="brb-close" onclick="clearBusRoute()" aria-label="Clear route">✕</button>`;
    document.getElementById('pane-map').appendChild(banner);
    _busRouteBanner = banner;
}

function showStopListPanel(shortName, color, stopList, shapePoints, routeDir, reversed) {
    const panel = document.getElementById('stopListPanel');
    const title = document.getElementById('stopListTitle');
    const body  = document.getElementById('stopListBody');
    if (!panel) return;

    // Title: badge + direction arrow
    const dirText = routeDir ? ` → ${routeDir}` : '';
    title.innerHTML =
        `<span class="slp-badge" style="background:${color};color:${routeTextColor(shortName, color)}">${escHtml(shortName)}</span>` +
        `<span class="slp-dir">${escHtml(dirText)}</span>`;

    // Build stop list HTML from stopList coords (reliable — no isStop flag needed)
    body.innerHTML = stopList.map((stop, i) => {
        const isFirst   = i === 0;
        const isLast    = i === stopList.length - 1;
        const isCurrent = !!_slpHighlightStopId && stop.stop_id === _slpHighlightStopId;
        // Interchanges: every other line calling at this stop, in its own colour
        const linesHtml = (stop.lines || []).map(l =>
            `<span class="slp-line-badge" style="background:${routeColor(l)};color:${routeTextColor(l)}">${escHtml(l)}</span>`
        ).join('');
        return `<div class="slp-row${isCurrent ? ' slp-row--current' : ''}"
                     data-stop-id="${escHtml(stop.stop_id || '')}"
                     data-stop-name="${escHtml(stop.name || '')}"
                     data-lat="${Number(stop.lat)}" data-lon="${Number(stop.lon)}">
            <div class="slp-line-col">
                <div class="slp-connector${isFirst ? ' slp-connector--hidden' : ''}"></div>
                <div class="slp-dot${isCurrent ? ' slp-dot--current' : ''}"></div>
                <div class="slp-connector${isLast  ? ' slp-connector--hidden' : ''}"></div>
            </div>
            <div class="slp-info">
                <div class="slp-stop-name${isCurrent ? ' slp-stop-name--current' : ''}">${escHtml(stop.name)}</div>
                <div class="slp-lines">${linesHtml}</div>
            </div>
        </div>`;
    }).join('');

    panel.classList.add('open');

    // Scroll current stop into view
    requestAnimationFrame(() => {
        const cur = body.querySelector('.slp-row--current');
        if (cur) cur.scrollIntoView({ block: 'center', behavior: 'smooth' });
    });
}

// Tapping a stop in the list pans the main map to it and opens its arrivals —
// the same sheet a tap on the map marker gives, reached from the list instead.
document.addEventListener('click', e => {
    const row = e.target.closest('#stopListBody .slp-row[data-stop-id]');
    if (!row || !row.dataset.stopId) return;
    const lat = parseFloat(row.dataset.lat);
    const lon = parseFloat(row.dataset.lon);
    if (!Number.isNaN(lat) && !Number.isNaN(lon)) map.panTo([lat, lon]);
    showStopArrivals(row.dataset.stopId, row.dataset.stopName || row.dataset.stopId);
});

function invertRoute() {
    if (!_slpStopList || !_slpShapePoints) return;
    _slpReversed    = !_slpReversed;
    const revStops  = [..._slpStopList].reverse();
    const revShape  = [..._slpShapePoints].reverse();
    // Swap direction label: first stop name ↔ last stop name as new direction
    const newDir    = revStops[revStops.length - 1]?.name || _slpRouteDir || '';
    _slpStopList    = revStops;
    _slpShapePoints = revShape;
    _slpRouteDir    = newDir;
    showStopListPanel(_slpShortName, _slpColor, revStops, revShape, newDir, _slpReversed);
    // The dots on the map are the same set in the same places — only the list
    // order and the direction label flip, so the map layers are left untouched.
}

// Ritardo nel popup fermata: real-time (Google on), retrospettivo C1 (off), o niente.
function delayLine(a) {
    if (!a.departed) return '';                       // non partito: solo orario
    const m = a.delay_minutes;
    if (a.real_time) {
        if (m == null)  return `<span class="delay-chip d-live d-unknown">${t('delay_live')}</span>`;
        if (m <= 0)     return `<span class="delay-chip d-live d-ontime">${t('delay_ontime_live')}</span>`;
        return `<span class="delay-chip d-live d-late">${tf('delay_late_live', { m })}</span>`;
    }
    if (m == null) return '';                          // partito ma nessun arrivo misurato
    const at = a.delay_stop_name ? tf('delay_at_stop', { stop: escHtml(a.delay_stop_name) }) : '';
    if (m <= 0)   return `<span class="delay-chip d-hist d-ontime">${t('delay_was_ontime')}${at}</span>`;
    return `<span class="delay-chip d-hist d-late">${tf('delay_was_late', { m })}${at}</span>`;
}

// Deterministic color per route short-name (consistent across renders)
// Colours come from CassiTrack, where the fleet manager sets them per line and
// where they are chosen to stay apart from one another. They are filled in by
// loadNetworkLines() and keyed both ways: by route id for the map, by short name
// for the interchange badges, which only ever know the number.
const ROUTE_COLORS = { byId: {}, byShortName: {}, textByShortName: {} };

// Fallback only. A hash into ten colours cannot keep eighteen lines apart — two
// lines landing on the same swatch is arithmetic, not bad luck — so this is what
// a line gets when CassiTrack has no colour for it, not the normal path.
const ROUTE_PALETTE = ['#d32f2f','#1565c0','#2e7d32','#e65100','#6a1b9a','#00695c','#37474f','#ad1457','#0277bd','#558b2f'];
function hashColor(name) {
    let h = 0;
    for (const c of (name || '')) h = (h * 31 + c.charCodeAt(0)) & 0xffffffff;
    return ROUTE_PALETTE[Math.abs(h) % ROUTE_PALETTE.length];
}

function routeColor(name) {
    return ROUTE_COLORS.byShortName[name] || hashColor(name);
}

function routeColorById(routeId, shortName) {
    return ROUTE_COLORS.byId[routeId] || routeColor(shortName);
}

// Label colour for a badge painted in `hex`. CassiTrack publishes one per line,
// but a route added in the editor may carry a colour and no text colour, so the
// readable choice is computed when it is missing rather than defaulting to white
// and leaving white-on-yellow badges.
function routeTextColor(shortName, hex) {
    return ROUTE_COLORS.textByShortName[shortName] || contrastingText(hex || routeColor(shortName));
}

function contrastingText(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(hex).trim());
    if (!m) return '#ffffff';
    const n = parseInt(m[1], 16);
    // WCAG relative luminance, so the choice matches the contrast the palette
    // was validated against rather than a rough brightness average.
    const lin = v => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const L = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
    return (1.05 / (L + 0.05)) >= ((L + 0.05) / 0.05) ? '#ffffff' : '#111111';
}

function etaText(isoString, now) {
    if (!isoString) return '—';
    const diff = Math.round((new Date(isoString).getTime() - now) / 1000);
    if (diff <= 0) return t('lbl_now');
    if (diff < 60) return `${diff} sec`;
    return `${Math.round(diff / 60)} min`;
}

function renderArrivals(list, arrivals) {
    if (!arrivals.length) { list.innerHTML = ''; return; }
    const now = Date.now();

    // Group by route short-name, preserving order of first appearance
    const groups = new Map();
    for (const a of arrivals) {
        const key = a.route_short_name || a.route_name || '?';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(a);
    }

    list.innerHTML = [...groups.entries()].map(([shortName, group]) => {
        const first   = group[0];
        // Keyed by route id, not by the number on the badge: two routes can
        // share a short name ("11" is both the Liceo and the ITIS run) and have
        // different colours, and the dots this colour paints must match the line
        // the map highlights.
        const color   = routeColorById(first.route_id, shortName);
        const second  = group[1];
        const t1      = etaText(first.estimated_arrival, now);
        const t2      = second ? etaText(second.estimated_arrival, now) : null;
        const timesHtml = t2
            ? `<span class="tmb-t1">${t1}</span><span class="tmb-sep"> | </span><span class="tmb-t2">${t2}</span>`
            : `<span class="tmb-t1">${t1}</span>`;

        const isLive = first.real_time || first.departed;
        const rtHtml = isLive
            ? `<span class="tmb-rt live">
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                  <path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/>
                  <path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/>
                </svg> ${t('lbl_real_time')}</span>`
            : `<span class="tmb-rt sched">🕐 ${t('lbl_scheduled')}</span>`;

        const direction = first.route_name ? escHtml(first.route_name) : '';

        // Delay note on first arrival only (if live and delayed)
        let delayHtml = '';
        if (isLive && first.delay_minutes != null && first.delay_minutes > 0) {
            delayHtml = `<span class="tmb-delay">${tf('delay_late', { m: first.delay_minutes })}</span>`;
        }

        // Crowding on first arrival
        const crowding = first.crowding_level;
        const crowdHtml = (crowding && CROWDING_BG[crowding])
            ? `<span class="tmb-crowd" style="${CROWDING_BG[crowding]}">${t('lbl_crowding')} ${getCrowdingLabel(crowding)}</span>`
            : '';

        const routeId   = first.route_id || '';
        const routeDir  = direction || '';
        return `<div class="tmb-route-row${routeId ? ' tmb-route-tappable' : ''}"
                     data-route-id="${escHtml(routeId)}"
                     data-route-short="${escHtml(shortName)}"
                     data-route-color="${color}"
                     data-route-dir="${escHtml(routeDir)}"
                     ${routeId ? 'title="Tap to show route on map"' : ''}>
            <div class="tmb-badge" style="background:${color};color:${routeTextColor(shortName, color)}">${escHtml(shortName)}</div>
            <div class="tmb-info">
                <div class="tmb-times">${timesHtml}</div>
                <div class="tmb-meta">${rtHtml}${delayHtml}${crowdHtml}</div>
                ${direction ? `<div class="tmb-dir">→ ${direction}</div>` : ''}
            </div>
            ${routeId ? `<div class="tmb-map-btn" title="Show on map">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21"/>
                    <line x1="9" y1="3" x2="9" y2="18"/><line x1="15" y1="6" x2="15" y2="21"/>
                </svg>
            </div>` : ''}
        </div>`;
    }).join('');
}

// ── Load stops from the backend → fill dropdowns + map markers ─────
async function loadStops() {
    try {
        const r = await apiFetch('/journeys/stops');
        if (!r.ok) throw new Error('stops ' + r.status);
        const stops = await r.json();

        if (!Array.isArray(stops) || stops.length === 0) {
            showToast(t('no_stops_avail'), true);
            return;
        }

        STOPS = {};
        stops.forEach(s => { STOPS[s.id] = { id: s.id, name: s.name, lat: s.lat, lon: s.lon }; });

        // Both fields stay empty on purpose, showing their "Origin" / "Destination"
        // placeholders: pre-filling them with the first two stops of the list put a choice
        // in the traveller's mouth that they never made, and a distracted tap on Search
        // would plan a journey between two stops they had never picked.

        renderStopMarkers();

        const bounds = stops.map(s => [s.lat, s.lon]);
        if (bounds.length) map.fitBounds(bounds, { padding: [60, 60], maxZoom: 15 });
    } catch (e) {
        console.error('loadStops failed:', e);
        showToast(t('toast_stops_error'), true);
    }
}

// ── GPS state ─────────────────────────────────────────────────────
let userLat = null;
let userLon = null;
let userMarker = null;

function placeUserMarker(lat, lon) {
    if (userMarker) map.removeLayer(userMarker);
    // zIndexOffset keeps the dot on top — a stop sitting on your position used to
    // hide it completely, which is half the reason it was hard to find
    userMarker = L.marker([lat, lon], { icon: userIcon, zIndexOffset: 1000 })
        .addTo(map)
        .bindPopup('📍 ' + t('you_are_here'));
}

function tryGetGPS() {
    return new Promise((resolve, reject) => {
        if (!navigator.geolocation) { reject('no_geolocation'); return; }
        navigator.geolocation.getCurrentPosition(
            pos => {
                userLat = pos.coords.latitude;
                userLon = pos.coords.longitude;
                placeUserMarker(userLat, userLon);
                resolve({ name: t('my_location'), lat: userLat, lon: userLon, isGPS: true });
            },
            err => {
                // Friendly fallback so the demo still works
                userLat = 41.5020; userLon = 13.8200;
                placeUserMarker(userLat, userLon);
                resolve({ name: t('approx_location'), lat: userLat, lon: userLon, isGPS: true });
            },
            { timeout: 8000, maximumAge: 60000 }
        );
    });
}

// ── GPS trigger when user switches to "My Location" ───────────────
// (origin GPS handling now lives in the autocomplete selection below)

function swapStops() {
    const o = document.getElementById('originSelect');
    const d = document.getElementById('destSelect');
    const ov = o.value, oid = o.dataset.id || '';
    o.value = d.value; _acSetId(o, d.dataset.id || '');
    d.value = ov;      _acSetId(d, oid);
}

function getOrigin() {
    const el  = document.getElementById('originSelect');
    const val = el.dataset.id;
    // No origin picked (empty field) → default to My Location (GPS)
    if (!el.value.trim() || !val || val === 'GPS') {
        setStop(el, 'GPS');          // show "My Location" in the field, don't leave it empty
        if (!userLat) return null;   // null → doSearch will request GPS
        return { name: t('my_location'), lat: userLat, lon: userLon, isGPS: true };
    }
    return { ...STOPS[val], isGPS: false };
}

// Mirrors getOrigin(), minus the "empty means where I am" default: an unset destination is
// an error, not a shortcut. GPS is a valid destination now — riding to the stop nearest to
// where you are is an ordinary trip, and doSearch fills in the position when it is missing.
function getDest() {
    const el  = document.getElementById('destSelect');
    const val = el.dataset.id;
    if (val === 'GPS') {
        if (!userLat) return null;   // null → doSearch will request GPS
        return { name: t('my_location'), lat: userLat, lon: userLon, isGPS: true };
    }
    return { ...STOPS[val], isGPS: false };
}

// ── Filter / sort state ──────────────────────────────────────────
let activeSort  = 'eco';   // 'eco' | 'budget' | 'fast'  — always exactly one
let activeModes = [];      // [] = all modes; otherwise subset of BUS/BIKE/SCOOTER (WALK always included)

function setSort(el) {
    document.querySelectorAll('#sortChips .cat-chip').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    activeSort = el.dataset.sort;
    if (window._lastSearchData) renderRoutes(window._lastSearchData);
}

function toggleModeChip(el) {
    el.classList.toggle('active');
    activeModes = Array.from(document.querySelectorAll('#modeChips .cat-chip.active'))
        .map(c => c.dataset.mode);
    // Mode filter changes what was actually computed server-side, so re-search.
    doSearch();
}

// Ordina per il punteggio multi-criterio calcolato dal backend (OM-17).
// Ogni profilo pesa tempo/costo/ambiente diversamente, quindi due opzioni che
// pareggiano sul criterio principale vengono comunque distinte.
const SCORE_KEY = { eco: 'score_eco', budget: 'score_budget', fast: 'score_fast' };

function sortOptions(options) {
    const sorted = [...options];
    const key = SCORE_KEY[activeSort];

    if (key && sorted.every(o => typeof o[key] === 'number')) {
        sorted.sort((a, b) => b[key] - a[key]);      // punteggio alto = migliore
        return sorted;
    }

    // Ripiego a criterio singolo: backend non aggiornato o punteggi assenti.
    if (activeSort === 'eco') {
        sorted.sort((a, b) => b.green_index - a.green_index);
    } else if (activeSort === 'budget') {
        sorted.sort((a, b) => a.cost_euros - b.cost_euros);
    } else if (activeSort === 'fast') {
        sorted.sort((a, b) => a.duration_minutes - b.duration_minutes);
    }
    return sorted;
}

// ── Search ────────────────────────────────────────────────────────
async function doSearch() {
    _acHide();   // close the suggestion list on search
    const originEl = document.getElementById('originSelect');
    const destEl   = document.getElementById('destSelect');
    const destId   = destEl.dataset.id;

    // The fields start empty now, so a destination is no longer guaranteed. An empty
    // origin is fine — getOrigin() reads it as "where I am" and asks for GPS.
    if (!destId || (destId !== 'GPS' && !STOPS[destId])) {
        showToast(t('pick_dest'), true);
        destEl.focus();
        return;
    }
    // Travelling *to* your own position needs a real starting point: the "empty origin
    // means where I am" default would otherwise plan a trip from here to here.
    if (destId === 'GPS' && !originEl.dataset.id) {
        showToast(t('pick_origin'), true);
        originEl.focus();
        return;
    }
    // Caught before the position is requested: asking the traveller for a GPS fix only to
    // tell them the two ends are the same place is a permission prompt for nothing.
    if (destId === 'GPS' && originEl.dataset.id === 'GPS') {
        showToast(t('toast_same_stops'), true);
        return;
    }

    let origin = getOrigin();
    let dest   = getDest();

    // Either end can be "my location" now, and one position fix serves both.
    if (!origin || !dest) {
        showToast(t('toast_locating'), false);
        let pos;
        try { pos = await tryGetGPS(); }
        catch (e) {
            showToast(!dest ? t('toast_gps_dest_fail') : t('toast_gps_origin_fail'), true);
            return;
        }
        if (!origin) origin = pos;
        if (!dest)   dest   = pos;
    }

    if (origin.name === dest.name) {
        showToast(t('toast_same_stops'), true); return;
    }

    // A search is a fresh start. A detail sheet left open — or a journey still running
    // because End Journey was never tapped — belongs to the previous origin/destination
    // pair: the sheet is an opaque overlay on the sidebar, so the new results rendered
    // underneath stayed invisible and Search looked dead. Tear it down exactly like ✕ does.
    clearJourneySelection();
    // Same for a line the traveller was browsing on the network map
    clearBusRoute();
    closeStopSheet();

    // Switch to map pane
    document.querySelectorAll('.sidebar-nav .nav-item').forEach(n => n.classList.remove('active'));
    document.querySelector('[data-pane="map"]').classList.add('active');
    document.querySelectorAll('.pane').forEach(p => p.classList.remove('active'));
    document.getElementById('pane-map').classList.add('active');
    setTimeout(() => map.invalidateSize(), 50);

    document.querySelector('.routes-list').innerHTML =
        '<div style="text-align:center;padding:40px 20px;color:var(--text-soft)">'
        + '<div style="font-size:28px;margin-bottom:10px">🔄</div>'
        + `<div style="font-size:13px;font-weight:600">${t('finding_routes')}</div>`
        + '</div>';

    try {
        const payload = {
            origin_lat: origin.lat, origin_lon: origin.lon, origin_name: origin.name, origin_is_gps: origin.isGPS === true,
            dest_lat:   dest.lat,   dest_lon:   dest.lon,   dest_name:   dest.name, dest_is_gps: dest.isGPS === true,
            user_id: _user.id,
            dest_stop_id:   dest.isGPS   ? null : dest.id,
            origin_stop_id: origin.isGPS ? null : origin.id,
            lang: getLang()
        };
        // Only constrain modes when the traveler picked specific ones via the chips.
        if (activeModes.length > 0) {
            payload.modes = [...activeModes];
        }
        if (_pickerHour !== null) {
            const hh = String(_pickerHour).padStart(2, '0');
            const mm = String(_pickerMin).padStart(2, '0');
            payload.departure_time = `${hh}:${mm}`;
            if (_pickerMode === 'arrive') payload.arrive_by = true;
        }

        const r = await apiFetch('/journeys/search', {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        if (r.status === 429) throw new Error('rate_limited');
        if (!r.ok) throw new Error('Search failed');
        const data = await r.json();
        window._currentOrigin = origin;
        window._currentDest   = dest;
        window._lastSearchData = data;
        renderRoutes(data);
    } catch (e) {
        const msg = e.message === 'rate_limited'
            ? t('err_rate_limited')
            : t('err_load_routes');
        document.querySelector('.routes-list').innerHTML =
            '<div style="text-align:center;padding:40px 20px;color:var(--red)">'
            + '<div style="font-size:28px;margin-bottom:10px">⚠️</div>'
            + `<div style="font-size:13px;font-weight:600">${msg}</div>`
            + '</div>';
    }
}

// ── Route rendering ───────────────────────────────────────────────
const MODE_ICONS = { BUS:'🚌', BIKE:'🚲', SCOOTER:'🛴', WALK:'🚶' };
const MODE_BTNS  = {
    BUS:     { labelKey:'btn_bus',     cls:'btn-dark'   },
    BIKE:    { labelKey:'btn_bike',    cls:'btn-blue'   },
    SCOOTER: { labelKey:'btn_scooter', cls:'btn-purple' },
    WALK:    { labelKey:'btn_walk',    cls:'btn-green'  },
};

const LINE_COLORS     = { BUS:'#0f172a', BIKE:'#3b82f6', SCOOTER:'#7c3aed', WALK:'#10b981' };
const BUS_LEG_COLORS  = ['#0f172a', '#3b82f6', '#7c3aed']; // indexed by bus-leg order

function greenColor(g) {
    return g >= 75 ? '#10b981' : g >= 50 ? '#f59e0b' : '#ef4444';
}

let selectedJourney = null;

function renderRoutes(data) {
    if (data.weather_condition) {
        const temp = data.temperature_celsius != null ? Math.round(data.temperature_celsius) + '°C' : '';
        const key = 'weather_' + data.weather_condition;
        const raw = t(key);
        document.querySelector('.weather-pill').textContent = raw.replace('{t}', temp);
    } else if (data.weather_summary) {
        document.querySelector('.weather-pill').textContent = data.weather_summary;
    }
    const list = document.querySelector('.routes-list');

    // Avvisi della ricerca (orario spostato a domani, traffico off, dati non real-time…)
    const noticeHtml = (Array.isArray(data.messages) && data.messages.length > 0)
        ? data.messages.map(m => `<div class="search-notice">${escHtml(m)}</div>`).join('')
        : '';

    if (!data.options || data.options.length === 0) {
        list.innerHTML = noticeHtml + `<div style="padding:20px;color:var(--text-soft);font-size:13px">${t('no_routes')}</div>`;
        return;
    }

    window._routeOptions = {};
    const orderedOptions = sortOptions(data.options);

    list.innerHTML = noticeHtml + orderedOptions.map(opt => {
        window._routeOptions[opt.mode] = opt;

        const icon = MODE_ICONS[opt.mode] || '🚗';
        const _btn = MODE_BTNS[opt.mode]  || { labelKey: null, cls: 'btn-dark' };
        const btn  = { label: _btn.labelKey ? t(_btn.labelKey) : 'Select', cls: _btn.cls };
        // For BUS keep the backend route label (e.g. "3 → Liceo Scientifico"); translate other modes
        const modeLabel = opt.mode === 'BUS'
            ? opt.mode_label
            : (t('mode_' + opt.mode.toLowerCase()) || opt.mode_label);
        const cost = opt.cost_euros === 0 ? t('lbl_free') : '€' + opt.cost_euros.toFixed(2);
        const co2  = opt.co2_grams > 0 ? Math.round(opt.co2_grams) + ' g' : '0 g';
        const delayBadge = opt.delay_label
            ? `<span class="status-badge delay-${(opt.delay_status||'unknown').toLowerCase()}">${escHtml(opt.delay_label)}</span>`
            : '';
        const warn = opt.weather_warning
            ? `<span class="status-badge s-delay">${opt.weather_warning}</span>` : '';
        // Departure / arrival time labels for the card
        const _depDate = _getPickerTripStart();
        const _arrDate = new Date(_depDate.getTime() + (opt.duration_minutes || 0) * 60000);
        const _depTime = _fmtHHMM(_depDate);
        const _arrTime = _fmtHHMM(_arrDate);
        return `
<div class="route-card" id="card-${opt.mode}">
    <div class="route-top">
        <div class="route-name">${icon} ${modeLabel}</div>
        <div class="route-time">${opt.duration_minutes} min</div>
    </div>
    <div style="display:flex;align-items:center;gap:6px;margin:-4px 0 6px;font-size:12px;color:var(--text-mid);font-weight:600">
        <span>${_depTime}</span>
        <span style="color:var(--border-mid)">→</span>
        <span>${_arrTime}</span>
    </div>
    <div class="status-row">
        ${warn || `<span class="status-badge s-ok">${t('badge_available')}</span>`}
        ${delayBadge}
    </div>
    <div class="metrics-row">
        <div class="metric-box"><div class="metric-label">${t('metric_cost')}</div><div class="metric-value">${cost}</div></div>
        <div class="metric-box"><div class="metric-label">CO₂</div><div class="metric-value">${co2}</div></div>
        <div class="metric-box"><div class="metric-label">${t('metric_green')}</div>
            <div class="metric-value" style="color:${greenColor(opt.green_index)}">${opt.green_index}/100</div>
        </div>
    </div>
    <button class="action-btn ${btn.cls}" data-select-mode="${escHtml(opt.mode)}">
        ${btn.label}
    </button>
</div>`;
    }).join('');
}

// ── Route preview (shown on card selection, before Start Journey) ──

function clearRoutePreview() {
    (window._previewLayers || []).forEach(l => map.removeLayer(l));
    window._previewLayers = [];
    // NOTE: stop marker restoration is handled by endJourney and renderRoutes,
    // not here — clearRoutePreview is also called from startJourney where stops
    // must stay hidden.
    clearInterval(window._busPollInterval);
    window._busPollInterval = null;
    clearBusMarkers();
    window._activeBusRouteIds = [];
}

function showRoutePreview(mode, legs) {
    clearRoutePreview();
    window._previewLayers = [];

    const origin = window._currentOrigin;
    const dest   = window._currentDest;
    if (!origin || !dest) return;

    // Hide all generic stop markers — we'll show only relevant ones for BUS
    (window._stopMarkers || []).forEach(m => map.removeLayer(m));
    // …and the base network too, so the journey legs are the only lines drawn
    setNetworkLinesVisible(false);

    const color = LINE_COLORS[mode] || '#0f172a';

    if (mode === 'BUS' && legs && legs.length > 0) {
        let colorIdx = 0;
        const activeBusLegs = [];
        legs.forEach(leg => {
            if (leg.mode === 'BUS' && leg.stop_coords && leg.stop_coords.length >= 2) {
                const legColor = BUS_LEG_COLORS[colorIdx % BUS_LEG_COLORS.length];
                colorIdx++;
                const coords     = leg.stop_coords.map(c => [c[0], c[1]]);
                const stopDots   = (leg.bus_stop_coords || leg.stop_coords).map(c => [c[0], c[1]]);
                window._previewLayers.push(
                    L.polyline(coords, { color: legColor, weight: 4, opacity: 0.85 }).addTo(map)
                );
                stopDots.forEach((c, i) => {
                    const isEnd = i === 0 || i === stopDots.length - 1;
                    window._previewLayers.push(
                        L.circleMarker(c, {
                            radius: isEnd ? 7 : 5,
                            color: legColor, fillColor: '#ffffff', fillOpacity: 1, weight: 2
                        }).addTo(map)
                    );
                });
                if (leg.route_id) {
                    activeBusLegs.push({
                        routeId: leg.route_id,
                        color: legColor,
                        boardingCoords: leg.stop_coords[0] // [lat, lon] of boarding stop
                    });
                }
            } else if (leg.mode === 'WALK' && leg.stop_coords && leg.stop_coords.length >= 2) {
                const coords = leg.stop_coords.map(c => [c[0], c[1]]);
                window._previewLayers.push(
                    L.polyline(coords, { color: '#94a3b8', weight: 3, opacity: 0.6, dashArray: '5,6' }).addTo(map)
                );
            }
        });
        const allCoords = legs
            .filter(l => l.stop_coords)
            .flatMap(l => l.stop_coords.map(c => [c[0], c[1]]));
        if (allCoords.length > 1) map.fitBounds(allCoords, { padding: [50, 50] });

        window._activeBusLegs     = activeBusLegs;
        window._activeBusRouteIds = activeBusLegs.map(l => l.routeId);

        // Only show live bus positions for "Now" trips — future departures would show
        // vehicles at their current position, which is irrelevant to the planned time.
        if (_pickerHour === null) {
            fetchAndRenderBusMarkers();
            window._busPollInterval = setInterval(fetchAndRenderBusMarkers, 12000);
        } else {
            showStaleNotice(t('live_buses_future'));
        }
    } else {
        window._previewLayers.push(
            L.polyline(
                [[origin.lat, origin.lon], [dest.lat, dest.lon]],
                { color, weight: 4, opacity: 0.7, dashArray: mode === 'WALK' ? '8,8' : '8,4' }
            ).addTo(map)
        );
        map.fitBounds([[origin.lat, origin.lon], [dest.lat, dest.lon]], { padding: [50, 50] });
    }
}

// The card button carries only the mode. It used to inline the whole option —
// onclick="selectMode('BUS','17 → Ospedale … Capo d'Acqua',…)" — and the
// apostrophe in a stop name closed the string literal mid-call, so the browser
// threw a SyntaxError and the button did nothing. Everything the handler needs
// is already in window._routeOptions, keyed by mode.
document.addEventListener('click', e => {
    const btn = e.target.closest('[data-select-mode]');
    if (btn) selectMode(btn.dataset.selectMode);
});

// selectMode — highlights the card, previews the route on the map, and shows the Start Journey banner.
// Full GPS resolution + solid lines happen in startJourney.
function selectMode(mode, label, greenIndex, distanceMetres, costEuros) {
    // Arguments stay optional so the function is still callable directly; when
    // omitted they come from the option the cards were rendered from.
    const _opt     = (window._routeOptions || {})[mode] || {};
    label          = label          ?? _opt.mode_label ?? mode;
    greenIndex     = greenIndex     ?? _opt.green_index ?? 0;
    distanceMetres = distanceMetres ?? _opt.distance_metres ?? 0;
    costEuros      = costEuros      ?? _opt.cost_euros ?? 0;

    selectedJourney = {
        mode, label, greenIndex,
        distanceKm: distanceMetres / 1000,
        costEuros,
        durationMinutes: window._routeOptions[mode]?.duration_minutes,
        co2Grams: window._routeOptions[mode]?.co2_grams ?? 0
    };

    if (window._routeOptions && window._routeOptions[mode]) {
        selectedJourney.legs = window._routeOptions[mode].legs || [];
    }

    // Highlight selected card
    document.querySelectorAll('.route-card').forEach(c => {
        c.style.border = '1px solid var(--border-mid)';
        c.style.opacity = '0.6';
    });
    const card = document.getElementById('card-' + mode);
    if (card) { card.style.border = '2px solid var(--primary)'; card.style.opacity = '1'; }

    // Show dashed preview on map immediately
    showRoutePreview(mode, selectedJourney.legs || []);

    // Open detail sheet instead of a sticky banner
    _openRouteDetail(mode, label, greenIndex, distanceMetres, costEuros);
}

// ── Route detail preview sheet ────────────────────────────────────────
function _openRouteDetail(mode, label, greenIndex, distanceMetres, costEuros) {
    const opt = window._routeOptions?.[mode] || {};
    const durationMin = opt.duration_minutes
        || selectedJourney.durationMinutes
        || Math.ceil((distanceMetres/1000) / (mode==='WALK'?5:mode==='BIKE'?15:mode==='SCOOTER'?20:25) * 60);
    const distanceKm  = distanceMetres / 1000;
    const modeEmoji   = MODE_ICONS[mode] || '🚗';
    const co2g        = opt.co2_grams ?? selectedJourney.co2Grams ?? 0;

    const depDate = _getPickerTripStart();
    const arrDate = new Date(depDate.getTime() + durationMin * 60000);

    const isBusMode = mode === 'BUS' && selectedJourney.legs?.length > 0;
    const timelineHtml = isBusMode
        ? buildTimeline(selectedJourney.legs, durationMin, greenIndex)
        : buildSingleLegTimeline(mode, selectedJourney.legs || [], distanceKm, co2g);

    const modeLabel = isBusMode ? (fmtRouteLabel(label) || label) : label;
    const costTxt   = (!costEuros || costEuros === 0) ? t('lbl_free') : '€' + costEuros.toFixed(2);
    const co2Txt    = Math.round(co2g) + ' g CO₂';
    const gcol      = greenColor(greenIndex);

    document.getElementById('rdEmoji').textContent = modeEmoji;
    document.getElementById('rdLabel').innerHTML   = modeLabel;
    document.getElementById('rdTimes').textContent = `${_fmtHHMM(depDate)} → ${_fmtHHMM(arrDate)}`;
    document.getElementById('rdDur').textContent   = durationMin + ' min';
    document.getElementById('rdTimeline').innerHTML = timelineHtml;
    document.getElementById('rdCost').textContent  = costTxt;
    document.getElementById('rdCo2').textContent   = co2Txt;
    document.getElementById('rdGreen').textContent = greenIndex + '/100';
    document.getElementById('rdGreen').style.color = gcol;

    // Always reset the start button in case a previous journey left it disabled
    const rdBtn = document.getElementById('rdStartBtn');
    if (rdBtn) { rdBtn.disabled = false; rdBtn.textContent = t('btn_start_journey'); }

    document.getElementById('routeDetailSheet').classList.add('open');
}

// Everything that belongs to "a route is currently picked": the detail sheet, the card
// highlight, the map layers, and the timers of a journey already under way. Closing the
// sheet with ✕, ending a journey and starting a new search all need exactly this, so the
// teardown lives in one place instead of being copied — and drifting — in three.
function clearJourneySelection() {
    const sheet = document.getElementById('routeDetailSheet');
    if (sheet) sheet.classList.remove('open');

    // De-select all route cards
    document.querySelectorAll('.route-card').forEach(c => {
        c.style.border = '1px solid var(--border-mid)';
        c.style.opacity = '1';
    });

    // The button stays disabled while a start is in flight; re-arm it for the next pick
    const rdBtn = document.getElementById('rdStartBtn');
    if (rdBtn) { rdBtn.disabled = false; rdBtn.textContent = t('btn_start_journey'); }

    // Live ETA countdown, bus polling, bus markers and the dashed preview
    clearInterval(window._etaInterval);
    window._etaInterval = null;
    clearRoutePreview();

    // Solid journey layers drawn by startJourney
    ['_routeLine','_routeLineGps','_journeyDestMarker','_journeyOriginMarker','_journeyGpsMarker']
        .forEach(k => { if (window[k]) { map.removeLayer(window[k]); window[k] = null; } });
    if (window._busRouteLines) {
        window._busRouteLines.forEach(l => map.removeLayer(l));
        window._busRouteLines = [];
    }

    // Restore bus-stop markers and the base network underneath them
    if (window._stopMarkers) window._stopMarkers.forEach(m => m.addTo(map));
    setNetworkLinesVisible(true);

    selectedJourney = null;
    // Left true by a successful start: without this reset, Start Journey on the next pick
    // returned immediately and nothing happened.
    window._journeyStarting = false;
}

function closeRouteDetail() {
    clearJourneySelection();
}

function startJourneyFromDetail() {
    document.getElementById('routeDetailSheet').classList.remove('open');
    startJourney();
}

// ── Helpers shared by selectMode preview and startJourney ───────────
function fmtD(m) { return m < 1000 ? Math.round(m) + ' m' : (m/1000).toFixed(1) + ' km'; }


// ── Route label: "3 → Dest" or "3 → Dest + 1 → Dest2" → circles ──
function fmtRouteLabel(instruction, circleStyle) {
    if (!instruction) return t('lbl_bus');
    // Transfer labels contain " + " separating each leg label — stack vertically
    if (instruction.includes(' + ')) {
        return instruction.split(' + ')
            .map(part => `<div style="line-height:1.6">${fmtRouteLabel(part.trim(), circleStyle)}</div>`)
            .join('');
    }
    const sep = instruction.indexOf(' → ');
    if (sep === -1) return escHtml(instruction);
    const num = escHtml(instruction.slice(0, sep).trim());
    const dest = escHtml(instruction.slice(sep + 3).trim());
    const cs  = circleStyle || 'background:#1d4ed8;color:#fff';
    return `<span class="rnum-bus" style="${cs}">${num}</span> → ${dest}`;
}

// ── Build Google Maps-style vertical timeline ──────────────────────
function buildTimeline(legs, totalMin, greenIdx) {
    const C = { WALK:'#6366f1', WAIT:'#f59e0b', BUS:'#10b981', TRANSFER:'#ef4444' };
    let html = '';
    let busIndex = 0;

    // Running clock — starts at the user's chosen departure time (or now)
    const _tripStart = _getPickerTripStart();
    let   _runMs     = 0;
    function _tick(min) { _runMs += (min || 0) * 60000; }
    function _fmtTime(offsetMs) {
        const d = new Date(_tripStart.getTime() + offsetMs);
        return d.getHours().toString().padStart(2,'0') + ':' + d.getMinutes().toString().padStart(2,'0');
    }
    // Inline time badge — prominent black HH:MM
    function _timeBadge(ms) {
        return `<span style="font-size:13px;font-weight:800;color:#0f172a;letter-spacing:-0.3px;flex-shrink:0">${_fmtTime(ms)}</span>`;
    }
    // Stop name row with time right-aligned
    function _stopRow(name, timeMs) {
        return `<div style="display:flex;justify-content:space-between;align-items:baseline;gap:6px">
                  <span class="tl-from" style="margin:0">${name}</span>
                  ${_timeBadge(timeMs)}
                </div>`;
    }

    legs.forEach((leg, i) => {
        const isLast = i === legs.length - 1;
        const col = C[leg.mode] || '#94a3b8';
        const names = leg.stop_names || [];
        const isTransfer = leg.mode === 'WAIT' && leg.transfer === true;

        if (leg.mode === 'WALK') {
            // ── WALK row ──────────────────────────────────────────────
            const walkDepMs = _runMs;
            _tick(leg.duration_minutes);
            html += `
            <div class="tl-row">
              <div class="tl-left">
                <div class="tl-dot" style="background:${col};border-color:${col}"></div>
                ${!isLast ? `<div class="tl-line" style="background:${col}33"></div>` : ''}
              </div>
              <div class="tl-body">
                ${_stopRow(leg.from || t('lbl_start'), walkDepMs)}
                <div class="tl-meta">
                  <span class="tl-badge" style="background:${col}18;color:${col}">🚶 ${t('walk')} · ${leg.duration_minutes || 0} min</span>
                  ${leg.distance_metres ? `<span class="tl-sub">${fmtD(leg.distance_metres)}</span>` : ''}
                </div>
              </div>
            </div>`;

        } else if (isTransfer) {
            // ── TRANSFER / CHANGE BUS ────────────────────────────────
            const xferMs = _runMs;
            _tick(leg.duration_minutes);
            html += `
            <div class="tl-row">
              <div class="tl-left">
                <div class="tl-dot tl-dot-transfer" style="background:white;border-color:#ef4444"></div>
                ${!isLast ? `<div class="tl-line" style="background:#ef444433"></div>` : ''}
              </div>
              <div class="tl-body">
                <div class="tl-transfer-badge">
                  <span style="font-size:14px">🔄</span>
                  <div style="flex:1">
                    <div style="font-size:12px;font-weight:700;color:#ef4444">${_transferLabel(leg)}</div>
                    <div style="font-size:11px;color:#64748b">${leg.duration_minutes || 0} ${t('min_wait')} · ${t('next_bus')} ${_fmtTime(_runMs)}</div>
                  </div>
                </div>
              </div>
            </div>`;

        } else if (leg.mode === 'WAIT') {
            // ── WAIT (no change) ─────────────────────────────────────
            _tick(leg.duration_minutes);
            html += `
            <div class="tl-row">
              <div class="tl-left">
                <div class="tl-dot" style="background:${col};border-color:${col}"></div>
                ${!isLast ? `<div class="tl-line" style="background:${col}33"></div>` : ''}
              </div>
              <div class="tl-body">
                <div class="tl-meta">
                  <span class="tl-badge" style="background:${col}18;color:${col}">🕐 ${t('wait_lbl')} · ${leg.duration_minutes || 0} min</span>
                </div>
              </div>
            </div>`;

        } else if (leg.mode === 'BUS') {
            // ── BUS row with collapsible stop list ───────────────────
            busIndex++;
            const stopListId = `tl-stops-${busIndex}`;
            const intermediates = names.slice(1, -1);
            const boardStop  = names[0]  || leg.from || '';
            const alightStop = names[names.length - 1] || leg.to || '';
            const stopListHtml = intermediates.map(n =>
                `<div class="tl-stop-item"><div class="tl-stop-dot"></div><span>${n}</span></div>`
            ).join('');

            const boardMs = _runMs;
            _tick(leg.duration_minutes);
            const alightMs = _runMs;

            html += `
            <div class="tl-row">
              <div class="tl-left">
                <div class="tl-dot tl-dot-board" style="background:${col};border-color:${col}"></div>
                <div class="tl-line tl-line-bus" style="background:${col}"></div>
              </div>
              <div class="tl-body">
                ${_stopRow(boardStop, boardMs)}
                <div class="tl-meta">
                  <span class="tl-badge" style="background:${col}18;color:${col}">🚌 ${fmtRouteLabel(leg.instruction, `background:${col};color:#fff`)} · ${leg.duration_minutes || 0} min</span>
                  ${leg.distance_metres ? `<span class="tl-sub">${fmtD(leg.distance_metres)}</span>` : ''}
                </div>
                ${intermediates.length > 0 ? `
                <button class="tl-expand-btn" onclick="
                  var el=document.getElementById('${stopListId}');
                  var btn=this;
                  var open=el.style.display==='block';
                  el.style.display=open?'none':'block';
                  btn.textContent=open?'▾ ${intermediates.length} '+window.t(${intermediates.length}===1?'lbl_stop':'lbl_stops'):window.t('stops_hide');
                ">▾ ${intermediates.length} ${intermediates.length === 1 ? t('lbl_stop') : t('lbl_stops')}</button>
                <div id="${stopListId}" class="tl-stop-list" style="display:none">
                  ${stopListHtml}
                </div>` : ''}
              </div>
            </div>
            <!-- Alight stop row -->
            <div class="tl-row">
              <div class="tl-left">
                <div class="tl-dot tl-dot-alight" style="background:white;border-color:${col}"></div>
                ${!isLast ? `<div class="tl-line" style="background:#e2e8f0"></div>` : ''}
              </div>
              <div class="tl-body" style="padding-bottom:${isLast?'0':'12px'}">
                ${_stopRow(alightStop, alightMs)}
                ${isLast ? `<div style="font-size:11px;color:#10b981;font-weight:600;margin-top:2px">${t('your_destination')}</div>` : ''}
              </div>
            </div>`;
        }
    });
    return html;
}

// The change-of-bus sentence: the stop and the line come from the server as data, the
// wording from the traveller's language.
function _transferLabel(leg) {
    const line = leg.transfer_line;
    const stop = leg.from;
    if (!line || !stop) return t('transfer_generic');
    // The template is ours; only the stop and line, which come from the database, are escaped
    return tf('transfer_change_at', { stop: escHtml(stop), line: escHtml(line) });
}

// ── non-BUS modes: single-leg vertical timeline (Option A) ──────────
function buildSingleLegTimeline(mode, legs, distKm, co2g) {
    const COLORS = { WALK:'#6366f1', BIKE:'#3b82f6', SCOOTER:'#7c3aed' };
    const ICONS  = { WALK:'🚶', BIKE:'🚲', SCOOTER:'🛴' };
    const LABEL  = { WALK:t('walk'), BIKE:t('bike'), SCOOTER:t('scooter') };
    const DASH   = { WALK:'8,6', BIKE:'6,4', SCOOTER:'4,3' };
    const col    = COLORS[mode] || '#94a3b8';
    const icon   = ICONS[mode]  || '🚶';
    const lbl    = LABEL[mode]  || mode;

    // Prefer leg from/to if available, fall back to selectedJourney label
    const leg    = (legs && legs.length > 0) ? legs[0] : null;
    const from   = leg?.from || t('lbl_start');
    const to     = leg?.to   || t('lbl_destination');
    const dist   = leg?.distance_metres ? fmtD(leg.distance_metres) : `${distKm.toFixed(1)} km`;
    const co2    = co2g > 0 ? `${Math.round(co2g)} g CO₂` : '0 g CO₂';
    const durMin = leg?.duration_minutes;
    const durStr = durMin ? `${durMin} min` : '';

    // Departure / arrival timestamps from picker
    const depDate = _getPickerTripStart();
    const arrDate = new Date(depDate.getTime() + (durMin || 0) * 60000);
    const depTime = _fmtHHMM(depDate);
    const arrTime = _fmtHHMM(arrDate);
    const timeStyle = 'font-size:13px;font-weight:800;color:#0f172a;letter-spacing:-0.3px;flex-shrink:0';

    // dashed SVG line encoded as background-image
    const svgLine = `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='2' height='100'%3E%3Cline x1='1' y1='0' x2='1' y2='100' stroke='${encodeURIComponent(col)}' stroke-width='2' stroke-dasharray='${DASH[mode]}'/%3E%3C/svg%3E")`;

    return `
    <div class="tl-row">
      <div class="tl-left">
        <div class="tl-dot" style="background:white;border-color:${col};border-width:2px"></div>
        <div class="tl-line" style="background-image:${svgLine};background-color:transparent;background-repeat:repeat-y;background-size:2px 100%"></div>
      </div>
      <div class="tl-body">
        <div style="display:flex;justify-content:space-between;align-items:baseline;gap:6px">
          <div class="tl-from" style="margin:0">${from}</div>
          <span style="${timeStyle}">${depTime}</span>
        </div>
        <div class="tl-meta">
          <span class="tl-badge" style="background:${col}18;color:${col}">${icon} ${lbl}${durStr ? ' · ' + durStr : ''}</span>
          <span class="tl-sub">${dist}</span>
          <span class="tl-sub" style="color:#10b981">🌱 ${co2}</span>
        </div>
      </div>
    </div>
    <div class="tl-row">
      <div class="tl-left">
        <div class="tl-dot" style="background:${col};border-color:${col}"></div>
      </div>
      <div class="tl-body" style="padding-bottom:0">
        <div style="display:flex;justify-content:space-between;align-items:baseline;gap:6px">
          <div class="tl-from" style="margin:0">${to}</div>
          <span style="${timeStyle}">${arrTime}</span>
        </div>
        <div style="font-size:11px;color:#10b981;font-weight:600;margin-top:2px">${t('your_destination')}</div>
      </div>
    </div>`;
}


async function startJourney() {
    if (!selectedJourney) return;
    if (window._journeyStarting) return;
    window._journeyStarting = true;
    const _startBtn = document.getElementById('rdStartBtn');
    if (_startBtn) { _startBtn.disabled = true; _startBtn.textContent = '⏳'; }

    try {
        const origin = window._currentOrigin;
        const dest   = window._currentDest;

        // 1) GPS silenzioso
        let gpsPos = null;
        if (origin && origin.isGPS) {
            try {
                gpsPos = await new Promise((resolve, reject) => {
                    if (!navigator.geolocation) { reject(); return; }
                    navigator.geolocation.getCurrentPosition(
                        p => resolve({ lat: p.coords.latitude, lon: p.coords.longitude }),
                        () => reject(),
                        { timeout: 6000, maximumAge: 30000 }
                    );
                });
                userLat = gpsPos.lat;
                userLon = gpsPos.lon;
            } catch (e) { gpsPos = null; }
        }

        // 2) Registra l'evento (non bloccante)
        try {
            await apiFetch('/journeys/select', {
                method: 'POST',
                body: JSON.stringify({
                    mode:        selectedJourney.mode,
                    green_index: selectedJourney.greenIndex,
                    distance_km: selectedJourney.distanceKm,
                    cost_euros:  selectedJourney.costEuros,
                    origin_name: origin ? origin.name : null,
                    dest_name:   dest ? dest.name : null
                })
            });
        } catch (e) { console.warn('Could not record journey event:', e); }

        // 3) Nascondi le fermate e la rete di base
        if (window._stopMarkers) window._stopMarkers.forEach(m => map.removeLayer(m));
        setNetworkLinesVisible(false);

        // 4) Pulisci i layer del viaggio precedente
        clearRoutePreview();
        ['_routeLine','_routeLineGps','_journeyDestMarker','_journeyOriginMarker','_journeyGpsMarker']
            .forEach(k => { if (window[k]) { map.removeLayer(window[k]); window[k] = null; } });
        clearInterval(window._busPollInterval);
        window._busPollInterval = null;
        if (typeof clearBusMarkers === 'function') clearBusMarkers();

        const { mode, label, greenIndex, distanceKm } = selectedJourney;
        const color = LINE_COLORS[mode] || '#0f172a';

        // 5) Marker origine
        const stopIcon = L.divIcon({
            html: '<div style="background:#10b981;color:white;width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 8px rgba(0,0,0,0.2);font-size:11px;font-weight:800">M</div>',
            className: '', iconSize: [28, 28]
        });
        window._journeyOriginMarker = L.marker([origin.lat, origin.lon], { icon: stopIcon })
            .addTo(map)
            .bindPopup('<b>' + escHtml(origin.name) + '</b><br>' + t('lbl_starting_point'));

        // 6) Linea GPS→fermata solo se origine = My Location
        if (gpsPos && origin.isGPS) {
            placeUserMarker(gpsPos.lat, gpsPos.lon);
            window._routeLineGps = L.polyline(
                [[gpsPos.lat, gpsPos.lon], [origin.lat, origin.lon]],
                { color: '#94a3b8', weight: 3, opacity: 0.7, dashArray: '6,6' }
            ).addTo(map);
        } else if (gpsPos) {
            placeUserMarker(gpsPos.lat, gpsPos.lon);
        } else if (userMarker) {
            map.removeLayer(userMarker);
            userMarker = null;
        }

        // 7) Marker destinazione
        window._journeyDestMarker = L.marker([dest.lat, dest.lon], { icon: makeDestIcon('#ef4444') })
            .addTo(map)
            .bindPopup('<b>📍 ' + dest.name + '</b>').openPopup();

        // 8) Disegna il percorso
        window._busRouteLines = window._busRouteLines || [];
        window._busRouteLines.forEach(l => map.removeLayer(l));
        window._busRouteLines = [];

        if (mode === 'BUS' && selectedJourney.legs && selectedJourney.legs.length > 0) {
            let colorIdx = 0;

            selectedJourney.legs.forEach(leg => {
                if (leg.mode === 'BUS' && leg.stop_coords && leg.stop_coords.length >= 2) {
                    const legColor = BUS_LEG_COLORS[colorIdx % BUS_LEG_COLORS.length];
                    colorIdx++;
                    const coords   = leg.stop_coords.map(c => [c[0], c[1]]);
                    const stopDots = (leg.bus_stop_coords || leg.stop_coords).map(c => [c[0], c[1]]);
                    const line = L.polyline(coords, { color: legColor, weight: 5, opacity: 0.9 }).addTo(map);
                    window._busRouteLines.push(line);

                    stopDots.forEach((c, i) => {
                        const isFirst = i === 0;
                        const isLast  = i === stopDots.length - 1;
                        const dotColor = isFirst || isLast ? legColor : '#fff';
                        const dot = L.circleMarker([c[0], c[1]], {
                            radius: isFirst || isLast ? 7 : 5,
                            color: legColor, fillColor: dotColor, fillOpacity: 1, weight: 2
                        }).addTo(map);
                        window._busRouteLines.push(dot);
                    });
                } else if (leg.mode === 'WALK' && leg.stop_coords && leg.stop_coords.length >= 2) {
                    const coords = leg.stop_coords.map(c => [c[0], c[1]]);
                    const line = L.polyline(coords, { color: '#94a3b8', weight: 3, opacity: 0.7, dashArray: '6,6' }).addTo(map);
                    window._busRouteLines.push(line);
                }
            });

            const allBusCoords = selectedJourney.legs
                .filter(l => l.stop_coords)
                .flatMap(l => l.stop_coords.map(c => [c[0], c[1]]));
            if (allBusCoords.length > 0) map.fitBounds(allBusCoords, { padding: [50, 50] });

        } else {
            window._routeLine = L.polyline(
                [[origin.lat, origin.lon], [dest.lat, dest.lon]],
                { color, weight: 5, opacity: 0.85, dashArray: mode === 'WALK' ? '8,8' : null }
            ).addTo(map);
        }

        // 9) Fit bounds
        const allPoints = (gpsPos && origin.isGPS)
            ? [[gpsPos.lat, gpsPos.lon], [origin.lat, origin.lon], [dest.lat, dest.lon]]
            : [[origin.lat, origin.lon], [dest.lat, dest.lon]];
        map.fitBounds(allPoints, { padding: [50, 50] });

        // 10) Pannello "in progress"
        const durationMin = selectedJourney.durationMinutes
            || Math.ceil(distanceKm / (mode==='WALK'?5:mode==='BIKE'?15:mode==='SCOOTER'?20:25) * 60);
        const modeEmoji = MODE_ICONS[mode];
        const isBusMode = mode === 'BUS' && selectedJourney.legs && selectedJourney.legs.length > 0;
        const timelineHtml = isBusMode
            ? buildTimeline(selectedJourney.legs, durationMin, greenIndex)
            : buildSingleLegTimeline(mode, selectedJourney.legs, distanceKm, selectedJourney.co2Grams ?? 0);

        document.querySelector('.routes-list').innerHTML = `
            <div style="padding:12px 0">
              <!-- Header -->
              <div style="background:white;border-radius:18px;padding:16px 16px 12px;margin-bottom:10px;box-shadow:0 2px 16px rgba(0,0,0,0.07);border:1px solid #f1f5f9">
                <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">
                  <div style="width:42px;height:42px;border-radius:14px;background:linear-gradient(135deg,#10b981,#3b82f6);display:flex;align-items:center;justify-content:center;font-size:22px;flex-shrink:0">${modeEmoji}</div>
                  <div style="flex:1;min-width:0">
                    <div style="font-size:10px;font-weight:700;color:#10b981;text-transform:uppercase;letter-spacing:0.5px">${t('journey_in_progress')}</div>
                    <div style="font-size:14px;font-weight:700;color:#0f172a;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${isBusMode ? fmtRouteLabel(label) : label}</div>
                  </div>
                  <div style="background:#f0fdf4;border:1.5px solid #bbf7d0;border-radius:20px;padding:6px 14px;text-align:center;flex-shrink:0">
                    <div style="font-size:18px;font-weight:900;color:#059669;line-height:1"><span id="etaCounter">${durationMin}</span></div>
                    <div style="font-size:9px;font-weight:700;color:#6ee7b7;text-transform:uppercase">${t('min_left')}</div>
                  </div>
                </div>
                <!-- Vertical timeline (all modes) -->
                <div class="journey-timeline">${timelineHtml}</div>
                <!-- Footer -->
                <div style="display:flex;align-items:center;justify-content:center;gap:8px;margin-top:12px;padding-top:10px;border-top:1px solid #f1f5f9">
                  <span style="width:7px;height:7px;border-radius:50%;background:#10b981;animation:pulse 1.5s infinite;display:inline-block"></span>
                  <span style="font-size:11px;color:#64748b;font-weight:600">${t('live_tracking')}</span>
                  <span style="font-size:11px;color:#cbd5e1">·</span>
                  <span style="font-size:11px;color:#64748b">🌱 ${greenIndex}/100</span>
                </div>
              </div>
              <!-- End Journey -->
              <button onclick="endJourney()" style="width:100%;background:white;color:#ef4444;border:1.5px solid #fecaca;border-radius:14px;font-size:14px;font-weight:700;padding:14px;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px">
                <span style="font-size:16px">🏁</span> ${t('end_journey')}
              </button>
            </div>`;

        let remaining = durationMin;
        window._etaInterval = setInterval(() => {
            if (remaining > 0) {
                remaining--;
                const el = document.getElementById('etaCounter');
                if (el) el.textContent = remaining + ' min';
            } else {
                clearInterval(window._etaInterval);
                showToast(t('toast_arrived'));
            }
        }, 60000);

        showToast(tf('toast_journey_started', { min: durationMin }));

        // 11) Live bus markers — polling already started in showRoutePreview.
        //     Restart here only if somehow not running (e.g. Start Journey without preview).
        if (mode === 'BUS' && !window._busPollInterval) {
            const activeBusLegs = (selectedJourney.legs || [])
                .filter(l => l.mode === 'BUS' && l.route_id)
                .map((l, idx) => ({
                    routeId: l.route_id,
                    color: BUS_LEG_COLORS[idx % BUS_LEG_COLORS.length],
                    boardingCoords: l.stop_coords ? l.stop_coords[0] : null
                }));
            window._activeBusLegs     = activeBusLegs;
            window._activeBusRouteIds = activeBusLegs.map(l => l.routeId);
            if (_pickerHour === null) {
                fetchAndRenderBusMarkers();
                window._busPollInterval = setInterval(fetchAndRenderBusMarkers, 12000);
            }
        }

    } catch (err) {
        console.error('startJourney failed:', err);
        showToast(t('toast_journey_start_fail'), true);
        window._journeyStarting = false;
        if (_startBtn) { _startBtn.disabled = false; _startBtn.textContent = 'Start Journey'; }
    }
}

// ── Live bus markers ──────────────────────────────────────────────

function makeBusMarkerHtml(color) {
    return `
    <div style="position:relative;width:30px;height:30px">
      <div style="
        position:absolute;top:50%;left:50%;
        width:30px;height:30px;border-radius:50%;
        background:${color};opacity:0.22;
        animation:busPulse 2s ease-out infinite;pointer-events:none;
      "></div>
      <div style="
        position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
        width:24px;height:24px;border-radius:50%;
        background:${color};border:2px solid white;
        box-shadow:0 2px 6px rgba(0,0,0,0.3);
        display:flex;align-items:center;justify-content:center;cursor:pointer;
      ">
        <svg width="14" height="14" viewBox="0 0 32 32" fill="white" xmlns="http://www.w3.org/2000/svg">
          <!-- roof / destination sign -->
          <rect x="6" y="2" width="20" height="5" rx="2" fill="white"/>
          <!-- body -->
          <rect x="4" y="6" width="24" height="18" rx="3" fill="white"/>
          <!-- windshield -->
          <rect x="7" y="8" width="18" height="9" rx="1.5" fill="${color}"/>
          <!-- windshield divider -->
          <rect x="15.5" y="8" width="1" height="9" fill="white" opacity="0.5"/>
          <!-- bumper -->
          <rect x="6" y="24" width="20" height="3" rx="1" fill="white"/>
          <!-- headlights -->
          <rect x="6"  y="19" width="5" height="4" rx="1" fill="${color}"/>
          <rect x="21" y="19" width="5" height="4" rx="1" fill="${color}"/>
          <!-- wheels -->
          <circle cx="10" cy="28" r="3" fill="white" stroke="${color}" stroke-width="1.2"/>
          <circle cx="22" cy="28" r="3" fill="white" stroke="${color}" stroke-width="1.2"/>
        </svg>
      </div>
    </div>`;
}

function clearBusMarkers() {
    (window._busMarkers || []).forEach(m => map.removeLayer(m));
    window._busMarkers = [];
    hideStaleNotice();
}

// Euclidean approximation — good enough for picking the nearest vehicle
function _latLonDist(lat1, lon1, lat2, lon2) {
    const d = (lat2 - lat1) * (lat2 - lat1) + (lon2 - lon1) * (lon2 - lon1);
    return Math.sqrt(d);
}

function renderBusMarkers(vehicles) {
    clearBusMarkers();
    const legs = window._activeBusLegs || [];

    if (legs.length === 0) {
        // No leg context — fall back to showing all vehicles (black)
        vehicles.forEach(v => {
            if (v.lat == null || v.lon == null) return;
            const icon = L.divIcon({ html: makeBusMarkerHtml('#0f172a'), className: '', iconSize: [32,32], iconAnchor: [16,16] });
            window._busMarkers.push(L.marker([v.lat, v.lon], { icon }).addTo(map));
        });
        return;
    }

    // One marker per leg: pick the vehicle on that route closest to the boarding stop
    legs.forEach(leg => {
        const candidates = vehicles.filter(v => v.route_id === leg.routeId && v.lat != null && v.lon != null);
        if (candidates.length === 0) return;

        let best = candidates[0];
        if (candidates.length > 1 && leg.boardingCoords) {
            const [bLat, bLon] = leg.boardingCoords;
            best = candidates.reduce((b, v) =>
                _latLonDist(v.lat, v.lon, bLat, bLon) < _latLonDist(b.lat, b.lon, bLat, bLon) ? v : b
            );
        }

        const delayTxt = best.delay_minutes == null
            ? t('delay_unknown')
            : best.delay_minutes <= 0 ? t('on_time') : tf('delay_late', { m: best.delay_minutes });
        const popup = `<b>🚌 ${escHtml(best.vehicle_id || '—')}</b><br>`
            + `${t('lbl_route')}: ${escHtml(best.route_name || best.route_id || '—')}<br>`
            + `${escHtml(delayTxt)}<br>`
            + `${t('next_stop')}: ${escHtml(best.next_stop_name || '—')}`;

        const icon = L.divIcon({
            html: makeBusMarkerHtml(leg.color),
            className: '', iconSize: [32, 32], iconAnchor: [16, 16]
        });
        window._busMarkers.push(L.marker([best.lat, best.lon], { icon }).addTo(map).bindPopup(popup));
    });
}

function showStaleNotice(msg) {
    document.getElementById('bus-stale-notice')?.remove(); // replace if already shown
    const el = document.createElement('div');
    el.id = 'bus-stale-notice';
    el.style.cssText = [
        'position:absolute;bottom:80px;left:50%;transform:translateX(-50%)',
        'background:rgba(30,30,30,0.88);color:#fff;border-radius:10px',
        'padding:8px 14px;font-size:12px;font-weight:600;z-index:1000',
        'display:flex;align-items:center;gap:8px;white-space:nowrap;pointer-events:auto'
    ].join(';');
    const text = msg || t('stale_bus_data');
    el.innerHTML = text + ' <span onclick="this.parentElement.remove()" style="cursor:pointer;opacity:0.7;margin-left:4px">✕</span>';
    const mapEl = document.getElementById('map');
    if (mapEl) mapEl.appendChild(el);
}

function hideStaleNotice() {
    const el = document.getElementById('bus-stale-notice');
    if (el) el.remove();
}

async function fetchAndRenderBusMarkers() {
    const routeIds = window._activeBusRouteIds || [];
    // Empty routeIds = no route filter = show all active buses
    const qs = routeIds.length > 0
        ? '?route_ids=' + routeIds.map(encodeURIComponent).join(',')
        : '';
    console.log('[BUS] fetchAndRenderBusMarkers → route_ids:', routeIds, '| qs:', qs || '(none)');
    try {
        const r = await apiFetch('/journeys/live-buses' + qs);
        console.log('[BUS] HTTP status:', r.status);
        if (!r.ok) throw new Error(r.status);
        const list = await r.json();
        console.log('[BUS] vehicles from API:', list);
        if (Array.isArray(list) && list.length > 0) {
            hideStaleNotice();
            renderBusMarkers(list);
        } else {
            console.warn('[BUS] empty list → showing stale notice');
            clearBusMarkers();
            showStaleNotice();
        }
    } catch (e) {
        console.warn('[BUS] Live bus fetch failed:', e);
        clearBusMarkers();
        showStaleNotice();
    }
}

function endJourney() {
    clearJourneySelection();

    // Keep the live GPS dot if we have a real position
    if (userLat) placeUserMarker(userLat, userLon);

    map.setView([41.4901, 13.8303], 15);

    // Restore route cards from the last search so the user can pick again without re-searching
    if (window._lastSearchData) {
        renderRoutes(window._lastSearchData);
        showToast(`🏁 ${t('journey_completed')} — ${t('search_new_route')}`);
    } else {
        document.querySelector('.routes-list').innerHTML =
            '<div style="text-align:center;padding:48px 20px;color:var(--text-soft)">'
            + '<div style="font-size:36px;margin-bottom:12px">🌱</div>'
            + `<div style="font-size:14px;font-weight:700;color:var(--text-dark)">${t('journey_completed')}</div>`
            + `<div style="font-size:12px;margin-top:6px">${t('search_new_route')}</div>`
            + '</div>';
    }

    if (!window.matchMedia('(max-width: 768px)').matches) showToast(t('toast_journey_ended'));
    loadEcoStats();
}

// ── Toast ─────────────────────────────────────────────────────────
function showToast(msg, isError = false) {
    const existing = document.getElementById('toast-notif');
    if (existing) existing.remove();
    const toast = document.createElement('div');
    toast.id = 'toast-notif';
    toast.style.cssText = `
        position:fixed;bottom:24px;left:50%;transform:translateX(-50%);
        background:${isError ? '#ef4444' : '#0f172a'};
        color:white;padding:12px 20px;border-radius:50px;
        font-size:13px;font-weight:700;z-index:9999;
        box-shadow:0 8px 24px rgba(0,0,0,0.2);white-space:nowrap;
    `;
    toast.textContent = msg;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

// ── Sidebar nav ───────────────────────────────────────────────────
document.querySelectorAll('.sidebar-nav .nav-item').forEach(item => {
    item.addEventListener('click', () => {
        const pane = item.dataset.pane;
        const tab  = item.dataset.tab;
        document.querySelectorAll('.sidebar-nav .nav-item').forEach(n => n.classList.remove('active'));
        item.classList.add('active');
        document.querySelectorAll('.pane').forEach(p => p.classList.remove('active'));
        document.getElementById('pane-' + pane).classList.add('active');
        if (pane === 'profile' && tab) switchProfileTab(tab);
        if (pane === 'map') setTimeout(() => map.invalidateSize(), 50);
    });
});

function showTicketType(type, el) {
    document.querySelectorAll('.type-chip').forEach(c => c.classList.remove('active'));
    el.classList.add('active');
    document.querySelectorAll('.ticket-list').forEach(l => l.classList.remove('active'));
    document.getElementById(type + '-tickets').classList.add('active');
}

function switchProfileTab(tab) {
    document.querySelectorAll('.profile-tab').forEach(t => t.classList.toggle('active', t.dataset.ptab === tab));
    document.querySelectorAll('.ptab-pane').forEach(p => p.classList.remove('active'));
    const el = document.getElementById('ptab-' + tab);
    if (el) el.classList.add('active');
    if (tab === 'history')   loadHistory();
    if (tab === 'favorites') loadFavorites();
    if (tab === 'settings')  loadPreferences();
    if (tab === 'account') {
        const user = JSON.parse(sessionStorage.getItem('omnimove_user') || '{}');
        document.getElementById('accountName').textContent  = user.name  || '—';
        document.getElementById('accountEmail').textContent = user.email || '—';
    }
}

// Escape a string so it is safe inside a double-quoted HTML attribute.
function escAttr(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;').replace(/"/g, '&quot;')
        .replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// One delegated listener handles every star, present or future.
document.addEventListener('click', e => {
    const star = e.target.closest('.fav-star');
    if (!star) return;
    toggleFav(star, star.dataset.mode, star.dataset.origin, star.dataset.dest);
});

async function toggleFav(star, mode, originName, destName) {
    try {
        const r = await apiFetch('/traveller/favorites/toggle', {
            method: 'POST',
            body: JSON.stringify({ mode, originName, destName })
        });
        if (!r.ok) throw new Error('toggle fav failed');
        const result = await r.json();

        star.classList.toggle('starred', result.favorited);
        star.textContent = result.favorited ? '★' : '☆';

        if (document.getElementById('ptab-favorites').classList.contains('active')) {
            loadFavorites();
        }
    } catch (e) {
        console.warn('Could not toggle favourite:', e);
        showToast(t('toast_fav_error'), true);
    }
}

function openAI() {
    document.getElementById('aiOverlay').classList.add('open');
    setTimeout(() => document.getElementById('aiInput').focus(), 100);
}

function closeAI(e) {
    if (!e || e.target === document.getElementById('aiOverlay')) {
        document.getElementById('aiOverlay').classList.remove('open');
    }
}

// ── Eco Score explainer ────────────────────────────────────────────
// Opened by the small "i" on the eco card; explains the GreenIndexService formula.
function openEcoInfo() {
    const modal = document.getElementById('ecoInfoModal');
    modal.classList.add('open');
    modal.querySelector('.eco-modal-body').scrollTop = 0;
    modal.querySelector('.eco-modal-ok').focus();
}

function closeEcoInfo(e) {
    // Backdrop clicks close, clicks inside the card do not
    if (!e || e.target === document.getElementById('ecoInfoModal')) {
        document.getElementById('ecoInfoModal').classList.remove('open');
    }
}

document.addEventListener('keydown', e => {
    if (e.key === 'Escape') closeEcoInfo();
});

// Every logout entry point (sidebar list, drawer arrow, Profile > Settings) goes through here
function confirmLogout() {
    // The drawer stays open on purpose: "No" must put you back exactly where you were
    document.getElementById('logoutModal').classList.add('open');
}

function closeLogoutModal(e) {
    if (!e || e.target === document.getElementById('logoutModal')) {
        document.getElementById('logoutModal').classList.remove('open');
    }
}

async function logout() {
    // Blacklists the token server-side, expires the JWT cookie, then wipes
    // localStorage + sessionStorage and drops this page from the history stack.
    await OmniSession.endSession(LOGIN_PAGE);
}

function confirmDeleteAccount() {
    const modal = document.getElementById('deleteModal');
    modal.style.display = 'flex';
}

async function deleteAccount() {
    const btn = document.getElementById('confirmDeleteBtn');
    btn.disabled = true;
    btn.textContent = 'Deleting…';

    try {
        const r = await fetch(API_BASE + '/auth/account', {
            method: 'DELETE',
            credentials: 'same-origin'
        });

        if (r.ok) {
            // The account is gone: tear the session down exactly like a logout
            OmniSession.clearClientSession();
            window.location.replace(LOGIN_PAGE);
        } else {
            const data = await r.json().catch(() => ({}));
            alert(data.message || 'Could not delete account. Please try again.');
            btn.disabled = false;
            btn.textContent = 'Yes, delete my account';
        }
    } catch (e) {
        alert('Cannot reach server.');
        btn.disabled = false;
        btn.textContent = 'Yes, delete my account';
    }
}

let aiThinking = false;
// Conversation memory for this chat session.
// Each entry: { role: 'user' | 'assistant', content: '...' }
let aiHistory = [];
const AI_HISTORY_LIMIT = 6; // last 3 exchanges sent to keep requests small

async function sendAI(presetText) {
    if (aiThinking) return;
    const input = document.getElementById('aiInput');
    const msgs  = document.getElementById('aiMessages');
    const text  = (presetText || input.value).trim();
    if (!text) return;

    const userMsg = document.createElement('div');
    userMsg.className = 'ai-msg user';
    userMsg.textContent = text;
    msgs.appendChild(userMsg);
    input.value = '';
    msgs.scrollTop = msgs.scrollHeight;

    // Remove any previous suggestion chips
    const oldChips = document.getElementById('aiSuggestions');
    if (oldChips) oldChips.remove();

    aiThinking = true;
    const waitMsg = document.createElement('div');
    waitMsg.className = 'ai-msg bot';
    waitMsg.textContent = '…';
    msgs.appendChild(waitMsg);
    msgs.scrollTop = msgs.scrollHeight;

    try {
        const r = await apiFetch('/ai/chat', {
            method: 'POST',
            body: JSON.stringify({
                message: text,
                language: getLang(),
                history: aiHistory.slice(-AI_HISTORY_LIMIT)
            })
        });
        const data = await r.json();
        const answer = data.answer || t('ai_no_answer');
        waitMsg.textContent = answer;

        // Save exchange to memory
        aiHistory.push({ role: 'user',      content: text });
        aiHistory.push({ role: 'assistant', content: answer });

        // Show tappable follow-up suggestions if backend sent any
        if (Array.isArray(data.suggestions) && data.suggestions.length) {
            renderSuggestions(data.suggestions);
        }
    } catch (e) {
        waitMsg.textContent = t('ai_conn_error');
    } finally {
        aiThinking = false;
        msgs.scrollTop = msgs.scrollHeight;
    }
}

function renderSuggestions(suggestions) {
    const msgs = document.getElementById('aiMessages');
    const wrap = document.createElement('div');
    wrap.id = 'aiSuggestions';
    wrap.style.cssText = 'display:flex;flex-wrap:wrap;gap:6px;margin-top:4px;align-self:flex-start;';
    suggestions.forEach(s => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.textContent = s;
        chip.style.cssText =
            'background:var(--primary-light);border:1px solid #a7f3d0;' +
            'color:var(--primary-dark);font-family:inherit;font-size:11px;' +
            'font-weight:700;padding:6px 12px;border-radius:50px;cursor:pointer;' +
            'transition:background .15s;';
        chip.onmouseover = () => chip.style.background = '#a7f3d0';
        chip.onmouseout  = () => chip.style.background = 'var(--primary-light)';
        chip.addEventListener('click', () => sendAI(s));
        wrap.appendChild(chip);
    });
    msgs.appendChild(wrap);
    msgs.scrollTop = msgs.scrollHeight;
}

// ── Initial load: stops (dropdowns + map markers) and the line network ──
// Independent of each other on purpose: a failing stops call should not leave
// the map blank of lines, and vice versa.
loadStops();
loadNetworkLines();

// ══════════════════════════════════════════════════════════════

// ── Step 3: mobile ☰ menu drawer ──────────────────────────────
function openMenu() {
    document.getElementById('menuDrawer').classList.add('open');
    document.getElementById('menuScrim').classList.add('open');
}
function closeMenu() {
    document.getElementById('menuDrawer').classList.remove('open');
    document.getElementById('menuScrim').classList.remove('open');
}
// Tapping a menu item on mobile switches the pane (existing handler) then closes the drawer
document.querySelectorAll('.sidebar-nav .nav-item').forEach(function (it) {
    it.addEventListener('click', function () {
        if (window.matchMedia('(max-width: 768px)').matches) closeMenu();
    });
});

// ── Step 4: mobile full-screen subpages (back arrow + title) ──
function setMobilePane(pane, title) {
    document.body.dataset.pane = pane || 'map';
    var t = document.getElementById('mobileTitle');
    if (t) t.textContent = title || '';
}
function backToMap() {
    var mapNav = document.querySelector('.sidebar-nav .nav-item[data-pane="map"]');
    if (mapNav) mapNav.click();          // reuse the existing pane-switch handler
    else setMobilePane('map', '');
}
// Track the active pane so CSS can swap the search UI for the back bar
document.querySelectorAll('.sidebar-nav .nav-item').forEach(function (it) {
    it.addEventListener('click', function () {
        var pane = it.dataset.pane || 'map';
        var clone = it.cloneNode(true);
        var badge = clone.querySelector('.nav-badge');
        if (badge) badge.remove();
        var label = clone.textContent.replace(/\s+/g, ' ').trim();
        setMobilePane(pane, pane === 'map' ? '' : label);
    });
});
// Start on the map pane
setMobilePane('map', '');

// ── Step 5: back arrow reopens the ☰ menu; track profile section ──
function backToMap() { openMenu(); }   // overrides the earlier definition
document.querySelectorAll('.sidebar-nav .nav-item').forEach(function (it) {
    it.addEventListener('click', function () {
        document.body.dataset.tab = it.dataset.tab || '';
    });
});

// ── Step 6: ← returns to the map (☰ still opens the menu for other sections) ──
function backToMap() {
    var n = document.querySelector('.sidebar-nav .nav-item[data-pane="map"]');
    if (n) n.click();
}

// ── Step 7: ensure the Leaflet map renders on mobile / after reflow ──
window.addEventListener('resize', function () { try { map.invalidateSize(); } catch (e) {} });
setTimeout(function () { try { map.invalidateSize(); } catch (e) {} }, 500);

// ── Step 9: the ← on option pages reopens the menu ──
function backToMap() { openMenu(); }

// ── Step 10: draggable routes sheet (Google-Maps style, mobile only) ──
(function () {
    var sheet = document.querySelector('.map-sidebar');
    var handle = document.getElementById('sheetHandle');
    if (!sheet || !handle) return;

    function isMobile() { return window.matchMedia('(max-width: 768px)').matches; }
    var _pane = document.getElementById('pane-map');
    function paneH() { return (_pane && _pane.clientHeight) ? _pane.clientHeight : window.innerHeight; }
    function states() { var h = paneH(); return [110, Math.round(h * 0.45), Math.round(h * 0.94)]; }   // peek · half · full (capped to the map area)
    function clamp(h) { var s = states(); return Math.max(s[0], Math.min(s[s.length - 1], h)); }
    function pointY(e) { return e.touches ? e.touches[0].clientY : e.clientY; }

    var dragging = false, startY = 0, startH = 0, lastY = 0;

    function down(e) {
        if (!isMobile()) return;
        dragging = true;
        sheet.style.transition = 'none';
        startY = lastY = pointY(e);
        startH = sheet.offsetHeight;
        e.preventDefault();
    }
    function move(e) {
        if (!dragging) return;
        lastY = pointY(e);
        sheet.style.height = clamp(startH + (startY - lastY)) + 'px';
        if (e.cancelable) e.preventDefault();
    }
    function up() {
        if (!dragging) return;
        dragging = false;
        sheet.style.transition = 'height 0.25s ease';
        var s = states();
        var dy = startY - lastY;                 // >0 up, <0 down
        var cur = 0, best = Infinity;
        s.forEach(function (t, i) { var d = Math.abs(t - startH); if (d < best) { best = d; cur = i; } });
        var target = cur;
        if (dy > 30 && cur < s.length - 1) target = cur + 1;      // dragged up → bigger
        else if (dy < -30 && cur > 0) target = cur - 1;          // dragged down → smaller
        else {
            best = Infinity;
            var h = sheet.offsetHeight;
            s.forEach(function (t, i) { var d = Math.abs(t - h); if (d < best) { best = d; target = i; } });
        }
        sheet.style.height = s[target] + 'px';
        try { map.invalidateSize(); } catch (e) {}
    }

    handle.addEventListener('mousedown', down);
    handle.addEventListener('touchstart', down, { passive: false });
    window.addEventListener('mousemove', move);
    window.addEventListener('touchmove', move, { passive: false });
    window.addEventListener('mouseup', up);
    window.addEventListener('touchend', up);
    window.addEventListener('resize', function () { if (!isMobile()) sheet.style.height = ''; });
})();

// ── Step 12: closing the menu (✕ / tap-outside) returns to the map ──
function closeMenuMap() {
    var n = document.querySelector('.sidebar-nav .nav-item[data-pane="map"]');
    if (n) n.click(); else closeMenu();
}

// ── Step 19: back arrow reveals the map behind the reopened menu ──
function backToMap() {
    var n = document.querySelector('.sidebar-nav .nav-item[data-pane="map"]');
    if (n) n.click();   // switch to the map pane (also closes menu + sets data-pane=map)
    openMenu();         // then reopen the menu over the map
}

// ── Step 23: on mobile, move the zoom control to top-right (menu floats top-left) ──
if (window.matchMedia('(max-width: 768px)').matches) {
    try { map.zoomControl.setPosition('topright'); } catch (e) {}
}

// ── Step 24: typable search with autocomplete suggestions ──────────
function setStop(el, id) {
    if (!el) return;
    if (id === 'GPS') { el.value = t('my_location'); _acSetId(el, 'GPS'); return; }
    const s = STOPS[id];
    if (s) { el.value = s.name; el.dataset.id = id; }
}

let _acFor = null;
let _acIndex = -1;   // keyboard-highlighted row, -1 = none

function _acSetId(el, id) {
    if (id) el.dataset.id = id; else delete el.dataset.id;
}

// The field's real value is dataset.id — the text is just a label. Typing therefore has to
// invalidate whatever was picked before: without this, editing "Cassino Stazione" down to
// "zzz" left the old id in place and Search quietly planned the journey to the stop the
// traveller had just deleted. A name typed in full still counts as a choice.
function _acSyncId(inputEl) {
    const v = (inputEl.value || '').trim().toLowerCase();
    if (!v) { _acSetId(inputEl, ''); return; }
    if (v === t('my_location').toLowerCase()) { _acSetId(inputEl, 'GPS'); return; }
    const hit = Object.values(STOPS).find(s => (s.name || '').toLowerCase() === v);
    _acSetId(inputEl, hit ? hit.id : '');
}

// Generous cap: the list is scrollable, so the limit is only there to keep the DOM
// bounded if the network ever grows well beyond today's 20 stops
const AC_MAX_ITEMS = 50;

// One rule for every keystroke: a stop matches when its name contains the query, and
// prefix matches are listed first. The old "contains" fallback only ran when nothing at
// all started with the query, so a stop you could see a moment ago disappeared as soon as
// an unrelated one happened to start with the same letters.
function _acItems(inputEl, q) {
    q = (q || '').trim().toLowerCase();
    const starts = [], contains = [];
    const consider = (id, name) => {
        const n = (name || '').toLowerCase();
        if (!q || n.startsWith(q)) starts.push({ id, name });
        else if (n.includes(q)) contains.push({ id, name });
    };
    consider('GPS', t('my_location'));   // offered on both ends: you can travel to where you are too
    Object.values(STOPS).forEach(s => consider(s.id, s.name));
    return starts.concat(contains).slice(0, AC_MAX_ITEMS);
}

// Opening the list on the whole catalogue only makes sense in two cases: an empty field,
// and a field still showing the stop picked last time — there, filtering on its own value
// would answer a click with a list of exactly one item, the very stop you want to change.
// Any other content is something the traveller typed, and it has to keep filtering.
function _acShowAllFor(inputEl) {
    const v = (inputEl.value || '').trim();
    if (!v) return true;
    const id = inputEl.dataset.id;
    if (!id) return false;
    const picked = id === 'GPS' ? t('my_location') : (STOPS[id] || {}).name;
    return !!picked && picked.toLowerCase() === v.toLowerCase();
}

function _acPosition(inputEl) {
    const acList = document.getElementById('acList');
    if (!acList || !inputEl) return;
    const r = inputEl.getBoundingClientRect();
    acList.style.left = r.left + 'px';
    acList.style.top = (r.bottom + 4) + 'px';
    acList.style.width = r.width + 'px';
}

function _acShow(inputEl, showAll) {
    const acList = document.getElementById('acList');
    if (!acList) return;
    _acFor = inputEl;
    const items = _acItems(inputEl, showAll ? '' : inputEl.value);
    if (!items.length) { acList.style.display = 'none'; return; }
    acList.innerHTML = items.map(it =>
        `<div class="ac-item" data-id="${escAttr(it.id)}">${escHtml(it.name)}</div>`).join('');
    _acIndex = -1;              // the rows were just rebuilt, nothing is highlighted
    _acPosition(inputEl);
    acList.style.display = 'block';
}

function _acHide() {
    const acList = document.getElementById('acList');
    if (acList) acList.style.display = 'none';
    _acFor = null;
    _acIndex = -1;
}

function _acIsOpen() {
    const acList = document.getElementById('acList');
    return !!acList && acList.style.display === 'block';
}

// Wraps around, so ArrowUp on a freshly opened list lands on the last stop
function _acHighlight(i) {
    const acList = document.getElementById('acList');
    if (!acList) return;
    const items = acList.querySelectorAll('.ac-item');
    if (!items.length) return;
    _acIndex = (i % items.length + items.length) % items.length;
    items.forEach((el, n) => el.classList.toggle('ac-active', n === _acIndex));
    items[_acIndex].scrollIntoView({ block: 'nearest' });
}

// Committing a choice is the same job whether it came from a tap or from Enter, so both
// go through here — the mouse path used to be the only way to ever set dataset.id.
function _acPick(target, id) {
    if (id === 'GPS') {
        setStop(target, 'GPS');
        showToast(t('toast_locating'));
        tryGetGPS().then(pos => { showToast(t('toast_located')); map.setView([pos.lat, pos.lon], 16); })
                   .catch(() => showToast(t('toast_gps_pick_stop'), true));
    } else {
        setStop(target, id);
    }
    _acHide();
}

function initAutocomplete() {
    const acList = document.getElementById('acList');
    if (!acList) return;
    ['originSelect', 'destSelect'].forEach(id => {
        const el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('focus', () => {
            const showAll = _acShowAllFor(el);
            // Select the text only when it is a stop already chosen, where typing means
            // "replace this". Selecting a half-typed query would let the next keystroke
            // wipe the filter the traveller was in the middle of writing.
            if (showAll) el.select();
            _acShow(el, showAll);
        });
        // A click on an already-focused field fires no focus event — without this, closing
        // the list and clicking again would leave you stuck with no way to reopen it. It
        // reopens on whatever the field holds right now, so clicking to fix a typo or move
        // the caret no longer throws away the filter you had just typed.
        el.addEventListener('click', () => _acShow(el, _acShowAllFor(el)));
        el.addEventListener('input', () => { _acSyncId(el); _acShow(el); });
        el.addEventListener('blur', () => setTimeout(_acHide, 150));
        el.addEventListener('keydown', (e) => {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();                       // don't jump the caret around
                const step = e.key === 'ArrowDown' ? 1 : -1;
                if (!_acIsOpen()) _acShow(el, _acShowAllFor(el));
                // Starting from "nothing highlighted", Down opens on the first row and Up
                // wraps to the last one
                _acHighlight(_acIndex < 0 ? (step === 1 ? 0 : -1) : _acIndex + step);
                return;
            }
            if (e.key === 'Escape') {
                if (_acIsOpen()) { e.preventDefault(); _acHide(); }
                return;
            }
            if (e.key === 'Enter') {
                if (!_acIsOpen()) return;                 // nothing to commit, let Search run
                const items = acList.querySelectorAll('.ac-item');
                // With nothing highlighted, Enter still commits when the query left exactly
                // one candidate — typing a name in full and pressing Enter is the common case.
                const item = _acIndex >= 0 ? items[_acIndex] : (items.length === 1 ? items[0] : null);
                if (!item) return;
                e.preventDefault();
                _acPick(el, item.dataset.id);
            }
        });
    });
    acList.addEventListener('mousedown', (e) => {
        const item = e.target.closest('.ac-item');
        if (!item || !_acFor) return;
        e.preventDefault();
        _acPick(_acFor, item.dataset.id);
    });
    // Capture fires for scrolls on any element, the list included — and the list is
    // scrollable (max-height 250px against ~20 stops), so hiding indiscriminately made
    // every stop past the seventh unreachable except by typing.
    window.addEventListener('scroll', (e) => {
        if (e.target && acList.contains(e.target)) return;
        _acHide();
    }, true);
    // Reposition rather than hide: on mobile the on-screen keyboard resizes the viewport
    // right after the tap that opened the list, which used to close it on the spot.
    const acFollowViewport = () => {
        if (_acFor && acList.style.display === 'block') _acPosition(_acFor);
    };
    window.addEventListener('resize', acFollowViewport);
    if (window.visualViewport) window.visualViewport.addEventListener('resize', acFollowViewport);
}
initAutocomplete();

// ── Route detail sheet: drag resizes the sidebar (unified with main sheet) ──
(function initRdDrag() {
    const ready = () => {
        const rdSheet  = document.querySelector('.rd-sheet');
        const overlay  = document.getElementById('routeDetailSheet');
        const sidebar  = document.querySelector('.map-sidebar');
        if (!rdSheet || !overlay || !sidebar) return;

        // Only start a drag from the non-scrollable zones (handle, header, pills)
        const dragZones = ['.rd-handle', '.rd-header', '.rd-pills'];

        function isMobile() { return window.matchMedia('(max-width: 768px)').matches; }
        const _pane = document.getElementById('pane-map');
        function paneH() { return (_pane && _pane.clientHeight) ? _pane.clientHeight : window.innerHeight; }
        function snapStates() { const h = paneH(); return [110, Math.round(h * 0.45), Math.round(h * 0.94)]; }
        function clamp(h) { const s = snapStates(); return Math.max(s[0], Math.min(s[s.length - 1], h)); }

        let startY = 0, startH = 0, dy = 0, active = false;

        function onStart(clientY) {
            startY = clientY;
            dy = 0;
            active = true;
            if (isMobile()) {
                startH = sidebar.offsetHeight;
                sidebar.style.transition = 'none';
            } else {
                rdSheet.style.transition = 'none';
            }
        }
        function onMove(clientY) {
            if (!active) return;
            dy = clientY - startY;
            if (isMobile()) {
                sidebar.style.height = clamp(startH - dy) + 'px';
            } else {
                rdSheet.style.transform = `translateY(${Math.max(0, dy)}px)`;
            }
        }
        function onEnd() {
            if (!active) return;
            active = false;
            if (isMobile()) {
                // Snap sidebar to nearest state with velocity bias
                sidebar.style.transition = 'height 0.25s ease';
                const s = snapStates();
                const curH = sidebar.offsetHeight;
                let best = Infinity, target = 0;
                s.forEach((t, i) => { const d = Math.abs(t - curH); if (d < best) { best = d; target = i; } });
                if (dy < -30 && target < s.length - 1) target++;   // flicked up → expand
                else if (dy > 30 && target > 0) target--;          // flicked down → shrink
                sidebar.style.height = s[target] + 'px';
                try { map.invalidateSize(); } catch (e) {}
            } else {
                if (dy > 90) {
                    rdSheet.style.transition = 'transform 0.22s ease';
                    rdSheet.style.transform  = 'translateY(110%)';
                    setTimeout(() => {
                        rdSheet.style.transition = '';
                        rdSheet.style.transform  = '';
                        closeRouteDetail();
                    }, 230);
                } else {
                    rdSheet.style.transition = 'transform 0.2s cubic-bezier(0.34,1.56,0.64,1)';
                    rdSheet.style.transform  = '';
                    setTimeout(() => { rdSheet.style.transition = ''; }, 210);
                }
            }
        }

        // Touch
        rdSheet.addEventListener('touchstart', e => {
            if (!dragZones.some(sel => e.target.closest(sel))) return;
            onStart(e.touches[0].clientY);
        }, { passive: true });
        rdSheet.addEventListener('touchmove', e => {
            if (!active) return;
            onMove(e.touches[0].clientY);
            if (e.cancelable) e.preventDefault();
        }, { passive: false });
        rdSheet.addEventListener('touchend',   onEnd);
        rdSheet.addEventListener('touchcancel', onEnd);

        // Mouse (desktop drag for dev/testing)
        rdSheet.addEventListener('mousedown', e => {
            if (!dragZones.some(sel => e.target.closest(sel))) return;
            onStart(e.clientY);
            const move = ev => onMove(ev.clientY);
            const up   = () => { onEnd(); window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up); };
            window.addEventListener('mousemove', move);
            window.addEventListener('mouseup', up);
        });
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready);
    else ready();
})();
