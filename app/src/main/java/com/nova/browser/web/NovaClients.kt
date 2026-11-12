package com.nova.browser.web

import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nova.browser.adblock.AdBlockEngine
import com.nova.browser.adblock.BlockResult
import com.nova.browser.data.ErrorLog
import com.nova.browser.data.Tab
import com.nova.browser.ui.BrowserState
import java.io.ByteArrayInputStream

/** Desktop Chrome user agent (used when a tab is in desktop mode). */
const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Configure a fresh WebView for a tab: settings, clients, download listener,
 * find listener. Called once per tab by the activity.
 */
object NovaWebView {

    fun configure(webView: WebView, tab: Tab, state: BrowserState, host: WebHost) {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.textZoom = state.settings.textZoom
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = if (tab.incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        // Local files are not loaded in v1.0 (local HTML preview is v1.2);
        // blocking file access also stops pages from reading device files.
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = true
        try {
            s.safeBrowsingEnabled = true
        } catch (_: Exception) {
            // Safe Browsing is not available on every WebView build.
        }
        applyDesktopMode(webView, tab)
        // Debugging is off in v1.0 (Dev Mode / USB remote debugging is v1.1).
        WebView.setWebContentsDebuggingEnabled(false)

        webView.webViewClient = NovaWebViewClient(state, host, tab)
        webView.webChromeClient = NovaWebChromeClient(state, host, tab)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            host.onDownloadStart(url, userAgent, contentDisposition, mimeType, contentLength)
        }
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            tab.findResult = if (numberOfMatches > 0) {
                "${activeMatchOrdinal + 1} / $numberOfMatches"
            } else {
                "No matches"
            }
        }
    }

    /** Apply (or remove) desktop mode on a live WebView, then reload. */
    fun applyDesktopMode(webView: WebView, tab: Tab) {
        val s = webView.settings
        if (tab.desktopMode) {
            s.userAgentString = DESKTOP_USER_AGENT
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        } else {
            // Restore the WebView default user agent.
            s.userAgentString = WebSettings.getDefaultUserAgent(webView.context)
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }
    }
}

/** Handles navigation, ad blocking, history, errors and SSL problems. */
class NovaWebViewClient(
    private val state: BrowserState,
    private val host: WebHost,
    private val tab: Tab
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (request.url.scheme?.lowercase()) {
            "http", "https" -> {
                // HTTPS-only upgrade on navigation (not just on typed input).
                if (state.settings.httpsOnly && UrlUtils.shouldUpgradeToHttps(url) &&
                    !state.settings.isHttpsException(UrlUtils.extractHost(url))
                ) {
                    tab.wasUpgradedFromHttp = true
                    view.loadUrl(url.replaceFirst("http://", "https://", ignoreCase = true))
                    true
                } else {
                    false // let the WebView load it
                }
            }
            "tel", "mailto", "sms" -> {
                host.openExternal(Intent(Intent.ACTION_VIEW, request.url))
                true
            }
            "intent" -> {
                host.openExternal(parseIntentUrl(url) ?: return true)
                true
            }
            "market" -> {
                host.openExternal(Intent(Intent.ACTION_VIEW, request.url))
                true
            }
            else -> false
        }
    }

    /** Try to turn an intent:// URL into a launchable Intent, falling back
     *  to its browser_fallback_url or the Play Store page. */
    private fun parseIntentUrl(url: String): Intent? {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (intent.`package` != null) intent else if (fallback != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
            } else {
                intent
            }
        } catch (e: Exception) {
            ErrorLog.log(state.context, "Browser", "Bad intent:// URL: $url", e)
            null
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // THREAD RULE — this runs on a BACKGROUND thread (a WebView worker
        // pool), not the main thread. NEVER call a WebView method here:
        // view.url / loadUrl / reload / evaluateJavascript / canGoBack /
        // settings / findAll all throw "A WebView method was called on
        // thread ..." and crash the app. Calling view.url here is what
        // crashed v1.0 on every search (6 crashes in 2 minutes). The only
        // WebView touch allowed in this method is view.post { ... }, which
        // is thread-safe and runs the block on the main thread. Everything
        // else must come from the tab / state, which the main thread keeps
        // up to date (tab.url is set in onPageStarted).
        // Ad-block layer 1 — network filter. Only GET sub-resource requests
        // over http(s); the main frame is never blocked, and POST bodies
        // are never swallowed.
        if (!state.settings.adBlockEnabled) return null
        if (request.method != "GET" || request.isForMainFrame) return null
        val url = request.url.toString()
        if (!UrlUtils.isHttpUrl(url)) return null
        // v1.1 FIX: shouldInterceptRequest runs on a background thread and
        // WebView.getUrl() may only be called on the main thread — calling
        // view.url here crashed the app on every page load (the search
        // crash). The tab tracks the current URL itself (set in
        // onPageStarted on the main thread), so use that. A blank tab URL
        // means unknown; shouldBlock(null) handles that safely.
        val pageHost = UrlUtils.extractHost(tab.url)
        if (state.settings.isAdBlockOffFor(pageHost)) return null
        val result = state.adBlock.shouldBlock(url, pageHost)
        if (result == BlockResult.ALLOWED) return null
        // shouldInterceptRequest runs on a background thread — count the
        // block on the main thread.
        view.post {
            if (result == BlockResult.AD) tab.blockedAds++ else tab.blockedTrackers++
        }
        // An empty response: the page keeps working, the ad simply never loads.
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        // about:blank is the home screen — keep the tab URL blank for it.
        tab.url = if (url == "about:blank") "" else url
        tab.error = null
        tab.blockedAds = 0
        tab.blockedTrackers = 0
        tab.sslHandler = null
        tab.sslError = null
        tab.sslErrorUrl = null
        tab.isLoading = true
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
    }

    override fun onPageFinished(view: WebView, url: String) {
        tab.isLoading = false
        tab.progress = 100
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
        val title = view.title?.takeIf { it.isNotBlank() } ?: url
        tab.title = title
        if (!tab.incognito && UrlUtils.isHttpUrl(url)) {
            state.recordVisit(url, title)
        }
        // Ad-block layer 2 — cosmetic filtering: hide the empty boxes the
        // blocked ads left behind. Only when the list has rules for this host.
        if (state.settings.adBlockEnabled) {
            val pageHost = UrlUtils.extractHost(url)
            if (!state.settings.isAdBlockOffFor(pageHost)) {
                val selectors = state.adBlock.cosmeticSelectors(pageHost)
                val script = CosmeticFilter.buildHideScript(selectors)
                if (script != null) {
                    try {
                        view.evaluateJavascript(script, null)
                    } catch (_: Exception) {
                        // A page that rejects injection keeps working.
                    }
                }
            }
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: android.webkit.WebResourceError
    ) {
        // Sub-frame errors (a broken image, a failed ad) must not replace
        // the page with an error screen.
        if (!request.isForMainFrame) return
        tab.isLoading = false
        val description = error.description?.toString()?.takeIf { it.isNotBlank() }
            ?: "Couldn't load this page"
        tab.error = description
        ErrorLog.log(
            view.context, "Browser",
            "Page load failed: ${request.url} (${error.errorCode}) $description"
        )
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: android.net.http.SslError
    ) {
        // Never proceed automatically: the UI shows a dialog and the user
        // decides. The handler is kept on the tab until it is answered.
        // Loading is stopped while the dialog is open, so the progress
        // bar does not spin forever if the user cancels (or walks away).
        tab.isLoading = false
        tab.sslHandler = handler
        tab.sslError = error
        tab.sslErrorUrl = view.url
        ErrorLog.log(view.context, "Browser", "SSL error on ${view.url}: ${error.primaryError}")
    }
}

/** Handles progress, titles, file picking, page permissions and popups. */
class NovaWebChromeClient(
    private val state: BrowserState,
    private val host: WebHost,
    private val tab: Tab
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tab.progress = newProgress
        tab.isLoading = newProgress < 100
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) tab.title = title
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        host.onShowFileChooser(filePathCallback, fileChooserParams)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        host.onPermissionRequest(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        host.onGeolocation(origin, callback)
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        // Popups opened without a user gesture (ad popups, auto-redirects)
        // are blocked outright — only real taps open a new tab.
        if (!isUserGesture) return false
        val newWebView = host.onCreateWindow() ?: return false
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }
}
