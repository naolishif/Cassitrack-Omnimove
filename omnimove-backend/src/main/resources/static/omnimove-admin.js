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
        if (t.dataset.tab === 'settings') loadGoogleSettings();
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
                  title="Open profile">${escHtml(u.name)}</button></td>
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

const MODE_LABEL = { BUS:'🚌 BUS', WALK:'🚶 WALK', BIKE:'🚲 BIKE', SCOOTER:'🛴 SCOOTER' };

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
    modal.classList.add('open');

    try {
        const r = await apiFetch(`/admin/users/${id}/profile`);
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

function renderProfile(data) {
    const a = data.account;
    const s = data.stats;

    document.getElementById('profileTitle').textContent = a.name;

    document.getElementById('profileId').innerHTML =
        `<span>ID <strong>#${escHtml(a.id)}</strong></span>` +
        `<span>${escHtml(a.email)}</span>` +
        `<span><span class="badge ${roles[a.role] || 'badge-user'}">${escHtml(a.role)}</span></span>` +
        `<span>${a.verified ? '<span class="text-green">verified</span>'
                            : '<span class="text-amber">not verified</span>'}</span>` +
        `<span>Registered <strong>${escHtml(fmtDateTime(a.registeredAt))}</strong></span>` +
        `<span>Last login <strong>${a.lastLoginAt ? escHtml(fmtDateTime(a.lastLoginAt)) : 'never'}</strong></span>` +
        `<span>Accesses <strong>${escHtml(a.loginCount)}</strong></span>`;

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

    document.getElementById('profileTripsTitle').textContent =
        data.truncated ? `Journeys — latest ${data.history.length} of ${data.totalTrips}`
                       : `Journeys (${data.totalTrips})`;

    const trips = document.getElementById('profileTrips');
    if (!data.history.length) {
        trips.innerHTML = `<div class="history-empty">This user has not travelled yet.</div>`;
    } else {
        trips.innerHTML = `
        <table class="trip-table">
          <thead>
            <tr><th>Date</th><th>Mode</th><th>Route</th><th>Distance</th><th>Cost</th><th>Green</th></tr>
          </thead>
          <tbody>
            ${data.history.map(j => `
            <tr>
              <td class="num">${escHtml(fmtDateTime(j.createdAt))}</td>
              <td><span class="mode-tag">${escHtml(MODE_LABEL[j.mode] || j.mode)}</span></td>
              <td class="route">${escHtml(j.originName || '—')} → ${escHtml(j.destName || '—')}
                  ${j.isFavorite ? '<span class="text-amber" title="Starred by the user">★</span>' : ''}</td>
              <td class="num">${j.distanceKm != null ? Number(j.distanceKm).toFixed(1) + ' km' : '—'}</td>
              <td class="num">${j.costEuros != null ? '€' + Number(j.costEuros).toFixed(2) : '—'}</td>
              <td class="num" style="color:${greenColor(j.greenIndex)}">${escHtml(j.greenIndex ?? '—')}</td>
            </tr>`).join('')}
          </tbody>
        </table>`;
    }

    renderFavRoutes(data.favoriteRoutes || []);
    renderFavStops(data.favoriteStops || []);

    // "Marco De Luca" → first "Marco", last "De Luca" — mirrors how the add-user
    // form joins the two halves back together.
    const space = (a.name || '').indexOf(' ');
    document.getElementById('editFirst').value = space < 0 ? (a.name || '') : a.name.slice(0, space);
    document.getElementById('editLast').value  = space < 0 ? '' : a.name.slice(space + 1);
    document.getElementById('editEmail').value = a.email;
}

function renderFavRoutes(items) {
    document.getElementById('profileFavRoutesTitle').textContent =
        `Favourite routes (${items.length})`;

    const box = document.getElementById('profileFavRoutes');
    if (!items.length) {
        box.innerHTML = `<div class="history-empty">No starred route.</div>`;
        return;
    }
    box.innerHTML = `<div class="fav-list">${items.map(f => `
      <div class="fav-row">
        <span class="fav-star">★</span>
        <div class="fav-main">
          ${escHtml(f.originName)} → ${escHtml(f.destName)}
          <div class="fav-sub">${escHtml(MODE_LABEL[f.mode] || f.mode)} · ~€${Number(f.avgCost).toFixed(2)} · used ${escHtml(f.usedCount)}×</div>
        </div>
        <div class="fav-green" style="color:${greenColor(f.greenIndex)}">🌱 ${escHtml(f.greenIndex)}</div>
      </div>`).join('')}</div>`;
}

function renderFavStops(items) {
    document.getElementById('profileFavStopsTitle').textContent =
        `Favourite stops (${items.length})`;

    const box = document.getElementById('profileFavStops');
    if (!items.length) {
        box.innerHTML = `<div class="history-empty">No starred stop.</div>`;
        return;
    }
    box.innerHTML = `<div class="fav-list">${items.map(st => `
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

function closeProfileModal() {
    document.getElementById('profileModal').classList.remove('open');
    _profileUserId = null;
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

function applyFilter() {
    filterTable(document.getElementById('searchInput').value);
}

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

function filterTable(q) {
    const role = document.getElementById('roleFilter').value;
    const f = users.filter(u => {
        const matchText = !q ||
            u.name.toLowerCase().includes(q.toLowerCase()) ||
            u.email.toLowerCase().includes(q.toLowerCase()) ||
            String(u.id).includes(q);
        const matchRole = !role || u.role === role;
        return matchText && matchRole;
    });
    renderTable(f);
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
    try {
        const r = await apiFetch(`/admin/analytics?range=${range}`);
        if (!r.ok) { console.warn('Analytics endpoint error', r.status); return; }
        const data = await r.json();

        updateKpis(data.kpis, range);
        updateModeChart(data.modeDistribution);
        updateModeByHourChart(data.modeByHour);
        updateGreenIndexChart(data.greenIndexTrend, range);
        updateDayOfWeek(data.dayOfWeek);
        updateTopRoutes(data.topRoutes);

    } catch(e) {
        console.error('loadAnalytics error:', e);
    }
}

function updateKpis(kpis, range) {
    if (!kpis) return;
    const label = RANGE_LABELS[range] || '';

    // KPI 1 — Searches
    const searches = Number(kpis.totalSearches);
    document.getElementById('kpiSearches').textContent = searches.toLocaleString('it-IT');
    document.getElementById('kpiSearchesSub').textContent =
        searches === 0 ? 'tracking from today' : `queries in ${label.toLowerCase()}`;
    document.getElementById('kpiSearchesSub').className = 'sub';

    // KPI 2 — Selections
    const selections = Number(kpis.totalSelections);
    document.getElementById('kpiSelections').textContent = selections.toLocaleString('it-IT');
    document.getElementById('kpiSelectionsSub').textContent = `confirmed trips · ${label.toLowerCase()}`;
    document.getElementById('kpiSelectionsSub').className = 'sub';

    // KPI 3 — CO₂ Saved
    const co2 = Number(kpis.co2SavedKg);
    document.getElementById('kpiCo2').textContent =
        co2 >= 1000 ? `${(co2/1000).toFixed(1)} t` : `${co2} kg`;
    document.getElementById('kpiCo2Sub').textContent = `vs all-car alternative · ${label.toLowerCase()}`;
    document.getElementById('kpiCo2Sub').className = 'sub up';

    // Update Green Index badge in chart card
    if (kpis.avgGreenIndex != null)
        document.getElementById('giAvgBadge').textContent = kpis.avgGreenIndex;
}

function updateModeChart(dist) {
    if (!dist || Object.keys(dist).length === 0) return;

    const colorMap = {
        BUS:'#00cfff', BIKE:'#00e5a0',
        SCOOTER:'#3a8eff', WALK:'#7a90a8', TRAIN:'#a855f7'
    };
    const labelMap = {
        BUS:'Bus', BIKE:'Shared Bike',
        SCOOTER:'E-Scooter', WALK:'Walking', TRAIN:'Train'
    };

    const total = Object.values(dist).reduce((a,b) => a + Number(b), 0) || 1;
    const entries = Object.entries(dist).sort((a,b) => b[1]-a[1]);
    const labels  = entries.map(([k]) => labelMap[k] || k);
    const values  = entries.map(([,v]) => Number(v));
    const colors  = entries.map(([k]) => colorMap[k] || '#7a90a8');
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

    const colorMap = {
        BUS:'#00cfff', BIKE:'#00e5a0', SCOOTER:'#3a8eff', WALK:'#7a90a8'
    };
    const labelMap = {
        BUS:'Bus', BIKE:'Shared Bike', SCOOTER:'E-Scooter', WALK:'Walking'
    };

    const labels = Array.from({length:24}, (_,i) => `${i}h`);
    const datasets = Object.entries(modeByHour).map(([mode, hours]) => ({
        label: labelMap[mode] || mode,
        data: Array.from(hours),
        backgroundColor: colorMap[mode] || '#7a90a8',
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


// ── Google API feature flags (Settings tab) ──────────────────────────
async function loadGoogleSettings() {
    try {
        const r = await apiFetch('/admin/settings/google');
        if (!r.ok) throw new Error('settings ' + r.status);
        const s = await r.json();
        setSwitch('swSearch',  s['google.search']);
        setSwitch('swStopEta', s['google.stop_eta']);
    } catch (e) {
        console.warn('Could not load Google settings:', e);
    }
}

function setSwitch(id, on) {
    const el = document.getElementById(id);
    if (el) el.setAttribute('aria-checked', on ? 'true' : 'false');
}

async function toggleGoogleSetting(btn) {
    const key = btn.dataset.key;
    const next = btn.getAttribute('aria-checked') !== 'true';
    btn.disabled = true;
    try {
        const r = await apiFetch('/admin/settings/google', {
            method: 'PUT',
            body: JSON.stringify({ [key]: next })
        });
        if (!r.ok) throw new Error('save ' + r.status);
        const s = await r.json();
        setSwitch('swSearch',  s['google.search']);
        setSwitch('swStopEta', s['google.stop_eta']);
    } catch (e) {
        console.warn('Could not toggle setting:', e);
    } finally {
        btn.disabled = false;
    }
}

document.addEventListener('click', e => {
    const sw = e.target.closest('.switch');
    if (sw) toggleGoogleSetting(sw);
});
