// ─── CASSITRACK session teardown ────────────────────────────────
// Single place that ends a session, shared by every authenticated page so the admin,
// analytics and fleet-manager consoles cannot drift apart.
//
// V-04 recap: the JWT lives in an httpOnly cookie that JavaScript cannot read or delete.
// Only the server can clear it, so a logout that never reaches POST /auth/logout leaves
// the session alive even if the browser looks clean.
//
// Note for anyone comparing with OmniMove: these pages keep nothing in web storage today.
// The wipe below is deliberately unconditional anyway — the day someone caches a user
// object here, logout must already be taking it with it.

(function (global) {
    'use strict';

    var API_BASE   = '/cassitrack/api/v1';
    var LOGOUT_URL = API_BASE + '/auth/logout';
    var LOGIN_PAGE = 'cassitrack-login.html';

    function clearClientSession() {
        try { localStorage.clear(); }   catch (e) { /* storage blocked */ }
        try { sessionStorage.clear(); } catch (e) { /* storage blocked */ }
        clearReadableCookies();
    }

    // Best effort on JS-visible cookies. The JWT is httpOnly and invisible here —
    // it is the server's Set-Cookie Max-Age=0 that actually kills it.
    function clearReadableCookies() {
        document.cookie.split(';').forEach(function (pair) {
            var name = pair.split('=')[0].trim();
            if (!name) return;
            document.cookie = name + '=; Max-Age=0; Path=/';
            document.cookie = name + '=; Max-Age=0; Path=' + location.pathname;
        });
    }

    // Ends the session server-side (token blacklisted + cookie expired), then locally.
    // replace() rather than href: the app page leaves the history stack, so Back cannot
    // walk back into a logged-out console.
    function endSession() {
        // Same-origin: the httpOnly cookie rides along automatically
        return fetch(LOGOUT_URL, { method: 'POST', credentials: 'same-origin' })
            .catch(function () { /* offline: still clear locally, never trap the user */ })
            .then(function () {
                clearClientSession();
                location.replace(LOGIN_PAGE);
            });
    }

    // Carry the page the user was on, so login can send them back to it
    function loginUrlWithReturn() {
        var page = location.pathname.split('/').pop();
        if (!page || page === LOGIN_PAGE) return LOGIN_PAGE;
        return LOGIN_PAGE + '?next=' + encodeURIComponent(page);
    }

    // A page restored from the back/forward cache runs no fresh request, so a session that
    // died in the meantime would go unnoticed. Unlike OmniMove there is no user object in
    // sessionStorage to test, so the check has to be the server's: reload, and let the
    // security entry point decide whether this still resolves to a page or to the login.
    function bindSessionGuard() {
        window.addEventListener('pageshow', function (e) {
            if (e.persisted) location.reload();
        });
    }

    // 48 fetch() call sites across these consoles, none of them sharing a helper. Rather
    // than touch every one, wrap fetch once: any same-origin 401 means the session is gone
    // (expired, blacklisted, or revoked from another tab), so stop rendering a logged-in UI
    // backed by nothing. The response is passed through untouched, so callers keep working.
    function installFetchGuard() {
        var nativeFetch = global.fetch.bind(global);

        global.fetch = function (input, init) {
            return nativeFetch(input, init).then(function (response) {
                var url = typeof input === 'string' ? input : (input && input.url) || '';
                var sameOrigin = url.indexOf('http') !== 0 || url.indexOf(location.origin) === 0;

                if (response.status === 401 && sameOrigin) {
                    clearClientSession();
                    location.replace(loginUrlWithReturn());
                }
                return response;
            });
        };
    }

    global.CassiSession = {
        clearClientSession: clearClientSession,
        endSession: endSession,
        loginUrlWithReturn: loginUrlWithReturn,
        bindSessionGuard: bindSessionGuard,
        installFetchGuard: installFetchGuard
    };
})(window);
