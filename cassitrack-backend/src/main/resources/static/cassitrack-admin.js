// A 401 from any call sends us back to login; Back out of a dead session
// re-checks with the server instead of showing a stale console
CassiSession.installFetchGuard();
CassiSession.bindSessionGuard();

function escHtml(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

let selectedRow = null;
let selectedUser = null;

/** The accounts as loaded. The export reads these, filtered to what is on screen. */
let allUsers = [];

function selectRow(row, user){

    document
        .querySelectorAll('#usersTable tr')
        .forEach(r => r.classList.remove('selected'));

    row.classList.add('selected');

    selectedRow = row;
    selectedUser = user;

    document.getElementById('editBtn').disabled = false;
    document.getElementById('deleteBtn').disabled = false;
    document.getElementById('activityBtn').disabled = false;
}

// SEARCH FILTER
//
// A function rather than only a listener: the table is rebuilt every 30 s and
// the rebuild brings every row back visible, so the filter has to be laid over
// it again. Left as an inline handler, a refresh would silently undo a search
// the operator was in the middle of reading.

function applyUserSearch(){

    const searchTerms = document
        .getElementById('searchInput')
        .value
        .toLowerCase()
        .trim()
        .split(/\s+/);

    const rows = document.querySelectorAll('#usersTable tr');

    rows.forEach(row => {

        const rowText = row.innerText.toLowerCase();

        const matches = searchTerms.every(term =>
            rowText.includes(term)
        );

        row.style.display = matches
            ? ''
            : 'none';
    });
}

document
    .getElementById('searchInput')
    .addEventListener('input', applyUserSearch);

// ─────────────────────────────
// LOGOUT
// ─────────────────────────────

async function logoutUser(){
    // Token blacklisted server-side, cookie expired, storage wiped, page dropped
    // from the history stack. (Earlier this was gated behind `if (token)` reading a
    // localStorage key nothing writes since the httpOnly-cookie migration, so the logout
    // call never fired at all — hence the shared module, one implementation for all three
    // consoles.)
    await CassiSession.endSession();
}

// ─────────────────────────────
// MODAL
// ─────────────────────────────

function openAddModal(){

    selectedUser = null;

    document.getElementById('modalTitle').innerText = 'Add User';

    clearModal();
    clearMessage();


    document.getElementById('userModal').style.display = 'flex';
}

function openEditModal(){

    if(!selectedRow) return;

    clearMessage();

    document.getElementById('modalTitle').innerText = 'Edit User';

    document.getElementById('modalTaxId').value = selectedUser.taxId;
    document.getElementById('modalName').value = selectedUser.name;
    document.getElementById('modalSurname').value = selectedUser.surname;
    document.getElementById('modalEmail').value = selectedUser.email;
    document.getElementById('modalTelephone').value = selectedUser.telephone;
    document.getElementById('modalRole').value = selectedUser.role;
    document.getElementById('modalPassword').value = '';
    document.getElementById('userModal').style.display = 'flex';
}

function closeModal(){

    clearMessage();
    document.getElementById('userModal').style.display = 'none';
}

function clearModal(){

    document.getElementById('modalTaxId').value = '';
    document.getElementById('modalName').value = '';
    document.getElementById('modalSurname').value = '';
    document.getElementById('modalEmail').value = '';
    document.getElementById('modalTelephone').value = '';
    document.getElementById('modalPassword').value = '';
    document.getElementById('modalRole').value = 'DRIVER';
}

function showError(message){

    const box = document.getElementById('formMessage');

    box.className = 'form-message error';

    box.innerText = message;
}

function showSuccess(message){

    const box = document.getElementById('formMessage');

    box.className = 'form-message success';

    box.innerText = message;
}

function clearMessage(){

    const box = document.getElementById('formMessage');

    box.className = 'form-message';

    box.innerText = '';
}

// ─────────────────────────────────────────────────────────────────
// PASSWORD VALIDATION FUNCTION
// ─────────────────────────────────────────────────────────────────
function isPasswordSecure(password) {
    // Validation Criteria:
    // - Min 8 characters: (?=.{8,})
    // - At least one uppercase letter: (?=.*[A-Z])
    // - At least one lowercase letter: (?=.*[a-z])
    // - At least one number: (?=.*[0-9])
    // - At least one special character/symbol: (?=.*[!@#$%^&*(),.?":{}|<>_+\-*\/])
    const regex = /^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[!@#$%^&*(),.?":{}|<>_+\-*\/])(?=.{8,})/;
    return regex.test(password);
}

async function saveUser(){

    const taxId = document.getElementById('modalTaxId').value.trim();
    const name = document.getElementById('modalName').value.trim();
    const surname = document.getElementById('modalSurname').value.trim();
    const email = document.getElementById('modalEmail').value.trim();
    const telephone = document.getElementById('modalTelephone').value.trim();
    const password = document.getElementById('modalPassword').value.trim();
    const role = document.getElementById('modalRole').value;

    // ─────────────────────────────────────────────────────────────────
    // GENERAL VALIDATION
    // ─────────────────────────────────────────────────────────────────

    if(
        !taxId ||
        !name ||
        !surname ||
        !email ||
        !role ||
        !telephone
    ){
        showError('Please fill all fields');
        return;
    }

    // ─────────────────────────────────────────────────────────────────
    // PASSWORD VALIDATION RULES
    // ─────────────────────────────────────────────────────────────────

    if (!selectedUser) {
        // CASE 1: Creating a new user -> Password is strictly required AND must be secure
        if (!password) {
            showError('Password is required');
            return;
        }
        if (!isPasswordSecure(password)) {
            showError('Password must be at least 8 characters long, including an uppercase letter, a lowercase letter, a number, and a special character.');
            return;
        }
    } else {
        // CASE 2: Editing an existing user -> Password change is optional, but IF typed, it MUST be secure
        if (password && !isPasswordSecure(password)) {
            showError('New password must be at least 8 characters long, including an uppercase letter, a lowercase letter, a number, and a special character.');
            return;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // USER OBJECT
    // ─────────────────────────────────────────────────────────────────

    const userData = {
        taxId,
        name,
        surname,
        email,
        telephone,
        role
    };

    // ONLY SEND PASSWORD IF WRITTEN
    if(password){
        userData.passwordHash = password;
    }

    try{

        // BUGFIX (auth): dead localStorage-token Authorization header removed
        // — auth is via the httpOnly cookie, sent automatically on
        // same-origin fetches. Content-Type is still needed since this is a
        // JSON POST/PUT.

        // ─────────────────────────────────────────────────────────────────
        // UPDATE EXISTING USER
        // ─────────────────────────────────────────────────────────────────

        if(selectedUser){

            const response = await fetch(`/cassitrack/api/v1/users/${selectedUser.id}`, {
                method:'PUT',
                headers:{
                    'Content-Type':'application/json'
                },
                body:JSON.stringify(userData)
            });

            if(!response.ok){
                const errorMessage = await response.text();
                showError(errorMessage);
                return;
            }

        }else{

            // ─────────────────────────────────────────────────────────────────
            // CREATE NEW USER
            // ─────────────────────────────────────────────────────────────────

            const response = await fetch('/cassitrack/api/v1/users', {
                method:'POST',
                headers:{
                    'Content-Type':'application/json'
                },
                body:JSON.stringify(userData)
            });

            if(!response.ok){
                const errorMessage = await response.text();
                showError(errorMessage);
                return;
            }
        }


        showSuccess(
            selectedUser
                ? 'User updated successfully'
                : 'User created successfully'
        );

        setTimeout(() => {
            // RELOAD USERS FROM BACKEND
            loadUsers();

            selectedUser = null;
            selectedRow = null;

            document.getElementById('editBtn').disabled = true;
            document.getElementById('deleteBtn').disabled = true;

            // CLOSE MODAL WINDOW
            closeModal();
        }, 1000);

    }catch(error){
        console.error(error);
        showError('Error saving user');
    }
}

function deleteUser(){

    if(!selectedUser) return;

    document.getElementById('deleteModal').style.display = 'flex';
}

function closeDeleteModal(){

    document.getElementById('deleteModal').style.display = 'none';
}

async function confirmDeleteUser(){

    if(!selectedUser) return;

    try{

        // BUGFIX (auth): dead localStorage-token header removed — auth is
        // via the httpOnly cookie, sent automatically on same-origin fetches.
        const response = await fetch(`/cassitrack/api/v1/users/${selectedUser.id}`, {

            method:'DELETE'
        });

        if(!response.ok){

            showError('Error deleting user');

            return;
        }

        closeDeleteModal();

        selectedUser = null;
        selectedRow = null;

        document.getElementById('editBtn').disabled = true;
        document.getElementById('deleteBtn').disabled = true;

        loadUsers();

    }catch(error){

        console.error(error);

        showError('Error deleting user');
    }
}

// ─────────────────────────────
// AUTO-REFRESH — the table keeps itself current
// ─────────────────────────────
// Last access and the two counters move while the panel is open: somebody signs
// in, somebody downloads a file. A table that only changes when you press F5
// invites the operator to trust a figure that went stale twenty minutes ago.
//
// WHAT A NAIVE REFRESH WOULD BREAK
// loadUsers() rebuilds every row from scratch, which throws away two things the
// operator is in the middle of using: the search, because new rows come back
// visible, and the selection, because the highlighted <tr> no longer exists and
// selectedUser is left pointing at a copy of an account rather than the one on
// screen. Both are put back below.
//
// AND WHEN IT MUST NOT RUN AT ALL
// Not with a modal open — Edit is a form being filled from selectedUser, and
// pulling the ground out from under it is how a save writes the wrong row — and
// not while the tab is in the background, where nobody is reading anything.

const USERS_REFRESH_MS = 30000;
let usersRefreshTimer = null;

function anyModalOpen() {
    return ['userModal', 'deleteModal', 'activityModal'].some(id => {
        const el = document.getElementById(id);
        return el && getComputedStyle(el).display !== 'none';
    });
}

async function refreshUsers() {
    if (document.hidden || anyModalOpen()) return;

    // Remembered before the rebuild, restored after it
    const keepId = selectedUser ? String(selectedUser.id) : null;

    await loadUsers();
    applyUserSearch();
    restoreSelection(keepId);
}

/**
 * Puts the highlight back on the row the operator had chosen.
 *
 * <p>An account that has disappeared since — deleted from another session —
 * clears the selection instead, because Edit and Delete pointed at something
 * that is no longer there and the buttons should stop offering it.
 */
function restoreSelection(keepId) {
    if (!keepId) return;

    const row = document.querySelector(`#usersTable tr[data-user-id="${CSS.escape(keepId)}"]`);
    const user = allUsers.find(u => String(u.id) === keepId);

    if (row && user) {
        selectRow(row, user);
        return;
    }

    selectedRow = null;
    selectedUser = null;
    ['editBtn', 'deleteBtn', 'activityBtn']
        .forEach(id => { const b = document.getElementById(id); if (b) b.disabled = true; });
}

function startUsersRefresh() {
    if (usersRefreshTimer) clearInterval(usersRefreshTimer);
    usersRefreshTimer = setInterval(refreshUsers, USERS_REFRESH_MS);
}

// Coming back to the tab is the moment the figures are most likely to be out of
// date, and it costs one request rather than the several that were skipped.
document.addEventListener('visibilitychange', () => {
    if (!document.hidden && usersRefreshTimer) refreshUsers();
});

// ─────────────────────────────
// EXPORT — the accounts, as a workbook
// ─────────────────────────────
// Rendered by the server (ReportExportService), same as every table in the
// fleet manager: numbers stay numbers so a column of accesses can be summed,
// the header row is frozen and carries an autofilter, and the columns are
// sized from their own content.
//
// WHAT LEAVES, AND WHAT DOES NOT
// Exactly what is on the screen, filters included — and that means MASKED. The
// tax id and the telephone are already reduced by UserDTO before they ever
// reach the browser, so the file carries "****R80A" because that is what the
// operator is looking at. An export that quietly un-masked them would be a
// different act from the one the button appears to offer.
//
// The download records itself: /reports/export writes a row to manager_exports,
// so taking the account list shows up in the taker's own Activity card. That is
// the point of routing it through the same endpoint as everything else.

/** The rows the search has left visible, in the order they are drawn. */
function visibleUsers() {
    const byId = new Map(allUsers.map(u => [String(u.id), u]));
    return [...document.querySelectorAll('#usersTable tr')]
        .filter(row => row.style.display !== 'none')
        .map(row => byId.get(row.dataset.userId))
        .filter(Boolean);
}

async function exportUsersXlsx() {
    const btn  = document.getElementById('exportUsersBtn');
    const rows = visibleUsers();

    if (!rows.length) {
        showError('Nothing to export — no account is shown.');
        return;
    }

    const columns = [
        ['ID',          u => u.id],
        ['Tax ID',      u => u.taxId],
        ['Name',        u => u.name],
        ['Surname',     u => u.surname],
        ['E-mail',      u => u.email],
        ['Telephone',   u => u.telephone],
        ['Role',        u => u.role],
        ['Registered',  u => plainWhen(u.createdAt)],
        ['Last access', u => plainWhen(u.lastLoginAt)],
        ['Accesses',    u => u.logins],
        ['Files',       u => u.downloads]
    ];

    const search = document.getElementById('searchInput').value.trim();

    const label = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Preparing…';

    try {
        const res = await fetch('/cassitrack/api/v1/reports/export?format=xlsx', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                title: 'Users',
                subtitle: search ? `Search: ${search}` : '',
                sections: [{
                    title: '',
                    headers: columns.map(c => c[0]),
                    rows: rows.map(u => columns.map(c => {
                        const v = c[1](u);
                        return (v === null || v === undefined) ? '' : String(v);
                    }))
                }]
            })
        });
        if (!res.ok) throw new Error(res.status);

        const blob = await res.blob();
        const name = (/filename="?([^"]+)"?/.exec(res.headers.get('Content-Disposition')) || [])[1]
                   || 'users.xlsx';

        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

    } catch (error) {
        console.error('Error exporting users:', error);
        showError('Could not build the export.');
    } finally {
        btn.disabled = false;
        btn.textContent = label;
    }
}

/**
 * A timestamp for a spreadsheet cell.
 *
 * fmtWhen writes HTML for the table — "never" wrapped in a span — which would
 * land in the file as markup. This is the same instant, as text.
 */
function plainWhen(iso) {
    if (!iso) return 'never';
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const p = n => String(n).padStart(2, '0');
    return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// ─────────────────────────────
// ACTIVITY — accesses and downloaded files
// ─────────────────────────────
// Two questions the panel could not answer before: when this account was last
// in the system, and what has left it. Both come from tables the application
// may read (V28); the forensic copy of the same facts stays in
// security_audit_events, which the app can only write to.
//
// Of a downloaded file nothing is kept but its shape — the table, the format,
// the filters and the row count. Enough to see that somebody took the whole bus
// register at two in the morning, without the register becoming a second copy
// of the data it describes.

/** "01/09/2026 11:47", or a dash. An account that has never signed in says so. */
function fmtWhen(iso) {
    if (!iso) return '<span class="muted">never</span>';
    const d = new Date(iso);
    if (isNaN(d)) return '<span class="muted">—</span>';
    const p = n => String(n).padStart(2, '0');
    return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * A User-Agent as something a person can read.
 *
 * The raw string stays in the database — it is what an investigation would want
 * — but 180 characters of version numbers in a list tell an operator nothing.
 * Browser and platform is the part that carries meaning: "signed in from a
 * phone" is a fact, the rest is a haystack.
 */
function fmtAgent(ua) {
    if (!ua) return 'unknown device';
    const browser = /Edg\//.test(ua)     ? 'Edge'
                  : /Chrome\//.test(ua)  ? 'Chrome'
                  : /Firefox\//.test(ua) ? 'Firefox'
                  : /Safari\//.test(ua)  ? 'Safari'
                  : 'browser';
    const os = /Android/.test(ua)            ? 'Android'
             : /iPhone|iPad|iOS/.test(ua)    ? 'iOS'
             : /Windows/.test(ua)            ? 'Windows'
             : /Mac OS X|Macintosh/.test(ua) ? 'macOS'
             : /Linux/.test(ua)              ? 'Linux'
             : 'unknown system';
    return `${browser} on ${os}`;
}

async function openActivityModal() {
    if (!selectedUser) return;

    document.getElementById('activityTitle').textContent =
        `${selectedUser.name || ''} ${selectedUser.surname || ''}`.trim() || selectedUser.email;
    document.getElementById('activityMeta').innerHTML =
        `<div>${escHtml(selectedUser.email || '')}</div>
         <div class="muted">${escHtml(selectedUser.role || '')} · registered ${fmtWhen(selectedUser.createdAt)}</div>`;
    document.getElementById('activityDownloads').innerHTML = '<div class="activity-empty">Loading…</div>';
    document.getElementById('activityLogins').innerHTML = '';
    document.getElementById('activityDlCount').textContent = '';
    document.getElementById('activityLoginCount').textContent = '';
    // Same as the other two modals on this page: display, not a class.
    document.getElementById('activityModal').style.display = 'flex';

    try {
        const r = await fetch(`/cassitrack/api/v1/users/${encodeURIComponent(selectedUser.id)}/activity`);
        if (!r.ok) throw new Error(r.status);
        const d = await r.json();

        document.getElementById('activityDlCount').textContent    = `(${d.downloadCount ?? 0})`;
        document.getElementById('activityLoginCount').textContent = `(${d.loginCount ?? 0})`;

        const dl = d.downloads || [];
        document.getElementById('activityDownloads').innerHTML = dl.length
            ? dl.map(x => `
                <div class="activity-item">
                  <span class="activity-tag tag-${escHtml(String(x.format || '').toLowerCase())}">${escHtml(String(x.format || '').toUpperCase())}</span>
                  <span class="activity-main">
                    <b>${escHtml(x.dataset || '—')}</b>${personalTag(x.dataset)}
                    <span class="muted">${Number(x.rows) || 0} rows${x.detail ? ' · ' + escHtml(x.detail) : ''}</span>
                  </span>
                  <span class="activity-when">${fmtWhen(x.at)}</span>
                </div>`).join('')
            : '<div class="activity-empty">This account has never downloaded anything.</div>';

        const lg = d.logins || [];
        document.getElementById('activityLogins').innerHTML = lg.length
            ? lg.map(e => `
                <div class="activity-item">
                  <span class="activity-main">
                    <b>${escHtml(e.ip || 'unknown address')}</b>
                    <span class="muted">${escHtml(fmtAgent(e.userAgent))}</span>
                  </span>
                  <span class="activity-when">${fmtWhen(e.at)}</span>
                </div>`).join('')
            : '<div class="activity-empty">This account has never signed in.</div>';

    } catch (error) {
        console.error('Error loading activity:', error);
        document.getElementById('activityDownloads').innerHTML =
            '<div class="activity-empty">Could not load this account\'s activity.</div>';
    }
}

/**
 * Marks a download that carried people rather than vehicles.
 *
 * <p>"Took the account list" and "took the bus timetable" are materially
 * different events, and a register that prints them identically makes the
 * operator read every line to tell them apart. The masking means the file holds
 * no tax id and no full telephone — but it still holds names and e-mail
 * addresses, and that is worth seeing at a glance.
 */
function personalTag(dataset) {
    return /^users$/i.test(String(dataset || '').trim())
        ? ' <span class="activity-personal">personal data</span>'
        : '';
}

function closeActivityModal() {
    document.getElementById('activityModal').style.display = 'none';
}

// ─────────────────────────────
// LOAD USERS FROM API
// ─────────────────────────────

async function loadUsers(){

    try{

        // BUGFIX (auth): dead localStorage-token header removed — auth is
        // via the httpOnly cookie, sent automatically on same-origin fetches.
        //
        // /activity rather than the bare list: it returns exactly the same
        // fields, masking included, plus when each account was last in and how
        // much it has done. One request instead of two, and Edit still finds
        // every field it fills its form from.
        const response = await fetch('/cassitrack/api/v1/users/activity');

        const users = await response.json();
        allUsers = users;

        const table = document.getElementById('usersTable');

        table.innerHTML = '';

        users.forEach(user => {

            const roleClass =
                user.role === 'ADMIN'
                    ? 'admin'
                    : user.role === 'FLEET_MANAGER'
                        ? 'manager'
                        : 'driver';

            // CREATE ROW
            const row = document.createElement('tr');

            // CLICK EVENT
            row.onclick = () => selectRow(row, user);

            // So the export can tell which account a visible row belongs to
            // without parsing its cells back out of the DOM.
            row.dataset.userId = user.id;

            // HTML
            row.innerHTML = `
                <td>${escHtml(user.id)}</td>
                <td>${escHtml(user.taxId)}</td>
                <td>${escHtml(user.name)}</td>
                <td>${escHtml(user.surname)}</td>
                <td>${escHtml(user.email)}</td>
                <td>${escHtml(user.telephone || '-')}</td>
                <td>
                    <span class="role ${roleClass}">
                        ${escHtml(user.role)}
                    </span>
                </td>
                <td>${fmtWhen(user.lastLoginAt)}</td>
                <td class="num">${Number(user.logins) || 0}</td>
                <td class="num">${Number(user.downloads) || 0}</td>
            `;

            // ADD TO TABLE
            table.appendChild(row);
        });

    }catch(error){

        console.error('Error loading users:', error);

    }
}

// CSP FIX (A03/A05): bind every button here instead of inline onclick="" attributes,
// since CSP script-src governs inline event-handler attributes too.
document.getElementById('userModalCloseBtn').addEventListener('click', closeModal);
document.getElementById('saveUserBtn').addEventListener('click', saveUser);
document.getElementById('deleteModalCloseBtn').addEventListener('click', closeDeleteModal);
document.getElementById('deleteCancelBtn').addEventListener('click', closeDeleteModal);
document.getElementById('confirmDeleteBtn').addEventListener('click', confirmDeleteUser);

// BUGFIX: the CSP inline-handler migration above missed the header/topbar
// buttons (Log Out, Add User, Edit User, Delete User) -- they had no listener
// at all, so clicking them did nothing.
document.getElementById('logoutBtn').addEventListener('click', logoutUser);
document.getElementById('addUserBtn').addEventListener('click', openAddModal);
document.getElementById('editBtn').addEventListener('click', openEditModal);
document.getElementById('deleteBtn').addEventListener('click', deleteUser);
document.getElementById('exportUsersBtn').addEventListener('click', exportUsersXlsx);
document.getElementById('activityBtn').addEventListener('click', openActivityModal);
document.getElementById('activityCloseBtn').addEventListener('click', closeActivityModal);
document.getElementById('activityModal').addEventListener('click', e => {
    // Only the backdrop closes it; a click inside the card must not.
    if (e.target === document.getElementById('activityModal')) closeActivityModal();
});

// BUGFIX: the user table was never populated on page load -- loadUsers() was
// only ever called after a save/delete, never on initial load.
loadUsers();
startUsersRefresh();

