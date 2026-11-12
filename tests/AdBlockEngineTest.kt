package com.nova.browser.adblock

import com.nova.browser.web.UrlUtils

/**
 * JVM unit tests for AdBlockEngine — plain main() + check(), no framework.
 * Run with tests/run-tests.sh. Exits non-zero on any failure.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private val SAMPLE_RULES = """
    ! a comment line
    ||ads.example.com^
    ||tracker.example.org^${'$'}third-party
    ||doubleclick.net^
    @@||good.example.com^
    @@/allowme/
    [trackers]
    ||analytics.example.com^
    /banners/
    ##.ad-banner
    example.com##.sponsored
    ads2.example.net
    adserver
    ||cdn.example.com/ads/
""".trimIndent()

fun main() {
    val engine = AdBlockEngine.parse(SAMPLE_RULES)

    // ruleCount = blockDomains (ads, doubleclick, analytics, ads2 = 4)
    //   + thirdParty (tracker = 1) + patterns (banners, adserver, cdn path = 3)
    //   + exceptions (good.example.com = 1) + trackers (analytics = 1) = 10.
    // Cosmetic rules and exception patterns are not counted.
    check("ruleCount counts every section", engine.ruleCount == 10)

    // --- Domain blocking ---
    check("blocks a listed ad domain", engine.shouldBlock("https://ads.example.com/banner.js", "news.com") == BlockResult.AD)
    check("blocks a subdomain of a listed domain", engine.shouldBlock("https://cdn.ads.example.com/x.js", "news.com") == BlockResult.AD)
    check("blocks first-party ads too (|| rules are not third-party-only)", engine.shouldBlock("https://ads.example.com/x", "ads.example.com") == BlockResult.AD)
    check("blocks doubleclick", engine.shouldBlock("https://doubleclick.net/ads.js", "news.com") == BlockResult.AD)
    check("does not block an unlisted domain", engine.shouldBlock("https://news.com/article", "news.com") == BlockResult.ALLOWED)
    check("does not block a lookalike domain", engine.shouldBlock("https://notads.example.com/x", "news.com") == BlockResult.ALLOWED)
    check("does not block a suffix lookalike", engine.shouldBlock("https://ads.example.com.evil.com/x", "news.com") == BlockResult.ALLOWED)
    check("bare-word domain rule blocks", engine.shouldBlock("https://ads2.example.net/x", "news.com") == BlockResult.AD)

    // --- Third-party-only rules ---
    check("third-party rule fires cross-site", engine.shouldBlock("https://tracker.example.org/pixel", "news.com") == BlockResult.AD)
    check("third-party rule does not fire first-party", engine.shouldBlock("https://tracker.example.org/pixel", "tracker.example.org") == BlockResult.ALLOWED)
    check("third-party rule does not fire on a subdomain of itself", engine.shouldBlock("https://tracker.example.org/pixel", "sub.tracker.example.org") == BlockResult.ALLOWED)
    check("third-party rule does not fire without a page host", engine.shouldBlock("https://tracker.example.org/pixel", null) == BlockResult.ALLOWED)

    // --- v1.1 regression: the search crash fix ---
    // shouldInterceptRequest runs on a background thread, so the client must
    // never call WebView.getUrl() there — it uses the tab's own URL instead.
    // A blank tab URL (before the first onPageStarted) must behave like an
    // unknown page host: no throw, no third-party-only block.
    check("blank tab URL extracts to null host", UrlUtils.extractHost("") == null)
    check("blank page host allows third-party-only rules", engine.shouldBlock("https://tracker.example.org/pixel", UrlUtils.extractHost("")) == BlockResult.ALLOWED)
    check("blank page host still blocks plain domain rules", engine.shouldBlock("https://ads.example.com/banner.js", UrlUtils.extractHost("")) == BlockResult.AD)

    // --- Exceptions win over everything ---
    check("exception domain is allowed", engine.shouldBlock("https://good.example.com/ad.js", "news.com") == BlockResult.ALLOWED)
    check("exception domain subdomain is allowed", engine.shouldBlock("https://cdn.good.example.com/x", "news.com") == BlockResult.ALLOWED)
    check("exception pattern is allowed", engine.shouldBlock("https://evil.com/allowme/x", "news.com") == BlockResult.ALLOWED)
    check("exception pattern beats a block pattern", engine.shouldBlock("https://news.com/allowme/banners/x", "news.com") == BlockResult.ALLOWED)

    // --- Substring patterns ---
    check("substring pattern blocks", engine.shouldBlock("https://news.com/images/banners/top.jpg", "news.com") == BlockResult.AD)
    check("bare-word pattern blocks", engine.shouldBlock("https://news.com/adserver/x", "news.com") == BlockResult.AD)
    check("domain/path rule blocks its path", engine.shouldBlock("https://cdn.example.com/ads/banner.js", "news.com") == BlockResult.AD)
    check("domain/path rule does not block other paths", engine.shouldBlock("https://cdn.example.com/other.js", "news.com") == BlockResult.ALLOWED)

    // --- Tracker section ---
    check("tracker domain is counted as TRACKER", engine.shouldBlock("https://analytics.example.com/collect", "news.com") == BlockResult.TRACKER)
    check("isTrackerHost true for tracker + subdomains", engine.isTrackerHost("analytics.example.com") && engine.isTrackerHost("sub.analytics.example.com"))
    check("isTrackerHost false for a plain ad domain", !engine.isTrackerHost("ads.example.com"))

    // --- matchDomain ---
    check("matchDomain exact", AdBlockEngine.matchDomain("ads.example.com", setOf("ads.example.com")))
    check("matchDomain subdomain", AdBlockEngine.matchDomain("cdn.ads.example.com", setOf("ads.example.com")))
    check("matchDomain case-insensitive", AdBlockEngine.matchDomain("CDN.ADS.EXAMPLE.COM", setOf("ads.example.com")))
    check("matchDomain rejects lookalikes", !AdBlockEngine.matchDomain("notads.example.com", setOf("ads.example.com")))
    check("matchDomain rejects suffix lookalikes", !AdBlockEngine.matchDomain("ads.example.com.evil.com", setOf("ads.example.com")))

    // --- Cosmetic selectors ---
    val onExample = engine.cosmeticSelectors("example.com")
    check("cosmetic: global selector applies everywhere", onExample.contains(".ad-banner"))
    check("cosmetic: domain selector applies on its domain", onExample.contains(".sponsored"))
    check("cosmetic: domain selector applies on subdomains", engine.cosmeticSelectors("sub.example.com").contains(".sponsored"))
    check("cosmetic: domain selector does not apply elsewhere", !engine.cosmeticSelectors("other.com").contains(".sponsored"))
    check("cosmetic: global selector applies elsewhere", engine.cosmeticSelectors("other.com").contains(".ad-banner"))
    check("cosmetic: null host gets globals only", engine.cosmeticSelectors(null) == listOf(".ad-banner"))

    // --- Empty engine ---
    val empty = AdBlockEngine.parse("")
    check("empty engine allows everything", empty.shouldBlock("https://ads.example.com/x", "news.com") == BlockResult.ALLOWED)
    check("empty engine has zero rules", empty.ruleCount == 0)
    check("comments-only list parses to zero rules", AdBlockEngine.parse("! only a comment\n\n").ruleCount == 0)

    println("AdBlockEngineTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
