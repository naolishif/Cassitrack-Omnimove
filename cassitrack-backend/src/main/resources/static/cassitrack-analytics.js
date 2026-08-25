// Auth is enforced server-side (SecurityConfig gates this page to FLEET_MANAGER/ADMIN).
// No client-side localStorage guard needed — the httpOnly cookie is sent automatically.

const API = '/cassitrack/api/v1';

// A 401 from any call sends us back to login; Back out of a dead session
// re-checks with the server instead of showing a stale console
CassiSession.installFetchGuard();
CassiSession.bindSessionGuard();

const STATUS_COL = {
    ON_TIME: '#22C55E',
    SLIGHTLY_LATE: '#F59E0B',
    SIGNIFICANTLY_LATE: '#EF4444',
    EARLY: '#06B6D4',
    UNKNOWN: '#4B5563'
};

const STATUS_LBL = {
    ON_TIME: 'ON TIME',
    SLIGHTLY_LATE: 'SLIGHTLY LATE',
    SIGNIFICANTLY_LATE: 'LATE',
    EARLY: 'EARLY',
    UNKNOWN: 'UNKNOWN'
};

// XSS defense: escape any API-supplied string before inserting into innerHTML
function escHtml(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// ── LOGOUT ─────────────────────────────────────────────────────────────────

async function logoutUser() {
    // Token blacklisted server-side, cookie expired, storage wiped,
    // page dropped from the history stack
    await CassiSession.endSession();
}

// ── SUMMARY STATS CARD ─────────────────────────────────────────────────────

async function loadSummary() {
    const r = await fetch(`${API}/analytics/summary`);
    const d = await r.json();

    document.getElementById('statsGrid').innerHTML = `
        <div class="stat-card">
            <div class="stat-card-val" style="color:var(--accent)">${escHtml(d.active_buses_now)}</div>
            <div class="stat-card-lbl">Active Buses</div>
            <div class="stat-card-sub" style="color:var(--dim)">Right now on Linea 16</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-val" style="color:var(--green)">${escHtml(d.on_time_percentage)}%</div>
            <div class="stat-card-lbl">On Time Rate</div>
            <div class="stat-card-sub" style="color:var(--dim)">${escHtml(d.on_time_count)} on time · ${escHtml(d.late_count)} late</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-val" style="color:var(--cyan)">${escHtml(d.buses_today)}</div>
            <div class="stat-card-lbl">Buses Today</div>
            <div class="stat-card-sub" style="color:var(--dim)">Distinct vehicles seen</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-val" style="color:var(--purple)">${escHtml(d.position_reports_today)}</div>
            <div class="stat-card-lbl">GPS Reports</div>
            <div class="stat-card-sub" style="color:var(--dim)">Received today</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-val" style="color:var(--amber)">${escHtml(d.late_count)}</div>
            <div class="stat-card-lbl">Delayed Buses</div>
            <div class="stat-card-sub" style="color:${d.late_count > 0 ? 'var(--amber)' : 'var(--green)'}">${d.late_count > 0 ? 'Attention needed' : 'All running well'}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card-val" style="color:var(--cyan)">${escHtml(d.early_count)}</div>
            <div class="stat-card-lbl">Running Early</div>
            <div class="stat-card-sub" style="color:var(--dim)">Ahead of schedule</div>
        </div>`;

    document.getElementById('lastUpdate').textContent =
        `Linea 16 · Updated: ${new Date().toLocaleTimeString('it-IT')}`;
    document.getElementById('statusDot').className = 'dot dot-green';
    document.getElementById('statusText').textContent = `${d.active_buses_now} buses live`;
}

// ── ADHERENCE CHART + VEHICLE TABLE ────────────────────────────────────────

async function loadAdherence() {
    const r = await fetch(`${API}/analytics/adherence`);
    const d = await r.json();
    const total = d.total_active || 1;
    const counts = d.status_counts || {};

    const statuses = [
        { key: 'ON_TIME',               label: 'On Time',           col: '#22C55E' },
        { key: 'SLIGHTLY_LATE',         label: 'Slightly Late',     col: '#F59E0B' },
        { key: 'SIGNIFICANTLY_LATE',    label: 'Significantly Late',col: '#EF4444' },
        { key: 'EARLY',                 label: 'Early',             col: '#06B6D4' },
        { key: 'UNKNOWN',               label: 'Unknown',           col: '#4B5563' },
    ];

    document.getElementById('adherenceChart').innerHTML = statuses.map(s => {
        const count = counts[s.key] || 0;
        const pct = total > 0 ? Math.round(count / total * 100) : 0;
        return `
            <div>
                <div class="status-row">
                    <div class="status-dot" style="background:${s.col}"></div>
                    <div class="status-name">${escHtml(s.label)}</div>
                    <div class="status-count" style="color:${s.col}">${count}</div>
                </div>
                <div class="status-bar-wrap">
                    <div class="status-bar" style="width:${pct}%;background:${s.col}"></div>
                </div>
            </div>`;
    }).join('');

    // Vehicle table
    const tbody = document.getElementById('vehicleTable');
    if (!d.vehicles || d.vehicles.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" class="empty">No active buses. Start the GPS simulator.</td></tr>`;
        return;
    }
    tbody.innerHTML = d.vehicles.map(v => {
        const col = STATUS_COL[v.status] || '#4B5563';
        const lbl = STATUS_LBL[v.status] || escHtml(v.status);
        const delay = v.delay_minutes != null
            ? (v.delay_minutes > 0 ? `+${v.delay_minutes} min` : `${v.delay_minutes} min`)
            : '—';
        return `<tr>
            <td style="font-weight:700;color:${col}">${escHtml(v.vehicle_id)}</td>
            <td><span class="badge" style="background:${col}22;color:${col};border:1px solid ${col}44">${lbl}</span></td>
            <td>${v.speed_kmh != null ? v.speed_kmh.toFixed(1) + ' km/h' : '—'}</td>
            <td style="color:${v.delay_minutes > 0 ? 'var(--amber)' : 'var(--green)'}">${delay}</td>
            <td>${escHtml(v.crowding) || '—'}</td>
        </tr>`;
    }).join('');
}

// ── HOURLY ACTIVITY CHART ──────────────────────────────────────────────────

async function loadBusiestHours() {
    const r = await fetch(`${API}/analytics/busiest-hours`);
    const d = await r.json();
    const data = d.hourly_activity || [];
    const max = Math.max(...data.map(h => h.count), 1);

    // Show every 2 hours to avoid crowding
    const filtered = data.filter((_, i) => i % 2 === 0);

    document.getElementById('hoursChart').innerHTML =
        filtered.map(h => {
            const pct = Math.round(h.count / max * 100);
            const col = pct > 70 ? '#EF4444' : pct > 40 ? '#F59E0B' : '#3B82F6';
            return `
                <div class="bar-row">
                    <div class="bar-label">${escHtml(h.hour)}</div>
                    <div class="bar-track">
                        <div class="bar-fill" style="width:${Math.max(pct, 2)}%;background:${col}">
                            ${h.count > 0 ? `<span>${h.count}</span>` : ''}
                        </div>
                    </div>
                    <div class="bar-val" style="color:${col}">${h.count}</div>
                </div>`;
        }).join('') +
        `<div class="bar-peak">Peak hour: ${escHtml(d.peak_hour || 'N/A')}</div>`;
}

// ── LOAD ALL ───────────────────────────────────────────────────────────────

async function loadAll() {
    try {
        await Promise.all([loadSummary(), loadAdherence(), loadBusiestHours()]);
    } catch (e) {
        document.getElementById('statusDot').className = 'dot dot-red';
        document.getElementById('statusText').textContent = 'Backend offline';
        console.error(e);
    }
}

// ── EVENT LISTENERS & INIT ─────────────────────────────────────────────────

document.getElementById('refreshBtn').addEventListener('click', loadAll);
document.getElementById('logoutBtn').addEventListener('click', logoutUser);

// Load on start and auto-refresh every 30 seconds
loadAll();
setInterval(loadAll, 30000);
