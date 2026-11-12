package com.nova.browser.web

import java.net.URLEncoder

/**
 * Pure-Kotlin URL helpers — no Android imports, JVM-testable.
 * Used by the address bar, HTTPS-only mode and the ad-block engine.
 */
object UrlUtils {

    private val SEARCH_TEMPLATES = mapOf(
        "google" to "https://www.google.com/search?q=",
        "duckduckgo" to "https://duckduckgo.com/?q=",
        "bing" to "https://www.bing.com/search?q=",
        "brave" to "https://search.brave.com/search?q="
    )

    fun searchUrl(engine: String, query: String): String {
        val base = SEARCH_TEMPLATES[engine] ?: SEARCH_TEMPLATES["google"]!!
        return base + URLEncoder.encode(query, "UTF-8")
    }

    /** Turn what the user typed into a URL: a real address loads directly,
     *  anything else becomes a search with the chosen engine. */
    fun resolveInput(input: String, searchEngine: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("file://") || lower.startsWith("about:")
        ) return text
        if (lower.startsWith("www.")) return "https://$text"
        if (isLocalAddress(text)) return "http://$text"
        if (looksLikeDomain(text)) return "https://$text"
        return searchUrl(searchEngine, text)
    }

    /** Host part of a URL, lowercased, without port or userinfo.
     *  Returns null when no host can be found. Never throws. */
    fun extractHost(url: String): String? {
        return try {
            var s = url.trim().lowercase()
            val schemeIdx = s.indexOf("://")
            if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
            val slashIdx = s.indexOf('/')
            val authority = if (slashIdx >= 0) s.substring(0, slashIdx) else s
            if (authority.isEmpty()) return null
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end < 0) hostPort else hostPort.substring(0, end + 1)
            } else {
                hostPort.substringBefore(':')
            }
            host.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun hostOf(url: String): String? = extractHost(url)

    /** localhost, loopback and private LAN addresses — the only hosts for
     *  which plain http is allowed when HTTPS-only mode is on. */
    fun isLocalHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase().trim()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "[::1]") return true
        if (h.endsWith(".local") || h.endsWith(".lan")) return true
        val parts = h.split(".")
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it < 0 || it > 255 }) return false
        return when {
            nums[0] == 127 -> true
            nums[0] == 10 -> true
            nums[0] == 192 && nums[1] == 168 -> true
            nums[0] == 172 && nums[1] in 16..31 -> true
            else -> false
        }
    }

    /** Should this http URL be upgraded to https? Never for local hosts. */
    fun shouldUpgradeToHttps(url: String): Boolean {
        if (!url.lowercase().startsWith("http://")) return false
        return !isLocalHost(extractHost(url))
    }

    fun isHttpUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** "localhost:3000", "127.0.0.1:8080/app", "192.168.1.5" — typed as an
     *  address, not a search. */
    private fun isLocalAddress(text: String): Boolean {
        val host = text.substringBefore('/').substringBefore(':').lowercase()
        if (host == "localhost") return true
        if (host == "[::1]" || host == "::1") return true
        val parts = host.split(".")
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        return nums.all { it in 0..255 }
    }

    /** "example.com", "news.site.co.uk/path" — has a dot, no spaces, and a
     *  plausible TLD (last label at least 2 letters, not all digits). */
    private fun looksLikeDomain(text: String): Boolean {
        if (text.contains(" ") || text.contains("\t")) return false
        val host = text.substringBefore('/').substringBefore(':').substringBefore('?')
        if (!host.contains(".")) return false
        if (host.startsWith(".") || host.endsWith(".")) return false
        if (host.contains("..")) return false
        val labels = host.split(".")
        if (labels.any { it.isEmpty() }) return false
        if (labels.any { label -> label.any { c -> !(c.isLetterOrDigit() || c == '-') } }) return false
        val tld = labels.last()
        if (tld.length < 2) return false
        if (tld.all { it.isDigit() }) return false
        return true
    }
}
