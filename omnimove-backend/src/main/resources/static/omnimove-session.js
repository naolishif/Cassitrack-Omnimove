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
    var ME_URL     = '/omnimove/api/v1/traveller/me';
    var USER_KEY   = 'omnimove_user';
    var LANG_KEY   = 'omnimove_lang';
    // Set for the duration of one recovery attempt so a server that answers 200
    // with nothing usable cannot put the page into a reload loop.
    var RECOVER_KEY = 'omnimove_recovering';

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

    /**
     * Called by a route guard that found no user in sessionStorage.
     *
     * An empty sessionStorage is NOT the same as a dead session. sessionStorage
     * belongs to one tab, and a tab opened through rel="noopener" — which every
     * link to the privacy notice and the cookie policy uses — does not inherit
     * it. Neither does a bookmark, nor a pasted address. The guard treated all
     * of those as signed out and bounced them to the sign-in page, session and
     * all: that is why "← Torna a OMNIMOVE" appeared to fail even once the link
     * itself pointed at the right page.
     *
     * The JWT is in an httpOnly cookie, which the browser sends from any tab, so
     * the server can still say who this is. Ask it once: if it answers, refill
     * sessionStorage and reload so the page starts over with everything it
     * expects; if it does not, this really is a signed-out visitor.
     */
    function recoverUser(loginPage) {
        var alreadyTried = false;
        try { alreadyTried = sessionStorage.getItem(RECOVER_KEY) === '1'; } catch (e) { /* blocked */ }
        if (alreadyTried) { bounceToLogin(loginPage); return; }
        try { sessionStorage.setItem(RECOVER_KEY, '1'); } catch (e) { /* blocked */ }

        // Blank the shell while asking: a signed-out visitor must not see the app
        var root = document.documentElement;
        root.style.visibility = 'hidden';

        fetch(ME_URL, { credentials: 'same-origin' })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (me) {
                if (!me || (!me.name && !me.email)) { bounceToLogin(loginPage); return; }
                try { sessionStorage.setItem(USER_KEY, JSON.stringify(me)); }
                catch (e) { bounceToLogin(loginPage); return; }   // no storage, no app
                location.reload();
            })
            .catch(function () { bounceToLogin(loginPage); });
    }

    function bounceToLogin(loginPage) {
        document.documentElement.style.visibility = '';
        location.replace(loginUrlWithReturn(loginPage));
    }

    /** Called once a guard has passed, so the next empty tab gets its own attempt. */
    function recoveryDone() {
        try { sessionStorage.removeItem(RECOVER_KEY); } catch (e) { /* blocked */ }
    }

    // True from the moment a 401 has been seen. The page is on its way to the
    // sign-in screen at that point, so every request still in flight is going to
    // fail too — and each of those failures was being reported as a fault in
    // whatever feature made it. "[BIKE] fetch failed: Error: 401" reads like a
    // broken bike layer; it is just a session that ended.
    var sessionOver = false;
    function isSessionOver() { return sessionOver; }

    // Any 401 means the session died server-side (expired, blacklisted, or
    // revoked from another tab): stop showing a logged-in UI backed by nothing.
    function handleUnauthorized(response, loginPage) {
        if (response && response.status === 401) {
            if (sessionOver) return response;   // one redirect is enough
            sessionOver = true;
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
        recoverUser: recoverUser,
        recoveryDone: recoveryDone,
        handleUnauthorized: handleUnauthorized,
        isSessionOver: isSessionOver
    };
})(window);
