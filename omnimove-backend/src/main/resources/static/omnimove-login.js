// ════════════════════════════════════════════════════════════
// CONFIG
// ════════════════════════════════════════════════════════════
const OMNIMOVE = '/omnimove/api/v1';
const REDIRECT = {
    ADMIN:     'omnimove-admin.html',
    TRAVELLER: 'omnimove-traveller.html',
    PASSENGER: 'omnimove-traveller.html'
};

let _failedAttempts = 0;
let _lastRegisteredEmail = '';
let _emailSentMode = 'verify'; // 'verify' | 'reset' — controls what the resend button does

function isPasswordValid(p) {
    return /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^a-zA-Z0-9]).{8,}$/.test(p);
}

// ── Field-level validation helpers ──────────────────────────
function fieldErr(inputId, msg) {
    const input = document.getElementById(inputId);
    const span  = document.getElementById('err-' + inputId);
    if (input) input.classList.add('input-err');
    if (span)  { span.textContent = msg; span.classList.add('visible'); }
    if (input) input.addEventListener('input', () => clearFieldErr(inputId), { once: true });
}
function clearFieldErr(inputId) {
    const input = document.getElementById(inputId);
    const span  = document.getElementById('err-' + inputId);
    if (input) input.classList.remove('input-err');
    if (span)  { span.textContent = ''; span.classList.remove('visible'); }
}
function clearAllFieldErrs(...ids) { ids.forEach(clearFieldErr); }

// ════════════════════════════════════════════════════════════
// STARTUP — URL PARAM DETECTION
// ════════════════════════════════════════════════════════════
(function init() {
    const params = new URLSearchParams(window.location.search);
    // ?pr=TOKEN  — server-side redirect from /api/v1/auth/reset-page (most reliable path)
    // ?reset=TOKEN — legacy / direct link fallback
    const resetToken = params.get('pr') || params.get('reset');

    if (resetToken) {
        document.getElementById('resetToken').value = resetToken;
        history.replaceState({}, '', window.location.pathname);
        hideTabs(); hideGuestSection();
        showPanel('resetForm');
        return;
    }

    // ?verified=true|expired|invalid
    const verified = params.get('verified');
    history.replaceState({}, '', window.location.pathname);
    if (verified === 'true') {
        showMsg('✓ Email verified! You can now sign in.', 'ok');
        showPanel('loginForm'); showTabs();
        return;
    }
    if (verified === 'expired') {
        showMsg('Verification link expired. Please register again or request a new link.', 'err');
        return;
    }
    if (verified === 'invalid') {
        showMsg('Invalid verification link.', 'err');
        return;
    }

    // Flash error from backend redirect (e.g. invalid/expired reset token)
    const flashErr = sessionStorage.getItem('omnimove_flash_err');
    if (flashErr) {
        sessionStorage.removeItem('omnimove_flash_err');
        showMsg(flashErr, 'err');
        return;
    }

    // V-04 FIX: localStorage token removed — auth is handled via httpOnly cookie.
    // If a valid session cookie exists, the server-side redirect handles re-entry.
    // No client-side auto-redirect needed here.
})();

// ════════════════════════════════════════════════════════════
// UI HELPERS
// ════════════════════════════════════════════════════════════
const ALL_PANELS = ['loginForm','registerForm','emailSentPanel','forgotForm','resetForm'];

function showPanel(id) {
    ALL_PANELS.forEach(p => {
        document.getElementById(p).className = 'form' + (p === id ? ' active' : '');
    });
    hideMsg();
}

function switchTab(tab) {
    document.querySelectorAll('.tab').forEach((t, i) =>
        t.classList.toggle('active', (i === 0 && tab === 'login') || (i === 1 && tab === 'register')));
    showPanel(tab === 'login' ? 'loginForm' : 'registerForm');
    showTabs(); showGuestSection();
    if (tab === 'login') syncForgotButton();
}

function hideTabs()        { document.getElementById('tabBar').style.display = 'none'; }
function showTabs()        { document.getElementById('tabBar').style.display = 'flex'; }
function hideGuestSection(){ }
function showGuestSection(){ }

function showMsg(text, type) {
    const el = document.getElementById('msg');
    el.textContent = text;
    el.className = 'msg ' + type;
}
function hideMsg() { document.getElementById('msg').className = 'msg'; }

function cancelReset() {
    sessionStorage.removeItem('omnimove_pending_reset');
    document.getElementById('resetToken').value = '';
    showTabs();
    switchTab('login');
}

function syncForgotButton() {
    document.getElementById('forgotBtn').style.display = _failedAttempts >= 2 ? 'block' : 'none';
}

function showForgotPanel() {
    const email = document.getElementById('loginEmail').value.trim();
    // Always show the form — pre-fill email if available so user can confirm before sending
    if (email) {
        document.getElementById('forgotEmail').value = email;
    }
    hideTabs(); hideGuestSection();
    showPanel('forgotForm');
}

// ════════════════════════════════════════════════════════════
// REDIRECT — V-11 FIX (OWASP A03): No more document.write()
// V-04 FIX (OWASP A02): Token stays in httpOnly cookie; not in localStorage.
// ════════════════════════════════════════════════════════════
function secureRedirect(role) {
    const cleanRole = (role || 'TRAVELLER').toUpperCase();
    const targetHtmlUrl = REDIRECT[cleanRole] || 'omnimove-traveller.html';
    // Navigate normally — the httpOnly session cookie is sent automatically.
    // document.write() is removed: it was an XSS vector and breaks CSP.
    setTimeout(() => {
        window.location.replace(targetHtmlUrl);
    }, 1200);
}

function saveSession(data) {
    // V-04 FIX: Token is stored in an httpOnly cookie by the server — not here.
    // We only keep non-sensitive display info in sessionStorage (cleared on tab close).
    let role = data.role || (data.roles && data.roles[0]) || 'TRAVELLER';
    if (role === 'USER') role = 'TRAVELLER';
    sessionStorage.setItem('omnimove_user', JSON.stringify({
        name:  data.name  || data.username || 'User',
        email: data.email || '',
        role
    }));
}

// ════════════════════════════════════════════════════════════
// LOGIN
// ════════════════════════════════════════════════════════════
async function handleLogin(e) {
    e.preventDefault();
    clearAllFieldErrs('loginEmail', 'loginPassword');
    hideMsg();

    const email = document.getElementById('loginEmail').value.trim();
    const pass  = document.getElementById('loginPassword').value;
    let hasErr  = false;
    if (!email) { fieldErr('loginEmail', 'Mandatory field'); hasErr = true; }
    if (!pass)  { fieldErr('loginPassword', 'Mandatory field'); hasErr = true; }
    if (hasErr) return;

    const btn = document.getElementById('loginBtn');
    btn.disabled = true; btn.textContent = 'Signing in…';

    try {
        const r = await fetch(`${OMNIMOVE}/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password: pass })
        });
        const data = await r.json();

        if (r.ok && data.token) {
            _failedAttempts = 0; syncForgotButton();
            saveSession(data);
            showMsg('Welcome back, ' + (data.name || 'user') + '! Redirecting…', 'ok');
            secureRedirect(data.role || 'TRAVELLER');
        } else {
            // Remove any existing resend button to avoid duplicates
            document.getElementById('resendVerifyBtn')?.remove();

            // 403 = not verified (don't count as lockout)
            if (r.status !== 403) {
                _failedAttempts++;
                syncForgotButton();
            }

            let errorMsg;
            if (r.status === 403) {
                errorMsg = data.message || 'Your email address has not been verified yet. Please check your inbox.';
            } else if (r.status === 401) {
                errorMsg = data.message || 'Incorrect email or password';
            } else {
                errorMsg = data.message || 'Login failed. Please try again.';
            }
            if (data.suggest_password_reset) {
                errorMsg += ' — Too many failed attempts, use "Forgot password?" below.';
            }
            showMsg(errorMsg, 'err');

            // Show inline resend link if not verified
            if (r.status === 403) {
                const resendBtn = document.createElement('button');
                resendBtn.id = 'resendVerifyBtn';
                resendBtn.type = 'button';
                resendBtn.className = 'btn-ghost';
                resendBtn.textContent = 'Resend verification email';
                resendBtn.style.marginTop = '8px';
                resendBtn.onclick = () => resendVerificationForEmail(
                    document.getElementById('loginEmail').value
                );
                document.getElementById('msg').after(resendBtn);
                setTimeout(() => resendBtn.remove(), 10000);
            }
        }
    } catch (err) {
        showMsg('Cannot reach OMNIMOVE server. Check it is running on :8180', 'err');
    } finally {
        btn.disabled = false; btn.textContent = 'Sign In';
    }
}

// ════════════════════════════════════════════════════════════
// REGISTER
// ════════════════════════════════════════════════════════════
async function handleRegister(e) {
    e.preventDefault();
    clearAllFieldErrs('regFirstName', 'regLastName', 'regEmail', 'regPassword', 'regConfirm');
    hideMsg();

    const firstName = document.getElementById('regFirstName').value.trim();
    const lastName  = document.getElementById('regLastName').value.trim();
    const name      = firstName && lastName ? `${firstName} ${lastName}` : '';
    const email     = document.getElementById('regEmail').value.trim();
    const pass      = document.getElementById('regPassword').value;
    const confirm   = document.getElementById('regConfirm').value;
    let hasErr = false;

    if (!firstName) { fieldErr('regFirstName', 'Mandatory field'); hasErr = true; }
    if (!lastName)  { fieldErr('regLastName',  'Mandatory field'); hasErr = true; }
    if (!email)     { fieldErr('regEmail',     'Mandatory field'); hasErr = true; }
    if (!pass)      { fieldErr('regPassword',  'Mandatory field'); hasErr = true; }
    if (!confirm)   { fieldErr('regConfirm',   'Mandatory field'); hasErr = true; }
    if (hasErr) return;

    if (!isPasswordValid(pass)) {
        fieldErr('regPassword', 'Min 8 chars: uppercase, lowercase, number & special character');
        return;
    }
    if (pass !== confirm) {
        fieldErr('regConfirm', 'Passwords do not match');
        return;
    }

    const btn = document.getElementById('regBtn');
    btn.disabled = true; btn.textContent = 'Creating account…';

    try {
        const r = await fetch(`${OMNIMOVE}/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name, email, password: pass, confirmPassword: confirm })
        });
        const data = await r.json();

        if (r.ok) {
            _lastRegisteredEmail = email;
            _emailSentMode = 'verify';
            document.getElementById('sentToEmail').textContent = email;
            hideTabs(); hideGuestSection();
            showPanel('emailSentPanel');
            showMsg('Account created! Verification email sent.', 'ok');
        } else {
            showMsg(data.message || data.error || 'Registration failed', 'err');
        }
    } catch (err) {
        showMsg('Cannot reach OMNIMOVE server. Check it is running on :8180', 'err');
    } finally {
        btn.disabled = false; btn.textContent = 'Create Account';
    }
}

// ════════════════════════════════════════════════════════════
// RESEND VERIFICATION
// ════════════════════════════════════════════════════════════
async function resendVerification() {
    // Resolve email: use registered email, or read from what's displayed in the panel
    const email = _lastRegisteredEmail
        || document.getElementById('sentToEmail').textContent.trim().replace('—','');
    if (!email) { showMsg('Cannot determine email address.', 'err'); return; }

    if (_emailSentMode === 'reset') {
        // Resend password reset link
        try {
            await fetch(`${OMNIMOVE}/auth/forgot-password`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ email })
            });
            showMsg('Reset link resent. Check your inbox (and spam folder).', 'ok');
        } catch { showMsg('Cannot reach server.', 'err'); }
    } else {
        await resendVerificationForEmail(email);
    }
}

async function resendVerificationForEmail(email) {
    if (!email) { showMsg('Cannot determine email address.', 'err'); return; }
    try {
        const r = await fetch(`${OMNIMOVE}/auth/resend-verification`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email })
        });
        const data = await r.json();
        showMsg(data.message || 'Email sent!', r.ok ? 'ok' : 'err');
    } catch (err) {
        showMsg('Cannot reach server.', 'err');
    }
}

// ════════════════════════════════════════════════════════════
// FORGOT PASSWORD
// ════════════════════════════════════════════════════════════
async function handleForgot(e) {
    e.preventDefault();
    clearAllFieldErrs('forgotEmail');
    hideMsg();

    const email = document.getElementById('forgotEmail').value.trim();
    if (!email) { fieldErr('forgotEmail', '⚠ Campo obbligatorio'); return; }

    const btn = document.getElementById('forgotSubmitBtn');
    btn.disabled = true; btn.textContent = 'Sending…';

    try {
        const r = await fetch(`${OMNIMOVE}/auth/forgot-password`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email })
        });
        const data = await r.json();
        // Switch to the emailSentPanel so the user can resend if needed
        _emailSentMode = 'reset';
        _lastRegisteredEmail = '';
        document.getElementById('sentToEmail').textContent = email;
        showPanel('emailSentPanel');
        showMsg(data.message || 'Check your email (and spam folder).', 'ok');
    } catch (err) {
        showMsg('Cannot reach server.', 'err');
    } finally {
        btn.disabled = false; btn.textContent = 'Send Reset Link';
    }
}

// ════════════════════════════════════════════════════════════
// RESET PASSWORD
// ════════════════════════════════════════════════════════════
async function handleReset(e) {
    e.preventDefault();
    clearAllFieldErrs('newPassword', 'newPasswordConfirm');
    hideMsg();

    const newPass  = document.getElementById('newPassword').value;
    const confPass = document.getElementById('newPasswordConfirm').value;
    let hasErr = false;

    if (!newPass)  { fieldErr('newPassword',        'Mandatory field'); hasErr = true; }
    if (!confPass) { fieldErr('newPasswordConfirm', 'Mandatory field'); hasErr = true; }
    if (hasErr) return;

    if (!isPasswordValid(newPass)) {
        fieldErr('newPassword', '⚠ Min 8 chars: uppercase, lowercase, number & special character');
        return;
    }
    if (newPass !== confPass) {
        fieldErr('newPasswordConfirm', 'Passwords do not match');
        return;
    }

    const btn = document.getElementById('resetBtn');
    btn.disabled = true; btn.textContent = 'Saving…';

    try {
        const r = await fetch(`${OMNIMOVE}/auth/reset-password`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                token:           document.getElementById('resetToken').value,
                newPassword:     newPass,
                confirmPassword: confPass
            })
        });
        const data = await r.json();

        if (r.ok) {
            showMsg(data.message || 'Password updated! You can now sign in.', 'ok');
            setTimeout(() => { showTabs(); showGuestSection(); switchTab('login'); }, 2000);
        } else {
            showMsg((data.message || 'Reset failed. The link may have expired.') +
                    '\nUse "Back to Sign In" to request a new link.', 'err');
        }
    } catch (err) {
        showMsg('Cannot reach server.', 'err');
    } finally {
        btn.disabled = false; btn.textContent = 'Set New Password';
    }
}
