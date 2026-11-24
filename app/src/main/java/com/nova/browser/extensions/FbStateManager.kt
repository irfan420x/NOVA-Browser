package com.nova.browser.extensions

import android.webkit.CookieManager
import com.nova.browser.data.ExtensionStore
import com.nova.browser.web.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FBState native layer (NOVA Browser v2.4, NOVA-EXTENSIONS.md §4.2).
 *
 * All cookie work is native, through android.webkit.CookieManager —
 * never page JS (the FBState screen never runs evaluateJavascript to
 * read cookies; a page script could be tampered with, the native call
 * cannot). All CookieManager calls run on Dispatchers.IO, never the
 * main thread; results are posted back to the UI thread by the caller.
 *
 * PRIVACY — the extracted state is the user's OWN Facebook session:
 * it NEVER leaves the device. No upload, no network call, no logging
 * of cookie values (or names), no analytics. Export is copy-to-
 * clipboard / save-to-file ONLY. There is no code path here that
 * could send state off-device.
 *
 * Coverage (§4.2): one getCookie call cannot see host-only cookies of
 * a sibling subdomain, so export queries three URLs and merges
 * (www, bare, m — first occurrence of a name wins, see
 * [FbStateFormat.mergeExportCookies]).
 *
 * HONEST LIMITS (platform, stated on screen too):
 * - CookieManager returns only `name=value; name=value` strings: no
 *   domain/path/secure/httpOnly/expiry flags. Export records those as
 *   null (see FbStateFormat) instead of fabricating them.
 * - HttpOnly CANNOT be set through CookieManager.setCookie — WebView
 *   drops the attribute. An imported cookie that was HttpOnly becomes
 *   JavaScript-readable after import. [FbStateFormat.buildSetCookieString]
 *   never emits an HttpOnly attribute.
 * - Delete is "set the cookie expired" — there is no per-cookie
 *   CookieManager.remove on all API levels; the expired-cookie write
 *   is the supported mechanism (same pattern as DevToolsScreen).
 * - A cookie value containing "; " is ambiguous in the CookieManager
 *   string format and may parse incorrectly; there is no better source
 *   in WebView.
 */
object FbStateManager {

    /** Export URLs, in merge order (§4.2): www first, then bare, then m. */
    val EXPORT_URLS = listOf(
        "https://www.facebook.com/",
        "https://facebook.com/",
        "https://m.facebook.com/"
    )

    /** Cookies soft logout keeps (C3C behaviour): they preserve the
     *  device's remembered-login state. */
    val KEEP_ON_SOFT_LOGOUT = setOf("sb", "dbln")

    const val EXPIRED_COOKIE_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"

    /** URL every setCookie/expire write is made against. The cookie
     *  string itself carries Domain=.facebook.com, so the write covers
     *  www, m, and bare facebook.com alike. */
    const val COOKIE_SET_URL = "https://www.facebook.com/"

    data class ImportResult(val imported: Int)
    data class SoftLogoutResult(val deleted: Int)

    /** True when [url] is a facebook.com page (the host itself or any
     *  subdomain). Used by the screen to decide which open tabs to
     *  reload after import / soft logout. Never throws. */
    fun isFacebookUrl(url: String?): Boolean {
        val host = UrlUtils.extractHost(url ?: "")?.lowercase() ?: return false
        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    /** Read the current Facebook cookies (three URLs, merged, first
     *  name wins). Returns an empty list when there are no cookies or
     *  a URL read fails — a failed read must not abort the export.
     *  Throws [IllegalStateException] in the private process. */
    suspend fun exportCookies(): List<FbStateFormat.FbStateCookie> = withContext(Dispatchers.IO) {
        checkNotPrivate()
        val cm = CookieManager.getInstance()
        val perUrl = EXPORT_URLS.map { url -> readCookiesForUrl(cm, url) }
        FbStateFormat.mergeExportCookies(*perUrl.toTypedArray())
    }

    /**
     * Write [cookies] into the WebView cookie store. Merge by default
     * (same name+path is overwritten, everything else untouched);
     * when [replaceAll] is true, delete ALL current facebook.com
     * cookies first, then set the new ones (§4.3-Q5). `flush()` after
     * the batch, so the write is not lost if the app is killed.
     * A single bad cookie never aborts the batch. Returns how many
     * cookies were set. Throws [IllegalStateException] in the private
     * process.
     */
    suspend fun importCookies(
        cookies: List<FbStateFormat.FbStateCookie>,
        replaceAll: Boolean
    ): ImportResult = withContext(Dispatchers.IO) {
        checkNotPrivate()
        val cm = CookieManager.getInstance()
        if (replaceAll) deleteAllFacebookCookies(cm)
        var imported = 0
        val nowMs = System.currentTimeMillis()
        for (cookie in cookies) {
            try {
                cm.setCookie(COOKIE_SET_URL, FbStateFormat.buildSetCookieString(cookie, nowMs))
                imported++
            } catch (_: Exception) {
                // One bad cookie must not abort the batch.
            }
        }
        try {
            cm.flush()
        } catch (_: Exception) {
        }
        ImportResult(imported)
    }

    /**
     * Soft logout (§4.7): delete all facebook.com cookies EXCEPT `sb`
     * and `dbln`, then flush. Returns how many cookies were deleted.
     * Throws [IllegalStateException] in the private process.
     */
    suspend fun softLogout(): SoftLogoutResult = withContext(Dispatchers.IO) {
        checkNotPrivate()
        val cm = CookieManager.getInstance()
        val names = EXPORT_URLS
            .flatMap { url -> readCookiesForUrl(cm, url) }
            .map { it.first }
            .toSet()
        var deleted = 0
        for (name in names) {
            if (name in KEEP_ON_SOFT_LOGOUT) continue
            try {
                expireCookie(cm, name)
                deleted++
            } catch (_: Exception) {
                // One failed expiry must not abort the logout.
            }
        }
        try {
            cm.flush()
        } catch (_: Exception) {
        }
        SoftLogoutResult(deleted)
    }

    private fun readCookiesForUrl(cm: CookieManager, url: String): List<Pair<String, String>> {
        return try {
            FbStateFormat.parseCookieHeader(cm.getCookie(url))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun deleteAllFacebookCookies(cm: CookieManager) {
        val names = EXPORT_URLS
            .flatMap { url -> readCookiesForUrl(cm, url) }
            .map { it.first }
            .toSet()
        for (name in names) {
            try {
                expireCookie(cm, name)
            } catch (_: Exception) {
            }
        }
        try {
            cm.flush()
        } catch (_: Exception) {
        }
    }

    /** Expire one cookie by name (§4.2 exact string). */
    private fun expireCookie(cm: CookieManager, name: String) {
        cm.setCookie(
            COOKIE_SET_URL,
            "$name=; Domain=${FbStateFormat.CANONICAL_DOMAIN}; Path=/; Expires=$EXPIRED_COOKIE_DATE"
        )
    }

    /** Private-profile cookies are a separate store; exporting them
     *  would leak a private session. The screen refuses first — this
     *  is defence-in-depth, so a future caller cannot forget. */
    private fun checkNotPrivate() {
        if (ExtensionStore.isPrivateProcess()) {
            throw IllegalStateException("FBState is not available in private mode")
        }
    }
}
