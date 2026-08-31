// ════════════════════════════════════════════════════════════
// CONFIG
// ════════════════════════════════════════════════════════════
const OMNIMOVE = '/omnimove/api/v1';

// Returns headers with current UI language so the server can send localised emails
function langHeaders() {
    return { 'Content-Type': 'application/json',
             'X-Omnimove-Lang': localStorage.getItem('omnimove_lang') || 'en' };
}

// Apply stored language on page load
document.addEventListener('DOMContentLoaded', applyTranslations);
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

// Declared here, not next to the functions that read them: init() runs while
// this file is still being evaluated, so a `let` or `const` further down would
// be in its temporal dead zone and would throw.
let _pendingTarget = null;
let _restoreRegisterTab = false;
const TAB_KEY = 'omnimove_login_tab';

(function init() {
    const params = new URLSearchParams(window.location.search);
    // Captured before the replaceState below wipes the query string. Reading it
    // later, at redirect time, always found an empty search and quietly sent
    // everyone to their default page — so ?next=, which both the server entry
    // point and the client route guard take the trouble to set, did nothing.
    _pendingTarget = params.get('next');
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
        showMsg(t('msg_email_verified'), 'ok');
        showPanel('loginForm'); showTabs();
        return;
    }
    if (verified === 'expired') {
        showMsg(t('msg_link_expired'), 'err');
        return;
    }
    if (verified === 'invalid') {
        showMsg(t('msg_link_invalid'), 'err');
        return;
    }

    // Flash error from backend redirect (e.g. invalid/expired reset token)
    const flashErr = sessionStorage.getItem('omnimove_flash_err');
    if (flashErr) {
        sessionStorage.removeItem('omnimove_flash_err');
        showMsg(flashErr, 'err');
        return;
    }

    // Only noted here, not acted on: switchTab -> showPanel reads ALL_PANELS,
    // which is declared below and is still in its temporal dead zone while this
    // runs. Reached last on purpose, so anything the URL asked for wins — a
    // reset token, a verification outcome and a flash error all return above.
    try { _restoreRegisterTab = sessionStorage.getItem(TAB_KEY) === 'register'; }
    catch (e) { /* storage blocked */ }

    // V-04 FIX: localStorage token removed — auth is handled via httpOnly cookie.
    // If a valid session cookie exists, the server-side redirect handles re-entry.
    // No client-side auto-redirect needed here.
})();

// ════════════════════════════════════════════════════════════
// UI HELPERS
// ════════════════════════════════════════════════════════════
const ALL_PANELS = ['loginForm','registerForm','emailSentPanel','forgotForm','resetForm','googleConsentPanel'];

function showPanel(id) {
    ALL_PANELS.forEach(p => {
        document.getElementById(p).className = 'form' + (p === id ? ' active' : '');
    });
    hideMsg();
}

function switchTab(tab) {
    // Remembered so that leaving this page and coming back — which now happens
    // in the same tab, since the privacy notice no longer opens a new one —
    // returns to the half-filled Register form rather than to Sign In.
    // Browsers restore the field values on a history navigation; which panel was
    // open is our own state and nobody restores it for us.
    try { sessionStorage.setItem(TAB_KEY, tab); } catch (e) { /* storage blocked */ }

    document.querySelectorAll('.tab').forEach((t, i) =>
        t.classList.toggle('active', (i === 0 && tab === 'login') || (i === 1 && tab === 'register')));
    showPanel(tab === 'login' ? 'loginForm' : 'registerForm');
    showTabs(); showGuestSection();
    if (tab === 'login') syncForgotButton();
}

// Deferred out of init() for the reason given there. Coming back from the
// privacy notice — same tab now — lands on the panel the visitor left.
if (_restoreRegisterTab) switchTab('register');

function hideTabs()        { document.getElementById('tabBar').style.display = 'none';
                             setGoogleVisible(false); }
function showTabs()        { document.getElementById('tabBar').style.display = 'flex';
                             setGoogleVisible(true); }
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
// The security entry point parks the page the user asked for in ?next=.
// Returns it only if it is a bare local page name — anything with a scheme, a slash,
// a backslash or a host is rejected, so ?next=https://evil.tld cannot bounce the user
// off-site after a successful login (OWASP A01, open redirect).
function pendingTarget() {
    const next = _pendingTarget;
    if (!next) return null;
    return /^[A-Za-z0-9._-]+\.html$/.test(next) ? next : null;
}

function secureRedirect(role) {
    const cleanRole = (role || 'TRAVELLER').toUpperCase();
    let targetHtmlUrl = REDIRECT[cleanRole] || 'omnimove-traveller.html';

    // Honour the requested page, unless the role cannot open it anyway
    const wanted = pendingTarget();
    if (wanted && !(/admin/i.test(wanted) && cleanRole !== 'ADMIN')) {
        targetHtmlUrl = wanted;
    }
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
    if (!email) { fieldErr('loginEmail', t('err_required')); hasErr = true; }
    if (!pass)  { fieldErr('loginPassword', t('err_required')); hasErr = true; }
    if (hasErr) return;

    // Caught here purely to save a round trip — the server refuses an unsolved
    // login regardless of what this page does
    if (captchaRequired()) {
        // The widget is built on first interaction, so it may still be arriving
        await captchaReady();
        if (_captchaBroken) { showMsg(t('err_captcha_broken'), 'err'); return; }
        if (!captchaToken()) { showMsg(t('err_captcha_required'), 'err'); return; }
    }

    const btn = document.getElementById('loginBtn');
    btn.disabled = true; btn.textContent = t('btn_sending');

    try {
        const r = await fetch(`${OMNIMOVE}/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password: pass, captchaToken: captchaToken() })
        });
        const data = await r.json();

        if (r.ok && data.token) {
            _failedAttempts = 0; syncForgotButton();
            saveSession(data);
            showMsg(t('msg_welcome_back').replace('{name}', data.name || 'user'), 'ok');
            secureRedirect(data.role || 'TRAVELLER');
        } else {
            // Google invalidates a token once verified, so the widget has to be
            // re-solved before the next try
            resetCaptcha();
            // Remove any existing resend button to avoid duplicates
            document.getElementById('resendVerifyBtn')?.remove();

            // 403 = not verified (don't count as lockout)
            if (r.status !== 403) {
                _failedAttempts++;
                syncForgotButton();
            }

            let errorMsg;
            if (r.status === 403) {
                errorMsg = data.message || t('err_not_verified');
            } else if (r.status === 401) {
                errorMsg = data.message || t('msg_wrong_creds');
            } else {
                errorMsg = data.message || t('msg_login_failed');
            }
            if (data.suggest_password_reset) {
                errorMsg += t('msg_too_many');
            }
            showMsg(errorMsg, 'err');

            // Show inline resend link if not verified
            if (r.status === 403) {
                const resendBtn = document.createElement('button');
                resendBtn.id = 'resendVerifyBtn';
                resendBtn.type = 'button';
                resendBtn.className = 'btn-ghost';
                resendBtn.textContent = t('btn_resend');
                resendBtn.style.marginTop = '8px';
                resendBtn.onclick = () => resendVerificationForEmail(
                    document.getElementById('loginEmail').value
                );
                document.getElementById('msg').after(resendBtn);
                setTimeout(() => resendBtn.remove(), 10000);
            }
        }
    } catch (err) {
        showMsg(t('msg_server_err'), 'err');
    } finally {
        btn.disabled = false; btn.textContent = t('btn_signin');
    }
}

// ════════════════════════════════════════════════════════════
// REGISTER
// ════════════════════════════════════════════════════════════
async function handleRegister(e) {
    e.preventDefault();
    clearAllFieldErrs('regFirstName', 'regLastName', 'regEmail', 'regPassword', 'regConfirm', 'regPrivacy');
    hideMsg();

    const firstName = document.getElementById('regFirstName').value.trim();
    const lastName  = document.getElementById('regLastName').value.trim();
    const name      = firstName && lastName ? `${firstName} ${lastName}` : '';
    const email     = document.getElementById('regEmail').value.trim();
    const pass      = document.getElementById('regPassword').value;
    const confirm   = document.getElementById('regConfirm').value;
    let hasErr = false;

    const privacyOk  = document.getElementById('regPrivacy').checked;
    const profiling  = document.getElementById('regProfiling').checked;

    if (!firstName) { fieldErr('regFirstName', t('err_required')); hasErr = true; }
    if (!lastName)  { fieldErr('regLastName',  t('err_required')); hasErr = true; }
    if (!email)     { fieldErr('regEmail',     t('err_required')); hasErr = true; }
    if (!pass)      { fieldErr('regPassword',  t('err_required')); hasErr = true; }
    if (!confirm)   { fieldErr('regConfirm',   t('err_required')); hasErr = true; }
    // The notice must be acknowledged; the profiling box is free either way and
    // is deliberately not validated.
    if (!privacyOk) { fieldErr('regPrivacy', t('err_privacy_required')); hasErr = true; }
    if (hasErr) return;

    if (!isPasswordValid(pass)) {
        fieldErr('regPassword', t('err_pwd_weak'));
        return;
    }
    if (pass !== confirm) {
        fieldErr('regConfirm', t('err_pwd_match'));
        return;
    }

    const btn = document.getElementById('regBtn');
    btn.disabled = true; btn.textContent = t('btn_sending');

    try {
        const r = await fetch(`${OMNIMOVE}/auth/register`, {
            method: 'POST',
            headers: langHeaders(),
            body: JSON.stringify({
                name, email, password: pass, confirmPassword: confirm,
                // Recorded in the consent ledger so the choice is provable (art. 7(1)).
                privacyNoticeAccepted: privacyOk,
                profilingConsent: profiling,
                // The banner is shown before anyone has an account, and an
                // anonymous acknowledgement is never sent to the server on its
                // own — it lives in this tab. Registration is where it becomes
                // attributable, so it travels with the account that is being
                // created, and the visitor is not asked the same thing again at
                // their first sign-in. If registration fails, nothing was
                // written and the acknowledgement stays where it was.
                cookieNoticeAccepted: !!(window.OmniConsent && OmniConsent.state()
                                         && OmniConsent.state().notice === true),
                // Links any choice already made in the cookie banner to this account.
                subjectKey: (window.OmniConsent && OmniConsent.state()
                             && OmniConsent.state().subjectKey) || null
            })
        });
        const data = await r.json();

        if (r.ok) {
            _lastRegisteredEmail = email;
            _emailSentMode = 'verify';
            document.getElementById('sentToEmail').textContent = email;
            hideTabs(); hideGuestSection();
            showPanel('emailSentPanel');
            showMsg(t('msg_account_created'), 'ok');
        } else {
            showMsg(data.message || data.error || t('msg_register_failed'), 'err');
        }
    } catch (err) {
        showMsg(t('msg_server_err'), 'err');
    } finally {
        btn.disabled = false; btn.textContent = t('btn_create');
    }
}

// ════════════════════════════════════════════════════════════
// RESEND VERIFICATION
// ════════════════════════════════════════════════════════════
async function resendVerification() {
    // Resolve email: use registered email, or read from what's displayed in the panel
    const email = _lastRegisteredEmail
        || document.getElementById('sentToEmail').textContent.trim().replace('—','');
    if (!email) { showMsg(t('msg_no_email'), 'err'); return; }

    if (_emailSentMode === 'reset') {
        // Resend password reset link
        try {
            await fetch(`${OMNIMOVE}/auth/forgot-password`, {
                method: 'POST',
                headers: langHeaders(),
                body: JSON.stringify({ email })
            });
            showMsg(t('msg_reset_resent'), 'ok');
        } catch { showMsg(t('msg_server_short'), 'err'); }
    } else {
        await resendVerificationForEmail(email);
    }
}

async function resendVerificationForEmail(email) {
    if (!email) { showMsg(t('msg_no_email'), 'err'); return; }
    try {
        const r = await fetch(`${OMNIMOVE}/auth/resend-verification`, {
            method: 'POST',
            headers: langHeaders(),
            body: JSON.stringify({ email })
        });
        const data = await r.json();
        showMsg(data.message || t('msg_account_created'), r.ok ? 'ok' : 'err');
    } catch (err) {
        showMsg(t('msg_server_short'), 'err');
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
    if (!email) { fieldErr('forgotEmail', t('err_required')); return; }

    const btn = document.getElementById('forgotSubmitBtn');
    btn.disabled = true; btn.textContent = t('btn_sending');

    try {
        const r = await fetch(`${OMNIMOVE}/auth/forgot-password`, {
            method: 'POST',
            headers: langHeaders(),
            body: JSON.stringify({ email })
        });
        const data = await r.json();
        // Switch to the emailSentPanel so the user can resend if needed
        _emailSentMode = 'reset';
        _lastRegisteredEmail = '';
        document.getElementById('sentToEmail').textContent = email;
        showPanel('emailSentPanel');
        showMsg(data.message || t('msg_check_email'), 'ok');
    } catch (err) {
        showMsg(t('msg_server_short'), 'err');
    } finally {
        btn.disabled = false; btn.textContent = t('btn_send_reset');
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

    if (!newPass)  { fieldErr('newPassword',        t('err_required')); hasErr = true; }
    if (!confPass) { fieldErr('newPasswordConfirm', t('err_required')); hasErr = true; }
    if (hasErr) return;

    if (!isPasswordValid(newPass)) {
        fieldErr('newPassword', t('err_pwd_weak'));
        return;
    }
    if (newPass !== confPass) {
        fieldErr('newPasswordConfirm', t('err_pwd_match'));
        return;
    }

    const btn = document.getElementById('resetBtn');
    btn.disabled = true; btn.textContent = t('btn_sending');

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
            showMsg(data.message || t('msg_pwd_updated'), 'ok');
            setTimeout(() => { showTabs(); showGuestSection(); switchTab('login'); }, 2000);
        } else {
            showMsg(data.message || t('msg_reset_failed'), 'err');
        }
    } catch (err) {
        showMsg(t('msg_server_short'), 'err');
    } finally {
        btn.disabled = false; btn.textContent = t('btn_set_pass');
    }
}

// ════════════════════════════════════════════════════════════
// SIGN IN WITH GOOGLE
// ════════════════════════════════════════════════════════════
// Google Identity Services hands the browser a signed ID token; the server
// verifies that signature itself and answers with our own session cookie, the
// same one the password form gets. Nothing about the Google account is trusted
// here — this file only carries the token across.

// ════════════════════════════════════════════════════════════
//  THIRD-PARTY SCRIPTS, LOADED ONLY WHEN THEY ARE ACTUALLY NEEDED
// ════════════════════════════════════════════════════════════
// Google Sign-In and reCAPTCHA used to be <script src> tags in the page, so
// every visit reached Google before the visitor did anything — including
// visits to deployments with both features off, and visits from someone who
// only opened the page to read the privacy notice. Each is now fetched when
// two conditions hold: the server says the feature is on, and the visitor has
// started using the page. What is left is described in privacy.html § 5 and
// cookie-policy.html § 4; if this changes, those change with it.

const GIS_SRC       = 'https://accounts.google.com/gsi/client';
const RECAPTCHA_SRC = 'https://www.google.com/recaptcha/api.js?render=explicit';

const _scriptLoads = new Map();

/** Injects a script once and resolves when it has run. Rejects if it fails. */
function loadScriptOnce(src) {
    if (_scriptLoads.has(src)) return _scriptLoads.get(src);
    const p = new Promise((resolve, reject) => {
        const el = document.createElement('script');
        el.src = src;
        el.async = true;
        el.defer = true;
        el.onload  = () => resolve(true);
        el.onerror = () => reject(new Error('could not load ' + src));
        document.head.appendChild(el);
    });
    _scriptLoads.set(src, p);
    return p;
}

let _interaction = null;

/**
 * Resolves the first time the visitor touches the page — a click, a key, a
 * tap. Landing here and leaving must not reach Google at all, and reading the
 * page is not using it. Anything that could lead to a sign-in goes through one
 * of these events first, so nothing is delayed that the visitor is waiting on.
 */
function firstInteraction() {
    if (_interaction) return _interaction;
    const events = ['pointerdown', 'keydown', 'touchstart'];
    _interaction = new Promise(resolve => {
        const fire = () => {
            events.forEach(e => document.removeEventListener(e, fire));
            resolve();
        };
        events.forEach(e =>
            document.addEventListener(e, fire, { once: true, passive: true }));
    });
    return _interaction;
}

async function initGoogle() {
    let cfg;
    try {
        const r = await fetch(`${OMNIMOVE}/auth/google/config`);
        if (!r.ok) return;
        cfg = await r.json();
    } catch { return; }

    // No client id configured on this deployment: leave the block hidden rather
    // than draw a button that could only fail. Nothing has been asked of Google
    // at this point, and on a deployment that stops here nothing ever is.
    if (!cfg.enabled || !cfg.clientId) return;

    // Enabled, so the button will be wanted — but not until someone is here to
    // press it. The button appears a moment after the first interaction, which
    // is no later than it appeared before: the tag was async anyway.
    await firstInteraction();
    try {
        await loadScriptOnce(GIS_SRC);
    } catch (e) {
        console.warn('Google Identity Services did not load:', e.message);
        return;
    }

    // Loaded is not the same as initialised: the library defines its namespace
    // as it runs, so keep polling for the piece actually used here.
    const ready = await waitForGis();
    if (!ready) { console.warn('Google Identity Services did not load'); return; }

    google.accounts.id.initialize({
        client_id: cfg.clientId,
        callback: handleGoogleCredential,
        cancel_on_tap_outside: true
    });
    renderGoogleButton();

    // Only reveal it once there is a real button inside, and only if the panel
    // showing is one the button belongs to
    document.getElementById('googleBlock').dataset.ready = '1';
    setGoogleVisible(document.getElementById('tabBar').style.display !== 'none');
}

/**
 * Google writes the button's own label ("Continue with" / "Continua con"), and
 * it picks the wording from the locale it is given — not from our page. Without
 * this it falls back to the browser's language and the button stayed in Italian
 * while the rest of the page was in English. Google draws the button once, so
 * changing language means drawing it again.
 */
function renderGoogleButton() {
    const target = document.getElementById('googleBtn');
    if (!target || !window.google?.accounts?.id) return;

    target.innerHTML = '';
    google.accounts.id.renderButton(target, {
        theme: 'filled_black',
        size: 'large',
        shape: 'pill',
        text: 'continue_with',
        width: 280,
        locale: getLang()
    });
}

// i18n.js calls this after every language switch
window._onLangChange = () => renderGoogleButton();

/** The GIS script is injected async, so poll briefly instead of assuming. */
function waitForGis(timeoutMs = 5000) {
    return new Promise(resolve => {
        const started = Date.now();
        (function poll() {
            if (window.google?.accounts?.id) return resolve(true);
            if (Date.now() - started > timeoutMs) return resolve(false);
            setTimeout(poll, 100);
        })();
    });
}

// Readiness is kept on the element rather than in a variable: init() runs
// hideTabs() while this script is still being evaluated, so any `let` declared
// down here would still be in its temporal dead zone and would throw.
function setGoogleVisible(visible) {
    const block = document.getElementById('googleBlock');
    if (!block) return;
    block.style.display = (visible && block.dataset.ready === '1') ? 'block' : 'none';
}

async function handleGoogleCredential(response) {
    const err = document.getElementById('err-google');
    err.textContent = '';
    hideMsg();

    if (!response || !response.credential) {
        err.textContent = t('err_google_failed');
        return;
    }

    await postGoogleCredential(response.credential, null);
}

/**
 * Sends the Google token, and handles the one answer that is neither a session
 * nor a failure: the token is good but belongs to nobody yet, and the account
 * cannot be created until the privacy notice has been acknowledged.
 *
 * With Google there is no form to put that checkbox on — signing in and signing
 * up are the same single click — so the panel is raised only when the server
 * says an account would be created, and the same credential is posted again
 * with the answers. Someone who already has an account never sees it.
 *
 * @param consents null on the first attempt; the collected answers on the retry.
 */
async function postGoogleCredential(credential, consents) {
    const err = document.getElementById('err-google');
    try {
        const body = { credential: credential };
        if (consents) {
            body.privacyNoticeAccepted = true;
            body.profilingConsent      = consents.profiling === true;
            // Carried over from the banner, exactly as the e-mail form does
            body.cookieNoticeAccepted  = !!(window.OmniConsent && OmniConsent.state()
                                            && OmniConsent.state().notice === true);
            body.subjectKey            = (window.OmniConsent && OmniConsent.state()
                                          && OmniConsent.state().subjectKey) || null;
        }

        const r = await fetch(`${OMNIMOVE}/auth/google`, {
            method: 'POST',
            headers: langHeaders(),
            body: JSON.stringify(body)
        });
        const data = await r.json();

        if (r.ok && data.token) {
            _failedAttempts = 0; syncForgotButton();
            saveSession(data);
            showMsg(t('msg_welcome_back').replace('{name}', data.name || 'user'), 'ok');
            secureRedirect(data.role || 'TRAVELLER');
            return;
        }

        if (r.ok && data.consentRequired === true) {
            // Nothing was written server-side. Ask, then retry with the same token.
            openGoogleConsent(credential, data.email, data.name);
            return;
        }

        showMsg(data.message || t('err_google_failed'), 'err');
    } catch (e) {
        if (err) err.textContent = '';
        showMsg(t('msg_server_short'), 'err');
    }
}

// The token being answered for. Held only until the panel is dismissed.
let _pendingGoogleCredential = null;

function openGoogleConsent(credential, email, name) {
    _pendingGoogleCredential = credential;
    document.getElementById('gConsentWho').textContent = email || name || '';
    document.getElementById('gcPrivacy').checked  = false;
    document.getElementById('gcProfiling').checked = false;
    clearFieldErr('gcPrivacy');
    hideTabs(); hideGuestSection();
    showPanel('googleConsentPanel');
}

function cancelGoogleConsent() {
    // No account was created, so backing out leaves nothing behind.
    _pendingGoogleCredential = null;
    showPanel('loginForm'); showTabs(); showGuestSection();
}

async function confirmGoogleConsent() {
    if (!document.getElementById('gcPrivacy').checked) {
        fieldErr('gcPrivacy', t('err_privacy_required'));
        return;
    }
    const btn = document.getElementById('gcBtn');
    btn.disabled = true; btn.textContent = t('btn_sending');

    const credential = _pendingGoogleCredential;
    _pendingGoogleCredential = null;
    await postGoogleCredential(credential, {
        profiling: document.getElementById('gcProfiling').checked
    });

    btn.disabled = false; btn.textContent = t('gc_create');
}

initGoogle();

// ── Password strength ──
// Both forms that ask for a new password show the same bar and the same rules,
// from omnimove-password.js — the mirror of the server-side policy.
omniPwdWatch(document.getElementById('regPassword'),
             document.getElementById('regPwdBar'),
             document.getElementById('regPwdRules'));

omniPwdWatch(document.getElementById('newPassword'),
             document.getElementById('resetPwdBar'),
             document.getElementById('resetPwdRules'));

// The rule labels come from the module, not from data-i18n, so a language
// switch has to repaint them
const _prevLangChange = window._onLangChange;
window._onLangChange = (lang) => {
    if (typeof _prevLangChange === 'function') _prevLangChange(lang);
    omniPwdRender(document.getElementById('regPassword')?.value,
                  document.getElementById('regPwdBar'),
                  document.getElementById('regPwdRules'));
    omniPwdRender(document.getElementById('newPassword')?.value,
                  document.getElementById('resetPwdBar'),
                  document.getElementById('resetPwdRules'));
};

// ════════════════════════════════════════════════════════════
// reCAPTCHA
// ════════════════════════════════════════════════════════════
// Whether the check runs is the administrator's call, taken server-side; this
// file only asks and obeys. The server verifies the token on every login, so a
// page that skipped the widget gets a 400 rather than a way in.
let _captchaId       = null;   // the rendered widget
let _captchaRequired = false;  // what the server says it will enforce
let _captchaBroken   = false;  // required, but the widget never appeared
let _captchaReady    = null;   // resolves once the widget exists, or is known broken

async function initCaptcha() {
    let cfg;
    try {
        const r = await fetch(`${OMNIMOVE}/auth/captcha/config`);
        if (!r.ok) return;
        cfg = await r.json();
    } catch { return; }

    // Off on this deployment: Google is never contacted from this page.
    if (!cfg.enabled || !cfg.siteKey) return;

    // From here on the server WILL reject a login without a token, so any
    // failure below has to be shown rather than swallowed — otherwise the user
    // meets an error about a checkbox that is not on the page.
    _captchaRequired = true;

    // Built on first interaction rather than on load. Someone who opens the
    // page and reads it never reaches Google; anyone about to sign in has
    // clicked or typed by definition, so the widget is on its way well before
    // the form can be submitted — and captchaReady() covers the case where it
    // is not.
    _captchaReady = (async () => {
        await firstInteraction();
        try {
            await loadScriptOnce(RECAPTCHA_SRC);
        } catch (e) {
            captchaBroken(e.message);
            return;
        }

        // api.js renders explicitly and defines grecaptcha as it runs
        const ready = await waitFor(() => window.grecaptcha?.render);
        if (!ready) { captchaBroken('reCAPTCHA API never became available'); return; }

        const box = document.getElementById('captchaBox');
        try {
            _captchaId = grecaptcha.render(box, {
                sitekey: cfg.siteKey,
                theme: 'dark'
            });
        } catch (e) {
            // Most often a key registered as v3 while this page renders a v2
            // checkbox — grecaptcha throws "Invalid site key type"
            captchaBroken(e && e.message ? e.message : 'could not render');
            return;
        }
        box.classList.add('on');
    })();
}

/**
 * Waits for the deferred widget, so a fast submit — autofill, then Enter —
 * does not hit "please complete the check" while the checkbox is still being
 * built. Returns immediately when the check is off or already rendered.
 */
async function captchaReady() {
    if (_captchaReady) await _captchaReady;
}

function captchaBroken(reason) {
    _captchaBroken = true;
    console.error('reCAPTCHA unavailable:', reason);
    document.getElementById('captchaErr').textContent = t('err_captcha_broken');
}

/** Polls briefly for a value an async script will eventually define. */
function waitFor(get, timeoutMs = 6000) {
    return new Promise(resolve => {
        const started = Date.now();
        (function poll() {
            if (get()) return resolve(true);
            if (Date.now() - started > timeoutMs) return resolve(false);
            setTimeout(poll, 100);
        })();
    });
}

/** The solved token, or '' when the widget is not on this page. */
function captchaToken() {
    return _captchaId === null ? '' : grecaptcha.getResponse(_captchaId);
}

/** A token is single-use: after any failed attempt the widget must be re-solved. */
function resetCaptcha() {
    if (_captchaId !== null) grecaptcha.reset(_captchaId);
}

function captchaRequired() {
    return _captchaRequired;
}

initCaptcha();
