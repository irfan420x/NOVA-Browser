// ==UserScript==
// @name        Cookie Banner Hider
// @version     1.0
// @description Hides cookie-consent banners and overlays on the sites it matches.
// @match       *://*/*
// @run-at      document-end
// ==/UserScript==
/* NOVA Browser — Cookie Banner Hider (built-in extension, v2.0)
 *
 * What it does: hides the cookie-consent banners, walls and overlays that
 * cover the bottom (or the whole screen) of many sites, so the page behind
 * them is readable without tapping anything.
 *
 * What it deliberately does NOT do: it never clicks "Accept" / "Accept all"
 * — clicking accept would set tracking cookies on the user's behalf. The
 * banner is only hidden, never answered. If a site truly blocks content
 * behind its consent wall, the wall stays hidden but the content stays
 * blocked; the user can disable this script per site in Extensions.
 *
 * Approach — hide, don't break: a <style> rule hides the known banner
 * selectors, and a MutationObserver hides banners that appear late
 * (consent walls often render seconds after load). Elements are hidden
 * with CSS (display:none) rather than removed where possible, so a page
 * that re-checks its banner does not crash; the strongest offenders are
 * removed outright. Everything is defensive and fail-OPEN: any error
 * leaves the page exactly as it was.
 *
 * Idempotent: window.__novaCookieHider guards against double install when
 * the script is re-injected on SPA navigations.
 */
(function () {
    'use strict';
    if (window.__novaCookieHider) return;
    window.__novaCookieHider = true;

    /* Common cookie-banner selectors (class/id/attribute patterns seen in
     * the wild, late 2026). Kept to consent UI only — nothing here matches
     * a site's own navigation, login box or paywall. */
    var HIDE_SELECTORS = [
        '#cookie-banner',
        '#cookieBanner',
        '#cookie-consent',
        '#cookieConsent',
        '#cookie-notice',
        '#cookieNotice',
        '#gdpr-banner',
        '#gdpr-cookie',
        '#consent-banner',
        '#consentBanner',
        '#privacy-banner',
        '#cookie-law',
        '#cookiebar',
        '#cookie-bar',
        '.cookie-banner',
        '.cookieBanner',
        '.cookie-consent',
        '.cookieConsent',
        '.cookie-notice',
        '.cookieNotice',
        '.gdpr-banner',
        '.gdpr-cookie',
        '.consent-banner',
        '.consentBanner',
        '.privacy-banner',
        '.cookie-law',
        '.cookiebar',
        '.cookie-bar',
        '.cookie-notification',
        '.cookie-alert',
        '.cookie-warning',
        '.cookie-modal',
        '.cookie-overlay',
        '.cookie-dialog',
        '.cookies-banner',
        '.cookies-consent',
        '.cookies-notice',
        '[id*="cookie-consent"]',
        '[id*="cookieConsent"]',
        '[id*="cookie-banner"]',
        '[id*="cookieBanner"]',
        '[id*="cookie-notice"]',
        '[class*="cookie-consent"]',
        '[class*="cookieConsent"]',
        '[class*="cookie-banner"]',
        '[class*="cookieBanner"]',
        '[aria-label*="ookie consent" i]',
        '[aria-label*="ookie banner" i]'
        // NOTE (N-11): no `[role="dialog"][aria-label*="ookie" i]` — it
        // matched any dialog whose label contains "ookie" ("Rookie",
        // "Bookie"). No #cookie-policy / .cookie-policy selectors either —
        // those also match a site's cookie-policy CONTENT, not just its
        // banner.
    ];

    /* Full-screen consent walls from the big consent-management platforms.
     * These are removed (not just hidden): they cover the viewport and
     * usually lock scrolling, and hiding alone leaves the scroll lock.
     * EXCEPT #CookiebotWidget and .cc-revoke (N-11): those are the controls
     * for RE-OPENING cookie settings — removing them takes away the user's
     * way to change consent, so they are hidden, not removed (they live in
     * HIDE_EXTRA_SELECTORS below). */
    var REMOVE_SELECTORS = [
        '#onetrust-consent-sdk',
        '#onetrust-banner-sdk',
        '.onetrust-pc-dark-filter',
        '#didomi-host',
        '#didomi-popup',
        '.didomi-popup-container',
        '#quantcast-choice',
        '.qc-cmp2-container',
        '#CybotCookiebotDialog',
        '.cc-window'
    ];

    /* Consent re-open controls: hidden, never removed (N-11). */
    var HIDE_EXTRA_SELECTORS = [
        '#CookiebotWidget',
        '.cc-revoke'
    ];

    function installCss() {
        try {
            if (document.getElementById('nova-cookie-hider-css')) return;
            var css = HIDE_SELECTORS.concat(HIDE_EXTRA_SELECTORS).join(',') + '{display:none!important}';
            var s = document.createElement('style');
            s.id = 'nova-cookie-hider-css';
            s.textContent = css;
            (document.head || document.documentElement).appendChild(s);
        } catch (e) { /* page keeps working without the CSS */ }
    }

    function hideList(selectors) {
        for (var i = 0; i < selectors.length; i++) {
            try {
                var found = document.querySelectorAll(selectors[i]);
                for (var j = 0; j < found.length; j++) {
                    try { found[j].style.setProperty('display', 'none', 'important'); } catch (e) {}
                }
            } catch (e) { /* one bad selector must not stop the rest */ }
        }
    }

    function hideBanners() {
        try {
            hideList(HIDE_SELECTORS);
            hideList(HIDE_EXTRA_SELECTORS);
            for (var i = 0; i < REMOVE_SELECTORS.length; i++) {
                try {
                    var doomed = document.querySelectorAll(REMOVE_SELECTORS[i]);
                    for (var k = 0; k < doomed.length; k++) {
                        try { doomed[k].remove(); } catch (e) {}
                    }
                } catch (e) { /* same */ }
            }
        } catch (e) { /* fail open — the banner simply stays visible */ }
    }

    function start() {
        try {
            installCss();
            hideBanners();
            /* Consent walls often render late (after a CMP script loads),
             * so watch for new nodes and re-hide. The observer disconnects
             * itself after 30 seconds — banners that late are rare, and a
             * permanent observer on every page is wasted work.
             * B-22: the callback is debounced (one hide pass per 250 ms
             * at most) — the old version ran ~50 querySelectorAlls on
             * EVERY DOM mutation, a real cost on dynamic pages. */
            var hideScheduled = false;
            function scheduleHide() {
                if (hideScheduled) return;
                hideScheduled = true;
                setTimeout(function () { hideScheduled = false; hideBanners(); }, 250);
            }
            var observer = new MutationObserver(function () { scheduleHide(); });
            var target = document.body || document.documentElement;
            if (target && observer.observe) {
                observer.observe(target, { childList: true, subtree: true });
                setTimeout(function () {
                    try { observer.disconnect(); } catch (e) {}
                }, 30000);
            }
            /* One last pass for banners injected after the first paint. */
            setTimeout(hideBanners, 1500);
        } catch (e) { /* the page loads on without the hider */ }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
