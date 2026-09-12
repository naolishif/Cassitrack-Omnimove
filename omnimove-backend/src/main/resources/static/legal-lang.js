/**
 * Language switch for the standalone legal documents.
 *
 * The notice and the cookie policy are not part of the application shell: they
 * are opened from the sign-in page, from inside the app, and from the consent
 * banner, and they have to be readable by someone who does not speak Italian.
 * So each one carries BOTH texts and shows one of them.
 *
 * WHY NOT omnimove-i18n.js. That file is a thousand lines of interface strings
 * for an application these pages are not part of, and a legal text cannot be cut
 * into translation keys without becoming impossible to proofread as a document —
 * which is the one thing a privacy notice has to remain. The pages therefore keep
 * their two texts whole, side by side, and this picks between them.
 *
 * The three lines that duplicate i18n.js are the storage key and the fallback
 * rule. They are duplicated deliberately and must stay in step: read the same
 * key, apply the same rule, and a reader who set English in the app finds English
 * here without asking twice.
 */
(function () {
    'use strict';

    /** The same key omnimove-i18n.js uses, with the same fallback to the browser. */
    var KEY = 'omnimove_lang';

    function current() {
        var stored = null;
        try { stored = localStorage.getItem(KEY); } catch (e) { /* private mode */ }
        if (stored === 'it' || stored === 'en') return stored;
        var nav = (navigator.language || '').toLowerCase();
        return nav.indexOf('it') === 0 ? 'it' : 'en';
    }

    /**
     * Shows one language and hides the other, and tells the rest of the page what
     * it is now in: `lang` on <html> so screen readers switch voice and browsers
     * offer the right translation, and the title so the tab and any bookmark read
     * in the language the reader chose.
     */
    function render(lang) {
        var blocks = document.querySelectorAll('[data-doc-lang]');
        for (var i = 0; i < blocks.length; i++) {
            blocks[i].hidden = blocks[i].getAttribute('data-doc-lang') !== lang;
        }
        document.documentElement.lang = lang;

        var t = document.querySelector('[data-doc-title-' + lang + ']');
        if (t) document.title = t.getAttribute('data-doc-title-' + lang);

        var buttons = document.querySelectorAll('[data-set-lang]');
        for (var j = 0; j < buttons.length; j++) {
            var on = buttons[j].getAttribute('data-set-lang') === lang;
            buttons[j].classList.toggle('is-on', on);
            buttons[j].setAttribute('aria-pressed', on ? 'true' : 'false');
        }
    }

    /**
     * Records the choice where the application will find it, and tells the server
     * so the e-mails sent later are written in it too.
     *
     * Nothing is awaited and nothing is reported: a reader who has just switched
     * language can already see the result, and the request only matters for
     * messages that may never be sent. A 401 is the ordinary answer here — these
     * pages are readable without signing in — and is discarded like any failure.
     */
    function choose(lang) {
        try { localStorage.setItem(KEY, lang); } catch (e) { /* private mode */ }
        render(lang);
        try {
            fetch('/omnimove/api/v1/auth/language', {
                method: 'PUT',
                credentials: 'same-origin',
                headers: { 'X-Omnimove-Lang': lang }
            }).catch(function () { /* signed out, offline, or gone */ });
        } catch (e) { /* fetch unavailable: the page still works */ }
    }

    function init() {
        var buttons = document.querySelectorAll('[data-set-lang]');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].addEventListener('click', function (ev) {
                choose(ev.currentTarget.getAttribute('data-set-lang'));
            });
        }
        render(current());
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
