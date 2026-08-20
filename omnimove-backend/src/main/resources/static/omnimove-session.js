// ─── OmniMove session teardown ──────────────────────────────────
// Single place that ends a session, shared by every authenticated page so the
// traveller app and the admin console cannot drift apart.
//
// V-04 recap: the JWT lives in an httpOnly cookie that JavaScript cannot read
// or delete. Only the server can clear it, so a logout that never reaches
// POST /auth/logout leaves the session alive even if the browser looks clean.

(function (global) {
    'use strict';

    var LOGOUT_URL = '/omnimove/api/v1/auth/logout';
    var USER_KEY   = 'omnimove_user';
    var LANG_KEY   = 'omnimove_lang';

    // Wipes every trace of the session from this browser.
    function clearClientSession() {
        var lang = null;
        try { lang = localStorage.getItem(LANG_KEY); } catch (e) { /* storage blocked */ }
        try { localStorage.clear(); }   catch (e) { /* storage blocked */ }
        try { sessionStorage.clear(); } catch (e) { /* storage blocked */ }
        // The language pick is a UI preference, not session data: it survives logout
        try { if (lang) localStorage.setItem(LANG_KEY, lang); } catch (e) { /* storage blocked */ }
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

    // Ends the session server-side (token blacklisted + cookie expired), then
    // locally. The redirect uses replace() so the app page leaves the history
    // stack and Back cannot walk into it.
    // No ?next= here on purpose: someone who chose to log out should land on their
    // home page next time, not be dragged back to the screen they left.
    function endSession(loginPage) {
        // Same-origin: the httpOnly cookie rides along automatically
        return fetch(LOGOUT_URL, { method: 'POST', credentials: 'same-origin' })
            .catch(function () { /* offline: still clear locally, never trap the user */ })
            .then(function () {
                clearClientSession();
                location.replace(loginPage);
            });
    }

    // When a route guard bounces someone to the login page, carry the page they
    // were on in ?next= — same contract the server-side entry point uses.
    function loginUrlWithReturn(loginPage) {
        var page = location.pathname.split('/').pop();
        if (!page || page === loginPage) return loginPage;
        return loginPage + '?next=' + encodeURIComponent(page);
    }

    // A page restored from the back/forward cache does not re-run its route
    // guard, so Back after a logout would otherwise show the app again.
    function bindSessionGuard(loginPage) {
        window.addEventListener('pageshow', function (e) {
            if (!e.persisted) return;
            if (!sessionStorage.getItem(USER_KEY)) location.replace(loginUrlWithReturn(loginPage));
        });
    }

    // Any 401 means the session died server-side (expired, blacklisted, or
    // revoked from another tab): stop showing a logged-in UI backed by nothing.
    function handleUnauthorized(response, loginPage) {
        if (response && response.status === 401) {
            clearClientSession();
            location.replace(loginUrlWithReturn(loginPage));
        }
        return response;
    }

    global.OmniSession = {
        clearClientSession: clearClientSession,
        endSession: endSession,
        loginUrlWithReturn: loginUrlWithReturn,
        bindSessionGuard: bindSessionGuard,
        handleUnauthorized: handleUnauthorized
    };
})(window);
