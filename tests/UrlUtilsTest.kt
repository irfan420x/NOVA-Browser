package com.nova.browser.web

/**
 * JVM unit tests for UrlUtils — plain main() + check(), no framework.
 * Run with tests/run-tests.sh. Exits non-zero on any failure.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    // --- resolveInput: real addresses load directly ---
    check("https URL passes through", UrlUtils.resolveInput("https://example.com", "google") == "https://example.com")
    check("http URL passes through", UrlUtils.resolveInput("http://example.com", "google") == "http://example.com")
    check("about:blank passes through", UrlUtils.resolveInput("about:blank", "google") == "about:blank")
    check("bare domain gets https", UrlUtils.resolveInput("example.com", "google") == "https://example.com")
    check("www. gets https", UrlUtils.resolveInput("www.example.com", "google") == "https://www.example.com")
    check("domain with path gets https", UrlUtils.resolveInput("news.site.co.uk/path", "google") == "https://news.site.co.uk/path")
    check("leading/trailing spaces are trimmed", UrlUtils.resolveInput("  example.com  ", "google") == "https://example.com")
    check("empty input stays empty", UrlUtils.resolveInput("", "google") == "")
    check("spaces-only input stays empty", UrlUtils.resolveInput("   ", "google") == "")

    // --- resolveInput: local addresses stay http ---
    check("localhost with port stays http", UrlUtils.resolveInput("localhost:3000", "google") == "http://localhost:3000")
    check("LAN IP stays http", UrlUtils.resolveInput("192.168.1.5", "google") == "http://192.168.1.5")
    check("LAN IP with port and path stays http", UrlUtils.resolveInput("192.168.1.5:8080/app", "google") == "http://192.168.1.5:8080/app")
    check("127.0.0.1 stays http", UrlUtils.resolveInput("127.0.0.1:8080", "google") == "http://127.0.0.1:8080")

    // --- resolveInput: anything else is a search ---
    check("words become a Google search", UrlUtils.resolveInput("hello world", "google") == "https://www.google.com/search?q=hello+world")
    check("words become a DuckDuckGo search", UrlUtils.resolveInput("kotlin compose", "duckduckgo") == "https://duckduckgo.com/?q=kotlin+compose")
    check("words become a Bing search", UrlUtils.resolveInput("kotlin", "bing") == "https://www.bing.com/search?q=kotlin")
    check("words become a Brave search", UrlUtils.resolveInput("kotlin", "brave") == "https://search.brave.com/search?q=kotlin")
    check("a one-letter TLD is not a domain", UrlUtils.resolveInput("example.c", "google") == "https://www.google.com/search?q=example.c")
    check("a numeric TLD is not a domain", UrlUtils.resolveInput("example.123", "google") == "https://www.google.com/search?q=example.123")

    // --- searchUrl ---
    check("search query is URL-encoded", UrlUtils.searchUrl("google", "a b&c") == "https://www.google.com/search?q=a+b%26c")
    check("unknown engine falls back to Google", UrlUtils.searchUrl("nosuchengine", "x") == "https://www.google.com/search?q=x")

    // --- extractHost ---
    check("extractHost strips port and path", UrlUtils.extractHost("https://sub.example.com:8080/path?q=1") == "sub.example.com")
    check("extractHost strips userinfo", UrlUtils.extractHost("https://user:pass@example.com/") == "example.com")
    check("extractHost lowercases", UrlUtils.extractHost("https://EXAMPLE.COM/") == "example.com")
    check("extractHost keeps IPv4", UrlUtils.extractHost("http://192.168.1.1:8080/x") == "192.168.1.1")
    check("extractHost keeps IPv6 brackets", UrlUtils.extractHost("http://[::1]:8080/") == "[::1]")
    check("extractHost of a bare scheme is null", UrlUtils.extractHost("https://") == null)
    check("extractHost of empty is null", UrlUtils.extractHost("") == null)

    // --- isLocalHost ---
    check("localhost is local", UrlUtils.isLocalHost("localhost"))
    check("127.0.0.1 is local", UrlUtils.isLocalHost("127.0.0.1"))
    check("127.x is local", UrlUtils.isLocalHost("127.5.6.7"))
    check("10.x is local", UrlUtils.isLocalHost("10.0.0.1"))
    check("192.168.x is local", UrlUtils.isLocalHost("192.168.1.5"))
    check("172.16.x is local", UrlUtils.isLocalHost("172.16.0.1"))
    check("172.31.x is local", UrlUtils.isLocalHost("172.31.255.255"))
    check("172.32.x is NOT local", !UrlUtils.isLocalHost("172.32.0.1"))
    check("8.8.8.8 is NOT local", !UrlUtils.isLocalHost("8.8.8.8"))
    check("a public domain is NOT local", !UrlUtils.isLocalHost("example.com"))
    check("null is NOT local", !UrlUtils.isLocalHost(null))
    check(".local is local", UrlUtils.isLocalHost("printer.local"))
    check("::1 is local", UrlUtils.isLocalHost("::1"))

    // --- shouldUpgradeToHttps ---
    check("http public upgrades", UrlUtils.shouldUpgradeToHttps("http://example.com"))
    check("http LAN does not upgrade", !UrlUtils.shouldUpgradeToHttps("http://192.168.1.5"))
    check("http localhost does not upgrade", !UrlUtils.shouldUpgradeToHttps("http://localhost:3000"))
    check("https never upgrades", !UrlUtils.shouldUpgradeToHttps("https://example.com"))

    // --- isHttpUrl ---
    check("https is http(s)", UrlUtils.isHttpUrl("https://example.com"))
    check("http is http(s)", UrlUtils.isHttpUrl("http://example.com"))
    check("ftp is not http(s)", !UrlUtils.isHttpUrl("ftp://example.com"))
    check("about is not http(s)", !UrlUtils.isHttpUrl("about:blank"))

    // --- escapeLike (history search wildcards) ---
    check("plain text unchanged", UrlUtils.escapeLike("hello") == "hello")
    check("% is escaped", UrlUtils.escapeLike("100%") == "100\\%")
    check("_ is escaped", UrlUtils.escapeLike("a_b") == "a\\_b")
    check("backslash is escaped", UrlUtils.escapeLike("a\\b") == "a\\\\b")
    check("mixed wildcards", UrlUtils.escapeLike("%_\\") == "\\%\\_\\\\")
    check("empty stays empty", UrlUtils.escapeLike("") == "")
    check("Bengali text unchanged", UrlUtils.escapeLike("বাংলা") == "বাংলা")

    println("UrlUtilsTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
