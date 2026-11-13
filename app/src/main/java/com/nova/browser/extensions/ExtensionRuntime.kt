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

    const val COOKIE_HIDER_ASSET = "userscripts/cookie-banner-hider.js"
    const val YOUTUBE_SKIPPER_ASSET = "youtube-skipper.js"

    // W6 note: bare youtube.com was missing here (only *.youtube.com) —
    // a bare-host navigation would have skipped the skipper.
    private val YOUTUBE_MATCHES = listOf(
        "*://youtube.com/*",
        "*://*.youtube.com/*",
        "*://*.youtube-nocookie.com/*",
        "*://youtu.be/*",
        "*://*.youtu.be/*"
    )

    /**
     * Sensitive-site protection (W6 S-1, plan §8: "content scripts are
     * default-off on sensitive sites"). Two layers:
     *
     * 1. Host list (synchronous, always on): well-known payment/identity
     *    hosts. Best-effort and deliberately small — a curated blocklist of
     *    every bank on earth is unmaintainable, so this is a backstop, not
     *    the mechanism.
     * 2. Password-field pre-check (the real mechanism): at injection time
     *    the hook runs [PASSWORD_FIELD_PROBE_JS] in the page; a page that
     *    contains `input[type=password]` is a sensitive page whatever its
     *    host is. The probe result is passed as [hasPasswordField].
     *
     * A script runs on a sensitive page ONLY when the user explicitly
     * allowed that host for that script ([Userscript.allowedSensitiveHosts]
     * — opt-in, managed in the Extensions screen). The rule applies to
     * built-ins too: the Cookie Banner Hider matches every site and would
     * otherwise run on banking pages. Probe failure is fail-OPEN for
     * usability (a page whose CSP blocks the probe still loads and still
     * runs scripts) — the host list is the layer that never depends on
     * the probe.
     */
    const val PASSWORD_FIELD_PROBE_JS =
        "(function(){try{return document.querySelector('input[type=password]')!==null;}" +
            "catch(e){return false;}})();"

    /** Well-known payment/identity hosts (suffix match: the entry and all
     *  its subdomains). Best-effort backstop — see the doc above. */
    private val SENSITIVE_HOST_SUFFIXES = listOf(
        "paypal.com",
        "stripe.com",
        "wise.com",
        "revolut.com",
        "coinbase.com",
        "binance.com",
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

    /** True when the page is sensitive: a known sensitive host, OR the page
     *  contains a password field ([hasPasswordField] comes from
     *  [PASSWORD_FIELD_PROBE_JS] at injection time). A null host with a
     *  password field still counts as sensitive (fail closed). */
    fun isSensitivePage(host: String?, hasPasswordField: Boolean): Boolean {
        return hasPasswordField || isSensitiveHost(host)
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

    /** Scripts matching [url] at the given [runAt], in injection order.
     *  Applies: enabled flag, @match, per-site off list, the sensitive-page
     *  rule ([isSensitivePage] — [hasPasswordField] comes from
     *  [PASSWORD_FIELD_PROBE_JS] at injection time), and the YouTube
     *  skipper master switch. Never throws. */
    fun scriptsForRunAt(
        scripts: List<Userscript>,
        url: String,
        runAt: RunAt,
        youtubeSkipperEnabled: Boolean = true,
        hasPasswordField: Boolean = false
    ): List<ScriptToInject> {
        val host = UrlUtils.extractHost(url)
        val sensitive = isSensitivePage(host, hasPasswordField)
        return scripts.filter { script ->
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
     *  [hasPasswordField] is the [PASSWORD_FIELD_PROBE_JS] result for this
     *  page; pass false only when the probe could not run. */
    fun scriptsForUrl(
        scripts: List<Userscript>,
        url: String,
        youtubeSkipperEnabled: Boolean = true,
        hasPasswordField: Boolean = false
    ): List<ScriptToInject> {
        return scriptsForRunAt(scripts, url, RunAt.DOCUMENT_START, youtubeSkipperEnabled, hasPasswordField) +
            scriptsForRunAt(scripts, url, RunAt.DOCUMENT_END, youtubeSkipperEnabled, hasPasswordField)
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
                    version = "2.0",
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
        if (id == YOUTUBE_SKIPPER_ID || id == COOKIE_HIDER_ID) {
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
