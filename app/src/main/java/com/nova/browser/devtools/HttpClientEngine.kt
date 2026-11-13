package com.nova.browser.devtools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP Client engine — pure java.net, no Android imports, no new
 * dependencies (OkHttp is NOT in tools/deps.txt, so HttpURLConnection is
 * the only honest option). JVM-compilable, so the request model,
 * validation and header codec are unit-tested; live execution is NOT
 * tested on the JVM (it would need the network).
 *
 * Rules:
 *  - 30 s connect + read timeouts.
 *  - Redirects followed manually, up to [MAX_REDIRECTS] (301/302/303/
 *    307/308); 303 switches to GET and drops the body, per RFC 7231.
 *  - Response body shown up to [MAX_BODY_BYTES] (1 MB); beyond that the
 *    body is truncated and [HttpResult.truncated] is true — the UI says so.
 *  - No auth helpers beyond a raw Authorization header the user types
 *    (scope kept tight on purpose).
 */
data class HttpRequestData(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String = ""
) {
    val cleanMethod: String get() = method.trim().uppercase().ifBlank { "GET" }

    /** User-facing validation error, or null when the request is sendable. */
    fun validate(): String? {
        if (url.isBlank()) return "Enter a URL"
        val u = url.trim()
        val lower = u.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "URL must start with http:// or https://"
        }
        try {
            // java.net.URL accepts "https://" (empty host) without throwing,
            // so the host must be checked explicitly.
            val parsed = URL(u)
            if (parsed.host.isNullOrBlank()) return "That URL has no host"
        } catch (_: Exception) {
            return "That URL doesn't look valid"
        }
        if (cleanMethod !in SUPPORTED_METHODS) return "Unsupported method: $cleanMethod"
        for ((name, _) in headers) {
            if (name.isBlank()) return "A header has no name"
            if (name.contains(':') || name.any { it.code < 0x20 }) return "Bad header name: $name"
        }
        // W3-6: request bodies are capped (1 MB, same as the response cap).
        // A huge pasted/saved body held as one String and written at once
        // is a cheap OOM; the user trims it instead.
        if (body.toByteArray(Charsets.UTF_8).size > HttpClientEngine.MAX_REQUEST_BODY_BYTES) {
            return "Request body is over 1 MB — trim it before sending"
        }
        return null
    }

    companion object {
        val SUPPORTED_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    }
}

data class HttpResult(
    /** HTTP status code, or -1 when the request never got a response. */
    val statusCode: Int,
    val finalUrl: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    /** True when the body was cut at [HttpClientEngine.MAX_BODY_BYTES]. */
    val truncated: Boolean,
    val durationMs: Long,
    /** Non-null when the request failed before a response (DNS, timeout...). */
    val error: String?,
    /** True when Authorization/Cookie headers were stripped on a
     *  cross-origin redirect (H-1) — the UI says so. */
    val authStripped: Boolean = false
)

/** Header list <-> single text block codec (used for DB storage). Pure. */
object HttpHeaderCodec {
    /** Header names whose values are secrets and must never be persisted. */
    private val REDACT_NAMES = setOf(
        "authorization", "cookie", "proxy-authorization", "set-cookie"
    )

    /** True when a header named [name] carries a secret value. */
    fun isSensitiveHeader(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in REDACT_NAMES) return true
        // Normalize separators so X-Api-Key, X_API_KEY and XApiKey all match.
        val flat = lower.replace("-", "").replace("_", "")
        return flat.contains("token") || flat.contains("secret") || flat.contains("apikey") ||
            flat.contains("password") || flat.contains("session") ||
            flat == "auth" || flat.startsWith("xauth") || flat == "cookie2"
    }

    /**
     * [headers] with secret values replaced by `<redacted>` (H-2).
     * The header NAME is kept so history still shows that an auth header
     * was sent — only the credential is gone. Use this before ANY
     * persistence (history or saved requests). Never throws.
     */
    fun redactedForStorage(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.map { (name, value) ->
            if (isSensitiveHeader(name)) name to "<redacted>" else name to value
        }

    /** "Name: Value\n" lines; a value never contains a raw newline. */
    fun encode(headers: List<Pair<String, String>>): String =
        headers.filter { it.first.isNotBlank() }
            .joinToString("\n") { (k, v) -> "${k.trim()}: ${v.replace('\n', ' ').replace('\r', ' ')}" }

    /** Encode [headers] for storage, with secret values redacted first. */
    fun encodeForStorage(headers: List<Pair<String, String>>): String =
        encode(redactedForStorage(headers))

    /** Parse [encoded] back; malformed lines (no colon) are skipped. */
    fun decode(encoded: String): List<Pair<String, String>> {
        if (encoded.isBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (line in encoded.split('\n')) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isNotEmpty()) out.add(name to value)
        }
        return out
    }
}

object HttpClientEngine {

    const val CONNECT_TIMEOUT_MS = 30_000
    const val READ_TIMEOUT_MS = 30_000
    const val MAX_REDIRECTS = 5
    const val MAX_BODY_BYTES = 1 * 1024 * 1024 // 1 MB shown; more is truncated
    /** Request bodies over this are refused by [HttpRequestData.validate]. */
    const val MAX_REQUEST_BODY_BYTES = 1 * 1024 * 1024 // 1 MB

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /** The connection of the request currently in flight, if any (B14).
     *  The HTTP Client screen cancels it when the user taps Cancel —
     *  disconnect() unblocks the stalled read so execute() returns
     *  promptly instead of hanging until the 30 s timeout. Only one
     *  request runs at a time from the screen, so a single slot is
     *  enough; it is cleared in the finally of every hop. */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** B14: abort the in-flight request, if any. Safe to call when
     *  nothing is running (no-op). */
    fun cancelActive() {
        try { activeConnection?.disconnect() } catch (_: Exception) { }
    }

    /**
     * True when [a] and [b] are the same origin: same scheme, host
     * (case-insensitive) and effective port (explicit, else 80/443
     * default). A redirect to a different origin — including an
     * https→http downgrade — must not inherit auth headers.
     */
    fun sameOrigin(a: String, b: String): Boolean {
        return try {
            val ua = URL(a)
            val ub = URL(b)
            ua.protocol.equals(ub.protocol, ignoreCase = true) &&
                ua.host.equals(ub.host, ignoreCase = true) &&
                effectivePort(ua) == effectivePort(ub)
        } catch (_: Exception) {
            false // unparseable: treat as different (fail closed)
        }
    }

    private fun effectivePort(url: URL): Int {
        if (url.port != -1) return url.port
        return when (url.protocol.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    /** [headers] with auth headers removed (H-1, B4). Drops not only the
     *  classic auth names but any token-like header (X-Api-Key,
     *  X-Auth-Token, ...) via [HttpHeaderCodec.isSensitiveHeader] — the
     *  same rule the history redaction uses, so a redirect can never
     *  leak a credential the history would have redacted. Never throws. */
    fun stripAuthHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.filter { !HttpHeaderCodec.isSensitiveHeader(it.first) }

    /**
     * Execute [request] synchronously — the caller must run this off the
     * main thread (the screen uses Dispatchers.IO). Never throws: every
     * failure comes back as an [HttpResult] with [HttpResult.error] set.
     */
    fun execute(request: HttpRequestData): HttpResult {
        val start = System.currentTimeMillis()
        val validationError = request.validate()
        if (validationError != null) {
            return HttpResult(
                statusCode = -1, finalUrl = request.url, headers = emptyList(),
                body = "", truncated = false,
                durationMs = System.currentTimeMillis() - start, error = validationError
            )
        }
        var currentUrl = request.url.trim()
        var method = request.cleanMethod
        var body = request.body
        var activeHeaders = request.headers
        var authStripped = false
        var redirects = 0
        while (true) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setChunkedStreamingMode(8192) // L-4: make the chunked write real
                    setRequestProperty("User-Agent", "NOVA-Browser-HttpClient/2.0")
                    setRequestProperty("Accept", "*/*")
                    for ((name, value) in activeHeaders) {
                        if (name.isNotBlank()) setRequestProperty(name.trim(), value)
                    }
                    if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                        doOutput = true
                        // Chunked write (W3-6): never hand the whole body to
                        // the stream in one call.
                        outputStream.use { out ->
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            var offset = 0
                            while (offset < bytes.size) {
                                val len = minOf(8192, bytes.size - offset)
                                out.write(bytes, offset, len)
                                offset += len
                            }
                        }
                    }
                }
                // B14: this hop's connection is the cancellable one.
                activeConnection = connection
                val status = connection.responseCode
                if (status in REDIRECT_CODES && redirects < MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        return result(
                            status, currentUrl, emptyList(), "", false, start,
                            "Redirect ($status) with no Location header", authStripped
                        )
                    }
                    val nextUrl = URL(URL(currentUrl), location).toString()
                    // H-1: a redirect to a different origin — a new host,
                    // or an https→http downgrade — must NOT inherit the
                    // Authorization / Cookie headers. Browsers and fetch()
                    // do exactly this; re-sending a bearer token to a
                    // redirect target is a credential leak.
                    if (!sameOrigin(currentUrl, nextUrl)) {
                        val stripped = stripAuthHeaders(activeHeaders)
                        if (stripped.size != activeHeaders.size) authStripped = true
                        activeHeaders = stripped
                    }
                    currentUrl = nextUrl
                    redirects++
                    // 303 always becomes GET; 301/302 with a body also
                    // become GET in practice (browsers do this).
                    if (status == 303 || ((status == 301 || status == 302) && method == "POST")) {
                        method = "GET"
                        body = ""
                        activeHeaders = stripBodyHeaders(activeHeaders)
                    }
                    continue
                }
                if (status in REDIRECT_CODES && redirects >= MAX_REDIRECTS) {
                    connection.disconnect()
                    return result(
                        status, currentUrl, emptyList(), "", false, start,
                        "Too many redirects (more than $MAX_REDIRECTS)", authStripped
                    )
                }
                val headerList = ArrayList<Pair<String, String>>()
                for ((key, values) in connection.headerFields) {
                    if (key == null) continue // the status line
                    for (v in values) headerList.add(key to v)
                }
                val stream = try {
                    if (status >= 400) connection.errorStream else connection.inputStream
                } catch (_: IOException) {
                    null
                }
                val (text, truncated) = readCapped(stream)
                return result(status, currentUrl, headerList, text, truncated, start, null, authStripped)
            } catch (e: Exception) {
                return result(
                    -1, currentUrl, emptyList(), "", false, start,
                    "Request failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}",
                    authStripped
                )
            } finally {
                // B14: clear the cancellable slot for this hop (a redirect
                // sets it again for the next hop).
                if (activeConnection == connection) activeConnection = null
                try { connection?.disconnect() } catch (_: Exception) { }
            }
        }
    }

    /** B15: headers to carry when a redirect turns the request into a
     *  GET — a GET has no body, so Content-Type / Content-Length (which
     *  describe the dropped body) must not survive the hop. Pure, so the
     *  JVM suite covers it; execute() cannot be tested without a live
     *  server. */
    fun stripBodyHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.filterNot {
            val n = it.first.trim().lowercase()
            n == "content-type" || n == "content-length"
        }

    private fun result(
        status: Int, url: String, headers: List<Pair<String, String>>,
        body: String, truncated: Boolean, startMs: Long, error: String?,
        authStripped: Boolean = false
    ): HttpResult = HttpResult(
        statusCode = status, finalUrl = url, headers = headers, body = body,
        truncated = truncated, durationMs = System.currentTimeMillis() - startMs,
        error = error, authStripped = authStripped
    )

    /** Read at most [MAX_BODY_BYTES] + 1 bytes; the +1 tells us it truncated. */
    private fun readCapped(stream: java.io.InputStream?): Pair<String, Boolean> {
        if (stream == null) return "" to false
        return try {
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var truncated = false
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_BODY_BYTES) {
                        val room = MAX_BODY_BYTES - out.size()
                        if (room > 0) out.write(buf, 0, room)
                        truncated = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                // Best-effort decode: UTF-8 with replacement chars for bad
                // bytes, so a binary body never crashes the viewer.
                String(out.toByteArray(), Charsets.UTF_8) to truncated
            }
        } catch (_: Exception) {
            "" to false
        }
    }
}
