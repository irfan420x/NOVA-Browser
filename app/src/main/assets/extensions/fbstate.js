/* NOVA Browser — FBState marker (built-in extension, v2.4).
 *
 * Marker ONLY: sets window.__novaFbstate so the extension has a
 * runtime presence governed by the same gating as every other
 * built-in (enable switch, sensitive-host allow). It reads nothing,
 * touches no DOM, sends nothing, clicks nothing. All cookie work is
 * native (android.webkit.CookieManager) — never page JS.
 *
 * Idempotent: guarded against double injection on SPA navigations,
 * like window.__novaCookieHider.
 */
(function () {
    'use strict';
    if (window.__novaFbstate) return;
    window.__novaFbstate = { version: "2.4" };
})();
