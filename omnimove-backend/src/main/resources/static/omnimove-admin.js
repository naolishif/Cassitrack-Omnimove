function escHtml(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

const LOGIN_PAGE = 'omnimove-login.html';

// ── Route guard: solo ADMIN può accedere ──────────────────────────────
(function checkAdminAuth() {
    const userRaw = sessionStorage.getItem('omnimove_user');
    if (!userRaw) {
        // Dopo il login si torna qui
        window.location.replace(OmniSession.loginUrlWithReturn(LOGIN_PAGE));
        return;
    }
    try {
        const user = JSON.parse(userRaw);
        const role = (user.role || '').toUpperCase();
        if (role !== 'ADMIN') {
            alert('Accesso negato: solo gli amministratori possono accedere a questa pagina.');
            window.location.replace(LOGIN_PAGE);
        }
    } catch (e) {
        sessionStorage.clear();
        window.location.replace(LOGIN_PAGE);
    }
})();
// Il guard qui sopra non gira su un ripristino da bfcache — questo copre il Back
OmniSession.bindSessionGuard(LOGIN_PAGE);

// ── API helper con JWT ─────────────────────────────────────────────────
const API_BASE = '/omnimove/api/v1';  // context-path prefix required

async function apiFetch(path, options = {}) {
    // V-04 FIX: Token is in httpOnly cookie (sent automatically by browser).
    // We don't need to manually add 'Authorization' header here.
    const res = await fetch(API_BASE + path, {
        ...options,
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            ...(options.headers || {})
        }
    });
    // Session revoked or expired server-side → back to the login page
    return OmniSession.handleUnauthorized(res, LOGIN_PAGE);
}

// ── Clock ──
function updateClock(){
    const n=new Date();
    document.getElementById('clock').innerHTML=
    document.getElementById('clock').innerHTML=
        `<span>${n.toLocaleTimeString('en-GB')}</span> | ${n.toLocaleDateString('it-IT', {day:'numeric', month:'long', year:'numeric'})}`;
}
setInterval(updateClock,1000); updateClock();

const _now = new Date();
const _month = _now.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
document.getElementById('exportLabel').textContent = `OMNIMOVE — FR-OM-009 Dashboard — ${_month}`;

// ── Tabs ──
document.querySelectorAll('.tab').forEach(t=>{
    t.addEventListener('click',()=>{
        document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));
        document.querySelectorAll('.pane').forEach(x=>x.classList.remove('active'));
        t.classList.add('active');
        document.getElementById('pane-'+t.dataset.tab).classList.add('active');
        if (t.dataset.tab === 'settings') { loadGoogleSettings(); loadRetention(); loadAiNudge(); }
    });
});

// ── Toast ──
function toast(msg, err=false){
    const el=document.getElementById('toastEl');
    el.textContent=msg;
    el.className='toast'+(err?' err':'');
    setTimeout(()=>el.classList.add('show'),10);
    setTimeout(()=>el.classList.remove('show'),2800);
}

// ── User data ──
const roles = { ADMIN:'badge-admin', TRAVELLER:'badge-user', Suspended:'badge-suspended' };
let users = [];

async function loadUsers({ silent = false } = {}) {
    try {
        const r = await apiFetch('/admin/users');
        if (r.status === 403) { if (!silent) toast('Accesso negato.', true); return false; }
        users = await r.json();
        // Re-render through the filter, otherwise a background refresh would
        // wipe whatever the operator has typed into the search box
        applyFilter();
        return true;
    } catch(e) {
        // A background poll that fails must not toast every 30 seconds
        if (!silent) toast('Errore caricamento utenti.', true);
        else console.warn('Background user refresh failed:', e);
        return false;
    }
}
// ── Date helpers ──
// Spring serialises LocalDateTime as ISO-8601 ("2026-08-27T15:04:05") — no
// timezone, so it is read as local time, which is what the operator expects.
function toDate(v) {
    if (!v) return null;
    // Defensive: some Jackson configurations emit [y,m,d,h,mi,s] instead
    if (Array.isArray(v)) {
        const [y, mo, d, h = 0, mi = 0, sec = 0] = v;
        return new Date(y, mo - 1, d, h, mi, sec);
    }
    const dt = new Date(v);
    return isNaN(dt.getTime()) ? null : dt;
}

function fmtDateTime(v) {
    const d = toDate(v);
    if (!d) return '—';
    return d.toLocaleDateString('it-IT', { day: '2-digit', month: '2-digit', year: 'numeric' })
         + ' ' + d.toLocaleTimeString('it-IT', { hour: '2-digit', minute: '2-digit' });
}

/** "3 hours ago" style hint next to an absolute timestamp. */
function relativeTime(v) {
    const d = toDate(v);
    if (!d) return '';
    const mins = Math.round((Date.now() - d.getTime()) / 60000);
    if (mins < 1)    return 'just now';
    if (mins < 60)   return `${mins} min ago`;
    const hours = Math.round(mins / 60);
    if (hours < 24)  return `${hours} h ago`;
    const days = Math.round(hours / 24);
    return days === 1 ? 'yesterday' : `${days} days ago`;
}

function renderTable(data) {
    const tb = document.getElementById('userTbody');
    tb.innerHTML = data.map(u => {
        const never   = !u.lastLoginAt;
        const count   = u.loginCount || 0;
        // Nothing to open when the account has never been used
        const lastCell = never
            ? `<button class="login-cell" disabled>never</button>`
            : `<button class="login-cell" onclick="openHistory(${u.id})"
                       title="Show every access">${escHtml(fmtDateTime(u.lastLoginAt))}<span class="count">${count}×</span></button>`;
        return `
    <tr>
      <td class="text-mono">#${escHtml(u.id)}</td>
      <td><button class="name-cell" onclick="openProfile(${u.id})"
                  title="Open profile">${escHtml(u.name)}</button>${unreadTag(u)}</td>
      <td style="color:var(--text-secondary);font-size:12px">${escHtml(u.email)}</td>
      <td><span class="badge ${roles[u.role] || 'badge-user'}">${escHtml(u.role)}</span></td>
      <td class="text-mono" style="font-size:11px">${escHtml(fmtDateTime(u.registeredAt))}</td>
      <td>${lastCell}</td>
      <td>
        <div class="action-btns">
          <button class="icon-btn del" title="Delete" onclick="deleteUser(${u.id})">✕</button>
        </div>
      </td>
    </tr>`;
    }).join('');
}

// ── User profile modal ──
// Shows the same travel figures the user sees in their own app (one server-side
// implementation feeds both), plus the identity fields an admin may correct.
let _profileUserId = null;

// ── Transport modes ──
// A combined journey reaches us as the chain the planner stitched — BUS_BIKE,
// SCOOTER_BUS, BUS_SCOOTER_BUS. Wherever the operator reads it, it has to say
// what the traveller app says: one category, "Combined". The test is the
// underscore rather than a list of names, so a chain the planner learns to
// build tomorrow lands in the right bucket without a change here.
const MODE_COLOR = {
    BUS:'#00cfff', BIKE:'#00e5a0', SCOOTER:'#3a8eff',
    WALK:'#7a90a8', TRAIN:'#a855f7', COMBINED:'#fbbf24'
};
const MODE_NAME = {
    BUS:'Bus', BIKE:'Shared Bike', SCOOTER:'E-Scooter',
    WALK:'Walking', TRAIN:'Train', COMBINED:'Combined'
};
const MODE_EMOJI = {
    BUS:'🚌', BIKE:'🚲', SCOOTER:'🛴', WALK:'🚶', TRAIN:'🚆', COMBINED:'🔀'
};

/** Collapses a stitched chain onto the single COMBINED category. */
function modeKey(mode) {
    if (!mode) return 'UNKNOWN';
    const m = String(mode).toUpperCase();
    return m.includes('_') ? 'COMBINED' : m;
}

function modeName(mode)  { const k = modeKey(mode); return MODE_NAME[k] || k; }
function modeColor(mode) { return MODE_COLOR[modeKey(mode)] || '#7a90a8'; }

/** Emoji + name, for the inline tags in the user card. */
function modeTag(mode) {
    const k = modeKey(mode);
    return `${MODE_EMOJI[k] || ''} ${MODE_NAME[k] || k}`.trim();
}

/**
 * Charts must not draw one slice per chain, all of them labelled "Combined".
 * These fold every key that maps to the same category into a single series
 * before anything is plotted.
 */
function foldModeCounts(dist) {
    const out = {};
    for (const [mode, v] of Object.entries(dist || {})) {
        const k = modeKey(mode);
        out[k] = (out[k] || 0) + Number(v);
    }
    return out;
}

function foldModeHours(byHour) {
    const out = {};
    for (const [mode, hours] of Object.entries(byHour || {})) {
        const k = modeKey(mode);
        if (!out[k]) out[k] = new Array(24).fill(0);
        Array.from(hours).forEach((n, i) => { out[k][i] += Number(n) || 0; });
    }
    return out;
}

async function openProfile(id) {
    _profileUserId = id;
    const modal = document.getElementById('profileModal');
    const user  = users.find(u => u.id === id);

    document.getElementById('profileTitle').textContent = user ? user.name : 'User Profile';
    document.getElementById('profileId').innerHTML      = '';
    document.getElementById('profileKpis').innerHTML    = '';
    document.getElementById('profileTrips').innerHTML     = `<div class="history-empty">Loading…</div>`;
    document.getElementById('profileFavRoutes').innerHTML = '';
    document.getElementById('profileFavStops').innerHTML  = '';
    ['secTrips','secFavRoutes','secFavStops'].forEach(k =>
        document.getElementById(k).classList.remove('open'));
    modal.classList.add('open');

    try {
        // Deeper slice than the app's own panel: the operator pages through it
        // locally, so one request beats a round trip per page
        const r = await apiFetch(`/admin/users/${id}/profile?trips=${TRIPS_FETCHED}`);
        if (!r.ok) {
            document.getElementById('profileTrips').innerHTML =
                `<div class="history-empty">Could not load the profile.</div>`;
            return;
        }
        renderProfile(await r.json());
    } catch (e) {
        document.getElementById('profileTrips').innerHTML =
            `<div class="history-empty">Connection error.</div>`;
    }
}

/**
 * How the account was created. Worth surfacing: a Google sign-up has no
 * password at all, so "no password set" is expected there and suspicious
 * anywhere else.
 */
function signUpBadge(account) {
    if (account.authProvider === 'GOOGLE') {
        const also = account.hasPassword ? ' + password' : '';
        return `<span class="signup-tag signup-google">Signed up with Google${escHtml(also)}</span>`;
    }
    return `<span class="signup-tag signup-local">Signed up with email</span>`;
}

/**
 * The messages this person has written.
 *
 * <p>Outside the travel block on purpose: a message is account data, not a
 * journey, so it is shown even for an operator account that never travels.
 * Opening this card is what marks them read server-side, which is why the
 * unread marker in the list clears as soon as somebody looks.
 */
/**
 * The marker beside a name when messages are waiting.
 *
 * <p>A count, not a dot: "3 unread" and "1 unread" are different amounts of
 * someone's patience. It disappears the moment the card is opened, because
 * that is when the server marks them read.
 */
function unreadTag(u) {
    const n = Number(u.unreadMessages || 0);
    if (!n) return '';
    return ' <span class="unread-tag" title="Unread messages">\u2709 ' + n + '</span>';
}

function renderUserMessages(messages) {
    const box   = document.getElementById('profileMessages');
    const title = document.getElementById('profileMessagesTitle');
    if (!box) return;

    setSectionTitle('profileMessagesTitle', 'Messages (' + messages.length + ')');
    if (!messages.length) {
        box.innerHTML = '<div class="history-empty">No messages from this user.</div>';
        return;
    }
    // The text is the user's own: escaped, and shown with its line breaks.
    box.innerHTML = messages.map(m =>
        '<div class="msg-row' + (m.read ? '' : ' msg-row--new') + '">'
      + '<div class="msg-row-head">'
      +   '<span class="text-mono" style="font-size:11px">' + escHtml(fmtDateTime(m.createdAt)) + '</span>'
      +   (m.read ? '' : '<span class="msg-new-tag">NEW</span>')
      + '</div>'
      + '<div class="msg-row-body">' + escHtml(m.body) + '</div>'
      + '</div>').join('');
}

/**
 * Whether this person has been shown, and acknowledged, each notice.
 *
 * Three states, not two: never shown, acknowledged under the text currently
 * published, and acknowledged under a superseded version — the last one matters
 * because a reworded notice invalidates the old acknowledgement and the banner
 * will ask again, so "seen" alone would mislead the operator.
 */
function noticeBadge(label, ack) {
    if (!ack || ack.seen !== true)
        return `<span class="signup-tag signup-local">${escHtml(label)}: <strong>not seen</strong></span>`;

    const when = ack.at ? fmtDateTime(ack.at) : '—';
    if (ack.current === false)
        return `<span class="signup-tag signup-local">${escHtml(label)}: ` +
               `<strong>older version ${escHtml(ack.policyVersion || '?')}</strong>, ${escHtml(when)}</span>`;

    return `<span class="signup-tag signup-google">${escHtml(label)}: ` +
           `<strong>seen</strong> ${escHtml(when)}</span>`;
}

function renderProfile(data) {
    const a = data.account;

    document.getElementById('profileTitle').textContent = a.name;

    document.getElementById('profileId').innerHTML =
        `<span>ID <strong>#${escHtml(a.id)}</strong></span>` +
        `<span>${escHtml(a.email)}</span>` +
        `<span><span class="badge ${roles[a.role] || 'badge-user'}">${escHtml(a.role)}</span></span>` +
        `<span>${a.verified ? '<span class="text-green">verified</span>'
                            : '<span class="text-amber">not verified</span>'}</span>` +
        `<span>${signUpBadge(a)}</span>` +
        `<span>Registered <strong>${escHtml(fmtDateTime(a.registeredAt))}</strong></span>` +
        `<span>Last login <strong>${a.lastLoginAt ? escHtml(fmtDateTime(a.lastLoginAt)) : 'never'}</strong></span>` +
        `<span>Accesses <strong>${escHtml(a.loginCount)}</strong></span>`;

    const ack = a.acknowledgements || {};
    document.getElementById('profileNotices').innerHTML =
        noticeBadge('Privacy notice', ack.PRIVACY_NOTICE) +
        noticeBadge('Cookie notice',  ack.COOKIE_NOTICE);

    renderUserMessages(data.messages || []);

    // Admin accounts carry no travel story — the server does not even compute it
    const travelBlock = document.getElementById('travelBlock');
    travelBlock.style.display = data.travelData === false ? 'none' : '';

    if (data.travelData !== false) {
        const s = data.stats;
        document.getElementById('profileKpis').innerHTML = `
          <div class="kpi"><div class="kpi-label">Eco points</div>
            <div class="kpi-value">${Number(s.ecoPoints).toLocaleString('it-IT')}</div></div>
          <div class="kpi"><div class="kpi-label">CO₂ saved</div>
            <div class="kpi-value">${escHtml(s.co2SavedKg)} kg</div>
            <div class="kpi-sub">vs the same trips by car</div></div>
          <div class="kpi"><div class="kpi-label">Trips</div>
            <div class="kpi-value">${escHtml(s.trips)}</div></div>
          <div class="kpi"><div class="kpi-label">Spent</div>
            <div class="kpi-value">€${escHtml(s.spent30d)}</div>
            <div class="kpi-sub">last 30 days</div></div>`;

        setSectionTitle('profileTripsTitle',
            data.truncated ? `Journeys — latest ${data.history.length} of ${data.totalTrips}`
                           : `Journeys (${data.totalTrips})`);
        setSectionTitle('profileFavRoutesTitle', `Favourite routes (${(data.favoriteRoutes || []).length})`);
        setSectionTitle('profileFavStopsTitle',  `Favourite stops (${(data.favoriteStops  || []).length})`);

        loadPaged('trips',     data.history        || [], 'secTrips');
        loadPaged('favRoutes', data.favoriteRoutes || [], 'secFavRoutes');
        loadPaged('favStops',  data.favoriteStops  || [], 'secFavStops');
    }

    // "Marco De Luca" → first "Marco", last "De Luca" — mirrors how the add-user
    // form joins the two halves back together.
    const space = (a.name || '').indexOf(' ');
    document.getElementById('editFirst').value = space < 0 ? (a.name || '') : a.name.slice(0, space);
    document.getElementById('editLast').value  = space < 0 ? '' : a.name.slice(space + 1);
    document.getElementById('editEmail').value = a.email;
}

// ── Collapsible, paged lists ──
// A traveller with hundreds of journeys used to stretch this modal past any
// screen. Each list now collapses behind a header that carries its count, and
// opens onto one fixed-size page — so the panel's height follows the page size,
// never the size of the data. Lists that already fit open on their own, because
// hiding two rows behind a click helps nobody.

/** Journeys pulled per profile — the server caps this at 100. */
const TRIPS_FETCHED = 100;
const PAGE_SIZE = 10;

const PAGED = {
    trips: {
        body: 'profileTrips', pager: 'tripsPager',
        empty: 'This user has not travelled yet.',
        render: tripsHtml
    },
    favRoutes: {
        body: 'profileFavRoutes', pager: 'favRoutesPager',
        empty: 'No starred route.',
        render: favRoutesHtml
    },
    favStops: {
        body: 'profileFavStops', pager: 'favStopsPager',
        empty: 'No starred stop.',
        render: favStopsHtml
    }
};

const PAGED_DATA = { trips: [], favRoutes: [], favStops: [] };
const PAGED_PAGE = { trips: 0,  favRoutes: 0,  favStops: 0  };

function setSectionTitle(id, text) {
    document.getElementById(id).textContent = text;
}

function toggleSection(id) {
    document.getElementById(id).classList.toggle('open');
}

function loadPaged(key, items, sectionId) {
    PAGED_DATA[key] = items;
    PAGED_PAGE[key] = 0;
    // One page or less is not worth a click
    document.getElementById(sectionId)
            .classList.toggle('open', items.length > 0 && items.length <= PAGE_SIZE);
    drawPaged(key);
}

function drawPaged(key) {
    const cfg   = PAGED[key];
    const items = PAGED_DATA[key];
    const body  = document.getElementById(cfg.body);
    const pager = document.getElementById(cfg.pager);

    if (!items.length) {
        body.innerHTML  = `<div class="history-empty">${cfg.empty}</div>`;
        pager.innerHTML = '';
        return;
    }

    const pages = Math.ceil(items.length / PAGE_SIZE);
    const page  = Math.min(Math.max(PAGED_PAGE[key], 0), pages - 1);
    PAGED_PAGE[key] = page;

    const from = page * PAGE_SIZE;
    body.innerHTML = cfg.render(items.slice(from, from + PAGE_SIZE));

    pager.innerHTML = pages <= 1 ? '' : `
      <span class="pg-label">${from + 1}–${Math.min(from + PAGE_SIZE, items.length)} of ${items.length}</span>
      <button class="pg" onclick="pagedGo('${key}',-1)" ${page === 0 ? 'disabled' : ''}>‹</button>
      <button class="pg" onclick="pagedGo('${key}',1)" ${page >= pages - 1 ? 'disabled' : ''}>›</button>`;
}

function pagedGo(key, delta) {
    PAGED_PAGE[key] += delta;
    drawPaged(key);
}

// ── Row builders: pure, one page of items in, HTML out ──

function tripsHtml(rows) {
    return `
    <table class="trip-table">
      <thead>
        <tr><th>Date</th><th>Mode</th><th>Route</th><th>Distance</th><th>Cost</th><th>Green</th></tr>
      </thead>
      <tbody>
        ${rows.map(j => `
        <tr>
          <td class="num">${escHtml(fmtDateTime(j.createdAt))}</td>
          <td><span class="mode-tag">${escHtml(modeTag(j.mode))}</span></td>
          <td class="route">${escHtml(j.originName || '—')} → ${escHtml(j.destName || '—')}
              ${j.isFavorite ? '<span class="text-amber" title="Starred by the user">★</span>' : ''}</td>
          <td class="num">${j.distanceKm != null ? Number(j.distanceKm).toFixed(1) + ' km' : '—'}</td>
          <td class="num">${j.costEuros != null ? '€' + Number(j.costEuros).toFixed(2) : '—'}</td>
          <td class="num" style="color:${greenColor(j.greenIndex)}">${escHtml(j.greenIndex ?? '—')}</td>
        </tr>`).join('')}
      </tbody>
    </table>`;
}

function favRoutesHtml(rows) {
    return `<div class="fav-list">${rows.map(f => `
      <div class="fav-row">
        <span class="fav-star">★</span>
        <div class="fav-main">
          ${escHtml(f.originName)} → ${escHtml(f.destName)}
          <div class="fav-sub">${escHtml(modeTag(f.mode))} · ~€${Number(f.avgCost).toFixed(2)} · used ${escHtml(f.usedCount)}×</div>
        </div>
        <div class="fav-green" style="color:${greenColor(f.greenIndex)}">🌱 ${escHtml(f.greenIndex)}</div>
      </div>`).join('')}</div>`;
}

function favStopsHtml(rows) {
    return `<div class="fav-list">${rows.map(st => `
      <div class="fav-row">
        <span class="fav-star">★</span>
        <div class="fav-main">
          ${escHtml(st.name)}
          <div class="fav-sub">${escHtml(st.stop_id)} · ${Number(st.lat).toFixed(5)}, ${Number(st.lon).toFixed(5)}</div>
        </div>
      </div>`).join('')}</div>`;
}

async function saveProfile() {
    if (_profileUserId == null) return;

    const first = document.getElementById('editFirst').value.trim();
    const last  = document.getElementById('editLast').value.trim();
    const email = document.getElementById('editEmail').value.trim();

    if (!first)  { toast('First name is required.', true); return; }
    if (!email)  { toast('Email is required.', true); return; }

    const btn = document.getElementById('saveProfileBtn');
    btn.disabled = true;
    try {
        const r = await apiFetch(`/admin/users/${_profileUserId}`, {
            method: 'PUT',
            body: JSON.stringify({ name: last ? `${first} ${last}` : first, email })
        });
        const data = await r.json();
        if (!r.ok) { toast(data.message || 'Error.', true); return; }

        closeProfileModal();
        toast(data.message || 'User updated.');
        refreshAll();
    } catch (e) {
        toast('Connection error.', true);
    } finally {
        btn.disabled = false;
    }
}

// Reloads the list on close: the messages were marked read while the card was
// open, so the marker beside the name is now stale.
function closeProfileModal() {
    document.getElementById('profileModal').classList.remove('open');
    _profileUserId = null;
    refreshNow();          // the unread marker beside the name is now stale
}

// ── Access history modal ──
async function openHistory(id) {
    const modal = document.getElementById('historyModal');
    const meta  = document.getElementById('historyMeta');
    const body  = document.getElementById('historyBody');

    const user = users.find(u => u.id === id);
    meta.innerHTML = user ? `<strong>${escHtml(user.name)}</strong> — ${escHtml(user.email)}` : '';
    body.innerHTML = `<div class="history-empty">Loading…</div>`;
    modal.classList.add('open');

    try {
        const r = await apiFetch(`/admin/users/${id}/logins`);
        if (!r.ok) { body.innerHTML = `<div class="history-empty">Could not load the history.</div>`; return; }
        const data = await r.json();
        renderHistory(data);
    } catch (e) {
        body.innerHTML = `<div class="history-empty">Connection error.</div>`;
    }
}

function renderHistory(data) {
    const meta = document.getElementById('historyMeta');
    const body = document.getElementById('historyBody');

    meta.innerHTML =
        `<strong>${escHtml(data.name)}</strong> — ${escHtml(data.email)}<br>` +
        `Registered: <strong>${escHtml(fmtDateTime(data.registeredAt))}</strong><br>` +
        `Total accesses: <strong>${escHtml(data.total)}</strong>` +
        (data.truncated ? ` <span style="color:var(--text-dim)">(showing the latest ${data.events.length})</span>` : '');

    if (!data.events.length) {
        body.innerHTML = `<div class="history-empty">No access recorded yet.</div>`;
        return;
    }

    body.innerHTML = `
    <table class="history-table">
      <thead>
        <tr><th>#</th><th>Date &amp; time</th><th>When</th><th>IP</th><th>Device</th></tr>
      </thead>
      <tbody>
        ${data.events.map((e, i) => `
        <tr>
          <td>${data.events.length - i}</td>
          <td class="when">${escHtml(fmtDateTime(e.loggedInAt))}</td>
          <td>${escHtml(relativeTime(e.loggedInAt))}</td>
          <td>${escHtml(e.ipAddress || '—')}</td>
          <td class="agent" title="${escHtml(e.userAgent || '')}">${escHtml(shortAgent(e.userAgent))}</td>
        </tr>`).join('')}
      </tbody>
    </table>`;
}

/** Boils a User-Agent down to "Browser · OS" — the full string is in the tooltip. */
function shortAgent(ua) {
    if (!ua) return '—';
    const browser = /Edg\//.test(ua)     ? 'Edge'
                  : /OPR\//.test(ua)     ? 'Opera'
                  : /Firefox\//.test(ua) ? 'Firefox'
                  : /Chrome\//.test(ua)  ? 'Chrome'
                  : /Safari\//.test(ua)  ? 'Safari'
                  : 'Unknown';
    const os = /Android/.test(ua)               ? 'Android'
             : /iPhone|iPad|iPod/.test(ua)      ? 'iOS'
             : /Windows/.test(ua)               ? 'Windows'
             : /Mac OS X/.test(ua)              ? 'macOS'
             : /Linux/.test(ua)                 ? 'Linux'
             : '';
    return os ? `${browser} · ${os}` : browser;
}

function closeHistoryModal() {
    document.getElementById('historyModal').classList.remove('open');
}

async function loadStats() {
    try {
        const r = await apiFetch('/admin/users/stats');
        if (!r.ok) return false;
        const data = await r.json();
        document.getElementById('statTotal').textContent      = data.total;
        document.getElementById('statAdmins').textContent     = data.admins;
        document.getElementById('statTravellers').textContent = data.travellers;
        document.getElementById('statTotalSub').textContent   = `${data.total} registered`;

        // One key per live token: -1 means Redis could not answer, which is not
        // the same as zero and must not be shown as a count
        const sessions = data.activeSessions;
        document.getElementById('statSessions').textContent =
            (sessions == null || sessions < 0) ? '—' : sessions;
        document.getElementById('statSessionsSub').textContent =
            (sessions == null || sessions < 0) ? 'count unavailable'
          : sessions === 0                     ? 'nobody signed in'
          :                                      'signed in right now';
        return true;
    } catch(e) {
        console.error('Stats error:', e);
        return false;
    }
}

// ── Refresh ──
// The dashboard is a snapshot of server state, so it polls; every mutation also
// refreshes immediately rather than waiting for the next tick.
const REFRESH_MS = 30000;

function stampRefresh(ok) {
    const el = document.getElementById('refreshStamp');
    if (!el) return;
    el.textContent = ok
        ? `updated ${new Date().toLocaleTimeString('en-GB')}`
        : `stale — last try ${new Date().toLocaleTimeString('en-GB')}`;
    el.style.color = ok ? 'var(--text-dim)' : 'var(--accent-amber)';
}

async function refreshAll({ silent = false } = {}) {
    const [usersOk, statsOk] = await Promise.all([loadUsers({ silent }), loadStats()]);
    stampRefresh(usersOk && statsOk);
}

/** Manual refresh from the button. */
async function refreshNow() {
    const btn = document.getElementById('refreshBtn');
    btn.classList.add('busy');
    try { await refreshAll(); } finally { btn.classList.remove('busy'); }
}

refreshAll();

setInterval(() => {
    // Nothing to gain from polling a hidden tab, and a refresh underneath an
    // open modal would move the ground the operator is standing on
    if (document.hidden) return;
    if (document.querySelector('.modal-overlay.open')) return;
    refreshAll({ silent: true });
}, REFRESH_MS);

// Catch up on whatever changed while the tab was in the background
document.addEventListener('visibilitychange', () => {
    if (!document.hidden && !document.querySelector('.modal-overlay.open'))
        refreshAll({ silent: true });
});

// ── Filtering & sorting ──
// All client-side: the registry is already in memory, so re-querying the server
// on every keystroke would buy nothing. Every control funnels through
// applyFilter(), which is also what a background refresh calls — that is what
// keeps a poll from resetting the operator's view.

/** Start of the given day, local time. Empty input → no bound. */
function dayStart(value) {
    if (!value) return null;
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d, 0, 0, 0, 0);
}

/** End of the given day, local time, so a single-day window is not empty. */
function dayEnd(value) {
    if (!value) return null;
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d, 23, 59, 59, 999);
}

const SORTERS = {
    'id-asc':          (a, b) => a.id - b.id,
    'id-desc':         (a, b) => b.id - a.id,
    // Locale-aware so accents and case do not scatter the alphabet
    'name-asc':        (a, b) => (a.name || '').localeCompare(b.name || '', 'it', { sensitivity: 'base' }),
    'name-desc':       (a, b) => (b.name || '').localeCompare(a.name || '', 'it', { sensitivity: 'base' }),
    'registered-desc': (a, b) => byDateDesc(a.registeredAt, b.registeredAt),
    'registered-asc':  (a, b) => byDateAsc(a.registeredAt,  b.registeredAt),
    'login-desc':      (a, b) => byDateDesc(a.lastLoginAt,  b.lastLoginAt),
    'login-asc':       (a, b) => byDateAsc(a.lastLoginAt,   b.lastLoginAt)
};

// Missing dates sort last in BOTH directions — "never logged in" belongs at the
// bottom of the list, not at the top of the ascending one. That rules out
// flipping the arguments of a single comparator, which would carry the nulls
// along with the reversal.
function byDateDesc(x, y) {
    const dx = toDate(x), dy = toDate(y);
    if (!dx && !dy) return 0;
    if (!dx) return 1;
    if (!dy) return -1;
    return dy - dx;
}

function byDateAsc(x, y) {
    const dx = toDate(x), dy = toDate(y);
    if (!dx && !dy) return 0;
    if (!dx) return 1;
    if (!dy) return -1;
    return dx - dy;
}

function applyFilter() {
    const q     = document.getElementById('searchInput').value.trim().toLowerCase();
    const role  = document.getElementById('roleFilter').value;
    const sort  = document.getElementById('sortSelect').value;
    const fromV = document.getElementById('fromDate').value;
    const toV   = document.getElementById('toDate').value;

    const from = dayStart(fromV);
    const to   = dayEnd(toV);

    // Cyan border marks a bound that is actually narrowing the list
    document.getElementById('fromDate').classList.toggle('set', !!fromV);
    document.getElementById('toDate').classList.toggle('set', !!toV);

    const rows = users.filter(u => {
        const matchText = !q ||
            (u.name  || '').toLowerCase().includes(q) ||
            (u.email || '').toLowerCase().includes(q) ||
            String(u.id).includes(q);
        if (!matchText) return false;

        if (role && u.role !== role) return false;

        if (from || to) {
            const reg = toDate(u.registeredAt);
            // An account with no registration date cannot satisfy a window
            if (!reg) return false;
            if (from && reg < from) return false;
            if (to   && reg > to)   return false;
        }
        return true;
    });

    rows.sort(SORTERS[sort] || SORTERS['id-asc']);
    renderTable(rows);

    const count = document.getElementById('filterCount');
    count.textContent = rows.length === users.length
        ? `${users.length} user${users.length === 1 ? '' : 's'}`
        : `${rows.length} of ${users.length}`;
    count.style.color = rows.length === users.length
        ? 'var(--text-secondary)' : 'var(--accent-cyan)';
}

function clearFilters() {
    document.getElementById('searchInput').value = '';
    document.getElementById('roleFilter').value  = '';
    document.getElementById('sortSelect').value  = 'id-asc';
    document.getElementById('fromDate').value    = '';
    document.getElementById('toDate').value      = '';
    applyFilter();
}
let _pendingDeleteId = null;

function deleteUser(id) {
    const user = users.find(u => u.id === id);
    const name = user ? user.name : `#${id}`;
    document.getElementById('deleteMessage').textContent =
        `Remove "${name}"? This action cannot be undone.`;
    _pendingDeleteId = id;
    document.getElementById('deleteModal').classList.add('open');
}

function closeDeleteModal() {
    document.getElementById('deleteModal').classList.remove('open');
    _pendingDeleteId = null;
}

async function confirmDeleteUser() {
    if (_pendingDeleteId == null) return;
    const id = _pendingDeleteId;
    closeDeleteModal();
    try {
        const r = await apiFetch(`/admin/users/${id}`, { method: 'DELETE' });
        const data = await r.json();
        if (!r.ok) { toast(data.message || 'Error.', true); return; }
        toast('User removed.');
        refreshAll();
    } catch(e) {
        toast('Connection error.', true);
    }
}

// log out
async function logout() {
    // Token in blacklist, cookie scaduto, storage ripulito, pagina fuori dalla history
    await OmniSession.endSession(LOGIN_PAGE);
}

// ── Modal ──
function openModal(){ document.getElementById('modalOverlay').classList.add('open'); }
function closeModal(){ document.getElementById('modalOverlay').classList.remove('open'); }

let userCounter=users.length+1;
async function addUser() {
    const first = document.getElementById('newFirst').value.trim();
    const last  = document.getElementById('newLast').value.trim();
    const email = document.getElementById('newEmail').value.trim();
    const pass  = document.getElementById('newPass').value.trim();
    const role  = document.getElementById('newRole').value;

    if (!first || !last || !email || !pass) {
        toast('Compila tutti i campi.', true); return;
    }

    try {
        const r = await apiFetch('/admin/users', {
            method: 'POST',
            body: JSON.stringify({
                name: `${first} ${last}`,
                email, password: pass, role
            })
        });
        const data = await r.json();
        if (!r.ok) { toast(data.message || 'Errore.', true); return; }
        closeModal();
        toast('Utente creato.');
        ['newFirst','newLast','newEmail','newPass'].forEach(i => document.getElementById(i).value = '');
        refreshAll();
    } catch(e) {
        toast('Errore di connessione.', true);
    }
}

// ── Shared chart defaults ──
const C = { f:'Inter', tc:'#7a90a8', dc:'#3d5268', gc:'#1e2d3d' };
const chartDefaults = {
    plugins:{ legend:{ labels:{ color:C.tc, font:{family:C.f,size:10}, boxWidth:10 } } },
    scales:{
        x:{ ticks:{ color:C.dc, font:{family:C.f,size:10} }, grid:{ color:C.gc } },
        y:{ ticks:{ color:C.dc, font:{family:C.f,size:10} }, grid:{ color:C.gc } }
    }
};

// ── Top routes (real data from API) ──────────────────────────────────
const greenColor = g => g >= 75 ? 'var(--accent-green)' : g >= 55 ? 'var(--accent-amber)' : 'var(--accent-red)';

function updateTopRoutes(routes) {
    const tbody = document.getElementById('topRoutesTbody');
    if (!routes || routes.length === 0) {
        tbody.innerHTML = `<tr><td colspan="4" style="text-align:center;color:var(--text-dim);padding:20px;font-size:12px">No route data yet for this period</td></tr>`;
        return;
    }
    tbody.innerHTML = routes.map((r, i) => `
      <tr>
        <td class="text-mono" style="color:var(--text-dim)">${String(i+1).padStart(2,'0')}</td>
        <td style="font-size:13px">${r.origin || '—'} → ${r.dest || '—'}</td>
        <td class="text-mono">${Number(r.uses).toLocaleString('it-IT')}</td>
        <td style="font-family:var(--font-mono);font-size:13px;color:${greenColor(r.avgGreenIndex)}">${r.avgGreenIndex ?? '—'}</td>
      </tr>`).join('');
}

// ── Day of week chart ─────────────────────────────────────────────────
let dowChart = null;
const DAY_SHORT = { MONDAY:'Mon', TUESDAY:'Tue', WEDNESDAY:'Wed', THURSDAY:'Thu', FRIDAY:'Fri', SATURDAY:'Sat', SUNDAY:'Sun' };

function updateDayOfWeek(dowData) {
    if (!dowData) return;
    const order = ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'];
    const labels = order.map(d => DAY_SHORT[d]);
    const values = order.map(d => dowData[d] || 0);
    const colors = order.map((_, i) => i >= 5 ? 'rgba(251,191,36,.7)' : 'rgba(56,189,248,.65)');

    if (dowChart) dowChart.destroy();
    dowChart = new Chart(document.getElementById('chartDayOfWeek'), {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                label: 'Trips',
                data: values,
                backgroundColor: colors,
                borderRadius: 4,
                borderSkipped: false
            }]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                x: { ticks: { color:'#7a90a8', font:{family:'Inter',size:11} }, grid: { display: false } },
                y: { ticks: { color:'#3d5268', font:{family:'Inter',size:10} }, grid: { color:'#1e2d3d' } }
            }
        }
    });
}

// ── Per-chart range: Green Index ──────────────────────────────────────
document.getElementById('giRangeBar').addEventListener('click', async e => {
    const btn = e.target.closest('.range-btn');
    if (!btn) return;
    document.querySelectorAll('#giRangeBar .range-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    try {
        const r = await apiFetch(`/admin/analytics?range=${btn.dataset.range}`);
        if (!r.ok) return;
        const data = await r.json();
        updateGreenIndexChart(data.greenIndexTrend, btn.dataset.range);
        if (data.kpis?.avgGreenIndex != null)
            document.getElementById('giAvgBadge').textContent = data.kpis.avgGreenIndex;
    } catch(e) { console.error('giRange error', e); }
});

// ── Per-chart range: Mode by Hour ────────────────────────────────────
document.getElementById('hourRangeBar').addEventListener('click', async e => {
    const btn = e.target.closest('.range-btn');
    if (!btn) return;
    document.querySelectorAll('#hourRangeBar .range-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    try {
        const r = await apiFetch(`/admin/analytics?range=${btn.dataset.range}`);
        if (!r.ok) return;
        const data = await r.json();
        updateModeByHourChart(data.modeByHour);
    } catch(e) { console.error('hourRange error', e); }
});

// ── Close modals on overlay click ──
document.getElementById('modalOverlay').addEventListener('click', function(e){ if(e.target===this) closeModal(); });
document.getElementById('deleteModal').addEventListener('click',  function(e){ if(e.target===this) closeDeleteModal(); });

// ── Time range filter ────────────────────────────────────────────────────
const RANGE_LABELS = {
    '1W': 'Last 7 days', '1M': 'Last 30 days',
    '3M': 'Last 90 days', '6M': 'Last 6 months', '1Y': 'Last 12 months'
};
let currentRange = '1M';

document.getElementById('rangeBar').addEventListener('click', e => {
    const btn = e.target.closest('.range-btn');
    if (!btn) return;
    document.querySelectorAll('.range-btn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    currentRange = btn.dataset.range;
    document.getElementById('rangeLabel').textContent = RANGE_LABELS[currentRange] || '';
    loadAnalytics(currentRange);
});

// ── Analytics: real data from InfluxDB ──────────────────────────────────
let modeChart = null;
let greenChart = null;

async function loadAnalytics(range = '1M') {
    // The captions are set from the range that was ASKED for, before the answer
    // arrives. A failed load used to return here leaving the previous range's
    // numbers on screen under the previous range's captions: the operator saw
    // "1Y" selected, "Last 12 months" in the corner, and figures labelled "last
    // 30 days" — stale data presented as current, which is worse than a gap.
    setKpiCaptions(range);
    try {
        const r = await apiFetch(`/admin/analytics?range=${range}`);
        if (!r.ok) { console.warn('Analytics endpoint error', r.status); clearKpis(); return; }
        const data = await r.json();

        updateKpis(data.kpis, range);
        updateModeChart(data.modeDistribution);
        updateModeByHourChart(data.modeByHour);
        updateGreenIndexChart(data.greenIndexTrend, range);
        updateDayOfWeek(data.dayOfWeek);
        updateTopRoutes(data.topRoutes);

    } catch(e) {
        console.error('loadAnalytics error:', e);
        clearKpis();
    }
}

/**
 * Captions only, from the range the operator picked. Split out of updateKpis so
 * they are right even when no figures come back — the caption describes the
 * question, and the question was asked whatever the answer was.
 */
function setKpiCaptions(range) {
    const label = (RANGE_LABELS[range] || '').toLowerCase();
    const sub = (id, text, cls) => {
        const el = document.getElementById(id);
        if (!el) return;
        el.textContent = text;
        el.className = cls || 'sub';
    };
    sub('kpiSearchesSub',   'queries in ' + label);
    sub('kpiSelectionsSub', 'confirmed trips · ' + label);
    sub('kpiCo2Sub',        'vs all-car alternative · ' + label, 'sub up');
}

/** Nothing to show. A dash, not the last range's numbers. */
function clearKpis() {
    ['kpiSearches', 'kpiSelections', 'kpiCo2'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.textContent = '—';
    });
    const el = document.getElementById('kpiSearchesSub');
    if (el) { el.textContent = 'could not be loaded'; el.className = 'sub warn'; }
}

function updateKpis(kpis, range) {
    if (!kpis) { clearKpis(); return; }
    setKpiCaptions(range);

    // KPI 1 — Searches
    const searches = Number(kpis.totalSearches);
    document.getElementById('kpiSearches').textContent = searches.toLocaleString('it-IT');
    // Zero over the whole window means the counter only started recently, which
    // is worth saying instead of an empty "queries in last 12 months".
    if (searches === 0) {
        const el = document.getElementById('kpiSearchesSub');
        el.textContent = 'tracking from today';
        el.className = 'sub';
    }

    // KPI 2 — Selections
    const selections = Number(kpis.totalSelections);
    document.getElementById('kpiSelections').textContent = selections.toLocaleString('it-IT');

    // KPI 3 — CO₂ Saved
    const co2 = Number(kpis.co2SavedKg);
    document.getElementById('kpiCo2').textContent =
        co2 >= 1000 ? `${(co2/1000).toFixed(1)} t` : `${co2} kg`;

    // Update Green Index badge in chart card
    if (kpis.avgGreenIndex != null)
        document.getElementById('giAvgBadge').textContent = kpis.avgGreenIndex;
}

function updateModeChart(dist) {
    if (!dist || Object.keys(dist).length === 0) return;

    const folded  = foldModeCounts(dist);
    const total   = Object.values(folded).reduce((a,b) => a + b, 0) || 1;
    const entries = Object.entries(folded).sort((a,b) => b[1]-a[1]);
    const labels  = entries.map(([k]) => modeName(k));
    const values  = entries.map(([,v]) => v);
    const colors  = entries.map(([k]) => modeColor(k));
    const pcts    = values.map(v => Math.round(v/total*100));

    if (modeChart) modeChart.destroy();
    modeChart = new Chart(document.getElementById('chartMode'), {
        type: 'doughnut',
        data: {
            labels,
            datasets: [{
                data: values,
                backgroundColor: colors,
                borderColor: '#131f2b',
                borderWidth: 3
            }]
        },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            cutout: '64%'
        }
    });

    document.getElementById('modeList').innerHTML = entries.map(([k], i) => `
    <div style="display:flex;align-items:center;gap:7px">
        <div style="width:8px;height:8px;border-radius:2px;background:${colors[i]};flex-shrink:0"></div>
        <span style="font-family:var(--font-mono);font-size:10px;color:var(--text-secondary);flex:1">${labels[i]}</span>
        <span style="font-family:var(--font-mono);font-size:11px;color:${colors[i]}">${pcts[i]}%</span>
    </div>
`).join('');
}

let hourChart = null;
function updateModeByHourChart(modeByHour) {
    if (!modeByHour) return;

    const labels = Array.from({length:24}, (_,i) => `${i}h`);
    const datasets = Object.entries(foldModeHours(modeByHour)).map(([mode, hours]) => ({
        label: modeName(mode),
        data: hours,
        backgroundColor: modeColor(mode),
        stack: 'modes'
    }));

    const canvas = document.getElementById('chartModeByHour');
    if (!canvas) return;
    if (hourChart) hourChart.destroy();

    hourChart = new Chart(canvas, {
        type: 'bar',
        data: { labels, datasets },
        options: {
            responsive: true, maintainAspectRatio: false,
            plugins: {
                legend: {
                    labels: {
                        color: '#7a90a8',
                        font: { family: 'Inter', size: 10 },
                        boxWidth: 10
                    }
                },
                tooltip: {
                    callbacks: {
                        title: ctx => `Hour ${ctx[0].label}`,
                        label: ctx => ` ${ctx.dataset.label}: ${ctx.parsed.y} trips`
                    }
                }
            },
            scales: {
                x: {
                    stacked: true,
                    ticks: { color:'#3d5268', font:{family:'Inter',size:9} },
                    grid: { color:'#1e2d3d' }
                },
                y: {
                    stacked: true,
                    ticks: { color:'#3d5268', font:{family:'Inter',size:9} },
                    grid: { color:'#1e2d3d' },
                    title: {
                        display: true, text: 'Trips',
                        color:'#3d5268', font:{family:'Inter',size:9}
                    }
                }
            }
        }
    });
}

function updateGreenIndexChart(trend, range) {
    if (!trend || trend.length === 0) return;

    const labels = trend.map(p => p.time?.substring(5));  // MM-DD
    const values = trend.map(p => p.value);

    // Moving average window: 7 points for short ranges, 5 for longer
    const maWindow = ['1W','1M'].includes(range) ? 7 : 5;
    const ma = values.map((_, i, a) => {
        if (i < maWindow - 1) return null;
        return (a.slice(i - maWindow + 1, i + 1).reduce((s,v) => s+v, 0) / maWindow).toFixed(1);
    });

    if (greenChart) greenChart.destroy();
    const ctx = document.getElementById('chartGreenIndex').getContext('2d');
    const grad = ctx.createLinearGradient(0, 0, 0, 158);
    grad.addColorStop(0, 'rgba(0,229,160,.35)');
    grad.addColorStop(1, 'rgba(0,229,160,0)');

    greenChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [
                { label:'Raw Avg', data:values, borderColor:'rgba(0,229,160,.2)',
                    backgroundColor:'transparent', pointRadius:0, tension:.3, borderWidth:1 },
                { label:`${maWindow}-pt MA`, data:ma, borderColor:'#00e5a0',
                    backgroundColor:grad, pointRadius:0, tension:.4, fill:true, borderWidth:2 }
            ]
        },
        options: {
            responsive:true, maintainAspectRatio:false,
            plugins:{legend:{labels:{color:'#7a90a8',font:{family:'Inter',size:9},boxWidth:8}}},
            scales:{
                x:{ticks:{color:'#3d5268',font:{family:'Inter',size:8},maxTicksLimit:7},grid:{color:'#1e2d3d'}},
                y:{ticks:{color:'#3d5268',font:{family:'Inter',size:9}},grid:{color:'#1e2d3d'},min:50,max:100}
            }
        }
    });
}

// Initial load
loadAnalytics('1M');


// ── Report download (Statistics tab) ─────────────────────────────────
// The three buttons were stubs that only raised a toast. They now fetch the
// real file for the range currently on screen, so what is downloaded is what
// the operator is looking at rather than a fixed period.
//
// Fetched rather than linked because the endpoint needs the session cookie and
// answers 401 to anyone without it: a plain <a href> would navigate the tab to
// an error page instead of reporting it here.
async function downloadReport(format, btn) {
    const label = btn ? btn.textContent : '';
    if (btn) { btn.disabled = true; btn.textContent = '⬇ …'; }
    try {
        const r = await apiFetch('/admin/analytics/export?range='
                                 + encodeURIComponent(currentRange || '1M')
                                 + '&format=' + encodeURIComponent(format));
        if (!r.ok) throw new Error('export ' + r.status);

        // Filename comes from the server, which knows the report's date
        const disp = r.headers.get('Content-Disposition') || '';
        const match = /filename="?([^"]+)"?/.exec(disp);
        const name  = match ? match[1] : 'omnimove-analytics.' + format;

        const blob = await r.blob();
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href = url;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        // Freed on the next tick: revoking immediately can cancel the download
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        toast(name + ' downloaded');
    } catch (e) {
        console.warn('Report download failed:', e);
        toast('Could not build the report');
    }
    if (btn) { btn.disabled = false; btn.textContent = label; }
}

// ── Assistant prompt frequency (Settings tab) ────────────────────────
async function loadAiNudge() {
    try {
        const r = await apiFetch('/admin/settings/ui');
        if (!r.ok) throw new Error('ui settings ' + r.status);
        const s = await r.json();
        document.getElementById('aiNudgeMinutes').value = s.aiNudgeMinutes;
        showAiNudgeState(s.aiNudgeMinutes);
    } catch (e) {
        console.warn('Could not load UI settings:', e);
    }
}

function showAiNudgeState(minutes) {
    const el = document.getElementById('aiNudgeState');
    if (!el) return;
    const n = Number(minutes);
    el.innerHTML = n > 0
        ? 'Travellers see the prompt every <strong>' + escHtml(String(n)) + '</strong> '
          + (n === 1 ? 'minute.' : 'minutes.')
        : '<span class="text-amber">Off</span> — the prompt is never shown.';
}

async function saveAiNudge() {
    const input = document.getElementById('aiNudgeMinutes');
    const btn   = document.getElementById('aiNudgeSave');
    const value = parseInt(input.value, 10);
    if (isNaN(value) || value < 0) { showAiNudgeState(0); input.value = 0; return; }

    btn.disabled = true;
    try {
        const r = await apiFetch('/admin/settings/ui', {
            method: 'PUT',
            body: JSON.stringify({ aiNudgeMinutes: value })
        });
        if (!r.ok) throw new Error('save ' + r.status);
        const s = await r.json();
        // The server clamps, so echo back what it actually stored rather than
        // what was typed — otherwise the box shows a value that is not in force.
        input.value = s.aiNudgeMinutes;
        showAiNudgeState(s.aiNudgeMinutes);
    } catch (e) {
        document.getElementById('aiNudgeState').innerHTML =
            '<span class="text-red">Could not save.</span>';
    }
    btn.disabled = false;
}

// ── Data retention (Settings tab) ────────────────────────────────────
// Read-only. The periods come from the published privacy notice, so they are
// changed by editing the notice and the configuration together — not from here.
async function loadRetention() {
    const state = document.getElementById('retentionState');
    const tbody = document.getElementById('retentionTbody');
    if (!state || !tbody) return;

    try {
        const r = await apiFetch('/admin/retention');
        if (!r.ok) throw new Error('retention ' + r.status);
        const data = await r.json();

        state.innerHTML = data.enabled
            ? '<div class="settings-note"><span class="text-green">Active</span> — '
              + 'the sweep runs nightly.</div>'
            : '<div class="settings-note"><span class="text-amber">Switched off</span> — '
              + 'nothing is being deleted, while the privacy notice tells users it is. '
              + 'Set RETENTION_ENABLED=true.</div>';

        tbody.innerHTML = (data.rules || []).map(function (x) {
            // Never run is its own state: a job that has not fired and a job that
            // found nothing both show zero, and only one of them is a problem.
            let outcome, when, removed;
            if (x.neverRun) {
                outcome = '<span class="text-amber">never run</span>';
                when    = '—';
                removed = '—';
            } else {
                when    = fmtDateTime(x.ran_at);
                removed = String(x.rows_removed);
                if (x.outcome === 'OK')           outcome = '<span class="text-green">ok</span>';
                else if (x.outcome === 'SKIPPED') outcome = '<span class="text-amber">skipped</span>';
                else                              outcome = '<span class="text-red">failed</span>';
                if (x.detail) outcome += '<br><span class="sub">' + escHtml(x.detail) + '</span>';
            }
            return '<tr>'
                 + '<td>' + escHtml(x.label)
                 + (x.note ? '<br><span class="sub">' + escHtml(x.note) + '</span>' : '')
                 + '</td>'
                 + '<td class="text-mono">' + escHtml(x.period) + '</td>'
                 + '<td class="text-mono" style="font-size:11px">' + escHtml(when) + '</td>'
                 + '<td class="text-mono">' + escHtml(removed) + '</td>'
                 + '<td>' + outcome + '</td>'
                 + '</tr>';
        }).join('');
    } catch (e) {
        console.warn('Could not load retention status:', e);
        state.innerHTML = '<div class="settings-note">Could not read the retention status.</div>';
        tbody.innerHTML = '';
    }
}

// ── Google API feature flags (Settings tab) ──────────────────────────
async function loadGoogleSettings() {
    try {
        const r = await apiFetch('/admin/settings/google');
        if (!r.ok) throw new Error('settings ' + r.status);
        const s = await r.json();
        applyGoogleSettings(s);
    } catch (e) {
        console.warn('Could not load Google settings:', e);
    }
    loadSecuritySettings();
}

async function loadSecuritySettings() {
    try {
        const r = await apiFetch('/admin/settings/security');
        if (!r.ok) throw new Error('security ' + r.status);
        applySecuritySettings(await r.json());
    } catch (e) {
        console.warn('Could not load security settings:', e);
    }
}

// One place that maps the payload onto the switches, so a new flag is a line
// here and a line of HTML — not another branch in the toggle handler.
const GOOGLE_SWITCHES = {
    'google.search':      'swSearch',
    'google.stop_eta':    'swStopEta',
    'google.route_shape': 'swRouteShape',
    'google.geocoding':   'swGeocoding'
};

function applyGoogleSettings(s) {
    Object.entries(GOOGLE_SWITCHES).forEach(([key, id]) => {
        if (key in s) setSwitch(id, s[key]);
    });
}

function applySecuritySettings(s) {
    setSwitch('swCaptcha', s['security.recaptcha']);

    // The switch and the reality can disagree: without a key pair the check
    // cannot run whatever the administrator has chosen, and saying so beats
    // showing a green switch over an unprotected login form.
    const state = document.getElementById('captchaState');
    if (!state) return;
    if (!s.recaptchaConfigured) {
        state.textContent = ' — no keys configured, the check is not running.';
        state.className = 'text-amber';
    } else if (s.recaptchaActive) {
        state.textContent = ' — active.';
        state.className = 'text-green';
    } else {
        state.textContent = ' — off.';
        state.className = '';
    }
}

function setSwitch(id, on) {
    const el = document.getElementById(id);
    if (el) el.setAttribute('aria-checked', on ? 'true' : 'false');
}

// One handler for every switch: the endpoint travels on the button, so a new
// setting is a line of HTML rather than another branch here.
async function toggleSetting(btn) {
    const key      = btn.dataset.key;
    const endpoint = btn.dataset.endpoint || '/admin/settings/google';
    const next     = btn.getAttribute('aria-checked') !== 'true';

    btn.disabled = true;
    try {
        const r = await apiFetch(endpoint, {
            method: 'PUT',
            body: JSON.stringify({ [key]: next })
        });
        if (!r.ok) throw new Error('save ' + r.status);
        const s = await r.json();

        // Repaint from the server's answer, never from the click: the stored
        // value is the truth, and for the captcha it can differ from what was
        // asked when no keys are configured.
        applyGoogleSettings(s);
        if ('security.recaptcha' in s) applySecuritySettings(s);

        if (key === 'security.recaptcha')
            toast(next ? 'Login reCAPTCHA enabled.' : 'Login reCAPTCHA disabled.');

    } catch (e) {
        console.warn('Could not toggle setting:', e);
        toast('Could not save the setting.', true);
    } finally {
        btn.disabled = false;
    }
}

document.addEventListener('click', e => {
    const sw = e.target.closest('.switch');
    if (sw) toggleSetting(sw);
});
