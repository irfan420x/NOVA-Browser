package com.nova.browser.devtools

/**
 * Thread-safe network request log — pure Kotlin, JVM-testable.
 *
 * Fed from WebViewClient.shouldInterceptRequest, which runs on a
 * BACKGROUND thread: this class never touches a WebView, never touches
 * Android, and synchronizes every access, so any number of WebView
 * worker threads can record concurrently. Ring buffer, capped at
 * [MAX_ENTRIES] — the oldest entries drop off, memory stays bounded.
 *
 * HONEST LIMIT (shown in the UI): shouldInterceptRequest only sees the
 * request — it cannot see the response status, the response size, or how
 * long the network transfer took. What is recorded is what is knowable
 * there: URL, method, whether the ad blocker blocked it, whether it was
 * the main frame, the request kind (guessed from the URL), the timestamp,
 * and how long the interception itself took ([durationMs], usually
 * sub-millisecond — the ad-block check, not the download).
 */
class NetworkLog {

    companion object {
        const val MAX_ENTRIES = 200

        /**
         * Guess the request kind from the URL. The main frame is always a
         * Document; anything else is guessed from its extension, and
         * "Other" covers XHR/fetch and extension-less URLs — the
         * interception point cannot tell an XHR from a plain sub-resource
         * fetch, so the UI must not claim otherwise.
         */
        fun inferKind(url: String, isMainFrame: Boolean): String {
            if (isMainFrame) return "Document"
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".js") || path.endsWith(".mjs") -> "Script"
                path.endsWith(".css") -> "Stylesheet"
                path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                    path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                    path.endsWith(".ico") || path.endsWith(".avif") -> "Image"
                path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                    path.endsWith(".otf") || path.endsWith(".eot") -> "Font"
                path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") -> "Media"
                else -> "Other"
            }
        }
    }

    data class Entry(
        val id: Long,
        /** URL with sensitive query values redacted (see [UrlRedactor]). */
        val url: String,
        val method: String,
        val blocked: Boolean,
        /** "AD" / "TRACKER" when blocked, null when allowed. */
        val blockedKind: String?,
        val isMainFrame: Boolean,
        /** "Document" / "Script" / "Stylesheet" / "Image" / "Font" / "Other". */
        val kind: String,
        val timestampMs: Long,
        /** Time spent inside the interception point, or null if unknown. */
        val durationMs: Long?,
        /** True when the stored URL had a query value redacted. */
        val urlRedacted: Boolean
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextId = 1L
    /** B16: how many entries the 200-cap has dropped since the last
     *  clear — the UI says so instead of silently losing the oldest
     *  requests. */
    private var droppedCount = 0L

    /**
     * Record one request. Safe to call from any thread, including the
     * WebView worker threads inside shouldInterceptRequest. Returns the
     * stored entry. Never throws.
     *
     * The URL is stored REDACTED ([UrlRedactor]): a password-reset or
     * `?token=…` URL must never sit in the log (or reach the clipboard
     * via Copy as cURL) with its secret intact.
     */
    fun record(
        url: String,
        method: String,
        blocked: Boolean = false,
        blockedKind: String? = null,
        isMainFrame: Boolean = false,
        durationMs: Long? = null,
        timestampMs: Long = System.currentTimeMillis()
    ): Entry {
        val entry: Entry
        synchronized(lock) {
            entry = Entry(
                id = nextId++,
                url = UrlRedactor.redact(url),
                method = method.uppercase(),
                blocked = blocked,
                blockedKind = if (blocked) blockedKind else null,
                isMainFrame = isMainFrame,
                kind = inferKind(url, isMainFrame),
                timestampMs = timestampMs,
                durationMs = durationMs,
                urlRedacted = UrlRedactor.wasRedacted(url)
            )
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
                droppedCount++
            }
        }
        return entry
    }

    /** How many entries the cap has dropped since the last clear (B16). */
    fun droppedCount(): Long = synchronized(lock) { droppedCount }

    /** Newest-first snapshot for the UI. Never throws. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.reversed() }

    /** Oldest-first snapshot (chronological). Never throws. */
    fun snapshotChronological(): List<Entry> = synchronized(lock) { entries.toList() }

    fun size(): Int = synchronized(lock) { entries.size }

    fun blockedCount(): Int = synchronized(lock) { entries.count { it.blocked } }

    fun clear() = synchronized(lock) { entries.clear(); droppedCount = 0 }
}
