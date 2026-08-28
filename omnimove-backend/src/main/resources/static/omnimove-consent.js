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
     omnimove_consent      localStorage             — this file's own record

   No analytics, no advertising, no cross-site tracking. Fonts and libraries are
   self-hosted (see /vendor), so no third-party server sees the user's IP.

   => There is nothing to refuse, so a blocking "Accept / Reject" banner would be
      wrong: it would ask for consent that is not legally required and train users
      to click through. What we show instead is a short NOTICE (mode "notice").

   If anyone ever reintroduces a CDN, an embedded map key, analytics or any other
   non-technical third party, flip THIRD_PARTY_ASSETS to true. The banner then
   becomes a real consent gate: equal-prominence Accept/Reject, nothing loaded
   before a choice, choice revocable, no re-prompt for 6 months after a refusal.
   ═══════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';

    // Keep in sync with omnimove.privacy.policy-version in application.yml and
    // with the "Ultimo aggiornamento" date in privacy.html / cookie-policy.html.
    const POLICY_VERSION = '2026-08-28';

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
    // Garante: after a refusal, do not ask again for at least six months.
    const REPROMPT_AFTER_MS = 183 * 24 * 60 * 60 * 1000;

    // ── stored record ───────────────────────────────────────────────────
    // { v: POLICY_VERSION, ts: epochMs, subjectKey: string,
    //   notice: true, thirdParty: true|false|null }

    function read() {
        try {
            const raw = localStorage.getItem(STORE_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch (e) {
            return null;   // private mode or corrupted value — behave as first visit
        }
    }

    function write(rec) {
        try { localStorage.setItem(STORE_KEY, JSON.stringify(rec)); } catch (e) { /* ignore */ }
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

    // ── decide whether to show anything ─────────────────────────────────
    function needsPrompt() {
        const rec = read();
        if (!rec) return true;
        if (rec.v !== POLICY_VERSION) return true;      // policy changed → ask again
        if (THIRD_PARTY_ASSETS) {
            if (rec.thirdParty === true) return false;
            // Refused (or never decided): re-ask only after the 6-month window.
            return (Date.now() - (rec.ts || 0)) > REPROMPT_AFTER_MS;
        }
        return false;
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

        report('PRIVACY_NOTICE', true, key);
        if (THIRD_PARTY_ASSETS) report('THIRD_PARTY_CONTENT', thirdParty === true, key);
        if (thirdParty === true) loadGatedAssets();
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

    // ── public API — used by the "Cookie preferences" footer link ────────
    window.OmniConsent = {
        open: function () {
            const existing = document.getElementById('omniConsent');
            if (existing) existing.remove();
            render();
        },
        state: read,
        policyVersion: POLICY_VERSION
    };

    function start() {
        const rec = read();
        // Re-apply a previously granted third-party consent on every page load.
        if (THIRD_PARTY_ASSETS && rec && rec.v === POLICY_VERSION && rec.thirdParty === true)
            loadGatedAssets();

        if (needsPrompt()) render();
    }

    if (document.readyState === 'loading')
        document.addEventListener('DOMContentLoaded', start);
    else
        start();
})();
