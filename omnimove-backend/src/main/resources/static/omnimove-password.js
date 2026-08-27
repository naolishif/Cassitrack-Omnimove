// ════════════════════════════════════════════════════════════════════
// PASSWORD STRENGTH
//
// Shared by the sign-up form, the reset page and the account panel, so the
// three places that ask for a password state the same rules in the same words.
//
// The server is still the authority — PasswordPolicy.java validates every
// request — but a rule cannot be checked per keystroke over the network, so it
// is mirrored here. THE TWO MUST BE KEPT IN STEP: change one, change the other.
//
// Self-contained on purpose: the reset page does not load omnimove-i18n.js, and
// a rule the user cannot read is not a rule.
// ════════════════════════════════════════════════════════════════════

const OMNI_PWD_RULES = [
    { id: 'len',    test: p => p.length >= 8,          en: 'At least 8 characters', it: 'Almeno 8 caratteri' },
    { id: 'lower',  test: p => /[a-z]/.test(p),        en: 'One lowercase letter',  it: 'Una lettera minuscola' },
    { id: 'upper',  test: p => /[A-Z]/.test(p),        en: 'One uppercase letter',  it: 'Una lettera maiuscola' },
    { id: 'digit',  test: p => /\d/.test(p),           en: 'One number',            it: 'Un numero' },
    { id: 'symbol', test: p => /[^a-zA-Z0-9]/.test(p), en: 'One special character', it: 'Un carattere speciale' }
];

const OMNI_PWD_LABELS = {
    en: { weak: 'Weak', fair: 'Fair', strong: 'Strong' },
    it: { weak: 'Debole', fair: 'Media', strong: 'Forte' }
};

function omniPwdLang() {
    const stored = localStorage.getItem('omnimove_lang');
    if (stored) return stored === 'it' ? 'it' : 'en';
    return navigator.language?.toLowerCase().startsWith('it') ? 'it' : 'en';
}

/** True only when every rule passes — the same answer the server will give. */
function omniPwdValid(password) {
    return OMNI_PWD_RULES.every(r => r.test(password || ''));
}

/**
 * Paints the bar and the rule list.
 *
 * The bar turns green only when the password would actually be accepted, so
 * green means "this will work", not "this is nearly there". Anything short of
 * that stays amber or red however long the password is.
 */
function omniPwdRender(password, barEl, listEl) {
    const pw     = password || '';
    const lang   = omniPwdLang();
    const passed = OMNI_PWD_RULES.filter(r => r.test(pw)).length;
    const all    = OMNI_PWD_RULES.length;

    if (barEl) {
        const level = pw.length === 0 ? 'empty'
                    : passed === all  ? 'strong'
                    : passed >= 3     ? 'fair'
                    :                   'weak';

        barEl.innerHTML = `
            <div class="pwd-bar-track">
                ${OMNI_PWD_RULES.map((_, i) =>
                    `<span class="pwd-bar-seg${i < passed ? ' on' : ''}"></span>`).join('')}
            </div>
            <span class="pwd-bar-label">${
                level === 'empty' ? '' : OMNI_PWD_LABELS[lang][level]
            }</span>`;
        barEl.className = 'pwd-bar level-' + level;
    }

    if (listEl) {
        listEl.innerHTML = OMNI_PWD_RULES.map(r => {
            const ok = r.test(pw);
            return `<li class="pwd-rule${ok ? ' ok' : ''}">
                        <span class="pwd-rule-mark">${ok ? '✓' : '○'}</span>${r[lang]}
                    </li>`;
        }).join('');
    }
}

/** Wires an input to its bar and rule list, and paints the initial state. */
function omniPwdWatch(inputEl, barEl, listEl) {
    if (!inputEl) return;
    const paint = () => omniPwdRender(inputEl.value, barEl, listEl);
    inputEl.addEventListener('input', paint);
    paint();
    return paint;
}

// ════════════════════════════════════════════════════════════════════
// SHOW / HIDE
//
// Typing a password you cannot see is where most "wrong password" attempts
// actually come from, and a confirm field only compounds it. Every password
// input on the page gets an eye, added from here so the three pages do not each
// grow their own version of it.
// ════════════════════════════════════════════════════════════════════

const OMNI_EYE_SHOW = '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" '
    + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    + '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';

const OMNI_EYE_HIDE = '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" '
    + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    + '<path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>'
    + '<path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>'
    + '<path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

const OMNI_EYE_TITLE = {
    en: { show: 'Show password', hide: 'Hide password' },
    it: { show: 'Mostra password', hide: 'Nascondi password' }
};

/** Adds the eye to one input, unless it already has one. */
function omniPwdReveal(input) {
    if (!input || input.dataset.revealed === '1') return;
    input.dataset.revealed = '1';

    // Wrapped rather than positioned against whatever container happens to be
    // there: the three pages lay these fields out differently, and the wrapper
    // takes the input's own place in the flow
    const wrap = document.createElement('span');
    wrap.className = 'pwd-reveal-wrap';
    input.parentNode.insertBefore(wrap, input);
    wrap.appendChild(input);

    const btn = document.createElement('button');
    // Never "submit": these fields sit inside forms that would post on click
    btn.type = 'button';
    btn.className = 'pwd-reveal-btn';
    btn.tabIndex = -1;

    const lang = omniPwdLang();
    const paint = () => {
        const shown = input.type === 'text';
        btn.innerHTML = shown ? OMNI_EYE_HIDE : OMNI_EYE_SHOW;
        const label = shown ? OMNI_EYE_TITLE[lang].hide : OMNI_EYE_TITLE[lang].show;
        btn.title = label;
        btn.setAttribute('aria-label', label);
    };

    btn.addEventListener('click', () => {
        input.type = input.type === 'password' ? 'text' : 'password';
        paint();
        input.focus();
    });

    paint();
    wrap.appendChild(btn);
}

/** Decorates every password field on the page. */
function omniPwdRevealAll(root) {
    (root || document).querySelectorAll('input[type="password"]').forEach(omniPwdReveal);
}

document.addEventListener('DOMContentLoaded', () => omniPwdRevealAll());
