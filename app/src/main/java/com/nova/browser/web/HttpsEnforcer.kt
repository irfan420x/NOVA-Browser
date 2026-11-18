package com.nova.browser.web

/**
 * HTTPS-only enforcement at the intercept level (F01, v2.2).
 *
 * Pure Kotlin — no Android imports, JVM-testable. The Android half lives
 * in NovaClients.shouldInterceptRequest (see the hook spec in the F01
 * report); this file is only the decision. It must never do I/O: it
 * cannot probe whether a host actually serves https, so it cannot know
 * that on its own — see [HttpsDecision.BLOCK] for how "cannot be
 * upgraded" is expressed without a network probe.
 *
 * THE HOLE THIS CLOSES
 * --------------------
 * Before F01, HTTPS-only upgraded ONLY top-level navigations, in
 * shouldOverrideUrlLoading (and in BrowserState.loadUrl for typed
 * input). Everything else reached the network as plain http:
 * sub-resources (images, scripts, stylesheets), iframes, fetch/XHR,
 * and — the worst case — an https page that redirects to an http URL
 * (a downgrade redirect: shouldOverrideUrlLoading is not called for
 * server redirects, so the redirect target sailed straight through).
 * shouldInterceptRequest did no HTTPS enforcement at all; it only
 * ad-blocked GET sub-resources.
 *
 * WHY network_security_config.xml STAYS cleartextTrafficPermitted="true"
 * ----------------------------------------------------------------------
 * Do NOT "fix" this by flipping that flag to false. The flag is a
 * system-level switch with no per-host nuance: false would block even
 * http://localhost:3000 and http://192.168.1.5, and local development
 * servers legitimately serve plain http with no https available.
 * Enforcement therefore lives HERE, in the interceptor, where the
 * decision can exempt localhost/LAN hosts (and the user's explicit
 * per-site exceptions) while upgrading or blocking everything else.
 * The config stays permissive for local hosts only; this enforcer is
 * the gate for every other host.
 *
 * DECISION TABLE (see [decide])
 * ------------------------------
 *  HTTPS-only OFF                    -> ALLOW (everything)
 *  URL is not http://                -> ALLOW (https, ftp, data:, about:, ws:, ...)
 *  http, localhost / LAN host        -> ALLOW (UrlUtils.isLocalHost)
 *  http, host in httpsExceptionHosts -> ALLOW (explicit user choice)
 *  http, main frame, NOT a redirect  -> ALLOW — SKIP. The navigation
 *      path (shouldOverrideUrlLoading / BrowserState.loadUrl) already
 *      owns main-frame navigations and upgrades them there; handling
 *      them again here would double-handle the same navigation.
 *  http, main frame, IS a redirect   -> UPGRADE (or BLOCK, below).
 *      This is the redirect hole: a redirect target arrives in
 *      shouldInterceptRequest as a fresh request, never having passed
 *      through shouldOverrideUrlLoading.
 *  http, sub-resource                -> UPGRADE (or BLOCK, below)
 *  http, no host at all ("http://")  -> BLOCK (nothing to upgrade to)
 *  http, host in upgradeFailedHosts  -> BLOCK (a previous https upgrade
 *      for this host is known to have failed, so re-upgrading would
 *      loop / fail again; the user can still allow it explicitly via
 *      a per-site https exception, which is checked first)
 *
 * UPGRADE means: return a 307 redirect response to [upgradeUrl] from
 * shouldInterceptRequest — WebView follows redirect responses returned
 * from the interceptor. BLOCK means: return an empty response and log
 * it (same empty-response shape the ad blocker already uses).
 *
 * HONEST LIMITS
 * -------------
 * - The enforcer cannot tell, without a network probe, whether a host
 *   "has no https". A host whose https endpoint is down or absent is
 *   UPGRADEd, not BLOCKed, unless the caller already knows the upgrade
 *   failed and passes the host in [upgradeFailedHosts]. Today no caller
 *   populates that set (BrowserState.loadHttpAnyway records the failure
 *   as a per-site exception instead, which is checked first and wins),
 *   so in practice BLOCK fires only for malformed http URLs with no
 *   host. That is deliberate: fail towards upgrading, never towards
 *   silently loading http.
 * - ws:// / wss:// WebSocket upgrades are invisible to
 *   shouldInterceptRequest (WebView does not route WebSocket handshakes
 *   through it), so a ws:// socket on an https page is NOT upgraded or
 *   blocked by this enforcer. Same for any request WebView itself
 *   handles without calling the interceptor.
 * - A plain main-frame http navigation that somehow bypasses
 *   shouldOverrideUrlLoading for a reason OTHER than a redirect is
 *   skipped here by design (see the table) and would load as http.
 *   The navigation path is the single owner for that case; if it ever
 *   misses one, this file will not catch it.
 */
object HttpsEnforcer {

    /** Outcome of [decide]. */
    enum class HttpsDecision {
        /** Let the request proceed unchanged. */
        ALLOW,
        /** Redirect the request to its https URL ([upgradeUrl]). */
        UPGRADE,
        /** Refuse the request: return an empty response and log it. */
        BLOCK
    }

    /** HTTP status + header the hook uses to build the upgrade response:
     *  307 preserves the request method (a 301/302 would turn a POST
     *  into a GET); WebView follows it from shouldInterceptRequest. */
    const val REDIRECT_STATUS_CODE = 307
    const val REDIRECT_REASON_PHRASE = "Temporary Redirect"
    const val REDIRECT_HEADER_NAME = "Location"

    /**
     * True when a main-frame navigation may be upgraded by reloading
     * the https URL with [WebView.loadUrl] — i.e. the navigation has
     * no body to lose. GET and HEAD carry no body; a null method is
     * treated as GET (WebResourceRequest always reports one, but the
     * hook must not crash on an unexpected null). POST/PUT/PATCH/
     * DELETE and anything else must NOT go through loadUrl: it would
     * silently turn the request into a GET and drop the form/body
     * data. Those are left for the interceptor, which upgrades them
     * with a 307 (see [decide]). AUDIT 2 (F01-A): the old
     * shouldOverrideUrlLoading upgraded EVERY main-frame http
     * navigation via loadUrl, so an https page posting a form to an
     * http endpoint lost the whole submission (login/checkout forms).
     * Never throws.
     */
    fun canUpgradeViaLoadUrl(method: String?): Boolean {
        if (method == null) return true
        val m = method.trim().uppercase()
        return m == "GET" || m == "HEAD"
    }

    /** Response headers for the 307 upgrade to [httpsUrl]. Pure data —
     *  the hook passes this map to the WebResourceResponse constructor. */
    fun redirectHeaders(httpsUrl: String): Map<String, String> =
        mapOf(REDIRECT_HEADER_NAME to httpsUrl)

    /**
     * The https URL for an http URL, preserving everything after the
     * scheme (host, port, path, query, fragment). Returns null when the
     * URL is not http://, or when it has no host to upgrade to
     * ("http://" / "http:///path"). Never throws. Scheme matching is
     * case-insensitive ("HTTP://EXAMPLE.COM" upgrades, keeping the rest
     * of the URL exactly as written).
     */
    fun upgradeUrl(url: String): String? {
        if (!url.lowercase().startsWith("http://")) return null
        if (UrlUtils.extractHost(url) == null) return null
        return url.replaceFirst("http://", "https://", ignoreCase = true)
    }

    /**
     * Per-site https-exception check, mirroring Settings.isHttpsException:
     * exact host match, or any subdomain of an exception host
     * ("sub.example.com" is exempt when "example.com" is). The dot
     * boundary matters — "badexample.com" and "example.com.evil.com"
     * do NOT match "example.com". Never throws.
     */
    fun isHttpsExceptionHost(host: String?, exceptionHosts: Set<String>): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return exceptionHosts.any { ex ->
            val e = ex.lowercase()
            h == e || h.endsWith(".$e")
        }
    }

    /** Same suffix rule as [isHttpsExceptionHost], for hosts whose https
     *  upgrade is already known to have failed. */
    fun isUpgradeFailedHost(host: String?, failedHosts: Set<String>): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return failedHosts.any { f ->
            val e = f.lowercase()
            h == e || h.endsWith(".$e")
        }
    }

    /**
     * Is [requestUrl] an https -> http downgrade redirect of [pageUrl]
     * (the URL of the page currently in the tab, i.e. tab.url)? This is
     * how the hook tells a redirect target apart from a fresh main-frame
     * navigation: WebResourceRequest carries no redirect flag, but the
     * tab's current URL is maintained on the main thread and is safe to
     * read from the interceptor thread. A fresh navigation to an http
     * URL from an http (or blank) page is NOT a downgrade — the
     * navigation path owns that case.
     */
    fun isDowngradeRedirect(requestUrl: String, pageUrl: String?): Boolean {
        if (pageUrl.isNullOrBlank()) return false
        return requestUrl.lowercase().startsWith("http://") &&
            pageUrl.lowercase().startsWith("https://")
    }

    /**
     * Decide what happens to [url] when it arrives in
     * shouldInterceptRequest.
     *
     * @param url the request URL (request.url.toString()).
     * @param httpsOnly Settings.httpsOnly.
     * @param httpsExceptionHosts Settings.httpsExceptionHosts — hosts the
     *   user explicitly allowed to load over plain http.
     * @param isForMainFrame request.isForMainFrame.
     * @param isRedirect true when this request is a redirect target —
     *   for a main-frame request the hook computes this with
     *   [isDowngradeRedirect] against tab.url (see [decideForRequest]).
     *   Irrelevant for sub-resources: they are upgraded whether or not
     *   they are redirects, because they never pass through
     *   shouldOverrideUrlLoading.
     * @param upgradeFailedHosts hosts whose https upgrade is already
     *   known to have failed. Default empty: with no such knowledge the
     *   enforcer upgrades rather than blocking. The caller is
     *   responsible for populating this; today nothing does.
     * @param method the request method (WebResourceRequest.method), or
     *   null when unknown. Only matters for a main-frame http request
     *   that is NOT a redirect: GET/HEAD (and null) are skipped — the
     *   navigation path owns them — but a POST/PUT/... is UPGRADEd
     *   here, because the navigation path cannot upgrade it without
     *   dropping its body (see [canUpgradeViaLoadUrl]); the 307 from
     *   the interceptor preserves the method. AUDIT 2 (F01-A).
     */
    fun decide(
        url: String,
        httpsOnly: Boolean,
        httpsExceptionHosts: Set<String> = emptySet(),
        isForMainFrame: Boolean = false,
        isRedirect: Boolean = false,
        upgradeFailedHosts: Set<String> = emptySet(),
        method: String? = null
    ): HttpsDecision {
        // HTTPS-only off: everything is allowed, no decision to make.
        if (!httpsOnly) return HttpsDecision.ALLOW
        // Only plain http is in scope. https, ftp, data:, about:, blob:,
        // ws:// and anything else pass through untouched.
        if (!url.lowercase().startsWith("http://")) return HttpsDecision.ALLOW
        val host = UrlUtils.extractHost(url)
        // Malformed http URL with no host: there is nothing to upgrade
        // to, and loading it as-is is never useful — block it.
        if (host == null) return HttpsDecision.BLOCK
        // Local development servers legitimately serve plain http.
        if (UrlUtils.isLocalHost(host)) return HttpsDecision.ALLOW
        // Explicit per-site user choice wins over enforcement.
        if (isHttpsExceptionHost(host, httpsExceptionHosts)) return HttpsDecision.ALLOW
        // Main-frame navigations are owned by shouldOverrideUrlLoading —
        // skip them here, EXCEPT redirect targets, which never pass
        // through that path (the redirect hole), AND non-GET/HEAD
        // navigations, which that path cannot upgrade without dropping
        // the request body (AUDIT 2 F01-A: the 307 preserves it).
        if (isForMainFrame && !isRedirect && canUpgradeViaLoadUrl(method)) return HttpsDecision.ALLOW
        // A host whose https upgrade is already known to have failed:
        // re-upgrading would fail again, so block instead of looping.
        if (isUpgradeFailedHost(host, upgradeFailedHosts)) return HttpsDecision.BLOCK
        // Everything else upgrades.
        return HttpsDecision.UPGRADE
    }

    /**
     * True when a page load of [loadedUrl] is the very navigation an
     * HTTPS-only upgrade produced: [upgradedUrl] is the https URL the
     * upgrade loaded (recorded on the tab by the upgrade path).
     * AUDIT 2 (F01-B): the tab's `wasUpgradedFromHttp` flag used to
     * survive every later navigation, so a plain https page that
     * failed afterwards wrongly offered "Load with HTTP anyway" — and
     * tapping it recorded a permanent per-site http exception for a
     * site that never had an https problem. onPageStarted keeps the
     * flag only when the loaded URL is the upgraded one. Exact match:
     * a normalised/redirected URL fails the match and the flag is
     * dropped, which is the safe direction (a missing affordance,
     * never a misleading one). Never throws.
     */
    fun isUpgradedTarget(upgradedUrl: String?, loadedUrl: String?): Boolean {
        if (upgradedUrl == null || loadedUrl == null) return false
        return upgradedUrl == loadedUrl
    }

    /**
     * Convenience wrapper for the hook: derives [isRedirect] from the
     * tab's current page URL instead of requiring the caller to compute
     * it. Pass tab.url as [pageUrl] (safe to read from the interceptor
     * thread — the main thread keeps it up to date; never view.url).
     * A null/blank page URL means "no page yet": main-frame requests
     * are then treated as fresh navigations and skipped, per [decide].
     */
    fun decideForRequest(
        url: String,
        httpsOnly: Boolean,
        httpsExceptionHosts: Set<String> = emptySet(),
        isForMainFrame: Boolean = false,
        pageUrl: String? = null,
        upgradeFailedHosts: Set<String> = emptySet(),
        method: String? = null
    ): HttpsDecision = decide(
        url = url,
        httpsOnly = httpsOnly,
        httpsExceptionHosts = httpsExceptionHosts,
        isForMainFrame = isForMainFrame,
        isRedirect = isForMainFrame && isDowngradeRedirect(url, pageUrl),
        upgradeFailedHosts = upgradeFailedHosts,
        method = method
    )
}
