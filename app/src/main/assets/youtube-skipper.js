/* NOVA Browser — YouTube Ad Skipper (v1.2)
 *
 * Why this exists: YouTube serves video ads from googlevideo.com — the same
 * domain as the video itself — so a network filter cannot tell an ad segment
 * from a video segment. Blocking that domain would break every video. Brave
 * solves this with scriptlets (JS injected into the page); this is NOVA's
 * version of the same idea, as a content script.
 *
 * Approach — "let the ad start, then skip it instantly":
 *   - NEVER block the ad request. The ad loads and plays, so YouTube's
 *     ad-block detection (which watches for missing ad requests) sees
 *     nothing to complain about. The impression still counts.
 *   - While an ad is showing: mute it, play it at 16x, click the Skip
 *     button the moment it appears, and seek unskippable ads to their end.
 *   - When the real video resumes: restore mute state and playback rate.
 *   - Hide the ad chrome (overlays, companion/masthead ads, Premium bar)
 *     with CSS, and dismiss the Premium upsell / anti-adblock popups.
 *
 * Selectors are current as of late 2026 (GreasyFork "Auto Skip YouTube Ads"
 * 2026.02.18, Fadblock, Mute Tube, and the common Tampermonkey skippers):
 *   .ad-showing on #movie_player, .ytp-ad-player-overlay, .ytp-ad-text,
 *   .ytp-ad-skip-button / -modern, .ytp-skip-ad-button, .videoAdUiSkipButton,
 *   .ytp-ad-overlay-close-button, yt-mealbar-promo-renderer.
 * YouTube rotates class names, so every check below is defensive and
 * fail-OPEN: if a selector stops matching, the video just plays its ad
 * normally — playback is never broken by this script. Every call is
 * wrapped in try/catch for the same reason.
 *
 * Idempotent: window.__novaYtSkipper guards against double install when
 * the script is re-injected on SPA navigations.
 */
(function () {
    'use strict';
    if (window.__novaYtSkipper) return;
    window.__novaYtSkipper = true;

    var SKIP_BUTTON_SELECTORS = [
        '.ytp-ad-skip-button',
        '.ytp-ad-skip-button-modern',
        '.ytp-skip-ad-button',
        '.videoAdUiSkipButton',
        '.ytp-ad-overlay-close-button'
    ];

    /* Hide the ad chrome. The skip button itself lives inside .video-ads,
     * so that container is deliberately NOT hidden. */
    function installCss() {
        try {
            if (document.getElementById('nova-yt-skipper-css')) return;
            var css = [
                '.ytp-ad-player-overlay,.ytp-ad-overlay-container,',
                '.ytp-ad-overlay-image,.ytp-ad-text,',
                '.ytp-ad-preview-container{display:none!important}',
                'ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,',
                'ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,',
                'ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,',
                '#masthead-ad{display:none!important}',
                'yt-mealbar-promo-renderer{display:none!important}'
            ].join('');
            var s = document.createElement('style');
            s.id = 'nova-yt-skipper-css';
            s.textContent = css;
            (document.head || document.documentElement).appendChild(s);
        } catch (e) { /* page keeps working without the CSS */ }
    }

    function player() {
        try { return document.getElementById('movie_player'); } catch (e) { return null; }
    }

    function video() {
        try {
            return document.querySelector('video.html5-main-video') ||
                document.querySelector('video');
        } catch (e) { return null; }
    }

    /* True only while a video ad is actually playing. `.ad-showing` is the
     * canonical marker; the overlay / "Ad" label are the backstop for
     * players that have not (yet) set the class. */
    function isAdShowing() {
        try {
            var p = player();
            if (p && p.classList && p.classList.contains('ad-showing')) return true;
            if (document.querySelector('.ytp-ad-player-overlay')) return true;
            if (document.querySelector('.ytp-ad-text')) return true;
            return false;
        } catch (e) { return false; }
    }

    /* Best-effort Shorts ad: the active reel carries a "Sponsored" label.
     * Shorts ads have no skip button — seeking to the end moves on. */
    function isShortsAd() {
        try {
            if (location.pathname.indexOf('/shorts/') !== 0) return false;
            var reel = document.querySelector('ytd-reel-video-renderer[is-active]');
            if (!reel) return false;
            if (reel.querySelector('.ytp-ad-player-overlay,.ytp-ad-text')) return true;
            var labelled = reel.querySelector('[aria-label*="ponsored"]');
            return !!labelled;
        } catch (e) { return false; }
    }

    function clickSkipButtons() {
        try {
            for (var i = 0; i < SKIP_BUTTON_SELECTORS.length; i++) {
                var btn = document.querySelector(SKIP_BUTTON_SELECTORS[i]);
                if (btn) { try { btn.click(); } catch (e) {} }
            }
            /* Modern player: a Skip button inside the ad module with an
             * aria-label, and survey/poll overlays with their own close. */
            var labelled = document.querySelector(
                '.video-ads button[aria-label*="kip"],' +
                '.video-ads button[aria-label*="lose"]'
            );
            if (labelled) { try { labelled.click(); } catch (e) {} }
        } catch (e) { /* clicking is best-effort */ }
    }

    /* Dismiss the Premium upsell bar and the "Ad blockers are not allowed"
     * enforcement popup if either ever appears. Best effort only — with
     * this script's approach (ads play, then get skipped) YouTube should
     * rarely show the enforcement popup at all. */
    function dismissPopups() {
        try {
            var mealbar = document.querySelector('yt-mealbar-promo-renderer');
            if (mealbar) {
                var close = mealbar.querySelector(
                    '#dismiss-button,button[aria-label*="ismiss"],button[aria-label*="lose"]'
                );
                if (close) { try { close.click(); } catch (e) {} }
            }
            var enforcement = document.querySelector('ytd-enforcement-message-view-model');
            if (enforcement) {
                var dismiss = enforcement.querySelector(
                    '#dismiss-button,button[aria-label*="ismiss"],button[aria-label*="lose"]'
                );
                if (dismiss) { try { dismiss.click(); } catch (e) {} }
            }
        } catch (e) { /* popups are cosmetic */ }
    }

    /* State saved when an ad starts, restored when the real video resumes. */
    var adActive = false;
    var savedRate = 1;
    var wasMuted = false;

    function check() {
        try {
            installCss();
            var v = video();
            var ad = isAdShowing() || isShortsAd();
            if (!ad) {
                if (adActive) {
                    /* The ad just ended — hand the player back exactly as
                     * the user left it. */
                    adActive = false;
                    if (v) {
                        try { v.muted = wasMuted; } catch (e) {}
                        try { v.playbackRate = savedRate; } catch (e) {}
                    }
                }
                dismissPopups();
                return;
            }
            if (!adActive) {
                adActive = true;
                if (v) {
                    try { wasMuted = v.muted; } catch (e) { wasMuted = false; }
                    try { savedRate = v.playbackRate || 1; } catch (e) { savedRate = 1; }
                }
            }
            if (v) {
                /* Silence the ad and burn through it at max speed. */
                try { v.muted = true; } catch (e) {}
                try { v.playbackRate = 16; } catch (e) {}
                /* Unskippable ad: jump to its end. Guarded — only when the
                 * ad segment has real metadata, and only while an ad is
                 * actually showing, so a false positive can never skip
                 * the user's video. */
                try {
                    if (v.readyState >= 1 && isFinite(v.duration) && v.duration > 0.5 &&
                        v.currentTime < v.duration - 0.25) {
                        v.currentTime = v.duration;
                    }
                } catch (e) {}
            }
            /* Skippable ad: click Skip the moment the button exists. */
            clickSkipButtons();
            dismissPopups();
        } catch (e) {
            /* Fail open — a broken check must never break playback. */
            adActive = false;
        }
    }

    function start() {
        try {
            installCss();
            /* The player mutates its DOM in two patterns depending on ad
             * type: the observer catches class/child changes, the interval
             * is the backstop (the Skip button appears ~5s into an ad, so
             * 150ms polling is far faster than it needs to be). */
            var observer = new MutationObserver(function () { check(); });
            var target = document.body || document.documentElement;
            if (target && observer.observe) {
                observer.observe(target, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class']
                });
            }
            setInterval(check, 150);
            check();
        } catch (e) { /* the video plays on without the skipper */ }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
