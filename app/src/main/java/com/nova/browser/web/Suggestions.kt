package com.nova.browser.web

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Minimal JSON values + parser (no new dependency: org.json is Android-only
// and is NOT on the JVM test classpath, so the engine parsers below use
// this instead). Capped input, never throws.
// ---------------------------------------------------------------------------

sealed interface JsonVal
data class JStr(val value: String) : JsonVal
data class JArr(val items: List<JsonVal>) : JsonVal
data class JObj(val entries: Map<String, JsonVal>) : JsonVal
data class JNum(val raw: String) : JsonVal
data class JBool(val value: Boolean) : JsonVal
object JNull : JsonVal

object JsonLite {
    /** 1 MB cap (raised from 256 KB, P-2): reader JSON is a flat
     *  {title, text, html} object and a long article's HTML easily
     *  exceeds 256 KB — the old cap made parse() return null for real
     *  articles. Suggestion bodies are still capped at 64 KB while
     *  streaming in [Suggestions.fetchEngineSuggestions]. */
    private const val MAX_LEN = 1048576

    /** Max nesting depth (M-W4-6 / W3-1): a hostile or corrupt payload
     *  of 150 nested arrays must return null, not StackOverflowError. */
    const val MAX_DEPTH = 100

    fun parse(input: String): JsonVal? {
        if (input.length > MAX_LEN) return null
        return try {
            val p = Parser(input)
            p.skipWs()
            val v = p.value()
            p.skipWs()
            if (p.pos != input.length) null else v
        } catch (_: Throwable) {
            // Throwable, not Exception: a StackOverflowError from a
            // deeply-nested payload must also land here as null.
            null
        }
    }

    /** Convenience: read one top-level string field from a JSON object. */
    fun stringField(json: String, field: String): String? {
        val obj = parse(json) as? JObj ?: return null
        return (obj.entries[field] as? JStr)?.value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(depth: Int = 0): JsonVal {
            if (depth > MAX_DEPTH) throw IllegalStateException("JSON too deeply nested")
            skipWs()
            if (pos >= s.length) throw IllegalStateException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JStr(str())
                't' -> literal("true", JBool(true))
                'f' -> literal("false", JBool(false))
                'n' -> literal("null", JNull)
                else -> {
                    if (c == '-' || c.isDigit()) num() else throw IllegalStateException("bad value at $pos")
                }
            }
        }

        private fun literal(word: String, v: JsonVal): JsonVal {
            if (!s.startsWith(word, pos)) throw IllegalStateException("bad literal")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): JObj {
            pos++ // {
            val map = LinkedHashMap<String, JsonVal>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return JObj(map)
            }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw IllegalStateException("expected :")
                pos++
                map[key] = value(depth + 1)
                skipWs()
                if (pos >= s.length) throw IllegalStateException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JObj(map)
                    }
                    else -> throw IllegalStateException("bad object separator")
                }
            }
        }

        private fun arr(depth: Int): JArr {
            pos++ // [
            val items = ArrayList<JsonVal>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return JArr(items)
            }
            while (true) {
                items.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) throw IllegalStateException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JArr(items)
                    }
                    else -> throw IllegalStateException("bad array separator")
                }
            }
        }

        fun str(): String {
            if (pos >= s.length || s[pos] != '"') throw IllegalStateException("expected string")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) throw IllegalStateException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw IllegalStateException("bad \\u")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalStateException("bad \\u hex")
                                pos += 4
                                // Surrogate pair: \uD83D\uDE00 etc.
                                if (code in 0xD800..0xDBFF && pos + 6 <= s.length &&
                                    s[pos] == '\\' && s[pos + 1] == 'u'
                                ) {
                                    val low = s.substring(pos + 2, pos + 6).toIntOrNull(16)
                                    if (low != null && low in 0xDC00..0xDFFF) {
                                        pos += 6
                                        sb.append(
                                            String(
                                                Character.toChars(
                                                    0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                                                )
                                            )
                                        )
                                    } else {
                                        sb.append(code.toChar())
                                    }
                                } else {
                                    sb.append(code.toChar())
                                }
                            }
                            else -> throw IllegalStateException("unknown escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalStateException("unterminated string")
        }

        private fun num(): JNum {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            if (pos == start) throw IllegalStateException("bad number")
            return JNum(s.substring(start, pos))
        }
    }
}

// ---------------------------------------------------------------------------
// Search suggestions.
// ---------------------------------------------------------------------------

/** Where a suggestion row came from (shown as the row's source label). */
enum class SuggestionSource { HISTORY, BOOKMARK, SEARCH }

/** One row in the address-bar dropdown. [url] is null for a pure search
 *  suggestion (tapping it searches for [text]); history/bookmark rows
 *  carry the page URL to load directly. */
data class Suggestion(
    val text: String,
    val url: String?,
    val source: SuggestionSource
)

/** A local history/bookmark row, mapped by the coordinator from
 *  BrowserDb types (which need Android and so cannot live here). */
data class LocalEntry(
    val title: String,
    val url: String,
    val visitCount: Int = 1,
    val visitedAt: Long = 0L
) {
    val displayText: String get() = title.ifBlank { url }
}

/**
 * Search-suggestion engine — pure Kotlin, JVM-testable, no Android imports.
 *
 * Engine endpoints were VERIFIED against live responses on 2026-11-13
 * (fetched and read, not guessed):
 * - Google  https://suggestqueries.google.com/complete/search?client=firefox&q=<q>
 *   returns ["q", ["s1", "s2", ...], [], {...}]
 * - DuckDuckGo https://ac.duckduckgo.com/ac/?q=<q>&type=list
 *   returns ["q", ["s1", "s2", ...]]  (without &type=list it returns
 *   [{"phrase": "..."}] objects instead — that shape is parsed too)
 * - Brave https://search.brave.com/api/suggest?q=<q>
 *   returns ["q", ["s1", "s2", ...]]
 * - Bing: NOT supported. Its suggest endpoint shape could not be verified
 *   (bing.com is blocked by policy in this environment), so Bing users get
 *   history/bookmark suggestions only — never a guessed parse. If the shape
 *   is verified later, add a "bing" branch to [suggestUrl] and a case in
 *   [parseEngineSuggestions]; the merge/ranking code needs no change.
 *
 * Network failures fail silently to history-only suggestions: [fetchEngineSuggestions]
 * never throws and [SuggestionEngine.suggest] never blocks typing.
 */
object Suggestions {

    /** Suggest endpoint for the active engine, or null when the engine has
     *  no verified endpoint (Bing / unknown) — caller then skips the fetch. */
    fun suggestUrl(engineId: String, query: String): String? {
        val q = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            return null
        }
        return when (engineId.lowercase()) {
            "google" -> "https://suggestqueries.google.com/complete/search?client=firefox&q=$q"
            "duckduckgo" -> "https://ac.duckduckgo.com/ac/?q=$q&type=list"
            "brave" -> "https://search.brave.com/api/suggest?q=$q"
            else -> null
        }
    }

    /** Parse one engine's suggest response. All three verified engines use
     *  the ["query", ["s1", ...]] array shape; DuckDuckGo's object shape
     *  ([{"phrase": ...}]) is accepted as well. Anything else (garbage,
     *  an error page, a changed shape) returns an empty list, never throws. */
    fun parseEngineSuggestions(engineId: String, json: String): List<String> {
        // No verified endpoint for this engine (Bing / unknown): history-only.
        if (suggestUrl(engineId, "probe") == null) return emptyList()
        val root = JsonLite.parse(json) as? JArr ?: return emptyList()
        // Array form: ["query", ["s1", "s2", ...], ...]
        if (root.items.size >= 2) {
            val second = root.items[1]
            if (second is JArr) {
                return second.items.filterIsInstance<JStr>()
                    .map { it.value.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(10)
            }
        }
        // DuckDuckGo object form: [{"phrase": "s1"}, ...]
        val phrases = root.items.filterIsInstance<JObj>()
            .mapNotNull { (it.entries["phrase"] as? JStr)?.value?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
        return phrases
    }

    /** Debug log hook (this file is JVM-pure, so it cannot use
     *  android.util.Log): the coordinator may set this to
     *  `{ Log.d("Suggestions", it) }`. Network failures are reported
     *  here at debug level and never shown to the user. */
    var onDebugLog: ((String) -> Unit)? = null

    /** The suggestion body cap: 64 KB, enforced WHILE STREAMING
     *  (L-W4-5) — a body past the cap is dropped without being read
     *  into memory first. */
    const val MAX_SUGGEST_BODY_BYTES = 65536

    /** Fetch suggestions from the active engine. Returns an empty list on
     *  ANY failure (no network, timeout, non-200, bad body) — the caller
     *  falls back to history-only suggestions. Never throws.
     *
     *  THREADING CONTRACT (B-5): this performs network I/O on the
     *  calling thread — NEVER call it on the main thread. Callers must
     *  use [SuggestionEngine.suggestDebounced] / [SuggestionEngine.suggestAsync],
     *  which run the fetch on a worker thread, debounce keystrokes and
     *  deliver only the latest query's results. */
    fun fetchEngineSuggestions(
        engineId: String,
        query: String,
        timeoutMs: Int = 2000
    ): List<String> {
        val url = suggestUrl(engineId, query) ?: return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "NOVA Browser")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                onDebugLog?.invoke("Suggestions: $engineId returned HTTP ${conn.responseCode}")
                return emptyList()
            }
            // Stream with the cap enforced as bytes arrive (L-W4-5) —
            // never readText() the whole body first.
            val bytes = ByteArray(MAX_SUGGEST_BODY_BYTES + 1)
            var total = 0
            conn.inputStream.use { input ->
                while (total <= MAX_SUGGEST_BODY_BYTES) {
                    val read = input.read(bytes, total, bytes.size - total)
                    if (read < 0) break
                    total += read
                }
            }
            if (total > MAX_SUGGEST_BODY_BYTES) {
                onDebugLog?.invoke("Suggestions: $engineId body exceeded 64 KB")
                return emptyList()
            }
            val body = String(bytes, 0, total, Charsets.UTF_8)
            parseEngineSuggestions(engineId, body)
        } catch (t: Throwable) {
            onDebugLog?.invoke("Suggestions: $engineId fetch failed: ${t.javaClass.simpleName}")
            emptyList()
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Merge local + engine suggestions, ranked:
     * 1. exact-prefix history (title or URL starts with the query), most
     *    visited first, then most recent;
     * 2. exact-prefix bookmarks;
     * 3. engine suggestions, in engine order, de-duplicated against local;
     * 4. substring history/bookmark matches (query appears anywhere).
     * De-duplicated by URL (local) and by text (engine vs local).
     */
    fun mergeSuggestions(
        query: String,
        history: List<LocalEntry>,
        bookmarks: List<LocalEntry>,
        engineSuggestions: List<String>,
        limit: Int = 8
    ): List<Suggestion> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Suggestion>()
        val seenUrls = HashSet<String>()
        val seenTexts = HashSet<String>()

        fun isPrefix(e: LocalEntry): Boolean {
            val title = e.title.lowercase()
            val url = e.url.lowercase()
            val bare = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            return title.startsWith(q) || url.startsWith(q) || bare.startsWith(q)
        }

        fun isSubstring(e: LocalEntry): Boolean {
            return e.title.lowercase().contains(q) || e.url.lowercase().contains(q)
        }

        fun addLocal(e: LocalEntry, source: SuggestionSource) {
            if (!seenUrls.add(e.url.lowercase())) return
            val text = e.displayText
            seenTexts.add(text.lowercase())
            out.add(Suggestion(text, e.url, source))
        }

        val byRank = compareByDescending<LocalEntry> { it.visitCount }
            .thenByDescending { it.visitedAt }
        history.filter(::isPrefix).sortedWith(byRank).forEach { addLocal(it, SuggestionSource.HISTORY) }
        bookmarks.filter(::isPrefix).sortedWith(byRank).forEach { addLocal(it, SuggestionSource.BOOKMARK) }
        for (raw in engineSuggestions) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (seenTexts.add(text.lowercase())) {
                out.add(Suggestion(text, null, SuggestionSource.SEARCH))
            }
        }
        history.filter { !seenUrls.contains(it.url.lowercase()) && isSubstring(it) }
            .sortedWith(byRank).forEach { addLocal(it, SuggestionSource.HISTORY) }
        bookmarks.filter { !seenUrls.contains(it.url.lowercase()) && isSubstring(it) }
            .sortedWith(byRank).forEach { addLocal(it, SuggestionSource.BOOKMARK) }
        return out.take(limit)
    }
}

/** Local history/bookmark source, wired by the coordinator to BrowserDb.
 *  Implementations must never throw (return empty lists on DB errors) and
 *  must return empty lists in private mode (no private history leaks into
 *  suggestions). */
interface SuggestionLocalSource {
    fun historyEntries(query: String): List<LocalEntry>
    fun bookmarkEntries(query: String): List<LocalEntry>
}

/** One-call suggestion lookup: local (history/bookmarks) merged with the
 *  active engine's network suggestions. Never throws, never blocks on
 *  network failure — worst case it returns history-only suggestions.
 *
 *  THREADING (B-5): [suggest] is SYNCHRONOUS and does network I/O on
 *  the calling thread — never call it on the main thread. The UI must
 *  use [suggestDebounced] (300 ms debounce, worker thread, stale-result
 *  suppression) or [suggestAsync] (same, no delay). [onResult] is
 *  invoked on the worker thread; the coordinator must post it to the
 *  main thread before touching Compose state.
 *
 *  PRIVATE MODE (B-5): [privateMode] = true forces includeNetwork =
 *  false AND empty local results — no keystroke ever leaves the device
 *  and no private history/bookmark can leak into the dropdown. The
 *  coordinator must pass the tab's private flag here. */
class SuggestionEngine(private val localSource: SuggestionLocalSource? = null) {

    private val lock = Any()
    private var generation = 0

    fun suggest(
        query: String,
        engineId: String,
        includeNetwork: Boolean = true,
        privateMode: Boolean = false
    ): List<Suggestion> {
        if (query.isBlank()) return emptyList()
        if (privateMode) return emptyList()
        val history = try {
            localSource?.historyEntries(query) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val bookmarks = try {
            localSource?.bookmarkEntries(query) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val engine = if (includeNetwork) {
            Suggestions.fetchEngineSuggestions(engineId, query)
        } else {
            emptyList()
        }
        return Suggestions.mergeSuggestions(query, history, bookmarks, engine)
    }

    /** Debounced + async lookup: waits [delayMs] after the last call,
     *  runs [suggest] on a worker thread, and delivers the result only
     *  if no newer query was submitted meanwhile (stale-response
     *  suppression — a slow network reply for "kot" never overwrites
     *  the results for "kotlin"). Previous pending work is abandoned,
     *  not cancelled mid-flight (its result is simply dropped). */
    fun suggestDebounced(
        query: String,
        engineId: String,
        includeNetwork: Boolean = true,
        privateMode: Boolean = false,
        delayMs: Long = 300,
        onResult: (List<Suggestion>) -> Unit
    ) {
        val gen = synchronized(lock) {
            generation += 1
            generation
        }
        Thread({
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!isLatest(gen)) return@Thread
            val result = try {
                suggest(query, engineId, includeNetwork, privateMode)
            } catch (_: Exception) {
                emptyList()
            }
            if (!isLatest(gen)) return@Thread
            try {
                onResult(result)
            } catch (_: Exception) {
            }
        }, "nova-suggest").apply {
            isDaemon = true
            start()
        }
    }

    /** Same as [suggestDebounced] with no delay — use when the caller
     *  already debounced (or for a single lookup off the main thread). */
    fun suggestAsync(
        query: String,
        engineId: String,
        includeNetwork: Boolean = true,
        privateMode: Boolean = false,
        onResult: (List<Suggestion>) -> Unit
    ) = suggestDebounced(query, engineId, includeNetwork, privateMode, 0, onResult)

    /** Abandon any pending debounced lookup (e.g. the bar lost focus):
     *  its result will not be delivered. */
    fun cancelPending() {
        synchronized(lock) { generation += 1 }
    }

    private fun isLatest(gen: Int): Boolean = synchronized(lock) { gen == generation }
}

// ---------------------------------------------------------------------------
// Per-site settings — pure model.
//
// NOTE ON PLACEMENT: this model lives in the web package (not in
// ui/SiteSettingsSheet.kt) deliberately: the JVM test suite compiles with
// the stdlib only, and a Compose file cannot be compiled there. The sheet
// (ui/SiteSettingsSheet.kt) renders this model; the coordinator wires it to
// the per-site sets in Settings (adBlockOffHosts etc.) — see the hook spec
// in the W4 FIX report. Behaviour: JavaScript + desktop mode need a page reload
// (see [needsReloadAfter]); ad-block applies on the next load. There is
// deliberately NO per-site cookies toggle: CookieManager is global, so a
// per-site cookies switch could never work (B-4).
// ---------------------------------------------------------------------------

/** Per-site overrides for one host. Defaults match the global defaults:
 *  JavaScript on, mobile layout, ad-block on.
 *
 *  REMOVED (B-4 / M-W4-7): the per-site Cookies toggle. CookieManager
 *  .setAcceptCookie is GLOBAL — WebView has no per-host cookie API — so
 *  a per-site cookies switch could never do what it promised. Cookies
 *  are a global Settings item instead (the coordinator owns that row);
 *  this model has no cookies field at all.
 *
 *  [host] is normalised (lowercased + trimmed) in init (P-1) — copy()
 *  re-runs init, so copies stay normalised too. Treat it as read-only
 *  (it is a var only so init can normalise it). */
data class PerSiteSettings(
    var host: String,
    val javaScriptEnabled: Boolean = true,
    val desktopMode: Boolean = false,
    val adBlockEnabled: Boolean = true
) {
    init {
        host = host.lowercase().trim()
    }

    fun withJavaScript(enabled: Boolean) = copy(javaScriptEnabled = enabled)
    fun withDesktopMode(enabled: Boolean) = copy(desktopMode = enabled)
    fun withAdBlock(enabled: Boolean) = copy(adBlockEnabled = enabled)

    /** True when moving from [previous] to this state needs a page reload
     *  to take effect (JavaScript and desktop mode are applied to the live
     *  WebView settings; ad-block applies on the next load anyway, so it
     *  does not force a reload here). */
    fun needsReloadAfter(previous: PerSiteSettings): Boolean {
        return javaScriptEnabled != previous.javaScriptEnabled ||
            desktopMode != previous.desktopMode
    }

    /** Short human-readable note for the sheet when a reload is needed. */
    fun reloadNote(previous: PerSiteSettings): String? {
        if (!needsReloadAfter(previous)) return null
        return "Reloads the page"
    }
}

/** Default per-site settings for a host (no overrides stored yet). */
fun defaultPerSiteSettings(host: String): PerSiteSettings = PerSiteSettings(host.lowercase())

/** True for http/https URLs only (B-11). The site-settings sheet must
 *  refuse anything else — UrlUtils.extractHost("about:blank") returns
 *  "about", so a scheme check here is the guard, not extractHost. The
 *  coordinator must also guard the sheet trigger with this. */
fun isSupportedUrl(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}

/** Display form of a suggestion URL (L-W4-2): host + path, with the
 *  query string and fragment dropped — full URLs including tracking
 *  query params must not be shown in the row. "https://example.com/a?x=1"
 *  → "example.com/a". Never throws. */
fun displayUrl(url: String): String {
    return try {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val q = s.indexOf('?')
        if (q >= 0) s = s.substring(0, q)
        val f = s.indexOf('#')
        if (f >= 0) s = s.substring(0, f)
        s.trim().trimEnd('/')
    } catch (_: Exception) {
        url
    }
}
