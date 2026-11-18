package com.nova.browser.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Clear-on-exit (H-W4-2 / D-4) — the wipe half. F06 rework: the old
 * [wipe] called `CookieManager.removeAllCookies(null)` — async with
 * no callback — and then `flush()` immediately, so the process could
 * die before the cookies were actually deleted while the caller had
 * already assumed success. The wipe now coordinates on callback
 * completion and reports a verified [WipeResult].
 *
 * What is wiped — EXACTLY history + cookies + cache:
 * - history: BrowserDb.clearHistory() (normal history only — the DB
 *   never holds private history, and this is never called from a
 *   private session);
 * - cookies + site data: CookieManager.removeAllCookies(callback) +
 *   flush() (flush runs only AFTER the callback fires),
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
 * This rework does NOT fix the kill case — it makes the unverified
 * case VISIBLE instead of claiming success (see [WipeStatus.UNVERIFIED]
 * and [checkInterruptedWipe]).
 *
 * This deliberately does NOT reuse BrowserState.clearBrowsingData: that
 * method lives in the ui package, needs a live BrowserState (tabs +
 * WebView), and is a user-triggered action — the exit hook must work
 * from MainActivity.onDestroy with only a Context. Same three parts,
 * same order, same fail-open behaviour.
 */

/** Outcome of one wipe attempt. */
enum class WipeStatus {
    /** Every step reported success (cookies confirmed by the
     *  removeAllCookies callback). */
    VERIFIED,

    /** At least one step succeeded, at least one failed or was
     *  unconfirmed. Data may remain — the result says which part. */
    PARTIAL,

    /** No step succeeded. */
    FAILED,

    /** No result was ever produced: the process died (or was killed)
     *  while a wipe was in flight — onDestroy started the wipe but
     *  the cookie callback never came back. Recorded at next launch
     *  by [ClearOnExit.checkInterruptedWipe], never claimed as a
     *  success. This is the wipe-on-kill case: UNVERIFIED is the
     *  honest status, not a fix. */
    UNVERIFIED,

    /** No wipe has ever run (fresh install / clear-on-exit never
     *  enabled). */
    NOT_RUN
}

/** Verified result of one wipe. [verified] is true only when every
 *  part is true — a single false makes the whole wipe unverified.
 *  Note on [storageCleared]: WebStorage.deleteAllData() has no
 *  completion callback, so "cleared" here means "delete requested
 *  without throwing", not an independently confirmed empty store.
 *  That limitation is part of the result, not hidden by it. */
data class WipeResult(
    val cookiesCleared: Boolean,
    val historyCleared: Boolean,
    val storageCleared: Boolean,
    val cacheCleared: Boolean,
    val verified: Boolean,
    val status: WipeStatus
)

/**
 * Pure wipe coordination (JVM-testable, no Android imports in this
 * class — CookieManager / WebStorage stay behind the step lambdas
 * the Android wrapper supplies). Steps run in a fixed order:
 * history → cookies (async: the callback gates everything after
 * it) → flush → storage → cache. Each step is fail-open: a throw
 * counts as "not cleared" and the remaining steps still run, so one
 * broken step never blocks the rest of the wipe.
 *
 * If the cookie callback never fires, [onResult] is NEVER called:
 * there is no result to report, and inventing one would be exactly
 * the old bug. The Android wrapper marks the wipe pending before
 * starting it; a pending wipe with no result at next launch is how
 * the kill case is detected ([ClearOnExit.checkInterruptedWipe]).
 */
class WipeCoordinator(
    private val clearHistory: () -> Boolean,
    private val removeCookies: ((Boolean) -> Unit) -> Unit,
    private val flushCookies: () -> Unit,
    private val deleteStorage: () -> Boolean,
    private val clearCache: () -> Boolean
) {
    fun run(onResult: (WipeResult) -> Unit) {
        val historyCleared = try {
            clearHistory()
        } catch (_: Exception) {
            false
        }
        try {
            removeCookies { cookiesCleared ->
                try {
                    flushCookies()
                } catch (_: Exception) {
                }
                val storageCleared = try {
                    deleteStorage()
                } catch (_: Exception) {
                    false
                }
                val cacheCleared = try {
                    clearCache()
                } catch (_: Exception) {
                    false
                }
                onResult(buildResult(cookiesCleared, historyCleared, storageCleared, cacheCleared))
            }
        } catch (_: Exception) {
            // removeAllCookies itself threw synchronously: cookies are
            // not cleared, but storage + cache still run.
            val storageCleared = try {
                deleteStorage()
            } catch (_: Exception) {
                false
            }
            val cacheCleared = try {
                clearCache()
            } catch (_: Exception) {
                false
            }
            onResult(buildResult(false, historyCleared, storageCleared, cacheCleared))
        }
    }

    companion object {
        fun buildResult(
            cookiesCleared: Boolean,
            historyCleared: Boolean,
            storageCleared: Boolean,
            cacheCleared: Boolean
        ): WipeResult {
            val all = cookiesCleared && historyCleared && storageCleared && cacheCleared
            val any = cookiesCleared || historyCleared || storageCleared || cacheCleared
            val status = when {
                all -> WipeStatus.VERIFIED
                any -> WipeStatus.PARTIAL
                else -> WipeStatus.FAILED
            }
            return WipeResult(
                cookiesCleared = cookiesCleared,
                historyCleared = historyCleared,
                storageCleared = storageCleared,
                cacheCleared = cacheCleared,
                verified = all,
                status = status
            )
        }
    }
}

object ClearOnExit {

    /** SharedPreferences file — the same "nova_browser" file Settings
     *  uses, so the Settings screen can read the last wipe status
     *  without a new file. Keys are namespaced `clear_on_exit_*`. */
    const val PREFS_NAME = "nova_browser"
    const val KEY_LAST_STATUS = "clear_on_exit_last_status"
    const val KEY_LAST_TIME_MS = "clear_on_exit_last_time_ms"
    const val KEY_PENDING = "clear_on_exit_pending"

    /**
     * Wipe history + cookies + cache, coordinated on the cookie
     * callback. [onClearWebViewCache] clears the live WebView's cache
     * when the coordinator supplies it, and returns true only when
     * a WebView actually cleared it — AUDIT 2 (F06-A): the old
     * callback swallowed every failure and the wipe reported
     * `cacheCleared = true` unconditionally, so a wipe could claim
     * VERIFIED while the cache was never cleared (e.g. no live
     * WebView, or clearCache throwing after the activity tore down).
     * A false result now makes the wipe PARTIAL, honestly.
     * [onResult] (optional) receives the verified [WipeResult] — only after removeAllCookies called
     * back. If the process dies before the callback, [onResult] never
     * fires and the wipe stays pending (see [checkInterruptedWipe]).
     * Never throws.
     */
    fun wipe(
        context: Context,
        onClearWebViewCache: (() -> Boolean)? = null,
        onResult: ((WipeResult) -> Unit)? = null
    ) {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }
        // Mark pending BEFORE starting: if the process dies mid-wipe,
        // the flag is still set at next launch — that is the only
        // evidence the wipe never completed.
        try {
            prefs?.edit()?.putBoolean(KEY_PENDING, true)?.apply()
        } catch (_: Exception) {
        }
        val coordinator = WipeCoordinator(
            clearHistory = {
                try {
                    BrowserDb(context).clearHistory()
                    true
                } catch (_: Exception) {
                    false
                }
            },
            removeCookies = { callback ->
                CookieManager.getInstance().removeAllCookies { removed -> callback(removed) }
            },
            flushCookies = {
                CookieManager.getInstance().flush()
            },
            deleteStorage = {
                try {
                    WebStorage.getInstance().deleteAllData()
                    true
                } catch (_: Exception) {
                    false
                }
            },
            clearCache = {
                // AUDIT 2 (F06-A): the result is measured, not assumed.
                // A null callback means "no WebView to clear" (the
                // caller had nothing live) — that counts as cleared,
                // since there is no WebView cache in this process run;
                // a callback that returns false, or throws, counts as
                // NOT cleared and the wipe reports PARTIAL.
                try {
                    onClearWebViewCache?.invoke() ?: true
                } catch (_: Exception) {
                    false
                }
            }
        )
        try {
            coordinator.run { result ->
                try {
                    prefs?.edit()
                        ?.putBoolean(KEY_PENDING, false)
                        ?.putString(KEY_LAST_STATUS, result.status.name)
                        ?.putLong(KEY_LAST_TIME_MS, System.currentTimeMillis())
                        ?.apply()
                } catch (_: Exception) {
                }
                if (!result.verified) {
                    ErrorLog.log(
                        context, "Browser",
                        "Clear-on-exit ${result.status}: " +
                            "cookies=${result.cookiesCleared} " +
                            "history=${result.historyCleared} " +
                            "storage=${result.storageCleared} " +
                            "cache=${result.cacheCleared} — data may remain"
                    )
                }
                try {
                    onResult?.invoke(result)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            // The coordinator itself never throws by design; this is a
            // last-resort guard so onDestroy can never crash on wipe.
            try {
                prefs?.edit()
                    ?.putBoolean(KEY_PENDING, false)
                    ?.putString(KEY_LAST_STATUS, WipeStatus.FAILED.name)
                    ?.putLong(KEY_LAST_TIME_MS, System.currentTimeMillis())
                    ?.apply()
            } catch (_: Exception) {
            }
        }
    }

    /** Last recorded wipe status, or NOT_RUN if no wipe ever reported.
     *  The Settings screen reads this to show e.g. "Last clear on
     *  exit: verified / partial / unverified (app was closed before
     *  the wipe finished)". */
    fun lastWipeStatus(context: Context): WipeStatus {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_STATUS, null) ?: return WipeStatus.NOT_RUN
            WipeStatus.valueOf(raw)
        } catch (_: Exception) {
            WipeStatus.NOT_RUN
        }
    }

    /** Epoch-ms of the last recorded wipe result, or 0 if none. */
    fun lastWipeTimeMs(context: Context): Long {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_TIME_MS, 0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Call once at startup (MainActivity.onCreate, before any wipe
     * can run): if a previous wipe is still marked pending, the
     * process died before its cookie callback — the wipe is
     * UNVERIFIED, not successful. Records UNVERIFIED + an ErrorLog
     * entry and clears the pending flag. Returns true when an
     * interrupted wipe was found. This detects the kill case; it
     * does NOT recover the data (the session's cookies/history are
     * still on disk — only a fresh wipe on next clean exit removes
     * them).
     */
    fun checkInterruptedWipe(context: Context): Boolean {
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return false
        }
        val pending = try {
            prefs.getBoolean(KEY_PENDING, false)
        } catch (_: Exception) {
            false
        }
        if (!pending) return false
        try {
            prefs.edit()
                .putBoolean(KEY_PENDING, false)
                .putString(KEY_LAST_STATUS, WipeStatus.UNVERIFIED.name)
                .putLong(KEY_LAST_TIME_MS, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
        ErrorLog.log(
            context, "Browser",
            "Clear-on-exit UNVERIFIED: the app was closed before the wipe " +
                "finished (process died before the cookie callback) — " +
                "data from that session may still be on disk"
        )
        return true
    }
}
