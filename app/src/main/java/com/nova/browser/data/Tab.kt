package com.nova.browser.data

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One browser tab. The WebView is created lazily by the activity and kept
 * alive while the tab exists, so switching tabs never loses scroll position
 * or page state. UI state is Compose state so the address bar, progress
 * and shield counts update without a separate ViewModel.
 *
 * A tab never writes history when [incognito] is true — that is enforced
 * wherever history is recorded, not just here.
 */
class Tab(
    val id: Long,
    url: String = "",
    title: String = "",
    val incognito: Boolean = false,
    groupId: Long? = null,
    lastUsedMs: Long = System.currentTimeMillis(),
    discarded: Boolean = false
) {
    var webView: WebView? = null

    var url by mutableStateOf(url)
    var title by mutableStateOf(title)

    /** Tab-group membership (null = ungrouped). Kept in sync with
     *  TabGroup.tabIds by TabGroups ops + TabStore persistence. */
    var groupId by mutableStateOf(groupId)

    /** Last time this tab was the selected tab. Drives the suspension
     *  policy (TabSuspensionPolicy.IDLE_SUSPEND_MS). Updated on select
     *  (TabSwitcherScreen / BrowserState.selectTab hook) and on creation. */
    var lastUsedMs by mutableStateOf(lastUsedMs)

    /** True when this tab's WebView was destroyed by background-tab
     *  suspension (TabSuspension). The tab metadata (url/title/group)
     *  survives; switching back recreates the WebView via
     *  BrowserState.ensureWebView, which reloads tab.url because
     *  suspension resets [initialUrlLoaded] to false. */
    var discarded by mutableStateOf(discarded)

    /** W1-1 — True when the tab's current page was reached by an HTTP
     *  POST (form submit, checkout, payment result). Set by the
     *  NovaClients hook (HOOK-F: shouldInterceptRequest sees the real
     *  method of every main-frame navigation; a POST sets this, a GET
     *  main-frame navigation clears it). WebBackForwardList carries no
     *  POST flag, so this marker is the only record. A tab with this
     *  set is NEVER suspended: restore is a plain GET reload of
     *  [url], which would lose the POST data (error page at best,
     *  double-submit at worst). Stale-true is the safe direction —
     *  the tab just never auto-suspends until its next GET navigation. */
    var wasPost by mutableStateOf(false)

    /** W1-1 — True while this tab has a download (or other transfer)
     *  running. Set by the download-start hook (HOOK-G, in
     *  NovaWebView.configure's DownloadListener lambda, which captures
     *  this tab). Cleared by TabSuspension when no download is running
     *  anywhere (DownloadManager query) — there is no per-tab
     *  completion callback to hook. A tab with this set is NEVER
     *  suspended: destroying its WebView mid-download could kill an
     *  in-flight transfer. HONEST LIMIT: uploads started inside the
     *  page (fetch/XHR/file upload) are not observable from app code
     *  at all — only the POST-result exemption covers them, and only
     *  after the POST navigation lands. */
    var hasActiveTransfer by mutableStateOf(false)
    var progress by mutableStateOf(0)
    var isLoading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var blockedAds by mutableStateOf(0)
    var blockedTrackers by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var desktopMode by mutableStateOf(false)
    var findActive by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findResult by mutableStateOf("")

    /** True when the current URL was upgraded from http by HTTPS-only mode.
     *  The error overlay offers "Load with HTTP anyway" only in that case. */
    var wasUpgradedFromHttp by mutableStateOf(false)

    /** Set while an SSL-error dialog is open. One-shot: the handler is only
     *  valid until the user answers or the page is left. Compose state, so
     *  the dialog appears the moment the WebView reports the error. */
    var sslHandler by mutableStateOf<SslErrorHandler?>(null)
    var sslError by mutableStateOf<SslError?>(null)
    var sslErrorUrl by mutableStateOf<String?>(null)

    /** True once a WebView has been told to load [url] (see
     *  BrowserState.ensureWebView). Restored tabs and tabs created from an
     *  external link carry a URL but no WebView yet; without this flag the
     *  fresh WebView would never be told to load anything and the tab
     *  would stay blank. */
    var initialUrlLoaded by mutableStateOf(false)

    fun resetForNavigation(newUrl: String) {
        url = newUrl
        error = null
        blockedAds = 0
        blockedTrackers = 0
        sslHandler = null
        sslError = null
        sslErrorUrl = null
    }

    /** Mark this tab as just used (selected). Only the idle clock moves
     *  here (P-3): [discarded] is cleared by [BrowserState.ensureWebView]
     *  once the WebView is actually recreated — clearing it here made
     *  the "Suspended" badge vanish before the tab had really reloaded. */
    fun touch(nowMs: Long = System.currentTimeMillis()) {
        lastUsedMs = nowMs
    }

    /** Snapshot for the recently-closed stack. Incognito tabs must never
     *  be passed to TabStore.pushClosed — it refuses them, but callers
     *  should not record them in the first place. */
    fun toClosedTab(closedAtMs: Long = System.currentTimeMillis()): ClosedTab =
        ClosedTab(url = url, title = title, groupId = groupId, closedAtMs = closedAtMs, incognito = incognito)
}
