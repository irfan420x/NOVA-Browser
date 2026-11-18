package com.nova.browser.web

import com.nova.browser.web.HttpsEnforcer.HttpsDecision

/**
 * JVM unit tests for HttpsEnforcer — plain main() + check(), no framework.
 * Run with tests/run-tests.sh (after the coordinator adds this file to it).
 * Exits non-zero on any failure.
 *
 * Shorthand below: decide() defaults to HTTPS-only ON, no exceptions,
 * sub-resource (isForMainFrame = false), not a redirect.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private fun decide(
    url: String,
    httpsOnly: Boolean = true,
    exceptions: Set<String> = emptySet(),
    isForMainFrame: Boolean = false,
    isRedirect: Boolean = false,
    failedHosts: Set<String> = emptySet(),
    method: String? = null
) = HttpsEnforcer.decide(url, httpsOnly, exceptions, isForMainFrame, isRedirect, failedHosts, method)

fun main() {
    // --- http sub-resource on a normal host: UPGRADE ---
    check(
        "http sub-resource upgrades",
        decide("http://example.com/style.css") == HttpsDecision.UPGRADE
    )
    check(
        "http sub-resource with port/path/query upgrades",
        decide("http://cdn.example.com:8080/app.js?v=1#frag") == HttpsDecision.UPGRADE
    )
    check(
        "HTTP uppercase scheme upgrades",
        decide("HTTP://EXAMPLE.COM/x.png") == HttpsDecision.UPGRADE
    )
    check(
        "http fetch/XHR URL upgrades (indistinguishable from a sub-resource here)",
        decide("http://api.example.com/data.json") == HttpsDecision.UPGRADE
    )
    check(
        "http iframe URL upgrades",
        decide("http://ads.example.com/frame.html") == HttpsDecision.UPGRADE
    )
    check(
        "upgradeUrl preserves host/port/path/query/fragment",
        HttpsEnforcer.upgradeUrl("http://cdn.example.com:8080/app.js?v=1#frag") ==
            "https://cdn.example.com:8080/app.js?v=1#frag"
    )
    check(
        "upgradeUrl is null for https",
        HttpsEnforcer.upgradeUrl("https://example.com/") == null
    )
    check(
        "upgradeUrl is null for non-http",
        HttpsEnforcer.upgradeUrl("ftp://example.com/file") == null
    )
    check(
        "redirect headers carry the https URL as Location",
        HttpsEnforcer.redirectHeaders("https://example.com/x.css") ==
            mapOf("Location" to "https://example.com/x.css")
    )
    check(
        "redirect status is 307 (method-preserving)",
        HttpsEnforcer.REDIRECT_STATUS_CODE == 307
    )

    // --- http sub-resource on localhost / LAN: ALLOW ---
    check("http localhost sub-resource allowed", decide("http://localhost:3000/app.js") == HttpsDecision.ALLOW)
    check("http localhost no-port allowed", decide("http://localhost/app.js") == HttpsDecision.ALLOW)
    check("http 127.0.0.1 allowed", decide("http://127.0.0.1:8080/app.js") == HttpsDecision.ALLOW)
    check("http 127.x allowed", decide("http://127.5.6.7/app.js") == HttpsDecision.ALLOW)
    check("http 192.168.x allowed", decide("http://192.168.1.5:8080/app.js") == HttpsDecision.ALLOW)
    check("http 10.x allowed", decide("http://10.0.0.2/app.js") == HttpsDecision.ALLOW)
    check("http 172.16.x allowed", decide("http://172.16.0.9/app.js") == HttpsDecision.ALLOW)
    check("http .local host allowed", decide("http://printer.local/app.js") == HttpsDecision.ALLOW)
    check("http [::1] allowed", decide("http://[::1]:8080/app.js") == HttpsDecision.ALLOW)
    check("http public IP still upgrades", decide("http://8.8.8.8/app.js") == HttpsDecision.UPGRADE)
    check("http 172.32.x is NOT LAN, upgrades", decide("http://172.32.0.1/app.js") == HttpsDecision.UPGRADE)

    // --- http sub-resource on an exception host: ALLOW ---
    check(
        "http exception host allowed",
        decide("http://legacy.example.com/page", exceptions = setOf("legacy.example.com")) == HttpsDecision.ALLOW
    )
    check(
        "http subdomain of exception host allowed",
        decide("http://sub.legacy.example.com/page", exceptions = setOf("legacy.example.com")) == HttpsDecision.ALLOW
    )
    check(
        "exception match is case-insensitive",
        decide("http://LEGACY.EXAMPLE.COM/page", exceptions = setOf("legacy.example.com")) == HttpsDecision.ALLOW
    )
    check(
        "badexample.com does NOT match exception example.com",
        decide("http://badexample.com/page", exceptions = setOf("example.com")) == HttpsDecision.UPGRADE
    )
    check(
        "example.com.evil.com does NOT match exception example.com",
        decide("http://example.com.evil.com/page", exceptions = setOf("example.com")) == HttpsDecision.UPGRADE
    )
    check(
        "unrelated host is not exempted by another host's exception",
        decide("http://other.com/page", exceptions = setOf("legacy.example.com")) == HttpsDecision.UPGRADE
    )

    // --- https -> http redirect target: UPGRADE (the redirect hole) ---
    check(
        "https->http redirect target (main frame, isRedirect) upgrades",
        decide("http://example.com/landing", isForMainFrame = true, isRedirect = true) == HttpsDecision.UPGRADE
    )
    check(
        "decideForRequest: https page + http main frame is a downgrade redirect, upgrades",
        HttpsEnforcer.decideForRequest(
            "http://example.com/landing", httpsOnly = true,
            isForMainFrame = true, pageUrl = "https://example.com/start"
        ) == HttpsDecision.UPGRADE
    )
    check(
        "decideForRequest: redirect target on a failed-upgrade host blocks",
        HttpsEnforcer.decideForRequest(
            "http://nohttps.example.com/landing", httpsOnly = true,
            isForMainFrame = true, pageUrl = "https://example.com/start",
            upgradeFailedHosts = setOf("nohttps.example.com")
        ) == HttpsDecision.BLOCK
    )
    check(
        "redirect target on localhost still allowed",
        decide("http://localhost:3000/landing", isForMainFrame = true, isRedirect = true) == HttpsDecision.ALLOW
    )
    check(
        "redirect target on exception host still allowed",
        decide(
            "http://legacy.example.com/landing", isForMainFrame = true, isRedirect = true,
            exceptions = setOf("legacy.example.com")
        ) == HttpsDecision.ALLOW
    )
    check(
        "isDowngradeRedirect: https page + http request is true",
        HttpsEnforcer.isDowngradeRedirect("http://example.com/x", "https://example.com/start")
    )
    check(
        "isDowngradeRedirect: https page + https request is false",
        !HttpsEnforcer.isDowngradeRedirect("https://example.com/x", "https://example.com/start")
    )
    check(
        "isDowngradeRedirect: http page + http request is false (not a downgrade)",
        !HttpsEnforcer.isDowngradeRedirect("http://example.com/x", "http://example.com/start")
    )
    check(
        "isDowngradeRedirect: blank page is false",
        !HttpsEnforcer.isDowngradeRedirect("http://example.com/x", "")
    )
    check(
        "isDowngradeRedirect: null page is false",
        !HttpsEnforcer.isDowngradeRedirect("http://example.com/x", null)
    )

    // --- main frame, NOT a redirect: SKIP (navigation path owns it) ---
    check(
        "http main-frame navigation (not a redirect) is skipped",
        decide("http://example.com/", isForMainFrame = true, isRedirect = false) == HttpsDecision.ALLOW
    )
    check(
        "decideForRequest: http page + http main frame is a fresh navigation, skipped",
        HttpsEnforcer.decideForRequest(
            "http://example.com/", httpsOnly = true,
            isForMainFrame = true, pageUrl = "http://example.com/"
        ) == HttpsDecision.ALLOW
    )
    check(
        "decideForRequest: blank tab + http main frame is a fresh navigation, skipped",
        HttpsEnforcer.decideForRequest(
            "http://example.com/", httpsOnly = true,
            isForMainFrame = true, pageUrl = ""
        ) == HttpsDecision.ALLOW
    )

    // --- https URL: ALLOW ---
    check("https sub-resource allowed", decide("https://example.com/style.css") == HttpsDecision.ALLOW)
    check(
        "https main frame allowed",
        decide("https://example.com/", isForMainFrame = true) == HttpsDecision.ALLOW
    )
    check(
        "https main-frame redirect target allowed",
        decide("https://example.com/landing", isForMainFrame = true, isRedirect = true) == HttpsDecision.ALLOW
    )

    // --- non-http URL: ALLOW ---
    check("ftp allowed", decide("ftp://example.com/file") == HttpsDecision.ALLOW)
    check("about:blank allowed", decide("about:blank") == HttpsDecision.ALLOW)
    check("data: URL allowed", decide("data:text/html,<b>x</b>") == HttpsDecision.ALLOW)
    check("blob: URL allowed", decide("blob:https://example.com/1234") == HttpsDecision.ALLOW)
    check("ws:// allowed (invisible to the interceptor — see HttpsEnforcer docs)", decide("ws://example.com/socket") == HttpsDecision.ALLOW)
    check("empty URL allowed", decide("") == HttpsDecision.ALLOW)

    // --- HTTPS-only OFF: ALLOW everything ---
    check(
        "https-only off: http sub-resource allowed",
        decide("http://example.com/style.css", httpsOnly = false) == HttpsDecision.ALLOW
    )
    check(
        "https-only off: http redirect target allowed",
        decide("http://example.com/landing", httpsOnly = false, isForMainFrame = true, isRedirect = true) == HttpsDecision.ALLOW
    )
    check(
        "https-only off: malformed http allowed (no enforcement at all)",
        decide("http://", httpsOnly = false) == HttpsDecision.ALLOW
    )

    // --- BLOCK: cannot be upgraded ---
    check("malformed http:// with no host blocks", decide("http://") == HttpsDecision.BLOCK)
    check("malformed http:///path with no host blocks", decide("http:///path") == HttpsDecision.BLOCK)
    check(
        "host with a known-failed upgrade blocks instead of re-upgrading",
        decide("http://nohttps.example.com/x", failedHosts = setOf("nohttps.example.com")) == HttpsDecision.BLOCK
    )
    check(
        "subdomain of a failed-upgrade host blocks",
        decide("http://sub.nohttps.example.com/x", failedHosts = setOf("nohttps.example.com")) == HttpsDecision.BLOCK
    )
    check(
        "failed-upgrade host does NOT block an unrelated host",
        decide("http://other.com/x", failedHosts = setOf("nohttps.example.com")) == HttpsDecision.UPGRADE
    )
    check(
        "per-site exception wins over a failed upgrade (explicit user choice)",
        decide(
            "http://nohttps.example.com/x",
            exceptions = setOf("nohttps.example.com"),
            failedHosts = setOf("nohttps.example.com")
        ) == HttpsDecision.ALLOW
    )
    check(
        "localhost wins over a failed upgrade",
        decide("http://localhost:3000/x", failedHosts = setOf("localhost")) == HttpsDecision.ALLOW
    )

    // --- AUDIT 2 (F01-A): POST main-frame navigations keep their body ---
    check("GET can upgrade via loadUrl", HttpsEnforcer.canUpgradeViaLoadUrl("GET"))
    check("HEAD can upgrade via loadUrl", HttpsEnforcer.canUpgradeViaLoadUrl("HEAD"))
    check("lowercase get can upgrade via loadUrl", HttpsEnforcer.canUpgradeViaLoadUrl("get"))
    check("null method defaults to loadUrl upgrade", HttpsEnforcer.canUpgradeViaLoadUrl(null))
    check("POST must NOT upgrade via loadUrl (body would be dropped)", !HttpsEnforcer.canUpgradeViaLoadUrl("POST"))
    check("PUT must NOT upgrade via loadUrl", !HttpsEnforcer.canUpgradeViaLoadUrl("PUT"))
    check("PATCH must NOT upgrade via loadUrl", !HttpsEnforcer.canUpgradeViaLoadUrl("PATCH"))
    check("DELETE must NOT upgrade via loadUrl", !HttpsEnforcer.canUpgradeViaLoadUrl("DELETE"))
    check("lowercase post must NOT upgrade via loadUrl", !HttpsEnforcer.canUpgradeViaLoadUrl("post"))
    // A main-frame POST to http is UPGRADEd by the interceptor (307
    // preserves the method) instead of being skipped for the
    // navigation path, which would have turned it into a GET.
    check(
        "main-frame POST to http upgrades (307, method preserved)",
        decide("http://example.com/login", isForMainFrame = true, method = "POST") == HttpsDecision.UPGRADE
    )
    check(
        "main-frame PUT to http upgrades",
        decide("http://example.com/api", isForMainFrame = true, method = "PUT") == HttpsDecision.UPGRADE
    )
    check(
        "main-frame POST from an https page (downgrade) upgrades",
        HttpsEnforcer.decideForRequest(
            "http://example.com/login", httpsOnly = true, isForMainFrame = true,
            pageUrl = "https://example.com/form", method = "POST"
        ) == HttpsDecision.UPGRADE
    )
    // GET main-frame behaviour is unchanged: the navigation path owns it.
    check(
        "main-frame GET to http is still skipped for the navigation path",
        decide("http://example.com/page", isForMainFrame = true, method = "GET") == HttpsDecision.ALLOW
    )
    check(
        "main-frame GET with null method is still skipped",
        decide("http://example.com/page", isForMainFrame = true, method = null) == HttpsDecision.ALLOW
    )
    // The POST upgrade still respects local hosts and exceptions.
    check(
        "main-frame POST to localhost is allowed, not upgraded",
        decide("http://localhost:3000/submit", isForMainFrame = true, method = "POST") == HttpsDecision.ALLOW
    )
    check(
        "main-frame POST to an exception host is allowed, not upgraded",
        decide(
            "http://example.com/submit", isForMainFrame = true, method = "POST",
            exceptions = setOf("example.com")
        ) == HttpsDecision.ALLOW
    )

    // --- AUDIT 2 (F01-B): the upgrade flag belongs to one navigation ---
    check(
        "same URL is the upgraded target",
        HttpsEnforcer.isUpgradedTarget("https://example.com/page", "https://example.com/page")
    )
    check(
        "a different URL is not the upgraded target",
        !HttpsEnforcer.isUpgradedTarget("https://example.com/page", "https://other.com/page")
    )
    check(
        "a different path on the same host is not the upgraded target",
        !HttpsEnforcer.isUpgradedTarget("https://example.com/page", "https://example.com/other")
    )
    check(
        "null upgraded URL is never the target",
        !HttpsEnforcer.isUpgradedTarget(null, "https://example.com/page")
    )
    check(
        "null loaded URL is never the target",
        !HttpsEnforcer.isUpgradedTarget("https://example.com/page", null)
    )
    check(
        "about:blank is never the upgraded target",
        !HttpsEnforcer.isUpgradedTarget("https://example.com/page", "about:blank")
    )

    println("HttpsEnforcerTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
