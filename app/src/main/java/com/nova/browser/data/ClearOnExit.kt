package com.nova.browser.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Clear-on-exit (H-W4-2 / D-4) — the wipe half. The coordinator wires
 * the hook; this object only does the wiping.
 *
 * [wipe] clears EXACTLY history + cookies + cache:
 * - history: BrowserDb.clearHistory() (normal history only — the DB
 *   never holds private history, and this is never called from a
 *   private session);
 * - cookies + site data: CookieManager.removeAllCookies() + flush(),
 *   WebStorage.deleteAllData();
 * - cache: the WebView in-memory/disk cache, via [onClearWebViewCache]
 *   (the coordinator passes `{ currentTab?.webView?.clearCache(true) }`
 *   — this file has no WebView reference of its own).
 *
 * It does NOT touch: bookmarks, downloads, saved tabs, per-site
 * settings, or incognito handling. It never throws.
 *
 * HONEST LIMIT (the Settings toggle subtitle must say this): wipe runs
 * from MainActivity.onDestroy, which only fires on a clean finish. A
 * process kill — swipe from Recents, OOM kill, force-stop — skips
 * onDestroy, so data from that session is NOT wiped. There is no
 * reliable wipe-on-kill on Android; clear-on-exit is best-effort.
 *
 * This deliberately does NOT reuse BrowserState.clearBrowsingData: that
 * method lives in the ui package, needs a live BrowserState (tabs +
 * WebView), and is a user-triggered action — the exit hook must work
 * from MainActivity.onDestroy with only a Context. Same three parts,
 * same order, same fail-open behaviour.
 */
object ClearOnExit {

    /** Wipe history + cookies + cache. [onClearWebViewCache] clears the
     *  live WebView's cache when the coordinator supplies it. */
    fun wipe(context: Context, onClearWebViewCache: (() -> Unit)? = null) {
        try {
            BrowserDb(context).clearHistory()
        } catch (_: Exception) {
        }
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }
        try {
            onClearWebViewCache?.invoke()
        } catch (_: Exception) {
        }
    }
}
