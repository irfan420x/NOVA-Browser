package com.nova.browser.adblock

import com.nova.browser.web.UrlUtils

/** What a blocked request was. Trackers are counted separately from ads
 *  so the shield sheet can show both numbers honestly. */
enum class BlockResult { ALLOWED, AD, TRACKER }

/**
 * Pure-Kotlin ad/tracker filter engine — no Android imports, so it is
 * fully JVM-testable (see tests/AdBlockEngineTest.kt).
 *
 * Rule syntax (parsed by [parse] from assets/adblock.txt):
 *   ||domain^              block domain + all subdomains
 *   ||domain^$third-party  block only when loaded by another site
 *   @@||domain^            exception — never block
 *   /substring/            block any URL containing the substring
 *   ##selector             cosmetic rule — hide matching elements everywhere
 *   domain##selector       cosmetic rule for one domain (+ subdomains)
 * Lines starting with ! are comments. A [trackers] line switches the
 * section: domains after it are counted as trackers as well as blocked.
 *
 * Matching is fast: domains are a hash set checked by walking the host's
 * label suffixes, so no rule list is ever scanned per request. Substring
 * patterns are the only linear scan, and the bundled list keeps them few.
 */
class AdBlockEngine(
    private val blockDomains: Set<String>,
    private val blockDomainsThirdParty: Set<String>,
    private val blockPatterns: List<String>,
    private val exceptionDomains: Set<String>,
    private val exceptionPatterns: List<String>,
    private val trackerDomains: Set<String>,
    private val globalSelectors: List<String>,
    private val domainSelectors: Map<String, List<String>>
) {
    companion object {
        fun parse(text: String): AdBlockEngine {
            val blockDomains = mutableSetOf<String>()
            val blockThirdParty = mutableSetOf<String>()
            val blockPatterns = mutableListOf<String>()
            val exceptionDomains = mutableSetOf<String>()
            val exceptionPatterns = mutableListOf<String>()
            val trackerDomains = mutableSetOf<String>()
            val globalSelectors = mutableListOf<String>()
            val domainSelectors = mutableMapOf<String, MutableList<String>>()
            var trackerSection = false

            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("!")) continue
                if (line == "[trackers]") { trackerSection = true; continue }

                // Cosmetic rules: [domains]##selector
                if (line.contains("##")) {
                    val parts = line.split("##", limit = 2)
                    val selector = parts[1].trim()
                    if (selector.isEmpty()) continue
                    val domains = parts[0].trim()
                    if (domains.isEmpty()) {
                        globalSelectors.add(selector)
                    } else {
                        for (d in domains.split(",")) {
                            val domain = d.trim().lowercase()
                            if (domain.isNotEmpty()) {
                                domainSelectors.getOrPut(domain) { mutableListOf() }.add(selector)
                            }
                        }
                    }
                    continue
                }

                // Exceptions
                if (line.startsWith("@@")) {
                    val body = line.substring(2)
                    when {
                        body.startsWith("||") -> {
                            val domain = domainPart(body.substring(2))
                            if (domain.isNotEmpty()) exceptionDomains.add(domain)
                        }
                        body.startsWith("/") && body.endsWith("/") && body.length > 2 ->
                            exceptionPatterns.add(body.substring(1, body.length - 1).lowercase())
                        else -> {
                            val domain = domainPart(body)
                            if (domain.isNotEmpty()) exceptionDomains.add(domain)
                        }
                    }
                    continue
                }

                // Domain rules
                if (line.startsWith("||")) {
                    val rest = line.substring(2)
                    val caret = rest.indexOf('^')
                    val domainPart = if (caret >= 0) rest.substring(0, caret) else rest
                    val options = if (caret >= 0 && rest.length > caret + 1 && rest[caret + 1] == '$')
                        rest.substring(caret + 2) else ""
                    val thirdParty = options.split(",").any { it.trim() == "third-party" }
                    if (domainPart.contains("/")) {
                        // ||domain/path — treat as a substring pattern.
                        blockPatterns.add(domainPart.lowercase())
                    } else {
                        val domain = domainPart.lowercase()
                        if (domain.isNotEmpty()) {
                            if (thirdParty) blockThirdParty.add(domain) else blockDomains.add(domain)
                            if (trackerSection) trackerDomains.add(domain)
                        }
                    }
                    continue
                }

                // Substring patterns /.../
                if (line.startsWith("/") && line.endsWith("/") && line.length > 2) {
                    blockPatterns.add(line.substring(1, line.length - 1).lowercase())
                    continue
                }

                // Bare word: a dotted word is a domain, anything else a pattern.
                if (line.contains(".")) blockDomains.add(line.lowercase())
                else blockPatterns.add(line.lowercase())
            }

            return AdBlockEngine(
                blockDomains = blockDomains,
                blockDomainsThirdParty = blockThirdParty,
                blockPatterns = blockPatterns,
                exceptionDomains = exceptionDomains,
                exceptionPatterns = exceptionPatterns,
                trackerDomains = trackerDomains,
                globalSelectors = globalSelectors,
                domainSelectors = domainSelectors
            )
        }

        /** The part before ^, $, / or : in a rule body — the domain itself. */
        private fun domainPart(body: String): String {
            var end = body.length
            for (c in charArrayOf('^', '$', '/', ':', '?', '#')) {
                val i = body.indexOf(c)
                if (i >= 0 && i < end) end = i
            }
            return body.substring(0, end).lowercase()
        }

        /** True if [host] itself or any parent domain is in [domains]. */
        fun matchDomain(host: String, domains: Set<String>): Boolean {
            var h: String? = host.lowercase()
            while (h != null) {
                if (domains.contains(h)) return true
                val dot = h.indexOf('.')
                h = if (dot >= 0) h.substring(dot + 1) else null
            }
            return false
        }
    }

    /** Total rules loaded (for the About/settings screen). */
    val ruleCount: Int get() =
        blockDomains.size + blockDomainsThirdParty.size + blockPatterns.size +
            exceptionDomains.size + trackerDomains.size

    /** Decide whether a request should be blocked. [pageHost] is the host of
     *  the page making the request (null when unknown — then third-party-only
     *  rules do not fire, so a main-frame load is never blocked by them). */
    fun shouldBlock(url: String, pageHost: String?): BlockResult {
        val lowerUrl = url.lowercase()
        val host = UrlUtils.extractHost(lowerUrl) ?: return BlockResult.ALLOWED
        // Exceptions win over everything.
        if (matchDomain(host, exceptionDomains)) return BlockResult.ALLOWED
        if (exceptionPatterns.any { lowerUrl.contains(it) }) return BlockResult.ALLOWED
        val thirdParty = isThirdParty(host, pageHost)
        val blocked = matchDomain(host, blockDomains) ||
            (thirdParty && matchDomain(host, blockDomainsThirdParty)) ||
            blockPatterns.any { lowerUrl.contains(it) }
        if (!blocked) return BlockResult.ALLOWED
        return if (matchDomain(host, trackerDomains)) BlockResult.TRACKER else BlockResult.AD
    }

    /** Cosmetic selectors to hide on [host]: global ones plus any that
     *  apply to the host or its parent domains. */
    fun cosmeticSelectors(host: String?): List<String> {
        if (host == null) return globalSelectors
        val out = ArrayList(globalSelectors)
        for ((domain, selectors) in domainSelectors) {
            if (matchDomain(host, setOf(domain))) out.addAll(selectors)
        }
        return out
    }

    fun isTrackerHost(host: String): Boolean = matchDomain(host, trackerDomains)

    private fun isThirdParty(host: String, pageHost: String?): Boolean {
        if (pageHost == null) return false
        val h = host.lowercase()
        val p = pageHost.lowercase()
        if (h == p) return false
        if (h.endsWith(".$p") || p.endsWith(".$h")) return false
        return true
    }
}
