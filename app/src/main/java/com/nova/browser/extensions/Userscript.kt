package com.nova.browser.extensions

/**
 * Userscript model + Tampermonkey-style header parser.
 *
 * Pure Kotlin — no Android imports, JVM-testable (see tests/ExtensionsTest.kt).
 *
 * HONEST PRIVILEGE MODEL (W6 S-2): a userscript runs with FULL PAGE
 * PRIVILEGES. There is no native JS bridge to app code — that is the only
 * thing scripts do not get. But an injected script runs as the page itself:
 * it can read page content including password fields, non-HttpOnly cookies
 * and localStorage, and it can use the page's own fetch()/XHR to send that
 * data anywhere the page can reach. There is NO network API in the sense
 * of a privileged native API — but the page's network capability is fully
 * available to the script, so "no network API" must never be read as
 * "cannot exfiltrate". The only boundaries are the @match scope and the
 * sensitive-site gating in [ExtensionRuntime]: @match decides which pages
 * a script may run on, sensitive pages (login/banking — see
 * [ExtensionRuntime.isSensitivePage]) are default-off for every script,
 * and installing a script is a trust decision the user confirms after
 * seeing its full @match scope.
 *
 * A script with no @match runs nowhere ([Userscript.matchesUrl] returns
 * false when [Userscript.matches] is empty) — that is a safety rule, not
 * a default-allow.
 *
 * @match glob semantics (Tampermonkey-style):
 * - Pattern shape: `scheme://host/path`. A pattern without "://" never matches.
 * - Scheme: `*` matches http/https only (never ftp/file/about). Otherwise
 *   the scheme must match exactly (case-insensitive).
 * - Host: exact host matches itself; `*` matches any host;
 *   `*.example.com` matches any subdomain of example.com (one or more
 *   labels, e.g. `a.example.com` and `a.b.example.com`) but NOT the bare
 *   `example.com` itself; `*` inside a label matches any run of
 *   characters except a dot (e.g. `ex*ple.com`).
 * - Path: `*` matches any characters (including `/` and `?`); the glob
 *   must match the whole path+query. Scheme/host compare case-insensitively,
 *   path compares case-sensitively.
 */

/** When a script runs relative to the page load. */
enum class RunAt {
    DOCUMENT_START,
    DOCUMENT_END;

    companion object {
        fun fromHeader(value: String?): RunAt =
            if (value?.trim()?.lowercase() == "document-start") DOCUMENT_START
            else DOCUMENT_END
    }
}

/** Maximum userscript size — larger scripts are rejected at install time. */
const val MAX_SCRIPT_SIZE_BYTES = 200 * 1024

/** Header metadata parsed out of a script's `// ==UserScript==` block. */
data class UserscriptMetadata(
    val name: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val runAt: RunAt
)

/** One installed script: metadata + source + runtime state. */
data class Userscript(
    /** Stable id, derived from the name ([ExtensionRuntime.slugify]). */
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val runAt: RunAt,
    /** Full script source (including its header block). */
    val source: String,
    /** Built-in scripts ship with the app and cannot be deleted. */
    val builtIn: Boolean = false,
    /** Master on/off switch for this script. */
    val enabled: Boolean = true,
    /** Hosts where this script is switched off (per-site off list — see
     *  [ExtensionRuntime.isEnabledForHost]). Empty = runs everywhere it matches. */
    val disabledHosts: Set<String> = emptySet(),
    /** Hosts the user EXPLICITLY allowed this script on even though they
     *  are sensitive pages (login/banking — see
     *  [ExtensionRuntime.isSensitivePage]). Empty = the script never runs
     *  on a sensitive page. This is an opt-IN list: the default is off. */
    val allowedSensitiveHosts: Set<String> = emptySet()
) {
    /** True when this script is allowed to run on [url]. A script with no
     *  @match runs nowhere. Never throws. */
    fun matchesUrl(url: String): Boolean {
        if (matches.isEmpty()) return false
        return matches.any { MatchPattern.matches(it, url) }
    }
}

/** Glob matching for a single @match pattern against a URL. Never throws. */
object MatchPattern {

    fun matches(pattern: String, url: String): Boolean {
        return try {
            val p = pattern.trim()
            val schemeSep = p.indexOf("://")
            if (schemeSep < 0) return false
            val schemePart = p.substring(0, schemeSep).lowercase()
            val rest = p.substring(schemeSep + 3)
            if (rest.isEmpty()) return false
            // B-3: strip the port from the PATTERN host too — the URL side
            // already strips it, so `*://localhost:3000/*` matched nothing.
            // (IPv6 bracketed hosts keep their brackets; the port after
            // ']' is dropped.)
            val patternAuthority = rest.substringBefore('/').substringBefore('?')
            val hostPart = if (patternAuthority.startsWith("[")) {
                val end = patternAuthority.indexOf(']')
                if (end < 0) patternAuthority.lowercase()
                else patternAuthority.substring(0, end + 1).lowercase()
            } else {
                patternAuthority.substringBefore(':').lowercase()
            }
            // No path in the pattern means "any path".
            val pathPart = if ('/' in rest) "/" + rest.substringAfter('/') else "/*"
            if (hostPart.isEmpty()) return false

            val u = url.trim()
            val uSchemeSep = u.indexOf("://")
            if (uSchemeSep < 0) return false
            val urlScheme = u.substring(0, uSchemeSep).lowercase()
            val urlRest = u.substring(uSchemeSep + 3)
            if (urlRest.isEmpty()) return false
            // N-14: the authority ends at the first '/' OR '?' — for
            // `https://example.com?q=1` the host is `example.com`, and
            // the path+query is `/?q=1` (the old code dropped the query
            // entirely when no '/' was present).
            val urlAuthority = urlRest.substringBefore('/').substringBefore('?')
            val urlHostRaw = urlAuthority.substringAfterLast('@')
            val urlHost = if (urlHostRaw.startsWith("[")) {
                val end = urlHostRaw.indexOf(']')
                if (end < 0) urlHostRaw.lowercase() else urlHostRaw.substring(0, end + 1).lowercase()
            } else {
                urlHostRaw.substringBefore(':').lowercase()
            }
            if (urlHost.isEmpty()) return false
            val urlPath = when {
                '/' in urlRest -> "/" + urlRest.substringAfter('/')
                '?' in urlRest -> "/?" + urlRest.substringAfter('?')
                else -> "/"
            }

            // Scheme: "*" covers http/https only — never ftp/file/about.
            when {
                schemePart == "*" -> if (urlScheme != "http" && urlScheme != "https") return false
                schemePart != urlScheme -> return false
            }
            if (!hostMatches(hostPart, urlHost)) return false
            if (!globMatches(pathPart, urlPath)) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Host glob: exact, `*` (any host), `*.suffix` (any subdomain of the
     *  suffix, never the bare suffix), or `*` inside a label (any run of
     *  characters except a dot). */
    fun hostMatches(hostPattern: String, host: String): Boolean {
        val pattern = hostPattern.lowercase()
        val h = host.lowercase()
        if (pattern == "*") return h.isNotEmpty()
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(2)
            if (suffix.isEmpty()) return false
            // Subdomain only: "a.example.com" yes, "example.com" no.
            return h.endsWith(".$suffix")
        }
        return globMatches(pattern, h, starMatchesDot = false)
    }

    /** Glob where `*` matches any run of characters. When [starMatchesDot]
     *  is false, `*` never crosses a `.` (host labels); otherwise it
     *  matches anything, including `/` and `?` (URL paths). The glob must
     *  match the whole [value]. Never throws. */
    fun globMatches(pattern: String, value: String, starMatchesDot: Boolean = true): Boolean {
        return try {
            val regex = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> regex.append(if (starMatchesDot) ".*" else "[^.]*")
                    '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\', '?' ->
                        regex.append('\\').append(c)
                    else -> regex.append(c)
                }
            }
            regex.append("$")
            Regex(regex.toString()).matches(value)
        } catch (_: Exception) {
            false
        }
    }
}

/** Parses the `// ==UserScript==` header block of a script. */
object UserscriptParser {

    data class Parsed(val metadata: UserscriptMetadata?, val error: String?)

    private const val HEADER_START = "// ==UserScript=="
    private const val HEADER_END = "// ==/UserScript=="

    /** Parse [source]. Returns metadata, or null metadata + a human-readable
     *  [Parsed.error] when the script is not installable: no header block,
     *  unterminated block, no @name, or no @match. Malformed lines inside
     *  the block are ignored (fail-open parsing, strict validation). */
    fun parse(source: String): Parsed {
        // B-17: strip a leading UTF-8 BOM — otherwise the header start
        // line never equals "// ==UserScript==" and install fails with
        // "No header".
        val clean = source.removePrefix("\uFEFF")
        val lines = clean.lines()
        val startIdx = lines.indexOfFirst { it.trim() == HEADER_START }
        if (startIdx < 0) {
            return Parsed(null, "No // ==UserScript== header block found")
        }
        val endIdx = lines.subList(startIdx + 1, lines.size)
            .indexOfFirst { it.trim() == HEADER_END }
        if (endIdx < 0) {
            return Parsed(null, "Header block is not closed with // ==/UserScript==")
        }
        val headerLines = lines.subList(startIdx + 1, startIdx + 1 + endIdx)

        var name: String? = null
        var version = "1.0"
        var description = ""
        var runAt: RunAt = RunAt.DOCUMENT_END
        val matches = mutableListOf<String>()
        for (raw in headerLines) {
            val line = raw.trim()
            if (!line.startsWith("//")) continue
            val body = line.removePrefix("//").trim()
            if (!body.startsWith("@")) continue
            // "@key value…" — key is the first whitespace-separated token,
            // value is everything after it (may contain spaces).
            val afterAt = body.substring(1).trim()
            val keyToken = afterAt.substringBefore(' ').substringBefore('\t').trim()
            val key = keyToken.lowercase()
            // Token length, not the lowercased key: "@Name Foo" must keep "Foo".
            val v = afterAt.substring(keyToken.length).trim()
            when (key) {
                "name" -> if (name == null && v.isNotEmpty()) name = v
                "version" -> if (v.isNotEmpty()) version = v
                "description" -> if (description.isEmpty() && v.isNotEmpty()) description = v
                "match" -> if (v.isNotEmpty()) matches.add(v)
                "run-at" -> runAt = RunAt.fromHeader(v)
                // @namespace, @author, @grant, @require, @updateURL etc.
                // are deliberately ignored: @require is never fetched.
                // (That does NOT mean a script cannot reach the network —
                // its own source can fetch() at runtime; see the privilege
                // model at the top of this file.)
            }
        }
        val n = name
        if (n == null) {
            return Parsed(null, "Script has no @name in its header")
        }
        if (matches.isEmpty()) {
            return Parsed(null, "Script has no @match rule — a script with no @match runs nowhere")
        }
        return Parsed(UserscriptMetadata(n, version, description, matches.toList(), runAt), null)
    }
}
