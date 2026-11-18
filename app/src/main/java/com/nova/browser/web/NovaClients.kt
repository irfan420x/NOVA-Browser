package com.nova.browser.web

import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
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
import com.nova.browser.data.ExtensionStore
import com.nova.browser.data.Tab
import com.nova.browser.devtools.DevToolsHub
import com.nova.browser.extensions.ExtensionRuntime
import com.nova.browser.reader.ReaderRunner
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
        // M3: per-site settings must apply BEFORE the first load —
        // onPageStarted is too late (the main-frame request already went
        // out with the old UA, so a stored per-site desktop mode loaded
        // the mobile layout on first visit). The tab starts with the
        // stored per-site values; applyDesktopMode below then uses them.
        // (A blank tab has no host — defaults stay until it navigates.)
        val firstHost = UrlUtils.extractHost(tab.url ?: "")
        if (firstHost != null) {
            val perSite = state.perSiteSettingsFor(firstHost)
            s.javaScriptEnabled = perSite.javaScriptEnabled
            tab.desktopMode = perSite.desktopMode
        }
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
        // USB remote debugging (W3-3): OFF unless Dev Mode is on AND the
        // user explicitly enabled it with the consent warning. This is a
        // global, app-wide flag — configure() re-applies it for every new
        // tab, so the Settings toggle handler must also call
        // WebView.setWebContentsDebuggingEnabled(false) immediately when
        // it is switched off, not only here.
        WebView.setWebContentsDebuggingEnabled(
            state.settings.devMode && state.settings.webContentsDebugging
        )

        webView.webViewClient = NovaWebViewClient(state, host, tab)
        webView.webChromeClient = NovaWebChromeClient(state, host, tab)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            tab.hasActiveTransfer = true // W1-1: never suspend mid-download
            host.onDownloadStart(url, userAgent, contentDisposition, mimeType, contentLength, tab)
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
                // AUDIT 2 (F01-A): only upgrade via loadUrl when the
                // navigation has no body to lose (GET/HEAD). A POST form
                // submission returns false here so the request reaches
                // shouldInterceptRequest, where HttpsEnforcer upgrades
                // it with a method-preserving 307 instead of silently
                // turning it into a GET and dropping the submission.
                if (state.settings.httpsOnly && UrlUtils.shouldUpgradeToHttps(url) &&
                    !state.settings.isHttpsException(UrlUtils.extractHost(url)) &&
                    HttpsEnforcer.canUpgradeViaLoadUrl(request.method)
                ) {
                    val httpsUrl = url.replaceFirst("http://", "https://", ignoreCase = true)
                    tab.wasUpgradedFromHttp = true
                    tab.upgradedFromHttpUrl = httpsUrl
                    view.loadUrl(httpsUrl)
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
                // F02: validate before launching. The URL string is
                // launched exactly as validated — never rebuild an
                // Intent from fields. Shared launch path for Allow and
                // for NeedsConfirmation + Allow: the confirmation tap
                // IS the gesture, so onAllow launches from the
                // already-validated decision and must NOT re-validate
                // (a package-without-fallback intent re-validates to
                // NeedsConfirmation even with a gesture, and
                // re-validating would silently do nothing after the
                // user tapped Allow).
                // AUDIT 2 (F02-A): the launch/fallback split lives in
                // IntentValidator.launchAction — a package-only intent
                // (package + fallback, no data) must TRY the app first,
                // not load the fallback outright.
                fun launchValidated(dataUri: String?, fallbackUrl: String?, packageName: String?) {
                    when (val action = IntentValidator.launchAction(dataUri, fallbackUrl, packageName)) {
                        is IntentValidator.LaunchAction.LoadFallback -> {
                            view.loadUrl(action.url)
                            return
                        }
                        IntentValidator.LaunchAction.LaunchApp -> { /* try the app below */ }
                    }
                    val intent = try {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    } catch (e: Exception) {
                        ErrorLog.log(state.context, "Browser", "Bad intent:// URL: $url", e)
                        return
                    }
                    // Try the app first; if it is missing
                    // (openExternal returns false on
                    // ActivityNotFoundException), load the validated
                    // http(s) fallback in the browser instead.
                    // Try-launch-then-fallback, NOT resolveActivity: on
                    // Android 11+ resolveActivity false-negatives for
                    // packages the app cannot see.
                    val launched = host.openExternal(intent)
                    if (!launched && fallbackUrl != null) {
                        view.loadUrl(fallbackUrl)
                    }
                }
                when (val decision = IntentValidator.validate(url, request.hasGesture())) {
                    is IntentValidator.Decision.Allow -> {
                        launchValidated(decision.intent.dataUri, decision.intent.fallbackUrl, decision.intent.packageName)
                        true
                    }
                    is IntentValidator.Decision.NeedsConfirmation -> {
                        host.confirmExternalIntent(
                            packageName = decision.packageName,
                            fallbackUrl = decision.fallbackUrl,
                            originalUrl = url,
                            onAllow = { launchValidated(decision.dataUri, decision.fallbackUrl, decision.packageName) }
                        )
                        true
                    }
                    is IntentValidator.Decision.Reject -> {
                        ErrorLog.log(state.context, "Browser", "Blocked intent:// URL (${decision.reason}): $url")
                        true
                    }
                }
            }
            "market" -> {
                host.openExternal(Intent(Intent.ACTION_VIEW, request.url))
                true
            }
            else -> false
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
        val netT0 = System.nanoTime()
        // Record every request outcome (allowed, per-site-off, blocked)
        // and return the response. Background-thread safe: recordNetwork
        // never touches a WebView.
        fun finish(blocked: Boolean, kind: String?, response: WebResourceResponse?): WebResourceResponse? {
            DevToolsHub.recordNetwork(
                tab.id, request.url.toString(), request.method, blocked, kind,
                request.isForMainFrame, (System.nanoTime() - netT0) / 1_000_000
            )
            return response
        }
        // W1-1: mark POST-result tabs — a suspended tab restores as a plain
        // GET, so POST tabs must never be suspended. The only place a POST
        // is observable (WebBackForwardList has no POST flag). Runs before
        // any early return, so a POST main-frame request is always marked.
        if (request.isForMainFrame) {
            val isPost = request.method == "POST"
            view.post { tab.wasPost = isPost }
        }
        // F01 (v2.2): HTTPS-only enforcement at the intercept level. Runs
        // BEFORE the ad-block early returns below (they would let http
        // through when ad-block is off, and main-frame requests return
        // early there). Covers every method, not just GET — a POST to
        // http:// upgrades via 307, which preserves the method.
        // decideForRequest reads tab.url (main-thread-maintained, safe
        // here) to spot an https -> http downgrade redirect; never
        // view.url (background thread — the v1.0 crash). Uses the existing
        // finish(), so both outcomes flow through DevToolsHub.recordNetwork
        // automatically.
        when (HttpsEnforcer.decideForRequest(
            url = request.url.toString(),
            httpsOnly = state.settings.httpsOnly,
            httpsExceptionHosts = state.settings.httpsExceptionHosts,
            isForMainFrame = request.isForMainFrame,
            pageUrl = tab.url,
            method = request.method
        )) {
            HttpsEnforcer.HttpsDecision.ALLOW -> { /* fall through to ad-block below */ }
            HttpsEnforcer.HttpsDecision.UPGRADE -> {
                val httpsUrl = HttpsEnforcer.upgradeUrl(request.url.toString())
                    ?: return finish(
                        true, "HTTPS_BLOCK",
                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    )
                // Main-frame redirect upgrade: the page itself was
                // upgraded, so the "load http anyway" affordance applies.
                // Sub-resource upgrades must NOT set this. view.post:
                // tab state is main-thread state.
                if (request.isForMainFrame) view.post {
                    tab.wasUpgradedFromHttp = true
                    tab.upgradedFromHttpUrl = httpsUrl
                }
                return finish(
                    true, "HTTPS_UPGRADE",
                    WebResourceResponse(
                        "text/plain", "UTF-8",
                        HttpsEnforcer.REDIRECT_STATUS_CODE,
                        HttpsEnforcer.REDIRECT_REASON_PHRASE,
                        HttpsEnforcer.redirectHeaders(httpsUrl),
                        ByteArrayInputStream(ByteArray(0))
                    )
                )
            }
            HttpsEnforcer.HttpsDecision.BLOCK -> {
                ErrorLog.log(
                    state.context, "Browser",
                    "HTTPS-only blocked plain-http request: ${request.url}"
                )
                return finish(
                    true, "HTTPS_BLOCK",
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                )
            }
        }
        // Ad-block layer 1 — network filter. Only GET sub-resource requests
        // over http(s); the main frame is never blocked, and POST bodies
        // are never swallowed.
        if (!state.settings.adBlockEnabled) return finish(false, null, null)
        if (request.method != "GET" || request.isForMainFrame) return finish(false, null, null)
        val url = request.url.toString()
        if (!UrlUtils.isHttpUrl(url)) return finish(false, null, null)
        // v1.1 FIX: the tab tracks the current URL itself (set in
        // onPageStarted on the main thread), so use that — never view.url
        // here (background thread). A blank tab URL means unknown;
        // shouldBlock(null) handles that safely.
        val pageHost = UrlUtils.extractHost(tab.url)
        if (state.settings.isAdBlockOffFor(pageHost)) return finish(false, null, null)
        val result = state.adBlock.shouldBlock(url, pageHost)
        if (result == BlockResult.ALLOWED) return finish(false, null, null)
        // shouldInterceptRequest runs on a background thread — count the
        // block on the main thread.
        view.post {
            if (result == BlockResult.AD) tab.blockedAds++ else tab.blockedTrackers++
        }
        // An empty response: the page keeps working, the ad simply never loads.
        return finish(
            true, result.name,
            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        )
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        // about:blank is the home screen — keep the tab URL blank for it.
        tab.url = if (url == "about:blank") "" else url
        tab.error = null
        // AUDIT 2 (F01-B): the upgrade flag belongs to one navigation
        // only. A load of any other URL (link to a plain https page,
        // back/forward, a redirect target) drops it, so the error
        // overlay never offers "Load with HTTP anyway" for a page
        // that was never upgraded. A reload of the upgraded URL
        // itself keeps it (HttpsEnforcer.isUpgradedTarget).
        if (tab.upgradedFromHttpUrl != null &&
            !HttpsEnforcer.isUpgradedTarget(tab.upgradedFromHttpUrl, url)
        ) {
            tab.wasUpgradedFromHttp = false
            tab.upgradedFromHttpUrl = null
        }
        tab.blockedAds = 0
        tab.blockedTrackers = 0
        tab.sslHandler = null
        tab.sslError = null
        tab.sslErrorUrl = null
        tab.isLoading = true
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
        // B-2: a new navigation resets the injection dedupe set (below).
        injectionDedupe.reset()
        // W4: per-site settings for the new host — JS off / desktop mode
        // apply from this load, not only when the sheet changes them.
        // (The UA change takes effect for subsequent requests; the JS
        // flag applies immediately to this page's scripts.)
        if (UrlUtils.isHttpUrl(url)) {
            val host = UrlUtils.extractHost(url)
            if (host != null) {
                val perSite = state.perSiteSettingsFor(host)
                try { view.settings.javaScriptEnabled = perSite.javaScriptEnabled } catch (_: Exception) { }
                if (perSite.desktopMode != tab.desktopMode) {
                    tab.desktopMode = perSite.desktopMode
                    try { NovaWebView.applyDesktopMode(view, tab) } catch (_: Exception) { }
                }
            }
        }
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
        // NOVA Extensions (v2.0 W2) — userscripts + built-ins. The YouTube
        // Ad Skipper is now registry-owned (its own toggle in Settings
        // still controls it); the registry also serves installed
        // userscripts and the Cookie Banner Hider.
        injectExtensions(view, url)
        // W4: the reader is open — re-extract for this page, never show
        // the previous page's article.
        refreshReaderIfOpen(view, url)
    }

    /** W4: if the reader is open and the page changed, re-extract the
     *  article for the new page. A null result closes the reader (the
     *  new page has no readable article) instead of showing stale text.
     *  Skipped when the URL is unchanged — onPageFinished fires for the
     *  page the reader was extracted from too. */
    /** L8: one extraction in flight at a time — onPageFinished and
     *  doUpdateVisitedHistory can fire for the same navigation, and two
     *  concurrent extractions are duplicate work (last-wins). */
    private var readerExtractInFlight = false

    private fun refreshReaderIfOpen(view: WebView, url: String) {
        if (state.readerArticle == null) return
        if (state.readerUrl == url) return
        if (readerExtractInFlight) return
        readerExtractInFlight = true
        ReaderRunner.extractArticle(view) { article ->
            readerExtractInFlight = false
            if (article == null) {
                state.readerArticle = null
                state.readerUrl = null
                state.status("No readable article on this page")
            } else {
                state.readerArticle = article
                state.readerUrl = url
            }
            // The page may have moved on while extracting — re-check so
            // the reader never shows the previous page's article.
            val currentUrl = view.url
            if (currentUrl != null && currentUrl != url) refreshReaderIfOpen(view, currentUrl)
        }
    }

    /** Re-inject the skipper on SPA navigations. YouTube changes videos
     *  without a page load, so onPageStarted/onPageFinished do not fire —
     *  without this, the skipper would go stale after the first video.
     *  This is also where the tab URL stays honest on SPA navigations
     *  (the address bar would otherwise show the previous video). Runs
     *  on the main thread, so evaluateJavascript is legal here. */
    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (UrlUtils.isHttpUrl(url)) {
            tab.url = url
            injectExtensions(view, url)
            // W4: SPA navigation while the reader is open — same rule as
            // onPageFinished: re-extract, never show a stale article.
            refreshReaderIfOpen(view, url)
        }
    }

    // REMOVED in v2.0 (W6 N-3): the old standalone skipper injector
    // (injectYoutubeSkipper) is gone — the skipper is registry-owned now
    // and served by injectExtensions below, gated by the same Settings
    // toggle AND the sensitive-page rule. The old call sites bypassed S-1
    // (they ran the skipper on YouTube login pages).

    /** B-2: last URL injected for this navigation. onPageFinished
     *  AND doUpdateVisitedHistory both fire per page load — without
     *  this, every user script would inject twice (double buttons,
     *  double handlers). Built-ins are safe either way (their own
     *  __nova guards), but user scripts get no guard. Reset in
     *  onPageStarted; SPA navigations bring a new URL, so they still
     *  re-inject once — and navigating BACK to an earlier SPA URL
     *  re-injects too (AUDIT 2 F05-B: the old set never forgot it). */
    private val injectionDedupe = ExtensionRuntime.InjectionDedupe()

    /** Inject NOVA Extensions userscripts (v2.0 W2). Two-phase: (1) probe
     *  the page for a password field — a page with one is a sensitive page
     *  (W6 S-1); (2) inject what the runtime returns for this URL,
     *  document-start first. In :incognito, loadEffectiveScripts returns
     *  built-ins only (W6 S-5). Main thread, so evaluateJavascript is legal. */
    private fun injectExtensions(view: WebView, url: String) {
        if (!UrlUtils.isHttpUrl(url)) return
        // B-2: one injection per URL per navigation (see injectionDedupe).
        if (!injectionDedupe.shouldInject(url)) return
        val context = view.context
        val scripts = try {
            ExtensionStore.loadEffectiveScripts(context, state.settings.youtubeAdSkipper) { asset ->
                runCatching { context.assets.open(asset).bufferedReader().readText() }.getOrNull()
            }
        } catch (_: Exception) { return }
        if (scripts.isEmpty()) return
        try {
            view.evaluateJavascript(ExtensionRuntime.PASSWORD_FIELD_PROBE_JS) { result ->
                // B-6: the probe is async — between the probe and this
                // callback an SPA/user navigation may have replaced the
                // page. Scripts selected for URL A must never inject
                // into page B (including a sensitive page B). Drop the
                // injection when the WebView has moved on.
                if (view.url != url) return@evaluateJavascript
                val probe = ExtensionRuntime.PasswordProbeResult.fromProbeResult(result)
                if (probe == ExtensionRuntime.PasswordProbeResult.UNKNOWN) {
                    // F05 fail closed: user scripts are blocked for this
                    // page; built-ins still run unless the host is
                    // sensitive. Log distinctly so a probe outage is
                    // visible instead of silent.
                    ErrorLog.log(
                        state.context, "Extensions",
                        "Password-field probe inconclusive for $url — user scripts blocked (fail closed), built-ins gated by host list only",
                        null
                    )
                }
                val toInject = try {
                    ExtensionRuntime.scriptsForUrl(scripts, url, state.settings.youtubeAdSkipper, probe)
                } catch (_: Exception) { emptyList() }
                for (script in toInject) {
                    try { view.evaluateJavascript(script.source, null) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { /* a page that rejects injection keeps working */ }
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

    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
        if (message != null) {
            DevToolsHub.recordConsole(
                tab.id,
                message.messageLevel().name,
                message.message() ?: "",
                message.sourceId(),
                message.lineNumber()
            )
        }
        return super.onConsoleMessage(message)
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
