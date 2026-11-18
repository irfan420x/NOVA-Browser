package com.nova.browser.web

/**
 * JVM unit tests for the v2.3 custom search engine + custom home page
 * (UrlUtils.isValidSearchTemplate / searchUrl(custom) /
 * normalizeHomePageUrl / resolveInput(custom)). Plain main() + check(),
 * no framework. Run with tests/run-tests.sh. Exits non-zero on failure.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    // --- isValidSearchTemplate: usable templates ---
    check("https template with %s is valid", UrlUtils.isValidSearchTemplate("https://search.example.com/search?q=%s"))
    check("http template with %s is valid", UrlUtils.isValidSearchTemplate("http://search.example.com/?q=%s"))
    check("template with path + extra params is valid", UrlUtils.isValidSearchTemplate("https://example.com/a/b?q=%s&lang=en"))
    check("template with port is valid", UrlUtils.isValidSearchTemplate("https://search.example.com:8443/search?q=%s"))
    check("leading/trailing spaces are trimmed", UrlUtils.isValidSearchTemplate("  https://example.com/search?q=%s  "))

    // --- isValidSearchTemplate: unusable templates ---
    check("null template is invalid", !UrlUtils.isValidSearchTemplate(null))
    check("blank template is invalid", !UrlUtils.isValidSearchTemplate("   "))
    check("template without %s is invalid", !UrlUtils.isValidSearchTemplate("https://example.com/search?q="))
    check("template with two %s is invalid", !UrlUtils.isValidSearchTemplate("https://example.com/search?q=%s&x=%s"))
    check("template with another % escape is invalid", !UrlUtils.isValidSearchTemplate("https://example.com/search?q=%s&x=%20"))
    check("ftp template is invalid", !UrlUtils.isValidSearchTemplate("ftp://example.com/search?q=%s"))
    check("no-host template is invalid", !UrlUtils.isValidSearchTemplate("https:///search?q=%s"))
    check("template with a space is invalid", !UrlUtils.isValidSearchTemplate("https://example.com/search?q=%s x"))
    check("bare domain without scheme is invalid", !UrlUtils.isValidSearchTemplate("example.com/search?q=%s"))

    // --- searchUrl with a custom template ---
    check(
        "custom template builds the search URL",
        UrlUtils.searchUrl("custom", "hello world", "https://search.example.com/search?q=%s") ==
            "https://search.example.com/search?q=hello+world"
    )
    check(
        "custom query is URL-encoded",
        UrlUtils.searchUrl("custom", "a b&c", "https://example.com/?q=%s") ==
            "https://example.com/?q=a+b%26c"
    )
    check(
        "invalid custom template falls back to Google",
        UrlUtils.searchUrl("custom", "x", "https://example.com/search") ==
            "https://www.google.com/search?q=x"
    )
    check(
        "missing custom template falls back to Google",
        UrlUtils.searchUrl("custom", "x", null) == "https://www.google.com/search?q=x"
    )
    check(
        "blank custom template falls back to Google",
        UrlUtils.searchUrl("custom", "x", "   ") == "https://www.google.com/search?q=x"
    )
    check(
        "built-in engines ignore the custom template",
        UrlUtils.searchUrl("duckduckgo", "x", "https://example.com/?q=%s") ==
            "https://duckduckgo.com/?q=x"
    )

    // --- resolveInput with a custom engine ---
    check(
        "words become a custom search",
        UrlUtils.resolveInput("hello world", "custom", "https://search.example.com/search?q=%s") ==
            "https://search.example.com/search?q=hello+world"
    )
    check(
        "a real address still loads directly with a custom engine",
        UrlUtils.resolveInput("example.com", "custom", "https://search.example.com/search?q=%s") ==
            "https://example.com"
    )
    check(
        "invalid custom template falls back to Google in resolveInput",
        UrlUtils.resolveInput("hello", "custom", "not a template") ==
            "https://www.google.com/search?q=hello"
    )
    check(
        "two-arg resolveInput still works (default template)",
        UrlUtils.resolveInput("hello", "google") == "https://www.google.com/search?q=hello"
    )

    // --- normalizeHomePageUrl ---
    check("blank home page stays blank", UrlUtils.normalizeHomePageUrl("") == "")
    check("null home page stays blank", UrlUtils.normalizeHomePageUrl(null) == "")
    check("spaces-only home page stays blank", UrlUtils.normalizeHomePageUrl("   ") == "")
    check("https home page passes through", UrlUtils.normalizeHomePageUrl("https://example.com") == "https://example.com")
    check("http home page passes through", UrlUtils.normalizeHomePageUrl("http://example.com/start") == "http://example.com/start")
    check("bare domain gets https", UrlUtils.normalizeHomePageUrl("example.com") == "https://example.com")
    check("www. gets https", UrlUtils.normalizeHomePageUrl("www.example.com") == "https://www.example.com")
    check("domain with path gets https", UrlUtils.normalizeHomePageUrl("news.site.co.uk/today") == "https://news.site.co.uk/today")
    check("localhost gets http", UrlUtils.normalizeHomePageUrl("localhost:3000") == "http://localhost:3000")
    check("LAN IP gets http", UrlUtils.normalizeHomePageUrl("192.168.1.5:8080/app") == "http://192.168.1.5:8080/app")
    check("leading/trailing spaces are trimmed", UrlUtils.normalizeHomePageUrl("  https://example.com  ") == "https://example.com")
    check("ftp home page is unusable", UrlUtils.normalizeHomePageUrl("ftp://example.com") == "")
    check("words with spaces are unusable", UrlUtils.normalizeHomePageUrl("not a url") == "")
    check("one-letter TLD is unusable", UrlUtils.normalizeHomePageUrl("example.c") == "")
    check("no-host URL is unusable", UrlUtils.normalizeHomePageUrl("https://") == "")

    println("CustomSearchHomeTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
