// XSS defense: escape any API-supplied string before inserting into innerHTML
function escHtml(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

//  FRONTEND ROUTE GUARD
// V-04 FIX: Token is in httpOnly cookie (sent automatically). User data is in sessionStorage.
const _user = JSON.parse(sessionStorage.getItem('omnimove_user') || '{}');
if (!_user.name && !_user.email) {
    window.location.href = 'omnimove-login.html';
}

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

const _name = _user.name || _user.username || 'there';
document.getElementById('aiGreeting').textContent =
    `👋 Hi ${_name}! I'm OmniAI. I can help you find the best route, check real-time delays, or suggest eco-friendly alternatives. What do you need?`;

const API_BASE = '/omnimove/api/v1';

async function loadEcoStats() {
    try {
        const r = await apiFetch('/traveller/stats');
        if (!r.ok) throw new Error('stats ' + r.status);
        const s = await r.json();

        document.getElementById('sidebarEcoPoints').textContent = s.ecoPoints.toLocaleString() + ' pts';
        document.getElementById('sidebarEcoSub').textContent = `🌱 ${s.co2SavedKg} kg CO₂ saved this month`;

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

        showToast('✅ Preferences saved!');
    } catch (e) {
        console.warn('Could not save preferences:', e);
        showToast('Errore nel salvataggio preferenze', true);
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
        const origin = j.originName || 'My Location';
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
    const token = localStorage.getItem('omnimove_token');
    return fetch(API_BASE + path, {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            ...(token ? {'Authorization': 'Bearer ' + token} : {}),
            ...(options.headers || {})
        }
    });
}

// ── Map init ──────────────────────────────────────────────────────
const map = L.map('map', { zoomControl: true }).setView([41.4901, 13.8303], 15);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap'
}).addTo(map);

// ── Icons ─────────────────────────────────────────────────────────

// Pulsing blue dot — used for "you are here" both on the idle map and during journey
const userIcon = L.divIcon({
    html: `<div style="
        width:18px;height:18px;border-radius:50%;
        background:#3b82f6;border:3px solid white;
        box-shadow:0 0 0 4px rgba(59,130,246,0.35);
        animation:pulse-blue 2s infinite;
    "></div>`,
    className: '',
    iconSize: [18, 18],
    iconAnchor: [9, 9]
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
        const safeName = escHtml(stop.name);
        // Use data-attributes on the button so the delegated listener can read them
        // without needing inline onclick (CSP-safe, no JSON injection risk).
        const popup =
            `<b>${safeName}</b><br>` +
            `<button class="stop-check-btn" ` +
            `data-stop-id="${escAttr(stop.id)}" data-stop-name="${escAttr(stop.name)}">` +
            `${t('btn_check_buses')}</button>`;
        const marker = L.marker([stop.lat, stop.lon], { icon: STOP_ICON })
            .addTo(map)
            .bindPopup(popup);
        window._stopMarkers.push(marker);
    });
}

// Delegated listener for "Check next buses" buttons inside Leaflet popups.
document.addEventListener('click', e => {
    const btn = e.target.closest('.stop-check-btn');
    if (btn) showStopArrivals(btn.dataset.stopId, btn.dataset.stopName);
});

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

async function showStopArrivals(stopId, stopName) {
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
}

function handleSheetBackdrop(e) {
    if (e.target === document.getElementById('stopSheetOverlay')) closeStopSheet();
}

document.addEventListener('keydown', e => { if (e.key === 'Escape') closeStopSheet(); });

// Ritardo nel popup fermata: real-time (Google on), retrospettivo C1 (off), o niente.
function delayLine(a) {
    if (!a.departed) return '';                       // non partito: solo orario
    const m = a.delay_minutes;
    if (a.real_time) {
        if (m == null)  return `<span class="delay-chip d-live d-unknown">Live</span>`;
        if (m <= 0)     return `<span class="delay-chip d-live d-ontime">On time (live)</span>`;
        return `<span class="delay-chip d-live d-late">${m} min late (live)</span>`;
    }
    if (m == null) return '';                          // partito ma nessun arrivo misurato
    const at = a.delay_stop_name ? ` at ${escHtml(a.delay_stop_name)}` : '';
    if (m <= 0)   return `<span class="delay-chip d-hist d-ontime">Was on time${at}</span>`;
    return `<span class="delay-chip d-hist d-late">Was ${m} min late${at}</span>`;
}

// Deterministic color per route short-name (consistent across renders)
const ROUTE_PALETTE = ['#d32f2f','#1565c0','#2e7d32','#e65100','#6a1b9a','#00695c','#37474f','#ad1457','#0277bd','#558b2f'];
function routeColor(name) {
    let h = 0;
    for (const c of (name || '')) h = (h * 31 + c.charCodeAt(0)) & 0xffffffff;
    return ROUTE_PALETTE[Math.abs(h) % ROUTE_PALETTE.length];
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
        const color   = routeColor(shortName);
        const first   = group[0];
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
            delayHtml = `<span class="tmb-delay">${first.delay_minutes} min late</span>`;
        }

        // Crowding on first arrival
        const crowding = first.crowding_level;
        const crowdHtml = (crowding && CROWDING_BG[crowding])
            ? `<span class="tmb-crowd" style="${CROWDING_BG[crowding]}">${t('lbl_crowding')} ${getCrowdingLabel(crowding)}</span>`
            : '';

        return `<div class="tmb-route-row">
            <div class="tmb-badge" style="background:${color}">${escHtml(shortName)}</div>
            <div class="tmb-info">
                <div class="tmb-times">${timesHtml}</div>
                <div class="tmb-meta">${rtHtml}${delayHtml}${crowdHtml}</div>
                ${direction ? `<div class="tmb-dir">→ ${direction}</div>` : ''}
            </div>
        </div>`;
    }).join('');
}

// ── Load stops from the backend → fill dropdowns + map markers ─────
async function loadStops() {
    const originSel = document.getElementById('originSelect');
    const destSel   = document.getElementById('destSelect');
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

        // Typable inputs: set sensible defaults (display name + hidden stop id)
        setStop(originSel, stops[0].id);
        setStop(destSel, stops.length > 1 ? stops[1].id : stops[0].id);

        renderStopMarkers();

        const bounds = stops.map(s => [s.lat, s.lon]);
        if (bounds.length) map.fitBounds(bounds, { padding: [60, 60], maxZoom: 15 });
    } catch (e) {
        console.error('loadStops failed:', e);
        showToast('Impossibile caricare le fermate dal backend', true);
    }
}

// ── GPS state ─────────────────────────────────────────────────────
let userLat = null;
let userLon = null;
let userMarker = null;

function placeUserMarker(lat, lon) {
    if (userMarker) map.removeLayer(userMarker);
    userMarker = L.marker([lat, lon], { icon: userIcon })
        .addTo(map)
        .bindPopup('📍 You are here');
}

function tryGetGPS() {
    return new Promise((resolve, reject) => {
        if (!navigator.geolocation) { reject('no_geolocation'); return; }
        navigator.geolocation.getCurrentPosition(
            pos => {
                userLat = pos.coords.latitude;
                userLon = pos.coords.longitude;
                placeUserMarker(userLat, userLon);
                resolve({ name: 'My Location', lat: userLat, lon: userLon, isGPS: true });
            },
            err => {
                // Friendly fallback so the demo still works
                userLat = 41.5020; userLon = 13.8200;
                placeUserMarker(userLat, userLon);
                resolve({ name: 'Via Folcara (approx)', lat: userLat, lon: userLon, isGPS: true });
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
    const ov = o.value, oid = o.dataset.id;
    o.value = d.value; o.dataset.id = d.dataset.id;
    d.value = ov;      d.dataset.id = oid;
}

function getOrigin() {
    const el  = document.getElementById('originSelect');
    const val = el.dataset.id;
    // No origin picked (empty field) → default to My Location (GPS)
    if (!el.value.trim() || !val || val === 'GPS') {
        setStop(el, 'GPS');          // show "My Location" in the field, don't leave it empty
        if (!userLat) return null;   // null → doSearch will request GPS
        return { name: 'My Location', lat: userLat, lon: userLon, isGPS: true };
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
    const destId = document.getElementById('destSelect').dataset.id;
    const dest   = STOPS[destId];
    let origin   = getOrigin();

    if (!origin) {
        showToast('📡 Getting your location...', false);
        try { origin = await tryGetGPS(); }
        catch (e) { showToast('GPS unavailable — select a stop as origin', true); return; }
    }

    if (origin.name === dest.name) {
        showToast('Origin and destination cannot be the same', true); return;
    }

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
            dest_lat:   dest.lat,   dest_lon:   dest.lon,   dest_name:   dest.name,
            user_id: _user.id, dest_stop_id: dest.id, origin_stop_id: origin.isGPS ? null : origin.id,
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
    <button class="action-btn ${btn.cls}"
        onclick="selectMode('${opt.mode}','${opt.mode_label}',${opt.green_index},${opt.distance_metres},${opt.cost_euros})">
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
            showStaleNotice(t('live_buses_future') || '🕐 Live positions not shown for future trips');
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

// selectMode — highlights the card, previews the route on the map, and shows the Start Journey banner.
// Full GPS resolution + solid lines happen in startJourney.
function selectMode(mode, label, greenIndex, distanceMetres, costEuros) {
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

function closeRouteDetail() {
    document.getElementById('routeDetailSheet').classList.remove('open');
    // De-select all route cards
    document.querySelectorAll('.route-card').forEach(c => {
        c.style.border = '1px solid var(--border-mid)';
        c.style.opacity = '1';
    });
    // Stop live bus polling and clear real-time bus markers
    clearInterval(window._busPollInterval);
    window._busPollInterval = null;
    clearBusMarkers();
    // Clear map preview and restore bus stop markers
    (window._previewLayers || []).forEach(l => map.removeLayer(l));
    window._previewLayers = [];
    if (window._stopMarkers) window._stopMarkers.forEach(m => m.addTo(map));
    selectedJourney = null;
}

function startJourneyFromDetail() {
    document.getElementById('routeDetailSheet').classList.remove('open');
    startJourney();
}

// ── Helpers shared by selectMode preview and startJourney ───────────
function fmtD(m) { return m < 1000 ? Math.round(m) + ' m' : (m/1000).toFixed(1) + ' km'; }


// ── Route label: "3 → Dest" or "3 → Dest + 1 → Dest2" → circles ──
function fmtRouteLabel(instruction, circleStyle) {
    if (!instruction) return 'Bus';
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
        const isTransfer = leg.mode === 'WAIT' && leg.instruction && leg.instruction.toLowerCase().includes('change at');

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
                ${_stopRow(leg.from || 'Start', walkDepMs)}
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
                    <div style="font-size:12px;font-weight:700;color:#ef4444">${leg.instruction || 'Change bus'}</div>
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
    const from   = leg?.from || 'Start';
    const to     = leg?.to   || 'Destination';
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

        // 3) Nascondi le fermate
        if (window._stopMarkers) window._stopMarkers.forEach(m => map.removeLayer(m));

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
            .bindPopup('<b>' + origin.name + '</b><br>Starting point');

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
                showToast('🎉 You have arrived!');
            }
        }, 60000);

        showToast(`Journey started! ${durationMin} min to destination`);

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
        showToast('⚠️ Impossibile avviare il percorso', true);
        window._journeyStarting = false;
        if (_startBtn) { _startBtn.disabled = false; _startBtn.textContent = 'Start Journey'; }
    }
}

// ── Live bus markers ──────────────────────────────────────────────

function makeBusMarkerHtml(color) {
    return `<div style="background:${color};color:white;width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;border:2px solid white;box-shadow:0 2px 8px rgba(0,0,0,0.35);font-size:16px;cursor:pointer">🚌</div>`;
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
            : best.delay_minutes <= 0 ? t('on_time') : `${best.delay_minutes} min late`;
        const popup = `<b>🚌 ${best.vehicle_id || '—'}</b><br>`
            + `Route: ${best.route_name || best.route_id || '—'}<br>`
            + `${delayTxt}<br>`
            + `${t('next_stop')}: ${best.next_stop_name || '—'}`;

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
    const text = msg || '⚠️ Live bus data unavailable — positions may be outdated';
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
    clearInterval(window._etaInterval);
    clearInterval(window._busPollInterval);
    window._busPollInterval = null;
    clearBusMarkers();
    clearRoutePreview();
    window._activeBusRouteIds = [];
    selectedJourney = null;
    window._journeyStarting = false;
    const _rdBtn = document.getElementById('rdStartBtn');
    if (_rdBtn) { _rdBtn.disabled = false; _rdBtn.textContent = t('btn_start_journey'); }

    // Remove all journey map layers
    ['_routeLine','_routeLineGps','_journeyDestMarker','_journeyOriginMarker','_journeyGpsMarker']
        .forEach(k => { if (window[k]) { map.removeLayer(window[k]); window[k] = null; } });

    if (window._busRouteLines) {
        window._busRouteLines.forEach(l => map.removeLayer(l));
        window._busRouteLines = [];
    }
    // Keep the live GPS dot if we have a real position
    if (userLat) placeUserMarker(userLat, userLon);

    // Restore bus-stop markers
    if (window._stopMarkers) window._stopMarkers.forEach(m => m.addTo(map));

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

    if (!window.matchMedia('(max-width: 768px)').matches) showToast('🏁 Journey ended — great trip!');
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
        showToast('Errore nel salvare il preferito', true);
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

async function logout() {
    const token = localStorage.getItem('omnimove_token');
    if (token) {
        await fetch('/omnimove/api/v1/auth/logout', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + token }
        }).catch(() => {});
    }
    localStorage.removeItem('omnimove_token');
    localStorage.removeItem('omnimove_user');
    window.location.href = 'omnimove-login.html';
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
        const token = localStorage.getItem('omnimove_token');
        const r = await fetch('/omnimove/api/v1/auth/account', {
            method: 'DELETE',
            headers: { 'Authorization': 'Bearer ' + token }
        });

        if (r.ok) {
            localStorage.removeItem('omnimove_token');
            localStorage.removeItem('omnimove_user');
            window.location.href = 'omnimove-login.html';
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
                language: 'it',
                history: aiHistory.slice(-AI_HISTORY_LIMIT)
            })
        });
        const data = await r.json();
        const answer = data.answer || 'Risposta non disponibile.';
        waitMsg.textContent = answer;

        // Save exchange to memory
        aiHistory.push({ role: 'user',      content: text });
        aiHistory.push({ role: 'assistant', content: answer });

        // Show tappable follow-up suggestions if backend sent any
        if (Array.isArray(data.suggestions) && data.suggestions.length) {
            renderSuggestions(data.suggestions);
        }
    } catch (e) {
        waitMsg.textContent = 'Errore di connessione al backend. Verifica che OMNIMOVE sia avviato.';
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

// ── Initial load: populate stops (dropdowns + map markers) ─────────
loadStops();

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
    if (id === 'GPS') { el.value = 'My Location'; el.dataset.id = 'GPS'; return; }
    const s = STOPS[id];
    if (s) { el.value = s.name; el.dataset.id = id; }
}

let _acFor = null;

function _acItems(inputEl, q) {
    q = (q || '').trim().toLowerCase();
    const out = [];
    if (inputEl.id === 'originSelect') {
        if (!q || 'my location'.indexOf(q) === 0) out.push({ id: 'GPS', name: 'My Location' });
    }
    Object.values(STOPS).forEach(s => {
        const n = (s.name || '').toLowerCase();
        if (!q || n.startsWith(q)) out.push({ id: s.id, name: s.name });
    });
    // fall back to "contains" if nothing starts with the query
    if (q && out.length === 0) {
        Object.values(STOPS).forEach(s => {
            if ((s.name || '').toLowerCase().includes(q)) out.push({ id: s.id, name: s.name });
        });
    }
    return out.slice(0, 8);
}

function _acShow(inputEl) {
    const acList = document.getElementById('acList');
    if (!acList) return;
    _acFor = inputEl;
    const items = _acItems(inputEl, inputEl.value);
    if (!items.length) { acList.style.display = 'none'; return; }
    acList.innerHTML = items.map(it =>
        `<div class="ac-item" data-id="${escAttr(it.id)}">${escHtml(it.name)}</div>`).join('');
    const r = inputEl.getBoundingClientRect();
    acList.style.left = r.left + 'px';
    acList.style.top = (r.bottom + 4) + 'px';
    acList.style.width = r.width + 'px';
    acList.style.display = 'block';
}

function _acHide() {
    const acList = document.getElementById('acList');
    if (acList) acList.style.display = 'none';
    _acFor = null;
}

function initAutocomplete() {
    const acList = document.getElementById('acList');
    if (!acList) return;
    ['originSelect', 'destSelect'].forEach(id => {
        const el = document.getElementById(id);
        if (!el) return;
        el.addEventListener('focus', () => { el.select(); _acShow(el); });
        el.addEventListener('input', () => _acShow(el));
        el.addEventListener('blur', () => setTimeout(_acHide, 150));
    });
    acList.addEventListener('mousedown', (e) => {
        const item = e.target.closest('.ac-item');
        if (!item || !_acFor) return;
        e.preventDefault();
        const id = item.dataset.id;
        const target = _acFor;
        if (id === 'GPS') {
            setStop(target, 'GPS');
            showToast('📡 Getting your location...');
            tryGetGPS().then(pos => { showToast('📍 Location detected!'); map.setView([pos.lat, pos.lon], 16); })
                       .catch(() => showToast('GPS unavailable — pick a stop', true));
        } else {
            setStop(target, id);
        }
        _acHide();
    });
    window.addEventListener('scroll', _acHide, true);
    window.addEventListener('resize', _acHide);
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
