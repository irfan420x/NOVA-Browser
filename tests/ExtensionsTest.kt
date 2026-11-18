package com.nova.browser.extensions

/**
 * JVM unit tests for the extensions engine — plain main() + check(),
 * no framework. Run with tests/run-tests.sh (coordinator adds the sources
 * + this main class; see the hook spec in the W2 report). Exits non-zero
 * on any failure.
 *
 * Covers: header parsing (incl. malformed headers), @match glob matching
 * (incl. subdomain / negative cases), run-at filtering + ordering,
 * per-site on/off, the YouTube skipper master switch, install validation
 * and the 200 KB size cap, and the built-in registry.
 * F05 (v2.2): tri-state password probe (fail closed for user scripts),
 * probe-result mapping, shadow-DOM/iframe probe JS structure, the
 * expanded ~30-entry sensitive host list, and the documented
 * post-injection dynamic-field residual.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private const val GOOD_SCRIPT = """// ==UserScript==
// @name        Dark Mode For Example
// @version     2.3
// @description Forces dark mode on example.com
// @match       *://*.example.com/*
// @match       https://example.org/news/*
// @run-at      document-start
// ==/UserScript==
document.body.style.background = '#000';
"""

private const val NO_MATCH_SCRIPT = """// ==UserScript==
// @name No Match Script
// @version 1.0
// ==/UserScript==
alert(1);
"""

private fun scriptWith(
    name: String = "Test",
    matches: List<String> = listOf("*://*/*"),
    runAt: RunAt = RunAt.DOCUMENT_END,
    enabled: Boolean = true,
    disabledHosts: Set<String> = emptySet(),
    allowedSensitiveHosts: Set<String> = emptySet(),
    builtIn: Boolean = false,
    id: String = "test",
    source: String = "x();"
): Userscript = Userscript(
    id = id, name = name, version = "1.0", description = "",
    matches = matches, runAt = runAt, source = source,
    builtIn = builtIn, enabled = enabled, disabledHosts = disabledHosts,
    allowedSensitiveHosts = allowedSensitiveHosts
)

fun main() {
    // --- Header parsing: valid script ---
    val parsed = UserscriptParser.parse(GOOD_SCRIPT)
    check("valid header parses", parsed.metadata != null && parsed.error == null)
    check("name parsed", parsed.metadata?.name == "Dark Mode For Example")
    check("version parsed", parsed.metadata?.version == "2.3")
    check("description parsed", parsed.metadata?.description == "Forces dark mode on example.com")
    check("both @match lines parsed", parsed.metadata?.matches?.size == 2)
    check("first @match kept", parsed.metadata?.matches?.get(0) == "*://*.example.com/*")
    check("document-start parsed", parsed.metadata?.runAt == RunAt.DOCUMENT_START)

    // --- Header parsing: defaults ---
    val minimal = UserscriptParser.parse(
        "// ==UserScript==\n// @name Minimal\n// @match *://*/*\n// ==/UserScript==\n// code"
    )
    check("minimal header parses", minimal.metadata != null)
    check("version defaults to 1.0", minimal.metadata?.version == "1.0")
    check("run-at defaults to document-end", minimal.metadata?.runAt == RunAt.DOCUMENT_END)
    check("description defaults to empty", minimal.metadata?.description == "")

    // --- Header parsing: malformed ---
    check("no header block fails", UserscriptParser.parse("alert(1);").metadata == null)
    check("no header block explains why",
        UserscriptParser.parse("alert(1);").error?.contains("==UserScript==") == true)
    check("unterminated header fails",
        UserscriptParser.parse("// ==UserScript==\n// @name X\n// @match *://*/*\n").metadata == null)
    check("missing @name fails",
        UserscriptParser.parse("// ==UserScript==\n// @match *://*/*\n// ==/UserScript==\n").metadata == null)
    check("missing @match fails", UserscriptParser.parse(NO_MATCH_SCRIPT).metadata == null)
    check("missing @match explains why",
        UserscriptParser.parse(NO_MATCH_SCRIPT).error?.contains("@match") == true)
    check("empty @match ignored, then fails",
        UserscriptParser.parse("// ==UserScript==\n// @name X\n// @match\n// ==/UserScript==\n").metadata == null)
    check("non-header lines before block are fine",
        UserscriptParser.parse("// a comment\n\n" + GOOD_SCRIPT).metadata?.name == "Dark Mode For Example")
    check("keys are case-insensitive",
        UserscriptParser.parse("// ==UserScript==\n// @Name Mixed Case\n// @MATCH *://*/*\n// ==/UserScript==\n")
            .metadata?.name == "Mixed Case")
    check("unknown @run-at falls back to document-end",
        UserscriptParser.parse("// ==UserScript==\n// @name X\n// @match *://*/*\n// @run-at document-idle\n// ==/UserScript==\n")
            .metadata?.runAt == RunAt.DOCUMENT_END)
    check("first @name wins on duplicates",
        UserscriptParser.parse("// ==UserScript==\n// @name First\n// @name Second\n// @match *://*/*\n// ==/UserScript==\n")
            .metadata?.name == "First")
    check("@grant/@require ignored, parse still succeeds",
        UserscriptParser.parse("// ==UserScript==\n// @name X\n// @match *://*/*\n// @grant none\n// @require https://evil.example.com/lib.js\n// ==/UserScript==\n")
            .metadata != null)

    // --- @match glob: scheme ---
    check("* scheme matches https", MatchPattern.matches("*://example.com/*", "https://example.com/"))
    check("* scheme matches http", MatchPattern.matches("*://example.com/*", "http://example.com/"))
    check("* scheme does NOT match ftp", !MatchPattern.matches("*://example.com/*", "ftp://example.com/"))
    check("https pattern does NOT match http", !MatchPattern.matches("https://example.com/*", "http://example.com/"))
    check("http pattern does NOT match https", !MatchPattern.matches("http://example.com/*", "https://example.com/"))
    check("pattern without :// never matches", !MatchPattern.matches("example.com/*", "https://example.com/"))

    // --- @match glob: host ---
    check("exact host matches", MatchPattern.matches("*://example.com/*", "https://example.com/page"))
    check("exact host does NOT match subdomain", !MatchPattern.matches("*://example.com/*", "https://www.example.com/"))
    check("*.host matches one subdomain", MatchPattern.matches("*://*.example.com/*", "https://www.example.com/"))
    check("*.host matches nested subdomain", MatchPattern.matches("*://*.example.com/*", "https://a.b.example.com/"))
    check("*.host does NOT match bare host", !MatchPattern.matches("*://*.example.com/*", "https://example.com/"))
    check("*.host does NOT match suffix lookalike", !MatchPattern.matches("*://*.example.com/*", "https://fakeexample.com/"))
    check("*.host does NOT match host-as-suffix", !MatchPattern.matches("*://*.example.com/*", "https://example.com.evil.com/"))
    check("* host matches anything", MatchPattern.matches("*://*/*", "https://anything.example.org/x"))
    check("host match is case-insensitive", MatchPattern.matches("*://EXAMPLE.com/*", "https://example.COM/"))
    check("host * inside label matches", MatchPattern.matches("*://ex*ple.com/*", "https://example.com/"))
    check("host * inside label does NOT cross a dot", !MatchPattern.matches("*://ex*e.com/*", "https://ex.foo.e.com/"))
    check("port in URL is ignored for host match", MatchPattern.matches("*://example.com/*", "https://example.com:8080/"))
    check("userinfo in URL is ignored for host match", MatchPattern.matches("*://example.com/*", "https://user:pass@example.com/"))

    // --- @match glob: path ---
    check("path glob matches subpath", MatchPattern.matches("*://example.com/news/*", "https://example.com/news/today"))
    check("path glob does NOT match other path", !MatchPattern.matches("*://example.com/news/*", "https://example.com/sports/"))
    check("exact path matches", MatchPattern.matches("*://example.com/news", "https://example.com/news"))
    check("exact path does NOT match longer path", !MatchPattern.matches("*://example.com/news", "https://example.com/news/today"))
    check("query string is part of the path match", MatchPattern.matches("*://example.com/search?q=*", "https://example.com/search?q=kotlin"))
    check("query glob does NOT match missing query", !MatchPattern.matches("*://example.com/search?q=*", "https://example.com/search"))
    check("no path in pattern means any path", MatchPattern.matches("*://example.com", "https://example.com/a/b?c=d"))
    check("path match is case-sensitive", !MatchPattern.matches("*://example.com/News/*", "https://example.com/news/x"))

    // --- Userscript.matchesUrl: no @match runs nowhere ---
    check("script with no @match runs nowhere",
        !scriptWith(matches = emptyList()).matchesUrl("https://example.com/"))
    check("script matches its @match host",
        scriptWith(matches = listOf("*://*.example.com/*")).matchesUrl("https://www.example.com/"))
    check("script does not match other hosts",
        !scriptWith(matches = listOf("*://*.example.com/*")).matchesUrl("https://other.org/"))

    // --- Run-at filtering + ordering ---
    val startScript = scriptWith(id = "start", runAt = RunAt.DOCUMENT_START)
    val endScript = scriptWith(id = "end", runAt = RunAt.DOCUMENT_END)
    val both = listOf(endScript, startScript) // deliberately end-first in the list
    check("document-start filter returns only start scripts",
        ExtensionRuntime.scriptsForRunAt(both, "https://example.com/", RunAt.DOCUMENT_START, probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).map { it.id } == listOf("start"))
    check("document-end filter returns only end scripts",
        ExtensionRuntime.scriptsForRunAt(both, "https://example.com/", RunAt.DOCUMENT_END, probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).map { it.id } == listOf("end"))
    check("scriptsForUrl orders document-start before document-end",
        ExtensionRuntime.scriptsForUrl(both, "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).map { it.id } == listOf("start", "end"))
    check("non-matching URL returns nothing",
        ExtensionRuntime.scriptsForUrl(
            listOf(scriptWith(matches = listOf("*://example.com/*"))),
            "https://other.org/"
        ).isEmpty())
    check("disabled script never runs",
        ExtensionRuntime.scriptsForUrl(listOf(scriptWith(enabled = false)), "https://example.com/").isEmpty())

    // --- Per-site on/off ---
    val perSite = scriptWith(disabledHosts = setOf("example.com"))
    check("disabled host does not run", ExtensionRuntime.scriptsForUrl(listOf(perSite), "https://example.com/").isEmpty())
    check("disabled host blocks its subdomains",
        ExtensionRuntime.scriptsForUrl(listOf(perSite), "https://www.example.com/").isEmpty())
    check("disabled host does not block other hosts",
        ExtensionRuntime.scriptsForUrl(listOf(perSite), "https://other.org/", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).size == 1)
    check("disabled host does not block suffix lookalike",
        ExtensionRuntime.scriptsForUrl(listOf(perSite), "https://fakeexample.com/", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).size == 1)
    check("isEnabledForHost: null host is enabled",
        ExtensionRuntime.isEnabledForHost(perSite, null))
    check("isEnabledForHost: exact disabled host is off",
        !ExtensionRuntime.isEnabledForHost(perSite, "example.com"))
    check("isEnabledForHost: subdomain of disabled host is off",
        !ExtensionRuntime.isEnabledForHost(perSite, "sub.example.com"))
    check("isEnabledForHost: host match is case-insensitive",
        !ExtensionRuntime.isEnabledForHost(perSite, "EXAMPLE.COM"))

    // --- YouTube skipper master switch ---
    val skipper = scriptWith(
        id = ExtensionRuntime.YOUTUBE_SKIPPER_ID, name = "YouTube Ad Skipper",
        matches = listOf("*://youtube.com/*", "*://*.youtube.com/*", "*://youtu.be/*"),
        // F05: the real skipper is registry-owned (builtIn = true) — the
        // fixture must say so, or the UNKNOWN probe blocks it as a user
        // script and the toggle tests below fail for the wrong reason.
        builtIn = true
    )
    check("skipper runs on YouTube when toggle is on",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://www.youtube.com/watch?v=x").size == 1)
    check("skipper does NOT run on YouTube when toggle is off",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://www.youtube.com/watch?v=x", youtubeSkipperEnabled = false).isEmpty())
    check("skipper does NOT run on non-YouTube even when toggle is on",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://example.com/").isEmpty())
    check("skipper runs on youtu.be short links",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://youtu.be/abc123").size == 1)

    // --- Install validation + size cap ---
    val installed = ExtensionRuntime.installFromText(GOOD_SCRIPT)
    check("valid script installs", installed is ExtensionRuntime.InstallResult.Success)
    val installedScript = (installed as? ExtensionRuntime.InstallResult.Success)?.script
    check("installed id is the slugified name", installedScript?.id == "dark-mode-for-example")
    check("installed script keeps its matches", installedScript?.matches?.size == 2)
    check("installed script keeps document-start", installedScript?.runAt == RunAt.DOCUMENT_START)
    check("installed script is enabled by default", installedScript?.enabled == true)
    check("install rejects a script with no @match",
        ExtensionRuntime.installFromText(NO_MATCH_SCRIPT) is ExtensionRuntime.InstallResult.Failure)
    check("install rejects a script with no header",
        ExtensionRuntime.installFromText("alert(1);") is ExtensionRuntime.InstallResult.Failure)
    check("install rejects a duplicate id",
        ExtensionRuntime.installFromText(GOOD_SCRIPT, setOf("dark-mode-for-example"))
            is ExtensionRuntime.InstallResult.Failure)
    check("install rejects a name colliding with a built-in",
        ExtensionRuntime.installFromText(
            "// ==UserScript==\n// @name YouTube Ad Skipper\n// @match *://*/*\n// ==/UserScript==\n"
        ) is ExtensionRuntime.InstallResult.Failure)
    val header = "// ==UserScript==\n// @name Big\n// @match *://*/*\n// ==/UserScript==\n//"
    val padded = header + "x".repeat(MAX_SCRIPT_SIZE_BYTES - header.length)
    check("script at exactly 200 KB installs (boundary)",
        padded.toByteArray(Charsets.UTF_8).size == MAX_SCRIPT_SIZE_BYTES &&
            ExtensionRuntime.installFromText(padded) is ExtensionRuntime.InstallResult.Success)
    val overMax = padded + "x"
    check("script 1 byte over 200 KB is rejected",
        ExtensionRuntime.installFromText(overMax) is ExtensionRuntime.InstallResult.Failure)
    check("oversize failure names the cap",
        (ExtensionRuntime.installFromText(overMax) as ExtensionRuntime.InstallResult.Failure)
            .reason.contains("200"))

    // --- slugify ---
    check("slugify lowercases and dashes spaces", ExtensionRuntime.slugify("Dark Mode Pro") == "dark-mode-pro")
    check("slugify collapses punctuation runs", ExtensionRuntime.slugify("My  Cool!!Script") == "my-cool-script")
    check("slugify trims edge dashes", ExtensionRuntime.slugify("  --Hi-- ") == "hi")
    check("slugify falls back for punctuation-only names", ExtensionRuntime.slugify("!!!") == "userscript")

    // --- fetch validation (no network: only the pre-flight check runs) ---
    var ftpRejected = false
    try {
        ExtensionRuntime.fetchScriptText("ftp://example.com/script.user.js")
    } catch (e: IllegalArgumentException) {
        ftpRejected = true
    }
    check("fetch rejects non-http(s) URLs before connecting", ftpRejected)
    var fileRejected = false
    try {
        ExtensionRuntime.fetchScriptText("file:///etc/passwd")
    } catch (e: IllegalArgumentException) {
        fileRejected = true
    }
    check("fetch rejects file:// URLs before connecting", fileRejected)

    // --- Built-in registry (fake asset reader — no Android needed) ---
    val cookieHiderSource = """// ==UserScript==
// @name        Cookie Banner Hider
// @version     1.0
// @description Hides cookie-consent banners and overlays.
// @match       *://*/*
// @run-at      document-end
// ==/UserScript==
(function(){})();
"""
    val registry = ExtensionRuntime.builtInScripts { path ->
        when (path) {
            "youtube-skipper.js" -> "(function(){/* skipper */})();"
            "userscripts/cookie-banner-hider.js" -> cookieHiderSource
            else -> null
        }
    }
    check("registry has both built-ins", registry.size == 2)
    check("skipper is built-in and enabled by default",
        registry.any { it.id == ExtensionRuntime.YOUTUBE_SKIPPER_ID && it.builtIn && it.enabled })
    check("cookie hider name comes from its header",
        registry.any { it.id == ExtensionRuntime.COOKIE_HIDER_ID && it.name == "Cookie Banner Hider" })
    check("cookie hider matches all sites",
        registry.first { it.id == ExtensionRuntime.COOKIE_HIDER_ID }.matches == listOf("*://*/*"))
    check("cookie hider runs on an arbitrary site",
        ExtensionRuntime.scriptsForUrl(registry, "https://news.example.org/article")
            .any { it.id == ExtensionRuntime.COOKIE_HIDER_ID })
    check("registry skips a missing asset instead of crashing",
        ExtensionRuntime.builtInScripts { null }.isEmpty())
    check("registry keeps the skipper when only the hider asset is missing",
        ExtensionRuntime.builtInScripts { path ->
            if (path == "youtube-skipper.js") "(function(){})();" else null
        }.size == 1)

    // --- W6 S-1: sensitive-site protection ---
    check("paypal.com is sensitive", ExtensionRuntime.isSensitiveHost("paypal.com"))
    check("www.paypal.com is sensitive", ExtensionRuntime.isSensitiveHost("www.paypal.com"))
    check("subdomain of accounts.google.com is sensitive",
        ExtensionRuntime.isSensitiveHost("mail.accounts.google.com"))
    check("fakepaypal.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("fakepaypal.com"))
    check("paypal.com.evil.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("paypal.com.evil.com"))
    check("example.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("example.com"))
    check("null host is NOT sensitive", !ExtensionRuntime.isSensitiveHost(null))
    check("blank host is NOT sensitive", !ExtensionRuntime.isSensitiveHost("  "))
    check("sensitive host is case-insensitive", ExtensionRuntime.isSensitiveHost("PayPal.COM"))
    check("password field makes any page sensitive",
        ExtensionRuntime.isSensitivePage("example.com", ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD))
    check("non-sensitive host without password field is not sensitive",
        !ExtensionRuntime.isSensitivePage("example.com", ExtensionRuntime.PasswordProbeResult.NO_PASSWORD))
    check("null host with password field is sensitive",
        ExtensionRuntime.isSensitivePage(null, ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD))

    val wideScript = scriptWith(matches = listOf("*://*/*"))
    check("user script runs on a normal page",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).size == 1)
    check("user script does NOT run on a page with a password field",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://example.com/login", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD).isEmpty())
    check("user script does NOT run on a known sensitive host",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://paypal.com/signin").isEmpty())
    check("user script does NOT run on a sensitive subdomain",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://www.paypal.com/signin").isEmpty())
    val allowedScript = scriptWith(
        matches = listOf("*://*/*"),
        allowedSensitiveHosts = setOf("example.com")
    )
    check("explicitly allowed host runs on its password page",
        ExtensionRuntime.scriptsForUrl(
            listOf(allowedScript), "https://example.com/login", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD
        ).size == 1)
    check("explicit allow covers subdomains of the allowed host",
        ExtensionRuntime.scriptsForUrl(
            listOf(allowedScript), "https://sub.example.com/login", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD
        ).size == 1)
    check("explicit allow does NOT cover other hosts",
        ExtensionRuntime.scriptsForUrl(
            listOf(allowedScript), "https://other.org/login", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD
        ).isEmpty())
    check("explicit allow does NOT cover suffix lookalikes",
        ExtensionRuntime.scriptsForUrl(
            listOf(allowedScript), "https://fakeexample.com/login", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD
        ).isEmpty())
    check("isHostAllowedForSensitive: null host is not allowed",
        !ExtensionRuntime.isHostAllowedForSensitive(allowedScript, null))
    val hider = scriptWith(
        id = ExtensionRuntime.COOKIE_HIDER_ID, name = "Cookie Banner Hider",
        matches = listOf("*://*/*"), builtIn = true
    )
    check("built-in cookie hider is ALSO blocked on a password page",
        ExtensionRuntime.scriptsForUrl(listOf(hider), "https://example.com/login", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD).isEmpty())
    check("built-in cookie hider runs on a normal page",
        ExtensionRuntime.scriptsForUrl(listOf(hider), "https://example.com/").size == 1)
    check("skipper is blocked on a YouTube page with a password field",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://www.youtube.com/signin", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD).isEmpty())
    check("skipper runs on bare youtube.com (W6 nit fix)",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://youtube.com/watch?v=x").size == 1)

    // --- F05 (v2.2): tri-state probe, FAIL CLOSED ---
    // Mapping: raw evaluateJavascript callback -> tri-state. The probe
    // returns a JS string, so WebView hands the callback its JSON
    // encoding ("true" WITH quotes); bare booleans are accepted too.
    check("probe mapping: bare 'true' is HAS_PASSWORD",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("true") == ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD)
    check("probe mapping: JSON-quoted '\"true\"' is HAS_PASSWORD",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("\"true\"") == ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD)
    check("probe mapping: ' true ' with whitespace is HAS_PASSWORD",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("  true  ") == ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD)
    check("probe mapping: bare 'false' is NO_PASSWORD",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("false") == ExtensionRuntime.PasswordProbeResult.NO_PASSWORD)
    check("probe mapping: JSON-quoted '\"false\"' is NO_PASSWORD",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("\"false\"") == ExtensionRuntime.PasswordProbeResult.NO_PASSWORD)
    check("probe mapping: null is UNKNOWN (never NO_PASSWORD)",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult(null) == ExtensionRuntime.PasswordProbeResult.UNKNOWN)
    check("probe mapping: 'error' is UNKNOWN",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("error") == ExtensionRuntime.PasswordProbeResult.UNKNOWN)
    check("probe mapping: JSON-quoted '\"error\"' is UNKNOWN",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("\"error\"") == ExtensionRuntime.PasswordProbeResult.UNKNOWN)
    check("probe mapping: 'null' string is UNKNOWN",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("null") == ExtensionRuntime.PasswordProbeResult.UNKNOWN)
    check("probe mapping: garbage is UNKNOWN",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("maybe") == ExtensionRuntime.PasswordProbeResult.UNKNOWN)
    check("probe mapping: empty string is UNKNOWN",
        ExtensionRuntime.PasswordProbeResult.fromProbeResult("") == ExtensionRuntime.PasswordProbeResult.UNKNOWN)

    // Tri-state sensitivity: UNKNOWN is sensitive for user scripts only.
    check("UNKNOWN probe is sensitive for user scripts",
        ExtensionRuntime.isSensitivePageForUserScripts("example.com", ExtensionRuntime.PasswordProbeResult.UNKNOWN))
    check("UNKNOWN probe is NOT sensitive for built-ins (host list only)",
        !ExtensionRuntime.isSensitivePage("example.com", ExtensionRuntime.PasswordProbeResult.UNKNOWN))
    check("NO_PASSWORD probe is not sensitive for user scripts",
        !ExtensionRuntime.isSensitivePageForUserScripts("example.com", ExtensionRuntime.PasswordProbeResult.NO_PASSWORD))
    check("HAS_PASSWORD probe is sensitive for both kinds",
        ExtensionRuntime.isSensitivePage("example.com", ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD) &&
            ExtensionRuntime.isSensitivePageForUserScripts("example.com", ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD))
    check("sensitive host is sensitive for both kinds even on NO_PASSWORD",
        ExtensionRuntime.isSensitivePage("paypal.com", ExtensionRuntime.PasswordProbeResult.NO_PASSWORD) &&
            ExtensionRuntime.isSensitivePageForUserScripts("paypal.com", ExtensionRuntime.PasswordProbeResult.NO_PASSWORD))

    // Fail closed: UNKNOWN blocks user scripts, runs built-ins.
    check("UNKNOWN probe BLOCKS a user script (fail closed)",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).isEmpty())
    check("UNKNOWN probe blocks a user script even when no probe arg is passed (default is UNKNOWN)",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://example.com/").isEmpty())
    check("UNKNOWN probe does NOT block a built-in script",
        ExtensionRuntime.scriptsForUrl(listOf(hider), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).size == 1)
    check("UNKNOWN probe does NOT block the skipper on YouTube",
        ExtensionRuntime.scriptsForUrl(listOf(skipper), "https://www.youtube.com/watch?v=x", probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).size == 1)
    check("UNKNOWN probe still blocks user scripts on a sensitive host (both layers agree)",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://paypal.com/", probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).isEmpty())
    check("UNKNOWN probe still blocks a built-in on a sensitive host",
        ExtensionRuntime.scriptsForUrl(listOf(hider), "https://paypal.com/", probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).isEmpty())
    check("explicit allow overrides UNKNOWN for a user script",
        ExtensionRuntime.scriptsForUrl(listOf(allowedScript), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).size == 1)
    check("NO_PASSWORD probe lets a user script run (decision table)",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).size == 1)
    check("HAS_PASSWORD probe blocks a user script (decision table)",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD).isEmpty())
    check("HAS_PASSWORD probe blocks a built-in script (decision table)",
        ExtensionRuntime.scriptsForUrl(listOf(hider), "https://example.com/", probe = ExtensionRuntime.PasswordProbeResult.HAS_PASSWORD).isEmpty())
    check("scriptsForRunAt honours UNKNOWN the same way (document-start user script blocked)",
        ExtensionRuntime.scriptsForRunAt(listOf(startScript), "https://example.com/", RunAt.DOCUMENT_START, probe = ExtensionRuntime.PasswordProbeResult.UNKNOWN).isEmpty())

    // Probe JS structure: shadow-DOM recursion + same-origin iframe
    // descent + the "error" UNKNOWN marker. (This checks the JS string;
    // real shadow-DOM/iframe pages need a phone — see the report.)
    val probeJs = ExtensionRuntime.PASSWORD_FIELD_PROBE_JS
    check("probe JS looks for input[type=password]", probeJs.contains("input[type=password]"))
    check("probe JS descends into shadow roots", probeJs.contains("shadowRoot"))
    check("probe JS descends into same-origin iframes", probeJs.contains("contentDocument"))
    check("probe JS catches per-frame cross-origin failures", probeJs.contains("catch(e)"))
    check("probe JS returns the 'error' UNKNOWN marker", probeJs.contains("'error'"))
    check("probe JS returns string markers, not booleans",
        probeJs.contains("'true'") && probeJs.contains("'false'") && !probeJs.contains("return true;})"))
    check("probe JS never returns bare false as a probe result",
        !probeJs.contains("return false;})();"))

    // Expanded sensitive host list (~30): new entries, subdomains, lookalikes.
    check("chase.com is sensitive", ExtensionRuntime.isSensitiveHost("chase.com"))
    check("www.chase.com is sensitive", ExtensionRuntime.isSensitiveHost("www.chase.com"))
    check("bankofamerica.com is sensitive", ExtensionRuntime.isSensitiveHost("bankofamerica.com"))
    check("hsbc.com is sensitive", ExtensionRuntime.isSensitiveHost("hsbc.com"))
    check("dbs.com is sensitive", ExtensionRuntime.isSensitiveHost("dbs.com"))
    check("ocbc.com is sensitive", ExtensionRuntime.isSensitiveHost("ocbc.com"))
    check("uob.com is sensitive", ExtensionRuntime.isSensitiveHost("uob.com"))
    check("maybank2u.com.my is sensitive", ExtensionRuntime.isSensitiveHost("maybank2u.com.my"))
    check("online.maybank2u.com.my is sensitive", ExtensionRuntime.isSensitiveHost("online.maybank2u.com.my"))
    check("cimb.com is sensitive", ExtensionRuntime.isSensitiveHost("cimb.com"))
    check("wellsfargo.com is sensitive", ExtensionRuntime.isSensitiveHost("wellsfargo.com"))
    check("capitalone.com is sensitive", ExtensionRuntime.isSensitiveHost("capitalone.com"))
    check("americanexpress.com is sensitive", ExtensionRuntime.isSensitiveHost("americanexpress.com"))
    check("amazon.com is sensitive", ExtensionRuntime.isSensitiveHost("amazon.com"))
    check("facebook.com is sensitive", ExtensionRuntime.isSensitiveHost("facebook.com"))
    check("github.com is sensitive", ExtensionRuntime.isSensitiveHost("github.com"))
    check("login.github.com is sensitive", ExtensionRuntime.isSensitiveHost("login.github.com"))
    check("gitlab.com is sensitive", ExtensionRuntime.isSensitiveHost("gitlab.com"))
    check("icloud.com is sensitive", ExtensionRuntime.isSensitiveHost("icloud.com"))
    check("outlook.live.com is sensitive", ExtensionRuntime.isSensitiveHost("outlook.live.com"))
    check("proton.me is sensitive", ExtensionRuntime.isSensitiveHost("proton.me"))
    check("mail.proton.me is sensitive", ExtensionRuntime.isSensitiveHost("mail.proton.me"))
    check("venmo.com is sensitive", ExtensionRuntime.isSensitiveHost("venmo.com"))
    check("kraken.com is sensitive", ExtensionRuntime.isSensitiveHost("kraken.com"))
    check("coinbase.com is still sensitive", ExtensionRuntime.isSensitiveHost("coinbase.com"))
    check("chase.com.evil.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("chase.com.evil.com"))
    check("fakegithub.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("fakegithub.com"))
    check("amazon.com.evil.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("amazon.com.evil.com"))
    check("proton.me.evil.com is NOT sensitive", !ExtensionRuntime.isSensitiveHost("proton.me.evil.com"))
    check("notproton.me is NOT sensitive", !ExtensionRuntime.isSensitiveHost("notproton.me"))
    check("user script does NOT run on new sensitive host chase.com",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://chase.com/login", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).isEmpty())
    check("user script does NOT run on github.com even with a clean probe",
        ExtensionRuntime.scriptsForUrl(listOf(wideScript), "https://github.com/login", probe = ExtensionRuntime.PasswordProbeResult.NO_PASSWORD).isEmpty())

    // DYNAMIC-FIELD RESIDUAL (documented, NOT solved): a password field
    // added to the SAME page AFTER injection cannot be un-injected — the
    // probe runs once per navigation (onPageFinished /
    // doUpdateVisitedHistory) and the script is already running. This
    // check exists so the residual is on the record in the test suite,
    // not just in prose; there is no runtime behaviour to assert here.
    check("DYNAMIC-FIELD RESIDUAL (documented, not solved): post-injection password fields cannot be un-injected", true)

    // --- W6 S-3: redirect + content-type rules (pure helpers, no network) ---
    check("https->https redirect is allowed",
        ExtensionRuntime.nextRedirectUrl("https://a.example/s.js", "https://b.example/s.js") == "https://b.example/s.js")
    check("http->https redirect is allowed",
        ExtensionRuntime.nextRedirectUrl("http://a.example/s.js", "https://b.example/s.js") == "https://b.example/s.js")
    check("relative redirect resolves against the current URL",
        ExtensionRuntime.nextRedirectUrl("https://a.example/dir/s.js", "/other/s.js") == "https://a.example/other/s.js")
    var downgradeRefused = false
    try {
        ExtensionRuntime.nextRedirectUrl("https://a.example/s.js", "http://a.example/s.js")
    } catch (e: IllegalArgumentException) {
        downgradeRefused = true
    }
    check("https->http downgrade redirect is refused", downgradeRefused)
    var midChainDowngradeRefused = false
    try {
        // N-7: the downgrade is refused at whichever hop it happens —
        // the old code checked only the ORIGINAL url's scheme, so an
        // http→https→http chain was allowed.
        ExtensionRuntime.nextRedirectUrl("https://b.example/s.js", "http://c.example/s.js")
    } catch (e: IllegalArgumentException) {
        midChainDowngradeRefused = true
    }
    check("https->http downgrade on an intermediate hop is refused", midChainDowngradeRefused)
    check("localhost with an explicit port matches (B-3)",
        com.nova.browser.extensions.MatchPattern.matches("*://localhost:3000/*", "http://localhost:3000/app"))
    check("pattern without a port still matches a URL with one",
        com.nova.browser.extensions.MatchPattern.matches("*://localhost/*", "http://localhost:3000/app"))
    check("query is kept when the URL has no '/' before '?' (N-14)",
        com.nova.browser.extensions.MatchPattern.matches("*://example.com/?q=*", "https://example.com?q=hello"))
    check("BOM-prefixed script parses (B-17)",
        com.nova.browser.extensions.UserscriptParser.parse("\uFEFF// ==UserScript==\n// @name T\n// @match *://*/*\n// ==/UserScript==\n").metadata?.name == "T")
    check("non-ASCII name slugifies to ASCII (B-4)",
        ExtensionRuntime.slugify("Café Mode") == "caf-mode" && ExtensionRuntime.isValidScriptId(ExtensionRuntime.slugify("Café Mode")))
    var nonHttpRefused = false
    try {
        ExtensionRuntime.nextRedirectUrl("https://a.example/s.js", "ftp://a.example/s.js")
    } catch (e: IllegalArgumentException) {
        nonHttpRefused = true
    }
    check("redirect to a non-http(s) URL is refused", nonHttpRefused)
    var badLocationRefused = false
    try {
        // Unclosed IPv6 bracket: java.net.URL rejects it outright.
        ExtensionRuntime.nextRedirectUrl("https://a.example/s.js", "http://[::1")
    } catch (e: IllegalArgumentException) {
        badLocationRefused = true
    }
    check("malformed redirect target is refused", badLocationRefused)
    check("a '://bad' Location is just a same-host relative path, not a new host",
        ExtensionRuntime.nextRedirectUrl("https://a.example/s.js", "://bad") == "https://a.example/://bad")
    check("text/javascript is an allowed script content-type",
        ExtensionRuntime.isAllowedScriptContentType("text/javascript"))
    check("text/plain with charset is allowed",
        ExtensionRuntime.isAllowedScriptContentType("text/plain; charset=utf-8"))
    check("application/javascript is allowed",
        ExtensionRuntime.isAllowedScriptContentType("application/javascript"))
    check("application/x-javascript is allowed",
        ExtensionRuntime.isAllowedScriptContentType("application/x-javascript"))
    check("missing content-type is allowed (some servers omit it)",
        ExtensionRuntime.isAllowedScriptContentType(null))
    check("text/html is NOT an allowed script content-type",
        !ExtensionRuntime.isAllowedScriptContentType("text/html"))
    check("application/octet-stream is NOT allowed",
        !ExtensionRuntime.isAllowedScriptContentType("application/octet-stream"))
    check("image/png is NOT allowed",
        !ExtensionRuntime.isAllowedScriptContentType("image/png"))

    // --- W6 S-4: built-in registry fails closed on a bad header ---
    val badHeaderHider = "// ==UserScript==\n// @name Broken\n// ==/UserScript==\n(function(){})();"
    val registryBadHider = ExtensionRuntime.builtInScripts { path ->
        when (path) {
            "youtube-skipper.js" -> "(function(){})();"
            "userscripts/cookie-banner-hider.js" -> badHeaderHider
            else -> null
        }
    }
    check("hider with an unparseable header is SKIPPED, never match-all",
        registryBadHider.size == 1 &&
            registryBadHider[0].id == ExtensionRuntime.YOUTUBE_SKIPPER_ID)
    val noMatchHider = "// ==UserScript==\n// @name No Match\n// @version 1.0\n// ==/UserScript==\n(function(){})();"
    check("hider with no @match is skipped, never match-all",
        ExtensionRuntime.builtInScripts { path ->
            when (path) {
                "youtube-skipper.js" -> "(function(){})();"
                "userscripts/cookie-banner-hider.js" -> noMatchHider
                else -> null
            }
        }.size == 1)

    // --- W6 S-6: script id validation ---
    check("plain slug id is valid", ExtensionRuntime.isValidScriptId("dark-mode-for-example"))
    check("digits and dashes are valid", ExtensionRuntime.isValidScriptId("a-1-b-2"))
    check("path traversal id is invalid", !ExtensionRuntime.isValidScriptId("../etc"))
    check("slash id is invalid", !ExtensionRuntime.isValidScriptId("a/b"))
    check("space id is invalid", !ExtensionRuntime.isValidScriptId("a b"))
    check("uppercase id is invalid (slugify output is lowercase)", !ExtensionRuntime.isValidScriptId("ABC"))
    check("underscore id is invalid", !ExtensionRuntime.isValidScriptId("a_b"))
    check("empty id is invalid", !ExtensionRuntime.isValidScriptId(""))

    // --- AUDIT 2 (F05-B): InjectionDedupe — one injection per navigation ---
    // onPageFinished + doUpdateVisitedHistory both fire per load, so the
    // same URL twice in a row injects once; but an SPA navigation BACK
    // to an earlier URL must re-inject (the old set never forgot it).
    val dedupe = ExtensionRuntime.InjectionDedupe()
    check("first injection of A runs", dedupe.shouldInject("https://www.youtube.com/watch?v=A"))
    check("second injection of A in a row is skipped", !dedupe.shouldInject("https://www.youtube.com/watch?v=A"))
    check("SPA navigation to B runs", dedupe.shouldInject("https://www.youtube.com/watch?v=B"))
    check("SPA navigation back to A runs again", dedupe.shouldInject("https://www.youtube.com/watch?v=A"))
    check("immediate repeat of A is skipped again", !dedupe.shouldInject("https://www.youtube.com/watch?v=A"))
    dedupe.reset()
    check("after reset (full navigation) A runs again", dedupe.shouldInject("https://www.youtube.com/watch?v=A"))
    val dedupe2 = ExtensionRuntime.InjectionDedupe()
    check("fresh dedupe injects", dedupe2.shouldInject("https://example.com/"))
    check("fresh dedupe skips the repeat", !dedupe2.shouldInject("https://example.com/"))

    println("ExtensionsTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
