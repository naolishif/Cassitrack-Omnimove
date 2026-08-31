/* ═══════════════════════════════════════════════════════════════════════
   OMNIMOVE — cookie notice and consent management
   Titolare del trattamento: Università degli Studi di Cassino e del Lazio
   Meridionale.

   WHY THIS FILE LOOKS THE WAY IT DOES
   ───────────────────────────────────
   Under art. 122 Codice Privacy and the Garante's "Linee guida cookie e altri
   strumenti di tracciamento" (10 June 2021), consent is required for anything
   stored on or read from the user's device EXCEPT what is strictly necessary to
   deliver the service the user asked for.

   Everything OMNIMOVE currently stores is strictly necessary:

     omnimove_jwt          httpOnly session cookie  — authentication
     omnimove_user         sessionStorage           — display name in the UI
     omnimove_lang         localStorage             — language the user picked
     omnimove_consent      sessionStorage           — this file's own record,
                                                      anonymous visitors only

   No analytics, no advertising, no cross-site tracking. Fonts and libraries are
   self-hosted (see /vendor), so no third-party server sees the user's IP.

   => There is nothing to refuse, so a blocking "Accept / Reject" banner would be
      wrong: it would ask for consent that is not legally required and train users
      to click through. What we show instead is a short NOTICE (mode "notice").

   WHEN IT IS SHOWN
   ────────────────
   On the first page anyone opens, signed in or not — the notice is owed to a
   visitor whether or not they ever create an account. Who answers "has this
   already been acknowledged?" depends on who is asking:

     signed in   the consent ledger, under COOKIE_NOTICE, keyed to the account.
                 That is the durable record: it follows the person to another
                 device and survives clearing site data, and it is what the
                 operator's user card reads.

     anonymous   sessionStorage, for this tab's session only. Nothing is sent to
                 the server and no ledger row is created, because a row about
                 somebody we cannot identify proves nothing under art. 7(1) and
                 would sit there with an IP address and no way to erase it.
                 sessionStorage, not localStorage, for the same reason: a
                 persistent random id on the device of a visitor who has consented
                 to nothing is exactly what art. 122 is about.

   The bridge between the two is registration. A visitor who acknowledges the
   notice and then signs up successfully carries that acknowledgement into the
   ledger with the sign-up request, so the banner does not reappear at their
   first sign-in. If they never register, or registration fails, it stays in the
   tab and dies with it.

   COOKIE_NOTICE is deliberately not PRIVACY_NOTICE: registration records the
   latter as soon as the account exists, so a banner keyed off it would never
   appear at all.

   If anyone ever reintroduces a CDN, an embedded map key, analytics or any other
   non-technical third party, flip THIRD_PARTY_ASSETS to true. The banner then
   becomes a real consent gate: equal-prominence Accept/Reject, nothing loaded
   before a choice, choice revocable, no re-prompt for 6 months after a refusal.
   ═══════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';

    // Keep in sync with omnimove.privacy.policy-version in application.yml and
    // with the "Ultimo aggiornamento" date in privacy.html / cookie-policy.html.
    const POLICY_VERSION = '2026-08-31';

    // Turn to true only if a non-technical third-party resource is reintroduced.
    const THIRD_PARTY_ASSETS = false;

    // Third-party assets to load *only after* consent, used when the flag above
    // is true. Left empty because everything is currently self-hosted.
    const GATED_ASSETS = [
        // { type: 'script', src: 'https://…', integrity: 'sha256-…' },
        // { type: 'style',  href: 'https://…' },
    ];

    const STORE_KEY = 'omnimove_consent';

    // The app is served under a context path (/omnimove by default, overridable
    // with SERVER_SERVLET_CONTEXT_PATH). Derive it from this script's own URL
    // instead of hardcoding it, so the banner keeps working if it changes.
    const BASE = (function () {
        const self = document.currentScript;
        if (!self || !self.src) return '/omnimove';
        return new URL('.', self.src).pathname.replace(/\/$/, '');
    })();
    const API = BASE + '/api/v1/privacy/consents';
    const TYPE_COOKIE_NOTICE = 'COOKIE_NOTICE';
    // Garante: after a refusal, do not ask again for at least six months.
    const REPROMPT_AFTER_MS = 183 * 24 * 60 * 60 * 1000;

    // ── stored record ───────────────────────────────────────────────────
    // { v: POLICY_VERSION, ts: epochMs, subjectKey: string,
    //   notice: true, thirdParty: true|false|null }
    //
    // Kept for the subjectKey and as a local echo of the decision. It is NOT
    // what decides whether the banner appears any more — the server is.

    // sessionStorage, deliberately: see the note at the top of this file. An
    // anonymous acknowledgement lives for this tab and no longer.
    function read() {
        try {
            const raw = sessionStorage.getItem(STORE_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;   // private mode or corrupted value — behave as first visit
        }
    }

    function write(rec) {
        try { sessionStorage.setItem(STORE_KEY, JSON.stringify(rec)); } catch (e) { /* ignore */ }
    }

    function newSubjectKey() {
        const bytes = new Uint8Array(16);
        crypto.getRandomValues(bytes);
        return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
    }

    function subjectKey() {
        const rec = read();
        if (rec && rec.subjectKey) return rec.subjectKey;
        return newSubjectKey();
    }

    /** Mirrors the decision to the server so it is provable (GDPR art. 7(1)). */
    function report(type, granted, key) {
        return fetch(API, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({ type: type, granted: granted, subjectKey: key })
        }).catch(function () {
            // A banner must never block the page because the API is down.
        });
    }

    /**
     * Who is asking, resolved once and remembered for the page.
     *
     * The endpoint answers 200 with this account's consent state, or 401 when
     * nobody is signed in — which is the signal, not an error. Cached because
     * both the show decision and the save path need it, and one round trip is
     * enough. A network failure resolves to "anonymous": the local record then
     * decides, which errs towards showing the notice rather than swallowing it.
     */
    let _whoPromise = null;
    function who() {
        if (_whoPromise) return _whoPromise;
        _whoPromise = fetch(API, { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (data) {
                return (data && data.consents)
                    ? { signedIn: true, consents: data.consents }
                    : { signedIn: false, consents: null };
            })
            .catch(function () { return { signedIn: false, consents: null }; });
        return _whoPromise;
    }

    /** Has the local, anonymous record already answered for the current text? */
    function localSaysSeen() {
        const rec = read();
        if (!rec || rec.v !== POLICY_VERSION) return false;
        if (!THIRD_PARTY_ASSETS) return true;
        if (rec.thirdParty === true) return true;
        // Consent mode: a refusal is not re-asked for six months.
        return (Date.now() - (rec.ts || 0)) <= REPROMPT_AFTER_MS;
    }

    /**
     * Files an acknowledgement already given in this tab against the account that
     * has just signed in.
     *
     * Someone who read and dismissed the notice before signing in has genuinely
     * been shown it; it simply had no account to belong to at the time. Signing
     * in — rather than registering — used to lose that, and the same question was
     * put to them a second time. Only reached when the ledger holds nothing
     * current for this account, so it never writes a duplicate over an
     * acknowledgement that is already there.
     */
    function adoptLocalRecord() {
        const rec = read();
        if (!rec) return;
        report(TYPE_COOKIE_NOTICE, true, rec.subjectKey);
        if (THIRD_PARTY_ASSETS && rec.thirdParty !== null)
            report('THIRD_PARTY_CONTENT', rec.thirdParty === true, rec.subjectKey);
    }

    // ── decide whether to show anything ─────────────────────────────────
    function needsPrompt() {
        return who().then(function (me) {
            if (!me.signedIn) return !localSaysSeen();

            // currentStateFor() already reports an acknowledgement given under a
            // superseded policy version as false, so a reworded notice raises the
            // banner again without any check here.
            if (me.consents[TYPE_COOKIE_NOTICE] === true) return false;

            // Nothing on the account, but this visitor already took note in this
            // tab before signing in. Adopt it instead of asking twice.
            if (localSaysSeen()) { adoptLocalRecord(); return false; }

            return true;
        });
    }

    // ── gated third-party loading ───────────────────────────────────────
    function loadGatedAssets() {
        GATED_ASSETS.forEach(function (a) {
            let el;
            if (a.type === 'script') {
                el = document.createElement('script');
                el.src = a.src;
                el.async = false;
            } else {
                el = document.createElement('link');
                el.rel = 'stylesheet';
                el.href = a.href;
            }
            if (a.integrity) { el.integrity = a.integrity; el.crossOrigin = 'anonymous'; }
            document.head.appendChild(el);
        });
    }

    // ── persistence of a decision ───────────────────────────────────────
    function save(thirdParty) {
        const key = subjectKey();
        write({
            v: POLICY_VERSION,
            ts: Date.now(),
            subjectKey: key,
            notice: true,
            thirdParty: THIRD_PARTY_ASSETS ? thirdParty : null
        });

        if (thirdParty === true) loadGatedAssets();

        // Only a signed-in decision reaches the ledger. An anonymous one stays in
        // the line above and nowhere else: a row about someone we cannot identify
        // proves nothing under art. 7(1), and it would carry that visitor's IP
        // address with no account to erase it from and no purge job to reach it.
        // If they go on to register, the sign-up request carries this
        // acknowledgement and the server writes it then — see RegisterRequest.
        //
        // COOKIE_NOTICE, not PRIVACY_NOTICE: the sign-up form records the latter,
        // and re-reporting it here would say nothing new.
        return who().then(function (me) {
            if (!me.signedIn) return;
            report(TYPE_COOKIE_NOTICE, true, key);
            if (THIRD_PARTY_ASSETS) report('THIRD_PARTY_CONTENT', thirdParty === true, key);
        });
    }

    // ── i18n ────────────────────────────────────────────────────────────
    // The strings live here rather than in omnimove-i18n.js on purpose: the
    // banner must render even on a page that does not load the i18n bundle.
    function isItalian() {
        try { return (localStorage.getItem('omnimove_lang') || 'en') === 'it'; }
        catch (e) { return false; }
    }

    const TEXT = {
        en: {
            noticeTitle: 'Cookies on OMNIMOVE',
            noticeBody:  'We only use technical cookies needed to keep you signed in and to ' +
                         'remember your language. No profiling, no advertising, no third-party ' +
                         'tracking. Fonts and map libraries are served from our own servers.',
            consentBody: 'We use technical cookies, which are always active. We would also like ' +
                         'to load content from third parties, which requires your consent. You ' +
                         'can change your choice at any time.',
            gotIt:  'Got it',
            accept: 'Accept',
            reject: 'Reject',
            policy: 'Cookie policy',
            privacy:'Privacy notice'
        },
        it: {
            noticeTitle: 'Cookie su OMNIMOVE',
            noticeBody:  'Utilizziamo solo cookie tecnici, necessari a mantenere attiva la ' +
                         'sessione e a ricordare la lingua scelta. Nessuna profilazione, nessuna ' +
                         'pubblicità, nessun tracciamento di terze parti. I font e le librerie ' +
                         'della mappa sono ospitati sui nostri server.',
            consentBody: 'Utilizziamo cookie tecnici, sempre attivi. Vorremmo inoltre caricare ' +
                         'contenuti di terze parti, per i quali è necessario il tuo consenso. ' +
                         'Puoi modificare la scelta in qualsiasi momento.',
            gotIt:  'Ho capito',
            accept: 'Accetta',
            reject: 'Rifiuta',
            policy: 'Cookie policy',
            privacy:'Informativa privacy'
        }
    };

    // ── banner ──────────────────────────────────────────────────────────
    function render() {
        if (document.getElementById('omniConsent')) return;
        const L = TEXT[isItalian() ? 'it' : 'en'];

        const box = document.createElement('div');
        box.id = 'omniConsent';
        box.className = 'omni-consent';
        box.setAttribute('role', 'dialog');
        box.setAttribute('aria-live', 'polite');
        box.setAttribute('aria-label', L.noticeTitle);

        const buttons = THIRD_PARTY_ASSETS
            // Equal prominence is mandatory: same class, same size, same weight.
            ? '<button type="button" class="omni-consent-btn" data-act="accept">' + L.accept + '</button>' +
              '<button type="button" class="omni-consent-btn" data-act="reject">' + L.reject + '</button>'
            : '<button type="button" class="omni-consent-btn" data-act="ok">' + L.gotIt + '</button>';

        box.innerHTML =
            '<div class="omni-consent-text">' +
                '<strong>' + L.noticeTitle + '</strong>' +
                '<p>' + (THIRD_PARTY_ASSETS ? L.consentBody : L.noticeBody) + '</p>' +
                '<p class="omni-consent-links">' +
                    '<a href="cookie-policy.html">' + L.policy + '</a>' +
                    '<a href="privacy.html">' + L.privacy + '</a>' +
                '</p>' +
            '</div>' +
            '<div class="omni-consent-actions">' + buttons + '</div>';

        box.addEventListener('click', function (ev) {
            const act = ev.target && ev.target.getAttribute('data-act');
            if (!act) return;
            // In consent mode, closing without choosing is NOT consent — only an
            // explicit "accept" grants it.
            save(act === 'accept');
            box.remove();
        });

        document.body.appendChild(box);
    }

    // ── public API ──────────────────────────────────────────────────────
    window.OmniConsent = {
        /** Reopen on demand — the "Cookie preferences" link in every footer. */
        open: function () {
            const existing = document.getElementById('omniConsent');
            if (existing) existing.remove();
            render();
        },

        /**
         * Show it only if this account has not acknowledged the current notice.
         *
         * Called by the app at start-up, and by nothing else: the banner is no
         * longer raised by merely loading a page. An anonymous visitor on the
         * sign-in page or reading the privacy notice is not asked, because there
         * is no account to record the answer against — and a record that cannot
         * be tied to anyone proves nothing under art. 7(1).
         */
        showIfNeeded: function () {
            return needsPrompt().then(function (yes) { if (yes) render(); return yes; });
        },

        state: read,
        policyVersion: POLICY_VERSION
    };

    // ── "← Torna a OMNIMOVE" on the legal pages ─────────────────────────
    // The link was hardcoded to omnimove-login.html, so a signed-in reader who
    // opened the notice was dropped back onto the sign-in screen.

    const APP_PAGE   = 'omnimove-traveller.html';
    const LOGIN_PAGE = 'omnimove-login.html';

    /** The API root, derived from where this page sits, so a change of context
     *  path does not need editing here as well. /omnimove/privacy.html →
     *  /omnimove/api/v1. */
    function apiRoot() {
        return location.pathname.replace(/\/[^/]*$/, '') + '/api/v1';
    }

    /**
     * Where the reader came from, when that is somewhere worth going back to.
     * Every link into these pages is same-origin and uses rel="noopener", which
     * — unlike noreferrer — leaves the referrer intact, and no Referrer-Policy
     * header narrows it.
     */
    function referrerTarget() {
        if (!document.referrer) return null;
        try {
            const u = new URL(document.referrer, location.href);
            // Same origin only. A referrer is not ours to trust in general, and
            // following one off-site would make this an open redirect.
            if (u.origin !== location.origin) return null;
            // Arriving from the other legal page would just bounce between the two
            if (/\/(privacy|cookie-policy)\.html$/.test(u.pathname)) return null;
            return u.pathname + u.search;
        } catch (e) {
            return null;   // malformed referrer
        }
    }

    function wireBackLink() {
        const links = document.querySelectorAll('[data-omni-back]');
        if (!links.length) return;
        const set = href => links.forEach(a => a.setAttribute('href', href));

        const fromReferrer = referrerTarget();
        if (fromReferrer) {
            set(fromReferrer);
            // These pages open in the same tab, so the page they came from is one
            // step back in this tab's history. Going back RESTORES it — a
            // half-typed registration form keeps its fields, a scrolled page its
            // position — where following the href would reload it blank. That
            // matters most on the sign-up form, which is exactly where the
            // privacy notice is most often opened.
            //
            // The href stays as computed, so a missing history entry, "copy link
            // address" and ctrl/cmd-click all still do the sensible thing.
            if (history.length > 1) {
                links.forEach(function (a) {
                    a.addEventListener('click', function (ev) {
                        // Let the browser handle any click that asks for a new
                        // tab or window; only plain left-click goes back.
                        if (ev.button !== 0 || ev.ctrlKey || ev.metaKey
                                || ev.shiftKey || ev.altKey) return;
                        ev.preventDefault();
                        history.back();
                    });
                });
            }
            return;
        }

        // No usable referrer: a fresh tab, or the address typed in. sessionStorage
        // cannot answer whether this person is signed in — it is per-tab, and a tab
        // opened this way has none, which is exactly how this link kept landing on
        // sign-in for someone who was signed in the whole time. The session rides
        // on an httpOnly same-origin cookie, so the browser sends it from any tab
        // and the server is the only one that can answer. Guess first so the link
        // is never dead, then correct it when the answer arrives.
        let guess = LOGIN_PAGE;
        try {
            if (JSON.parse(sessionStorage.getItem('omnimove_user') || '{}').email)
                guess = APP_PAGE;
        } catch (e) { /* unreadable storage: the login guess stands */ }
        set(guess);

        fetch(apiRoot() + '/traveller/me', { credentials: 'same-origin' })
            .then(r => set(r.ok ? APP_PAGE : LOGIN_PAGE))
            .catch(() => { /* offline or blocked: the guess stands */ });
    }

    function start() {
        wireBackLink();
        const rec = read();
        // Re-apply a previously granted third-party consent on every page load.
        if (THIRD_PARTY_ASSETS && rec && rec.v === POLICY_VERSION && rec.thirdParty === true)
            loadGatedAssets();

        // Shown on whatever page is opened first, signed in or not. showIfNeeded
        // works out which of the two records answers for this visitor, so the
        // same call is right on the sign-in page, the legal pages, the admin
        // console and the app.
        window.OmniConsent.showIfNeeded();
    }

    if (document.readyState === 'loading')
        document.addEventListener('DOMContentLoaded', start);
    else
        start();
})();
