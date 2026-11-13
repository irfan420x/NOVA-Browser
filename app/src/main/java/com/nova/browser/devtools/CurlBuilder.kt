package com.nova.browser.devtools

/**
 * Redacts sensitive query parameters from a URL — pure Kotlin,
 * JVM-testable. Password-reset links and `?token=…` / `?session=…` URLs
 * are the classic clipboard/log leak: the value is replaced with
 * `REDACTED` while the URL's shape (host, path, other params) is kept,
 * so the log stays useful for debugging without carrying secrets.
 */
object UrlRedactor {

    /** Query param names whose values are secrets (matched case-insensitively). */
    private val EXACT_NAMES = setOf(
        "token", "auth", "key", "password", "passwd", "secret", "session",
        "code", "apikey", "api_key", "sid", "sessionid", "access_token",
        "refresh_token", "id_token", "client_secret"
    )

    /** Substrings that mark a param name as secret-bearing. */
    private val NAME_SUBSTRINGS = listOf(
        "token", "secret", "password", "passwd", "apikey", "api_key", "session",
        "privatekey", "accesskey", "clientkey", "signingkey", "auth", "pwd", "otp"
    )

    /** True when a query parameter named [name] carries a secret value. */
    fun isSensitiveParam(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in EXACT_NAMES) return true
        // Normalize separators so api-key / api_key / apikey all match.
        val flat = lower.replace("-", "").replace("_", "")
        return NAME_SUBSTRINGS.any { flat.contains(it.replace("-", "").replace("_", "")) }
    }

    /**
     * Return [url] with sensitive query values replaced by `REDACTED`,
     * and any `scheme://user:pass@` userinfo stripped (N-4).
     * Fragment (#…) is preserved. URLs without a query pass through
     * unchanged (apart from userinfo stripping). Never throws.
     */
    fun redact(url: String): String {
        return try {
            val stripped = stripUserinfo(url)
            val hashIdx = stripped.indexOf('#')
            val beforeFragment = if (hashIdx >= 0) stripped.substring(0, hashIdx) else stripped
            val fragment = if (hashIdx >= 0) stripped.substring(hashIdx) else ""
            val queryIdx = beforeFragment.indexOf('?')
            if (queryIdx < 0) return stripped
            val base = beforeFragment.substring(0, queryIdx)
            val query = beforeFragment.substring(queryIdx + 1)
            if (query.isEmpty()) return url
            val redacted = query.split('&').joinToString("&") { pair ->
                val eqIdx = pair.indexOf('=')
                if (eqIdx <= 0) {
                    pair // bare flag param, no value to leak
                } else {
                    val name = pair.substring(0, eqIdx)
                    if (isSensitiveParam(name)) "$name=REDACTED" else pair
                }
            }
            "$base?$redacted$fragment"
        } catch (_: Exception) {
            url
        }
    }

    /** Strip `scheme://user:pass@` → `scheme://` (N-4). An `@` in the
     *  path or query is not userinfo and is left alone. Never throws. */
    fun stripUserinfo(url: String): String {
        return try {
            val schemeIdx = url.indexOf("://")
            if (schemeIdx < 0) return url
            val afterScheme = schemeIdx + 3
            val atIdx = url.indexOf('@', afterScheme)
            if (atIdx < 0) return url
            val slashIdx = url.indexOf('/', afterScheme)
            val queryIdx2 = url.indexOf('?', afterScheme)
            val hostEnd = listOf(slashIdx, queryIdx2).filter { it >= 0 }.minOrNull() ?: url.length
            if (atIdx > hostEnd) return url // '@' is in path/query, not userinfo
            url.substring(0, afterScheme) + url.substring(atIdx + 1)
        } catch (_: Exception) {
            url
        }
    }

    /** True when [url] contains a redacted value after [redact]. */
    fun wasRedacted(url: String): Boolean = redact(url) != url
}

/**
 * Pure-Kotlin cURL builder — no Android imports, JVM-testable.
 *
 * Turns an HTTP request (method + URL + headers + body) into a single
 * copy-pasteable `curl` command, for the Network log's "Copy as cURL" and
 * the HTTP Client's export. Shell-quotes every value with single quotes
 * (the POSIX-safe way: a literal `'` becomes `'\''`).
 *
 * SECURITY (W6 H-2 / W3-2): the output is meant for the clipboard, where
 * any app can read it — so auth header values (Authorization, Cookie,
 * Proxy-Authorization, token-like headers) are replaced with
 * `<redacted>`, and sensitive query params in the URL are redacted by
 * [UrlRedactor]. The command stays runnable for the request shape, but
 * it will NOT authenticate as the user. If the user needs a working
 * authenticated curl, they re-add the token themselves.
 */
object CurlBuilder {

    /** Header names whose VALUES must never reach the clipboard. */
    private val SENSITIVE_HEADERS = setOf("authorization", "cookie", "proxy-authorization", "set-cookie")

    /** True when a header named [name] carries a secret value. */
    fun isSensitiveHeader(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in SENSITIVE_HEADERS) return true
        // Normalize separators so X-Api-Key, X_API_KEY and XApiKey all match.
        val flat = lower.replace("-", "").replace("_", "")
        return flat.contains("token") || flat.contains("secret") || flat.contains("apikey") ||
            flat.contains("password") || flat.contains("session") ||
            flat == "auth" || flat.startsWith("xauth") || flat == "cookie2"
    }

    /** Build a curl command from the raw parts. Never throws. */
    fun build(
        method: String,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: String? = null
    ): String {
        val m = method.trim().uppercase().ifBlank { "GET" }
        val sb = StringBuilder()
        sb.append("curl")
        if (m != "GET") {
            sb.append(" -X ").append(m)
        }
        for ((name, value) in headers) {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) continue
            // Auth header values are redacted — the clipboard is world-readable.
            val shownValue = if (isSensitiveHeader(cleanName)) "<redacted>" else value.trim()
            sb.append(" -H ").append(sq("$cleanName: $shownValue"))
        }
        if (!body.isNullOrEmpty() && m != "GET" && m != "HEAD") {
            if (looksSecretBearing(body)) {
                // N-3: never copy a secret-bearing body to the world-readable
                // clipboard — omit it with a comment instead.
                sb.append(" # --data-raw omitted: body looks secret-bearing")
            } else {
                sb.append(" --data-raw ").append(sq(body))
            }
        }
        sb.append(" ").append(sq(UrlRedactor.redact(url)))
        return sb.toString()
    }

    /** True when [body] looks like it carries a secret — a JSON key
     *  `"password"` / `"token"` / `"secret"` / ... or a form field
     *  `password=` / `token=` / .... Pure. Over-redaction is intended:
     *  a body that merely mentions a secret word is omitted from the
     *  clipboard rather than risking a leak (N-3). */
    fun looksSecretBearing(body: String): Boolean {
        val lower = body.lowercase()
        return SECRET_BODY_PATTERNS.any { it in lower }
    }

    private val SECRET_BODY_PATTERNS = listOf(
        "\"password\"", "\"passwd\"", "\"pwd\"", "\"token\"", "\"secret\"",
        "\"apikey\"", "\"api_key\"", "\"access_token\"", "\"refresh_token\"",
        "password=", "passwd=", "pwd=", "token=", "secret=",
        "apikey=", "api_key="
    )

    /** Build a curl command from a [HttpRequestData]. */
    fun build(request: HttpRequestData): String =
        build(request.method, request.url, request.headers, request.body)

    /**
     * Single-quote [s] for POSIX shells: wrap in '...' and replace each
     * embedded ' with '\'' (close quote, escaped quote, reopen quote).
     */
    fun sq(s: String): String {
        if (!s.contains('\'')) return "'$s'"
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
