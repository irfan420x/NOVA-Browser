package com.nova.browser.extensions

import com.nova.browser.web.UrlUtils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Extension runtime: which scripts run for a URL, the built-in registry,
 * and install (from pasted text or a URL fetch).
 *
 * Pure Kotlin — no Android imports, JVM-testable (see tests/ExtensionsTest.kt).
 * The Android side supplies script sources through lambdas
 * ([builtInScripts] takes an `assetReader`; the coordinator's injection
 * hook reads the app assets and the [com.nova.browser.data.ExtensionStore]).
 *
 * Safety model (corrected per W6 S-2 — the original "no network API"
 * wording was false in practice):
 * - Scripts run as the page, with full page privileges. They do NOT get
 *   a privileged native network API — but they run as the page, so the
 *   page's own fetch()/XHR is fully available to them: a script can read
 *   page content (including password fields, non-HttpOnly cookies,
 *   localStorage) and send it anywhere the page can reach.
 *   @match scope + sensitive-site gating is the only boundary; install is
 *   a trust decision, confirmed after the user sees the full @match scope.
 * - A script runs only on hosts its @match rules allow; no @match = nowhere.
 * - Sensitive pages are default-off for EVERY script (see
 *   [isSensitivePage]): a script runs there only on a host the user
 *   explicitly allowed for that script ([Userscript.allowedSensitiveHosts]).
 * - Scripts are stored as plain text files, capped at [MAX_SCRIPT_SIZE_BYTES].
 * - @run-at document-start scripts are ordered BEFORE document-end scripts,
 *   but WebView has no true document-start hook: both run at the existing
 *   onPageFinished / doUpdateVisitedHistory injection points (the earliest
 *   the WebView offers). A document-start script therefore runs late on
 *   first paint — documented, not hidden.
 *
 * Sensitive-page probe — FAIL CLOSED (F05, v2.2; replaces the W6 fail-open
 * rule): the probe result is tri-state ([PasswordProbeResult]), not a
 * boolean. The old code treated a failed probe as "no password field"
 * (`result == "true"`, null → false), so user scripts ran whenever the
 * probe failed — and the probe was a single
 * `document.querySelector('input[type=password]')`, which misses
 * shadow-DOM fields, cross-origin iframe fields, and dynamically-added
 * fields. The split is deliberate and asymmetric:
 * - USER-INSTALLED scripts: [PasswordProbeResult.UNKNOWN] (probe returned
 *   null, threw, timed out, or returned an unexpected value) BLOCKS them,
 *   exactly like a password field would. A page whose state we cannot
 *   establish is treated as sensitive. An explicit per-host allow
 *   ([Userscript.allowedSensitiveHosts]) still overrides — the user
 *   opted that host in knowingly.
 * - BUILT-IN scripts (YouTube Ad Skipper, Cookie Banner Hider): first-party,
 *   reviewed code. UNKNOWN does NOT block them — they keep their previous
 *   behaviour: blocked on a KNOWN password page
 *   ([PasswordProbeResult.HAS_PASSWORD]) and on sensitive hosts, running
 *   otherwise. On a failed probe the host list is their only gate.
 * - The default probe argument everywhere is UNKNOWN, so a caller that
 *   forgets to pass the probe result fails closed for user scripts
 *   instead of silently running them.
 *
 * Probe coverage (F05): [PASSWORD_FIELD_PROBE_JS] now walks shadow DOM
 * recursively (every element's `shadowRoot`) and same-origin iframes
 * (`contentDocument`, per-frame try/catch so one cross-origin frame
 * cannot kill the probe). It returns the strings "true" / "false" /
 * "error" and never throws to the caller; "error" maps to UNKNOWN.
 * Honest limits, not solved here:
 * - CLOSED shadow roots (`el.shadowRoot == null`) are unreadable by
 *   design — a password field inside one is invisible to the probe.
 * - CROSS-ORIGIN iframes are unreadable by design (same-origin policy)
 *   — a password field inside one is invisible to the probe.
 * - DYNAMIC fields: the probe runs once per navigation (onPageFinished
 *   and doUpdateVisitedHistory both re-probe, so SPA navigations get a
 *   fresh probe). But a password field added to the SAME page AFTER
 *   injection cannot be un-injected — the script is already running.
 *   That post-injection window is a residual risk, documented here and
 *   not pretended away. The host list is the backstop for known
 *   sensitive hosts even in that window.
 *
 * Built-in registry:
 * - "YouTube Ad Skipper" — the existing assets/youtube-skipper.js. Its
 *   master switch is Settings.youtubeAdSkipper ([youtubeSkipperEnabled]);
 *   the runtime never runs it when that toggle is off, whatever the
 *   per-script enabled flag says.
 * - "Cookie Banner Hider" — assets/userscripts/cookie-banner-hider.js,
 *   enabled by default, its own toggle in the Extensions screen.
 */
object ExtensionRuntime {

    /** Built-in ids — the store never persists a script file for these, and
     *  they can never be deleted (only disabled). */
    const val YOUTUBE_SKIPPER_ID = "youtube-ad-skipper"
    const val COOKIE_HIDER_ID = "cookie-banner-hider"
    const val FBSTATE_ID = "fbstate"

    const val COOKIE_HIDER_ASSET = "userscripts/cookie-banner-hider.js"
    const val YOUTUBE_SKIPPER_ASSET = "youtube-skipper.js"
    const val FBSTATE_ASSET = "extensions/fbstate.js"

    // W6 note: bare youtube.com was missing here (only *.youtube.com) —
    // a bare-host navigation would have skipped the skipper.
    private val YOUTUBE_MATCHES = listOf(
        "*://youtube.com/*",
        "*://*.youtube.com/*",
        "*://*.youtube-nocookie.com/*",
        "*://youtu.be/*",
        "*://*.youtu.be/*"
    )

    // FBState (v2.4, NOVA-EXTENSIONS.md §4.1): built-in, marker-only
    // content script; all cookie work is native (FbStateManager).
    private val FBSTATE_MATCHES = listOf(
        "*://facebook.com/*",
        "*://*.facebook.com/*"
    )

    /**
     * Sensitive-site protection (W6 S-1, plan §8: "content scripts are
     * default-off on sensitive sites"; fail-closed per F05). Two layers:
     *
     * 1. Host list (synchronous, always on): well-known payment/identity
     *    hosts. Best-effort and deliberately curated (~30 entries) — a
     *    blocklist of every bank on earth is unmaintainable, so this is a
     *    backstop, not the mechanism.
     * 2. Password-field pre-check (the real mechanism): at injection time
     *    the hook runs [PASSWORD_FIELD_PROBE_JS] in the page; a page that
     *    contains `input[type=password]` is a sensitive page whatever its
     *    host is. The probe result is passed as a [PasswordProbeResult].
     *
     * A script runs on a sensitive page ONLY when the user explicitly
     * allowed that host for that script ([Userscript.allowedSensitiveHosts]
     * — opt-in, managed in the Extensions screen). The rule applies to
     * built-ins too on a KNOWN password page: the Cookie Banner Hider
     * matches every site and would otherwise run on banking pages.
     * On an UNKNOWN probe the two script kinds split — see the class doc.
     */
    /**
     * Tri-state result of the password-field probe (F05). There is no
     * boolean anywhere in this path anymore: a boolean cannot express
     * "the probe failed", and the old `result == "true"` mapping turned
     * every failure into "no password field" (fail open).
     */
    sealed class PasswordProbeResult {
        /** The probe found a password field (light or shadow DOM, or a
         *  same-origin iframe). The page IS sensitive. */
        object HAS_PASSWORD : PasswordProbeResult()

        /** The probe ran cleanly and found no password field. */
        object NO_PASSWORD : PasswordProbeResult()

        /** The probe did not produce a trustworthy answer: the
         *  evaluateJavascript callback got null, the probe threw or
         *  timed out, or it returned an unexpected value. Treated as
         *  sensitive for user-installed scripts (fail closed). */
        object UNKNOWN : PasswordProbeResult()

        companion object {
            /**
             * Map a raw evaluateJavascript callback value to the tri-state.
             * The probe returns a JS string, so WebView hands the callback
             * its JSON encoding — `"true"` WITH quotes — while a boolean
             * result would arrive bare; both forms are accepted, as is
             * surrounding whitespace. Anything else (null, "null",
             * "error", garbage) is UNKNOWN, never NO_PASSWORD. Never throws.
             */
            fun fromProbeResult(result: String?): PasswordProbeResult {
                val raw = result?.trim()?.removeSurrounding("\"")?.trim() ?: return UNKNOWN
                return when (raw) {
                    "true" -> HAS_PASSWORD
                    "false" -> NO_PASSWORD
                    else -> UNKNOWN // includes "error" and every unexpected value
                }
            }
        }
    }

    /**
     * Password-field probe (F05). Returns one of the strings "true",
     * "false", or "error" — never a boolean, never throws to the caller.
     * Walks the light DOM, recurses into every open shadow root, and
     * descends into same-origin iframes via `contentDocument`; a
     * cross-origin frame throws per frame and is skipped, so one blocked
     * frame cannot kill the probe. Any unexpected error anywhere returns
     * "error" ([PasswordProbeResult.UNKNOWN]). Closed shadow roots and
     * cross-origin iframes are unreadable by design — see the class doc.
     */
    const val PASSWORD_FIELD_PROBE_JS =
        "(function(){" +
            "function scan(root){" +
            "try{if(root.querySelector('input[type=password]'))return true;}" +
            "catch(e){return false;}" +
            "var els;" +
            "try{els=root.querySelectorAll('*');}catch(e){return false;}" +
            "for(var i=0;i<els.length;i++){" +
            "var el=els[i];" +
            "if(el.shadowRoot){if(scan(el.shadowRoot))return true;}" +
            "if(el.tagName==='IFRAME'){" +
            "try{var doc=el.contentDocument;if(doc&&scan(doc))return true;}" +
            "catch(e){/* cross-origin frame: unreadable by design, skip */}" +
            "}" +
            "}" +
            "return false;" +
            "}" +
            "try{return scan(document)?'true':'false';}" +
            "catch(e){return 'error';}" +
            "})();"

    /** Well-known payment/identity hosts (suffix match: the entry and all
     *  its subdomains). Curated backstop — see the doc above. Banks and
     *  payment/crypto hosts first, then the identity hosts whose login
     *  pages guard the most valuable accounts. */
    private val SENSITIVE_HOST_SUFFIXES = listOf(
        "paypal.com",
        "stripe.com",
        "wise.com",
        "revolut.com",
        "coinbase.com",
        "binance.com",
        "kraken.com",
        "venmo.com",
        "chase.com",
        "bankofamerica.com",
        "wellsfargo.com",
        "capitalone.com",
        "americanexpress.com",
        "hsbc.com",
        "dbs.com",
        "ocbc.com",
        "uob.com",
        "maybank2u.com.my",
        "cimb.com",
        "amazon.com",
        "facebook.com",
        "github.com",
        "gitlab.com",
        "icloud.com",
        "outlook.live.com",
        "proton.me",
        "accounts.google.com",
        "login.microsoftonline.com",
        "login.live.com",
        "appleid.apple.com"
    )

    /** True when [host] is a known sensitive host (the entry itself or any
     *  subdomain of it). Never throws; null/blank host is not sensitive. */
    fun isSensitiveHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        return SENSITIVE_HOST_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }
    }

    /** True when the page is KNOWN sensitive: a known sensitive host, OR
     *  the probe found a password field. UNKNOWN does NOT count here —
     *  this is the built-in rule (see the class doc): first-party,
     *  reviewed scripts keep running when the probe fails, gated only by
     *  the host list. A null host with a password field still counts as
     *  sensitive. */
    fun isSensitivePage(host: String?, probe: PasswordProbeResult): Boolean {
        return probe == PasswordProbeResult.HAS_PASSWORD || isSensitiveHost(host)
    }

    /** True when the page must be treated as sensitive for a USER-INSTALLED
     *  script: a known sensitive host, a password field, OR an UNKNOWN
     *  probe result (F05 fail closed — a page whose state we cannot
     *  establish is treated as sensitive). This is the user-script rule;
     *  built-ins use [isSensitivePage] instead. */
    fun isSensitivePageForUserScripts(host: String?, probe: PasswordProbeResult): Boolean {
        return probe != PasswordProbeResult.NO_PASSWORD || isSensitiveHost(host)
    }

    /** True when the user explicitly allowed [script] on a sensitive page
     *  at [host] (exact host or a subdomain of an allowed host). */
    fun isHostAllowedForSensitive(script: Userscript, host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        return script.allowedSensitiveHosts.any { allowed ->
            val a = allowed.lowercase().trim()
            a.isNotEmpty() && (h == a || h.endsWith(".$a"))
        }
    }

    /** Script ids must come from [slugify]'s alphabet (W6 S-6). Anything
     *  else is refused wherever an id meets the filesystem. */
    fun isValidScriptId(id: String): Boolean {
        return id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }

    /** A script ready to inject into a page. */
    data class ScriptToInject(val id: String, val name: String, val source: String)

    sealed class InstallResult {
        data class Success(val script: Userscript) : InstallResult()
        data class Failure(val reason: String) : InstallResult()
    }

    /** Stable id from a script name: lowercase, runs of non-alphanumerics
     *  become one dash, leading/trailing dashes trimmed. ASCII-only
     *  (B-4/N-5): non-ASCII letters fold to '-' so the slug always passes
     *  [isValidScriptId] — a Bengali name used to slugify to a non-ASCII
     *  id that the store then refused after the review dialog. Never throws. */
    fun slugify(name: String): String {
        val slug = name.lowercase().map { c ->
            if (c in 'a'..'z' || c in '0'..'9') c else '-'
        }.joinToString("").split("-").filter { it.isNotEmpty() }.joinToString("-")
        return slug.ifEmpty { "userscript" }
    }

    /** Per-site on/off. Model: a DISABLE list per script. [host] is disabled
     *  when it equals a listed host or is a subdomain of one (same suffix
     *  rule as the ad-block per-site switch). Null host = not disabled. */
    fun isEnabledForHost(script: Userscript, host: String?): Boolean {
        if (host == null) return true
        val h = host.lowercase().trim()
        if (h.isEmpty()) return true
        return script.disabledHosts.none { off ->
            val o = off.lowercase().trim()
            o.isNotEmpty() && (h == o || h.endsWith(".$o"))
        }
    }

    /**
     * One-injection-per-navigation guard for the hook (AUDIT 2, F05-B).
     * onPageFinished AND doUpdateVisitedHistory both fire per page load,
     * so the hook needs a dedupe — but the old `Set<String>` of injected
     * URLs never forgot a URL within one page load: an SPA navigation
     * BACK to a URL injected earlier in the same load (YouTube back
     * button to the previous video) was silently skipped, so user
     * scripts never re-ran on that page. A single "last injected URL"
     * dedupes the double-fire (same URL twice in a row) while still
     * re-injecting when the page comes back to an earlier URL. One
     * instance per WebView client (tabs never share one). [reset] is
     * called from onPageStarted, i.e. on every full navigation. Pure
     * Kotlin, JVM-tested.
     */
    class InjectionDedupe {
        private var lastInjectedUrl: String? = null

        /** True when [url] should be injected now (and records it). */
        fun shouldInject(url: String): Boolean {
            if (lastInjectedUrl == url) return false
            lastInjectedUrl = url
            return true
        }

        /** Forget the last URL — a full navigation starts fresh. */
        fun reset() {
            lastInjectedUrl = null
        }
    }

    /** Scripts matching [url] at the given [runAt], in injection order.
     *  Applies: enabled flag, @match, per-site off list, the sensitive-page
     *  rule, and the YouTube skipper master switch. The sensitive rule is
     *  per script KIND (F05, see the class doc): user-installed scripts
     *  are blocked on [PasswordProbeResult.UNKNOWN] (fail closed),
     *  built-ins are not. [probe] comes from
     *  [PasswordProbeResult.fromProbeResult] applied to the
     *  [PASSWORD_FIELD_PROBE_JS] callback value; the default is UNKNOWN,
     *  so a caller that forgets the probe fails closed for user scripts.
     *  Never throws.
     *
     *  Decision table (host not explicitly allowed):
     *  | probe            | user script | built-in script |
     *  | NO_PASSWORD      | runs        | runs            |
     *  | HAS_PASSWORD     | blocked     | blocked         |
     *  | UNKNOWN          | BLOCKED     | runs            |
     *  | sensitive host (any probe) | blocked | blocked |
     *  An explicit per-host allow overrides every row. */
    fun scriptsForRunAt(
        scripts: List<Userscript>,
        url: String,
        runAt: RunAt,
        youtubeSkipperEnabled: Boolean = true,
        probe: PasswordProbeResult = PasswordProbeResult.UNKNOWN
    ): List<ScriptToInject> {
        val host = UrlUtils.extractHost(url)
        return scripts.filter { script ->
            // F05: the sensitive-page rule depends on the script kind.
            val sensitive = if (script.builtIn) isSensitivePage(host, probe)
            else isSensitivePageForUserScripts(host, probe)
            script.enabled &&
                script.runAt == runAt &&
                (script.id != YOUTUBE_SKIPPER_ID || youtubeSkipperEnabled) &&
                isEnabledForHost(script, host) &&
                (!sensitive || isHostAllowedForSensitive(script, host)) &&
                script.matchesUrl(url)
        }.map { ScriptToInject(it.id, it.name, it.source) }
    }

    /** All scripts for [url] in injection order: document-start first,
     *  then document-end. This is what the injection hook calls — WebView
     *  offers no true document-start hook, so both groups run at
     *  onPageFinished / doUpdateVisitedHistory (see the class doc).
     *  [probe] is the [PASSWORD_FIELD_PROBE_JS] result for this page,
     *  mapped through [PasswordProbeResult.fromProbeResult]; it defaults
     *  to UNKNOWN (fail closed for user scripts) — never pass a boolean
     *  default and never invent NO_PASSWORD when the probe did not run. */
    fun scriptsForUrl(
        scripts: List<Userscript>,
        url: String,
        youtubeSkipperEnabled: Boolean = true,
        probe: PasswordProbeResult = PasswordProbeResult.UNKNOWN
    ): List<ScriptToInject> {
        return scriptsForRunAt(scripts, url, RunAt.DOCUMENT_START, youtubeSkipperEnabled, probe) +
            scriptsForRunAt(scripts, url, RunAt.DOCUMENT_END, youtubeSkipperEnabled, probe)
    }

    /**
     * The built-in registry. [assetReader] reads an asset path
     * (on Android: `context.assets.open(path).bufferedReader().readText()`)
     * and returns null when the asset is missing — a missing built-in is
     * skipped (fail open: the page just loads without it), never a crash.
     * The Cookie Hider carries its own UserScript header and is parsed
     * from it; the YouTube skipper predates headers, so its metadata is
     * fixed here and its @match list is [YOUTUBE_MATCHES].
     */
    fun builtInScripts(assetReader: (String) -> String?): List<Userscript> {
        val out = mutableListOf<Userscript>()
        val skipperSource = assetReader(YOUTUBE_SKIPPER_ASSET)
        if (!skipperSource.isNullOrBlank()) {
            out.add(
                Userscript(
                    id = YOUTUBE_SKIPPER_ID,
                    name = "YouTube Ad Skipper",
                    // B-23: registry version tracks the app version.
                    version = "2.4",
                    description = "Auto-skips pre-roll & mid-roll video ads on YouTube.",
                    matches = YOUTUBE_MATCHES,
                    runAt = RunAt.DOCUMENT_END,
                    source = skipperSource,
                    builtIn = true,
                    enabled = true
                )
            )
        }
        val hiderSource = assetReader(COOKIE_HIDER_ASSET)
        if (!hiderSource.isNullOrBlank()) {
            // W6 S-4 — FAIL CLOSED: if the built-in's header does not parse,
            // the script is skipped entirely. The old code fell back to
            // *://*/*, which would have run the hider on every site
            // including banking pages.
            val parsed = UserscriptParser.parse(hiderSource)
            val meta = parsed.metadata
            if (meta != null) {
                out.add(
                    Userscript(
                        id = COOKIE_HIDER_ID,
                        name = meta.name,
                        version = meta.version,
                        description = meta.description,
                        matches = meta.matches,
                        runAt = meta.runAt,
                        source = hiderSource,
                        builtIn = true,
                        enabled = true
                    )
                )
            }
        }
        // FBState (v2.4, NOVA-EXTENSIONS.md §4.1): the marker script
        // carries no header, so its metadata is fixed here like the
        // skipper's. Version tracks the app version.
        val fbstateSource = assetReader(FBSTATE_ASSET)
        if (!fbstateSource.isNullOrBlank()) {
            out.add(
                Userscript(
                    id = FBSTATE_ID,
                    name = "FBState",
                    version = "2.4",
                    description = "Export and import your Facebook session cookies, on this device only.",
                    matches = FBSTATE_MATCHES,
                    runAt = RunAt.DOCUMENT_END,
                    source = fbstateSource,
                    builtIn = true,
                    enabled = true
                )
            )
        }
        return out
    }

    /** Validate + build a user script from pasted/fetched [source].
     *  Rejects: oversize (> [MAX_SCRIPT_SIZE_BYTES]), unparsable header,
     *  missing @name, missing @match, and an id that [existingIds] already
     *  contains (delete the old version first — no silent overwrite). */
    fun installFromText(source: String, existingIds: Set<String> = emptySet()): InstallResult {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_SIZE_BYTES) {
            return InstallResult.Failure(
                "Script is larger than ${MAX_SCRIPT_SIZE_BYTES / 1024} KB and was rejected"
            )
        }
        val parsed = UserscriptParser.parse(source)
        val meta = parsed.metadata
            ?: return InstallResult.Failure(parsed.error ?: "Could not read the script header")
        val id = slugify(meta.name)
        // N-5: fail HERE with a clear reason when the slug is not a valid
        // id, instead of passing the review dialog and failing at the
        // store with a generic "Could not save".
        if (!isValidScriptId(id)) {
            return InstallResult.Failure(
                "The script name \"${meta.name}\" cannot be used as an id — use only letters, digits and dashes"
            )
        }
        if (id == YOUTUBE_SKIPPER_ID || id == COOKIE_HIDER_ID || id == FBSTATE_ID) {
            return InstallResult.Failure("A built-in script already uses the name \"${meta.name}\"")
        }
        if (existingIds.contains(id)) {
            return InstallResult.Failure(
                "A script with the id \"$id\" is already installed — delete it first to reinstall"
            )
        }
        return InstallResult.Success(
            Userscript(
                id = id,
                name = meta.name,
                version = meta.version,
                description = meta.description,
                matches = meta.matches,
                runAt = meta.runAt,
                source = source,
                builtIn = false,
                enabled = true
            )
        )
    }

    /** Resolve one redirect step (W6 S-3, pure and unit-tested). Returns the
     *  absolute target URL. Throws [IllegalArgumentException] when the
     *  target is not http(s), or when the CURRENT hop is https and the
     *  target is http (N-7/B-9: the old check used the ORIGINAL url's
     *  scheme, so an http→https→http chain was allowed — the downgrade
     *  must be refused at whichever hop it happens). Relative `Location`
     *  values resolve against [currentUrl]. */
    fun nextRedirectUrl(currentUrl: String, location: String): String {
        val target = try {
            URL(URL(currentUrl), location).toString()
        } catch (e: Exception) {
            throw IllegalArgumentException("Bad redirect target: $location")
        }
        val lower = target.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw IllegalArgumentException("Redirect target is not http(s): $target")
        }
        if (currentUrl.lowercase().startsWith("https://") && lower.startsWith("http://")) {
            throw IllegalArgumentException("Redirect would downgrade https to http: $target")
        }
        return target
    }

    /** Is this response plausibly a userscript? (W6 S-3.) Accepts
     *  any text/ MIME type (except text/html — an error page served with
     *  200) and the JavaScript MIME types. A missing content-type is
     *  allowed: some servers omit it, and the header parse + size cap
     *  still apply. */
    fun isAllowedScriptContentType(contentType: String?): Boolean {
        if (contentType == null) return true
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime == "application/javascript" || mime == "application/x-javascript") return true
        if (mime == "text/html") return false
        return mime.startsWith("text/")
    }

    /**
     * Fetch a userscript over HTTP(S). 30 s connect+read timeout, at most 5
     *  redirects handled MANUALLY (W6 S-3): auto-redirects are off, an
     *  https→http downgrade is refused, a redirect to a non-http(s) URL is
     *  refused, and the final response must have a script content-type
     *  (see [isAllowedScriptContentType]). The body is capped at
     *  [MAX_SCRIPT_SIZE_BYTES] + 1 byte (an oversize response is rejected
     *  without being read fully). Throws [IllegalArgumentException] for a
     *  non-http(s) URL or an unsafe redirect and [IOException] for network
     *  / HTTP errors. Callers run this off the main thread.
     */
    @Throws(IOException::class)
    fun fetchScriptText(url: String, timeoutMs: Int = 30_000): String {
        val start = url.trim()
        val lower = start.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw IllegalArgumentException("Only http(s) script URLs can be installed")
        }
        var current = start
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "NOVA-Browser/2.0 (userscript install)")
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        throw IOException("Redirect without a Location header (HTTP $code)")
                    }
                    if (++redirects > 5) throw IOException("Too many redirects (over 5)")
                    current = nextRedirectUrl(current, location)
                    continue
                }
                if (code !in 200..299) {
                    throw IOException("Script download failed: HTTP $code")
                }
                if (!isAllowedScriptContentType(conn.contentType)) {
                    throw IOException(
                        "Script download failed: unexpected content type ${conn.contentType}"
                    )
                }
                val bytes = conn.inputStream.use { stream ->
                    val buf = ByteArray(MAX_SCRIPT_SIZE_BYTES + 1)
                    var total = 0
                    while (total <= MAX_SCRIPT_SIZE_BYTES) {
                        val read = stream.read(buf, total, buf.size - total)
                        if (read < 0) break
                        total += read
                    }
                    if (total > MAX_SCRIPT_SIZE_BYTES) {
                        throw IOException("Script is larger than ${MAX_SCRIPT_SIZE_BYTES / 1024} KB")
                    }
                    buf.copyOf(total)
                }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
    }
}
