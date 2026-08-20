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

function selectRow(row, user){

    document
        .querySelectorAll('#usersTable tr')
        .forEach(r => r.classList.remove('selected'));

    row.classList.add('selected');

    selectedRow = row;
    selectedUser = user;

    document.getElementById('editBtn').disabled = false;
    document.getElementById('deleteBtn').disabled = false;
}

// SEARCH FILTER

document
    .getElementById('searchInput')
    .addEventListener('input', function(){

        const searchTerms = this.value
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

    });

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
// LOAD USERS FROM API
// ─────────────────────────────

async function loadUsers(){

    try{

        // BUGFIX (auth): dead localStorage-token header removed — auth is
        // via the httpOnly cookie, sent automatically on same-origin fetches.
        const response = await fetch('/cassitrack/api/v1/users');

        const users = await response.json();

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

// BUGFIX: the user table was never populated on page load -- loadUsers() was
// only ever called after a save/delete, never on initial load.
loadUsers();

