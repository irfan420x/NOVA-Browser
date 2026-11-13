package com.nova.browser.devtools

/**
 * JVM unit tests for the DevTools workstream (W3) — plain main() +
 * check(), no framework. Run with tests/run-tests.sh. Exits non-zero
 * on any failure.
 *
 * Covers: CurlBuilder (incl. quoting edge cases), JsonPretty (incl.
 * nested / invalid JSON), NetworkLog (cap + thread-safety smoke test),
 * ConsoleStore, ElementInspector JSON parsing, and the HTTP Client
 * request model + header codec. Live HTTP execution is NOT tested here
 * (it needs the network); HttpURLConnection behaviour is verified on
 * the phone instead.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    // ---------- CurlBuilder ----------
    check(
        "curl GET is bare",
        CurlBuilder.build("GET", "https://example.com/api") == "curl 'https://example.com/api'"
    )
    check(
        "curl POST with header and body",
        CurlBuilder.build(
            "POST", "https://example.com/api",
            listOf("Content-Type" to "application/json", "Authorization" to "Bearer abc"),
            """{"a":1}"""
        ) == "curl -X POST -H 'Content-Type: application/json' -H 'Authorization: <redacted>' --data-raw '{\"a\":1}' 'https://example.com/api'"
    )
    check(
        "curl PUT keeps method",
        CurlBuilder.build("PUT", "https://example.com/x", body = "hi").startsWith("curl -X PUT")
    )
    check(
        "curl DELETE keeps method",
        CurlBuilder.build("DELETE", "https://example.com/x").startsWith("curl -X DELETE")
    )
    check(
        "curl GET drops the body",
        CurlBuilder.build("GET", "https://example.com/x", body = "hi") == "curl 'https://example.com/x'"
    )
    check(
        "curl blank method defaults to GET",
        CurlBuilder.build("  ", "https://example.com/x") == "curl 'https://example.com/x'"
    )
    check(
        "curl lower-case method is uppercased",
        CurlBuilder.build("post", "https://example.com/x", body = "b").startsWith("curl -X POST")
    )
    check(
        "curl skips blank header names",
        CurlBuilder.build("GET", "https://example.com/x", listOf("" to "v", "X-A" to "1")) ==
            "curl -H 'X-A: 1' 'https://example.com/x'"
    )
    check(
        "curl single quote in URL is escaped",
        CurlBuilder.build("GET", "https://example.com/a'b") == "curl 'https://example.com/a'\\''b'"
    )
    check(
        "curl single quote in body is escaped",
        CurlBuilder.build("POST", "https://example.com/x", body = "it's") ==
            "curl -X POST --data-raw 'it'\\''s' 'https://example.com/x'"
    )
    check(
        "curl single quote in header value is escaped",
        CurlBuilder.build("GET", "https://example.com/x", listOf("X-Note" to "o'clock")) ==
            "curl -H 'X-Note: o'\\''clock' 'https://example.com/x'"
    )
    check(
        "curl empty body adds no --data-raw",
        CurlBuilder.build("POST", "https://example.com/x", body = "") == "curl -X POST 'https://example.com/x'"
    )
    check(
        "curl from HttpRequestData matches",
        CurlBuilder.build(HttpRequestData("GET", "https://example.com/x")) == "curl 'https://example.com/x'"
    )
    check("sq wraps plain text", CurlBuilder.sq("abc") == "'abc'")
    check("sq escapes quotes", CurlBuilder.sq("a'b'c") == "'a'\\''b'\\''c'")

    // ---------- JsonPretty ----------
    check(
        "pretty object indents",
        JsonPretty.pretty("""{"a":1,"b":{"c":[1,2]}}""") ==
            "{\n  \"a\": 1,\n  \"b\": {\n    \"c\": [\n      1,\n      2\n    ]\n  }\n}"
    )
    check(
        "pretty array of objects",
        JsonPretty.pretty("""[{"x":true},null]""") ==
            "[\n  {\n    \"x\": true\n  },\n  null\n]"
    )
    check("pretty empty object", JsonPretty.pretty("{}") == "{}")
    check("pretty empty array", JsonPretty.pretty("[]") == "[]")
    check("pretty scalar string", JsonPretty.pretty("\"hi\"") == "\"hi\"")
    check("pretty invalid JSON is null", JsonPretty.pretty("{not json") == null)
    check("pretty trailing garbage is null", JsonPretty.pretty("""{"a":1} extra""") == null)
    check("pretty unterminated string is null", JsonPretty.pretty("\"abc") == null)
    check("pretty empty input is null", JsonPretty.pretty("") == null)
    check("pretty keeps unicode", JsonPretty.pretty("""{"t":"বাংলা"}""")?.contains("বাংলা") == true)
    check("pretty handles escaped quotes", JsonPretty.pretty("""{"q":"say \"hi\""}""")?.contains("\\\"hi\\\"") == true)
    check("pretty handles exponents", JsonPretty.pretty("""{"n":1.5e-3}""") == "{\n  \"n\": 1.5e-3\n}")
    check("isJson accepts object", JsonPretty.isJson("""{"a":1}"""))
    check("isJson accepts array", JsonPretty.isJson("[1,2]"))
    check("isJson rejects scalar", !JsonPretty.isJson("\"hi\""))
    check("isJson rejects garbage", !JsonPretty.isJson("hello"))
    check(
        "parseFlatStringMap parses storage dump",
        JsonPretty.parseFlatStringMap("""{"token":"abc","count":"3"}""") ==
            mapOf("token" to "abc", "count" to "3")
    )
    check(
        "parseFlatStringMap coerces numbers",
        JsonPretty.parseFlatStringMap("""{"n":42,"b":true,"z":null}""") ==
            mapOf("n" to "42", "b" to "true", "z" to "")
    )
    check("parseFlatStringMap rejects array", JsonPretty.parseFlatStringMap("[1]") == null)
    check("parseFlatStringMap rejects garbage", JsonPretty.parseFlatStringMap("nope") == null)
    check("parseFlatStringMap empty object", JsonPretty.parseFlatStringMap("{}") == emptyMap<String, String>())
    check(
        "decodeJsStringResult unwraps a quoted string",
        JsonPretty.decodeJsStringResult("\"hello\"") == "hello"
    )
    check(
        "decodeJsStringResult unwraps escaped JSON",
        JsonPretty.decodeJsStringResult("\"{\\\"a\\\":1}\"") == """{"a":1}"""
    )
    check("decodeJsStringResult null stays null", JsonPretty.decodeJsStringResult("null") == null)
    check("decodeJsStringResult undefined stays null", JsonPretty.decodeJsStringResult("undefined") == null)
    check("decodeJsStringResult null input stays null", JsonPretty.decodeJsStringResult(null) == null)
    check("decodeJsStringResult non-string stays null", JsonPretty.decodeJsStringResult("42") == null)
    check(
        "decodeJsStringResult handles unicode escape",
        JsonPretty.decodeJsStringResult("\"\\u09ac\\u09be\\u0982\\u09b2\\u09be\"") == "বাংলা"
    )
    check(
        "escapeJsonString round-trips through parse",
        JsonPretty.parse("\"${JsonPretty.escapeJsonString("a\"b\\c\nd\te")} \"") ==
            JsonPretty.JsonValue.Str("a\"b\\c\nd\te ")
    )

    // ---------- NetworkLog ----------
    run {
        val log = NetworkLog()
        check("network log starts empty", log.size() == 0)
        val e1 = log.record("https://example.com/app.js", "GET")
        check("network record keeps URL", e1.url == "https://example.com/app.js")
        check("network kind from extension is Script", e1.kind == "Script")
        check("network main frame is Document", NetworkLog.inferKind("https://example.com/", true) == "Document")
        check("network css kind", NetworkLog.inferKind("https://example.com/a.css", false) == "Stylesheet")
        check("network image kind", NetworkLog.inferKind("https://example.com/a.webp?x=1", false) == "Image")
        check("network font kind", NetworkLog.inferKind("https://example.com/a.woff2", false) == "Font")
        check("network unknown kind is Other", NetworkLog.inferKind("https://example.com/api/data", false) == "Other")
        check("network blocked record", log.record("https://ads.example.com/x.js", "GET", blocked = true, blockedKind = "AD").blocked)
        check("network blocked count", log.blockedCount() == 1)
        check("network snapshot is newest-first", log.snapshot().first().url == "https://ads.example.com/x.js")
        check("network chronological is oldest-first", log.snapshotChronological().first().url == "https://example.com/app.js")
        log.clear()
        check("network clear empties", log.size() == 0 && log.blockedCount() == 0)
        // Cap: 250 records -> exactly 200 kept, oldest dropped.
        repeat(250) { i -> log.record("https://example.com/$i.js", "GET") }
        check("network cap is 200", log.size() == 200)
        check(
            "network cap drops oldest first",
            log.snapshotChronological().first().url == "https://example.com/50.js"
        )
        check("network ids keep increasing past the cap", log.snapshot().first().id == 252L)
        // Thread-safety smoke test: 8 threads x 100 records, no crash, cap holds.
        val concurrent = NetworkLog()
        val threads = (1..8).map { t ->
            Thread {
                repeat(100) { i -> concurrent.record("https://example.com/t$t-$i", "GET") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        check("network concurrent records respect the cap", concurrent.size() == 200)
        check("network concurrent ids are unique", concurrent.snapshot().map { it.id }.toSet().size == 200)
    }

    // ---------- ConsoleStore ----------
    run {
        val store = ConsoleStore()
        store.add("ERROR", "boom", "https://example.com/app.js", 42)
        store.add("WARNING", "careful", null, 0)
        store.add("TIP", "hint", null, 0)
        store.add("LOG", "hello", null, 0)
        store.add("BOGUS", "weird", null, 0)
        check("console size", store.size() == 5)
        check("console error count", store.errorCount() == 1)
        check("console WARNING normalizes to warn", store.snapshot("warn").size == 1)
        check("console TIP normalizes to info", store.snapshot("info").size == 1)
        check("console unknown level normalizes to log", store.snapshot("log").size == 2)
        check("console error filter", store.snapshot("error").first().message == "boom")
        check("console source and line kept", store.snapshot("error").first().sourceId == "https://example.com/app.js" && store.snapshot("error").first().lineNumber == 42)
        store.addLocal("log", "> document.title")
        check("console local message added", store.snapshot().last().message == "> document.title")
        repeat(250) { store.add("log", "msg $it", null, 0) }
        check("console cap is 200", store.size() == 200)
        check("console cap drops oldest first", store.snapshot().first().message == "msg 50")
        store.clear()
        check("console clear empties", store.size() == 0)
        check("normalizeLevel null is log", ConsoleMessage.normalizeLevel(null) == "log")
        check("normalizeLevel DEBUG stays debug", ConsoleMessage.normalizeLevel("DEBUG") == "debug")
    }

    // ---------- ElementInspector ----------
    run {
        val payload = """{"tag":"DIV","id":"main","classes":"a b","text":"Hello world","html":"<div id=\"main\">Hi</div>"}"""
        val jsResult = "\"" + JsonPretty.escapeJsonString(payload) + "\""
        val el = ElementInspector.parseInspected(jsResult)
        check("inspector parses tag", el?.tag == "DIV")
        check("inspector parses id", el?.id == "main")
        check("inspector parses classes", el?.classes == "a b")
        check("inspector parses text", el?.text == "Hello world")
        check("inspector parses html", el?.html == """<div id="main">Hi</div>""")
        check("inspector null result is null", ElementInspector.parseInspected(null) == null)
        check("inspector JS null is null", ElementInspector.parseInspected("null") == null)
        check("inspector garbage is null", ElementInspector.parseInspected("\"not json\"") == null)
        check("inspector empty tag is null", ElementInspector.parseInspected("\"" + JsonPretty.escapeJsonString("""{"tag":""}""") + "\"") == null)
        val dump = "\"" + JsonPretty.escapeJsonString("""{"k1":"v1","k2":"v2"}""") + "\""
        check("storage dump parses", ElementInspector.parseStorageDump(dump) == mapOf("k1" to "v1", "k2" to "v2"))
        // B17: a "null" callback (no page / dump failed) is null, not an
        // empty map — the screen must say "unavailable", not "Empty.".
        check("storage dump null is null (B17)", ElementInspector.parseStorageDump("null") == null)
        check("storage dump empty object", ElementInspector.parseStorageDump("\"{}\"") == emptyMap<String, String>())
        check(
            "removeLocalItemJs escapes quotes",
            ElementInspector.removeLocalItemJs("a\"b") == "try{localStorage.removeItem(\"a\\\"b\");'ok';}catch(e){'error: '+e;}"
        )
        check("install JS is idempotent-guarded", ElementInspector.INSTALL_JS.contains("__novaInspectInstalled"))
        check("install JS prevents navigation", ElementInspector.INSTALL_JS.contains("preventDefault"))
        check("stop JS removes the listener", ElementInspector.STOP_JS.contains("removeEventListener"))
        check("read JS reads the stored payload", ElementInspector.READ_JS == "window.__novaInspected||null")
    }

    // ---------- HTTP Client model ----------
    run {
        check("http validate blank URL", HttpRequestData("GET", "  ").validate() == "Enter a URL")
        check("http validate missing scheme", HttpRequestData("GET", "example.com").validate()?.contains("http://") == true)
        check("http validate bad URL", HttpRequestData("GET", "https://").validate() != null)
        check("http validate bad method", HttpRequestData("FETCH", "https://example.com").validate()?.contains("FETCH") == true)
        check("http validate good request", HttpRequestData("POST", "https://example.com/api").validate() == null)
        check("http validate lower-case method ok", HttpRequestData("post", "https://example.com").validate() == null)
        check("http validate blank header name", HttpRequestData("GET", "https://example.com", listOf("" to "x")).validate() != null)
        check("http validate header with colon rejected", HttpRequestData("GET", "https://example.com", listOf("A:B" to "x")).validate() != null)
        check("http cleanMethod uppercases", HttpRequestData("post", "https://example.com").cleanMethod == "POST")
        check("http cleanMethod blank is GET", HttpRequestData(" ", "https://example.com").cleanMethod == "GET")
        val headers = listOf("Authorization" to "Bearer t", "Content-Type" to "application/json")
        check(
            "header codec round-trips",
            HttpHeaderCodec.decode(HttpHeaderCodec.encode(headers)) == headers
        )
        check("header codec empty encodes empty", HttpHeaderCodec.encode(emptyList()) == "")
        check("header codec empty decodes empty", HttpHeaderCodec.decode("") == emptyList<String>())
        check("header codec skips malformed lines", HttpHeaderCodec.decode("NoColon\nA: 1") == listOf("A" to "1"))
        check("header codec strips newline in value", HttpHeaderCodec.encode(listOf("X" to "a\nb")) == "X: a b")
        check("http max body is 1 MB", HttpClientEngine.MAX_BODY_BYTES == 1024 * 1024)
        check("http timeouts are 30s", HttpClientEngine.CONNECT_TIMEOUT_MS == 30_000 && HttpClientEngine.READ_TIMEOUT_MS == 30_000)
        check("http max redirects is 5", HttpClientEngine.MAX_REDIRECTS == 5)
    }

    // ---------- Security fixes (W6 review) ----------
    // --- UrlRedactor (W3-2) ---
    check(
        "redactor redacts token param",
        UrlRedactor.redact("https://example.com/reset?token=abc123&x=1") ==
            "https://example.com/reset?token=REDACTED&x=1"
    )
    check(
        "redactor keeps non-sensitive params",
        UrlRedactor.redact("https://example.com/?q=hello&page=2") == "https://example.com/?q=hello&page=2"
    )
    check(
        "redactor preserves fragment",
        UrlRedactor.redact("https://example.com/?session=zzz#part") == "https://example.com/?session=REDACTED#part"
    )
    check(
        "redactor leaves no-query URL alone",
        UrlRedactor.redact("https://example.com/page") == "https://example.com/page"
    )
    check(
        "redactor matches param names case-insensitively",
        UrlRedactor.redact("https://example.com/?TOKEN=abc&Api_Key=k") ==
            "https://example.com/?TOKEN=REDACTED&Api_Key=REDACTED"
    )
    check(
        "redactor catches token substrings",
        UrlRedactor.redact("https://example.com/?access_token=a&reset_password=b&next=/home") ==
            "https://example.com/?access_token=REDACTED&reset_password=REDACTED&next=/home"
    )
    check(
        "redactor keeps bare flag params",
        UrlRedactor.redact("https://example.com/?debug&token=t") == "https://example.com/?debug&token=REDACTED"
    )
    check("redactor wasRedacted true on secret", UrlRedactor.wasRedacted("https://example.com/?code=123"))
    check("redactor wasRedacted false on clean", !UrlRedactor.wasRedacted("https://example.com/?q=1"))
    check("redactor isSensitiveParam token", UrlRedactor.isSensitiveParam("auth_token"))
    check("redactor isSensitiveParam plain q is not", !UrlRedactor.isSensitiveParam("q"))

    // --- CurlBuilder redaction (W3-2) ---
    check(
        "curl redacts Authorization value",
        CurlBuilder.build("GET", "https://example.com/x", listOf("Authorization" to "Bearer secret123")) ==
            "curl -H 'Authorization: <redacted>' 'https://example.com/x'"
    )
    check(
        "curl redacts Cookie value",
        CurlBuilder.build("GET", "https://example.com/x", listOf("Cookie" to "session=abc")) ==
            "curl -H 'Cookie: <redacted>' 'https://example.com/x'"
    )
    check(
        "curl redacts token-like header",
        CurlBuilder.build("GET", "https://example.com/x", listOf("X-Api-Key" to "k123")) ==
            "curl -H 'X-Api-Key: <redacted>' 'https://example.com/x'"
    )
    check(
        "curl keeps ordinary header values",
        CurlBuilder.build("GET", "https://example.com/x", listOf("Accept" to "application/json")) ==
            "curl -H 'Accept: application/json' 'https://example.com/x'"
    )
    check(
        "curl redacts sensitive URL params",
        CurlBuilder.build("GET", "https://example.com/reset?token=abc") ==
            "curl 'https://example.com/reset?token=REDACTED'"
    )
    check("curl isSensitiveHeader case-insensitive", CurlBuilder.isSensitiveHeader("AUTHORIZATION"))

    // --- HttpHeaderCodec redaction (H-2) ---
    check(
        "codec redacts auth headers for storage",
        HttpHeaderCodec.redactedForStorage(
            listOf("Authorization" to "Bearer t", "Cookie" to "s=1", "Accept" to "application/json")
        ) == listOf("Authorization" to "<redacted>", "Cookie" to "<redacted>", "Accept" to "application/json")
    )
    check(
        "codec encodeForStorage keeps name, drops secret",
        HttpHeaderCodec.encodeForStorage(listOf("Authorization" to "Bearer t")) == "Authorization: <redacted>"
    )
    check(
        "codec redacts Proxy-Authorization",
        HttpHeaderCodec.redactedForStorage(listOf("Proxy-Authorization" to "Basic x")) ==
            listOf("Proxy-Authorization" to "<redacted>")
    )

    // --- JsonPretty depth limit (W3-1) ---
    run {
        val deep150 = "[".repeat(150) + "]".repeat(150)
        check("json depth 150 is rejected", JsonPretty.parse(deep150) == null)
        check("json pretty of deep input is null", JsonPretty.pretty(deep150) == null)
        val deep50 = "[".repeat(50) + "]".repeat(50)
        check("json depth 50 still parses", JsonPretty.parse(deep50) != null)
        val deepObj = "{\"a\":".repeat(101) + "1" + "}".repeat(101)
        check("json deep object is rejected", JsonPretty.parse(deepObj) == null)
    }

    // --- NetworkLog URL redaction (W3-2) ---
    run {
        val log = NetworkLog()
        val e = log.record("https://example.com/reset?token=abc123&next=/home", "GET")
        check("network log stores redacted URL", e.url == "https://example.com/reset?token=REDACTED&next=/home")
        check("network log flags redaction", e.urlRedacted)
        val clean = log.record("https://example.com/page?q=1", "GET")
        check("network log leaves clean URL alone", clean.url == "https://example.com/page?q=1" && !clean.urlRedacted)
    }

    // --- HttpClientEngine origin + body cap (H-1, W3-6) ---
    check("sameOrigin identical URLs", HttpClientEngine.sameOrigin("https://api.example.com/a", "https://api.example.com/b"))
    check("sameOrigin host case differs", HttpClientEngine.sameOrigin("https://API.example.com/a", "https://api.example.com/b"))
    check("sameOrigin explicit 443 equals default", HttpClientEngine.sameOrigin("https://api.example.com:443/a", "https://api.example.com/b"))
    check("sameOrigin different host differs", !HttpClientEngine.sameOrigin("https://api.example.com/a", "https://evil.example/b"))
    check("sameOrigin https vs http differs", !HttpClientEngine.sameOrigin("https://api.example.com/a", "http://api.example.com/b"))
    check("sameOrigin different port differs", !HttpClientEngine.sameOrigin("https://api.example.com:8443/a", "https://api.example.com/b"))
    check("sameOrigin subdomain differs", !HttpClientEngine.sameOrigin("https://api.example.com/a", "https://sub.api.example.com/b"))
    check("sameOrigin unparseable is different", !HttpClientEngine.sameOrigin("https://api.example.com/a", ":::"))
    check(
        "stripAuthHeaders drops auth, keeps the rest",
        HttpClientEngine.stripAuthHeaders(
            listOf("Authorization" to "Bearer t", "Cookie" to "s=1", "Accept" to "application/json")
        ) == listOf("Accept" to "application/json")
    )
    check(
        "stripAuthHeaders is case-insensitive",
        HttpClientEngine.stripAuthHeaders(listOf("AUTHORIZATION" to "x", "X-Ok" to "1")) == listOf("X-Ok" to "1")
    )
    check(
        "stripAuthHeaders drops Proxy-Authorization",
        HttpClientEngine.stripAuthHeaders(listOf("Proxy-Authorization" to "Basic x")) == emptyList<Pair<String, String>>()
    )
    check(
        "request body over 1 MB is rejected",
        HttpRequestData("POST", "https://example.com/api", body = "x".repeat(1024 * 1024 + 1)).validate()
            ?.contains("1 MB") == true
    )
    check(
        "request body of exactly 1 MB is accepted",
        HttpRequestData("POST", "https://example.com/api", body = "x".repeat(1024 * 1024)).validate() == null
    )
    check("request body cap constant is 1 MB", HttpClientEngine.MAX_REQUEST_BODY_BYTES == 1024 * 1024)

    // --- ElementInspector masking (W3-5) ---
    check("mask plain value untouched", ElementInspector.maskStorageValue("theme", "dark") == "dark")
    check("mask sensitive key masks long value", ElementInspector.maskStorageValue("auth_token", "abcdefghijklmnop") == "abcd••••op")
    check("mask sensitive key masks short value fully", ElementInspector.maskStorageValue("password", "hunter2") == "••••••••")
    check("mask JWT-like value by shape", ElementInspector.maskStorageValue("data", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature") == "eyJh••••re")
    check("mask Bearer value by shape", ElementInspector.maskStorageValue("misc", "Bearer abcdefghijklmnop123456") == "Bear••••56")
    check("mask long random token by shape", ElementInspector.maskStorageValue("misc", "aB3dE5gH7jK9mN2pQ4rS6t") == "aB3d••••6t")
    check("mask leaves short plain values alone", ElementInspector.maskStorageValue("misc", "hello") == "hello")
    check("mask leaves repeated-char string alone", ElementInspector.maskStorageValue("misc", "aaaaaaaaaaaaaaaaaaaa") == "aaaaaaaaaaaaaaaaaaaa")
    check("mask isSensitiveStorageKey session", ElementInspector.isSensitiveStorageKey("session_id"))
    check("mask isSensitiveStorageKey plain", !ElementInspector.isSensitiveStorageKey("language"))
    check("mask looksLikeToken short is false", !ElementInspector.looksLikeToken("short"))
    check(
        "maskStorageDump masks only secrets",
        ElementInspector.maskStorageDump(mapOf("theme" to "dark", "token" to "abcdefghijklmnop")) ==
            mapOf("theme" to "dark", "token" to "abcd••••op")
    )

    // --- DevToolsHub gating (W3-4) ---
    run {
        DevToolsHub.setDevMode(false)
        DevToolsHub.clearAll()
        check("hub recording off by default", !DevToolsHub.devModeEnabled)
        DevToolsHub.recordConsole(1L, "log", "should not record", null, 0)
        check("hub drops console when Dev Mode off", DevToolsHub.consoleSnapshot("all").size == 0)
        check("hub drops network when Dev Mode off", DevToolsHub.recordNetwork(1L, "https://example.com/", "GET") == null)
        DevToolsHub.setDevMode(true)
        DevToolsHub.recordConsole(1L, "log", "hello", null, 0)
        check("hub records console when Dev Mode on", DevToolsHub.consoleSnapshot("all").size == 1)
        check("hub records network when Dev Mode on", DevToolsHub.recordNetwork(1L, "https://example.com/", "GET") != null)
        DevToolsHub.bindToTab(1L)
        DevToolsHub.recordConsole(1L, "log", "tab one", null, 0)
        // Binding cleared the pre-bind entries, so only "tab one" remains.
        check("hub keeps logs for the bound tab", DevToolsHub.consoleSnapshot("all").size == 1)
        DevToolsHub.recordConsole(2L, "log", "other tab", null, 0)
        check("hub drops messages from other tabs", DevToolsHub.consoleSnapshot("all").size == 1)
        DevToolsHub.bindToTab(2L)
        check("hub clears logs on tab switch", DevToolsHub.consoleSnapshot("all").size == 0 && DevToolsHub.networkSnapshot().size == 0)
        check("hub bound tab updated", DevToolsHub.boundTabId() == 2L)
        DevToolsHub.bindToTab(2L)
        DevToolsHub.recordConsole(2L, "log", "still here", null, 0)
        check("hub re-binding same tab keeps logs", DevToolsHub.consoleSnapshot("all").size == 1)
        DevToolsHub.setDevMode(false)
        check("hub clears logs on Dev Mode exit", DevToolsHub.consoleSnapshot("all").size == 0 && DevToolsHub.networkSnapshot().size == 0)
        check("hub unbinds on Dev Mode exit", DevToolsHub.boundTabId() == null)
        DevToolsHub.clearAll()
    }

    // --- W3 security behaviour (N-1/N-3/N-4/N-6, coordinator audit) ---
    // UrlRedactor: the N-4 substring gaps (privatekey/accesskey/
    // clientkey/signingkey/auth/pwd/otp) and userinfo stripping.
    check("redactor redacts private_key param", UrlRedactor.redact("https://example.com/cb?private_key=abc&x=1") == "https://example.com/cb?private_key=REDACTED&x=1")
    check("redactor redacts access_key param", UrlRedactor.redact("https://example.com/?access_key=abc") == "https://example.com/?access_key=REDACTED")
    check("redactor redacts client_key param", UrlRedactor.redact("https://example.com/?client_key=abc") == "https://example.com/?client_key=REDACTED")
    check("redactor redacts signing_key param", UrlRedactor.redact("https://example.com/?signing_key=abc") == "https://example.com/?signing_key=REDACTED")
    check("redactor redacts pwd param", UrlRedactor.redact("https://example.com/?pwd=hunter2") == "https://example.com/?pwd=REDACTED")
    check("redactor redacts otp param", UrlRedactor.redact("https://example.com/?otp=123456") == "https://example.com/?otp=REDACTED")
    check("redactor redacts auth_code param", UrlRedactor.redact("https://example.com/?auth_code=xyz") == "https://example.com/?auth_code=REDACTED")
    check("redactor redacts api-key with dashes", UrlRedactor.redact("https://example.com/?api-key=abc") == "https://example.com/?api-key=REDACTED")
    check("redactor keeps innocent params", UrlRedactor.redact("https://example.com/search?q=kotlin&page=2") == "https://example.com/search?q=kotlin&page=2")
    check("redactor preserves fragment", UrlRedactor.redact("https://example.com/?token=abc#section") == "https://example.com/?token=REDACTED#section")
    check("redactor keeps bare flag param", UrlRedactor.redact("https://example.com/?debug&x=1") == "https://example.com/?debug&x=1")
    check("stripUserinfo strips user:pass", UrlRedactor.stripUserinfo("https://user:pass@example.com/a") == "https://example.com/a")
    check("stripUserinfo strips user only", UrlRedactor.stripUserinfo("https://user@example.com/a") == "https://example.com/a")
    check("stripUserinfo leaves @ in path alone", UrlRedactor.stripUserinfo("https://example.com/a@b") == "https://example.com/a@b")
    check("stripUserinfo leaves @ in query alone", UrlRedactor.stripUserinfo("https://example.com/?mail=a@b.com") == "https://example.com/?mail=a@b.com")
    check("stripUserinfo strips on no-query URL", UrlRedactor.stripUserinfo("https://user:pass@example.com") == "https://example.com")
    check("redact strips userinfo even with no query", UrlRedactor.redact("https://user:pass@example.com/a") == "https://example.com/a")
    check("wasRedacted true on token URL", UrlRedactor.wasRedacted("https://example.com/?token=abc"))
    check("wasRedacted false on clean URL", !UrlRedactor.wasRedacted("https://example.com/?q=kotlin"))
    // CurlBuilder.looksSecretBearing (N-3): secret-bearing bodies are
    // omitted from copied cURL with a comment, never copied verbatim.
    check("secret body: JSON password key", CurlBuilder.looksSecretBearing("{\"password\": \"hunter2\"}"))
    check("secret body: JSON token key", CurlBuilder.looksSecretBearing("{\"token\": \"abc\"}"))
    check("secret body: form password=", CurlBuilder.looksSecretBearing("user=bob&password=hunter2"))
    check("secret body: form api_key=", CurlBuilder.looksSecretBearing("api_key=abc123"))
    check("secret body: refresh_token", CurlBuilder.looksSecretBearing("{\"refresh_token\": \"xyz\"}"))
    check("innocent body is not secret-bearing", !CurlBuilder.looksSecretBearing("{\"name\": \"kotlin\", \"page\": 2}"))
    check("innocent form body is not secret-bearing", !CurlBuilder.looksSecretBearing("q=kotlin&lang=en"))
    val secretCurl = CurlBuilder.build("POST", "https://api.example.com/login", emptyList(), "{\"password\": \"hunter2\"}")
    check("curl omits secret-bearing body", "--data-raw omitted: body looks secret-bearing" in secretCurl && "hunter2" !in secretCurl)
    val plainCurl = CurlBuilder.build("POST", "https://api.example.com/echo", emptyList(), "{\"name\": \"kotlin\"}")
    check("curl keeps innocent body", "--data-raw '{\"name\": \"kotlin\"}'" in plainCurl)
    val tokenCurl = CurlBuilder.build("GET", "https://api.example.com/data?token=abc&x=1", listOf("X-Api-Key" to "secret123", "Accept" to "application/json"))
    check("curl redacts token-like header", "'X-Api-Key: <redacted>'" in tokenCurl && "secret123" !in tokenCurl)
    check("curl keeps innocent header", "'Accept: application/json'" in tokenCurl)
    check("curl redacts token in URL", "?token=REDACTED" in tokenCurl && "token=abc" !in tokenCurl)
    // isSensitiveHeader (N-6): password/session/auth/cookie2 variants,
    // in both CurlBuilder and HttpHeaderCodec (they must agree — a
    // redirect strips what history redacts).
    val sensitiveHeaders = listOf(
        "Authorization", "X-Api-Key", "X-Auth-Token", "X-Password", "Password",
        "Session", "X-Session-Id", "Auth", "X-Auth", "Cookie2", "Cookie",
        "Proxy-Authorization", "Set-Cookie", "X-Secret", "X-API_KEY"
    )
    for (h in sensitiveHeaders) {
        check("CurlBuilder sensitive: $h", CurlBuilder.isSensitiveHeader(h))
        check("HttpHeaderCodec sensitive: $h", HttpHeaderCodec.isSensitiveHeader(h))
    }
    val innocentHeaders = listOf("Accept", "Content-Type", "User-Agent", "X-Request-Id", "Cache-Control")
    for (h in innocentHeaders) {
        check("CurlBuilder innocent: $h", !CurlBuilder.isSensitiveHeader(h))
        check("HttpHeaderCodec innocent: $h", !HttpHeaderCodec.isSensitiveHeader(h))
    }
    // stripAuthHeaders drops token-like custom headers on redirect (B4).
    val stripped = HttpClientEngine.stripAuthHeaders(
        listOf("Authorization" to "Bearer x", "X-Api-Key" to "k", "Accept" to "application/json", "X-Custom" to "v")
    )
    check("stripAuthHeaders drops auth + api key, keeps the rest", stripped == listOf("Accept" to "application/json", "X-Custom" to "v"))
    check("stripAuthHeaders keeps innocent headers", HttpClientEngine.stripAuthHeaders(listOf("Accept" to "a")) == listOf("Accept" to "a"))

    // ---------- Final-audit fixes ----------
    // B16: the 200-cap drops are counted, and clear() resets the count.
    val capLog = NetworkLog()
    for (i in 1..205) capLog.record("https://example.com/$i", "GET")
    check("NetworkLog caps at 200", capLog.size() == 200)
    check("NetworkLog counts the 5 dropped entries", capLog.droppedCount() == 5L)
    capLog.clear()
    check("NetworkLog clear resets the dropped count", capLog.droppedCount() == 0L && capLog.size() == 0)

    // B17: a failed storage dump is null (screen: "unavailable"), empty
    // storage is an empty map (screen: "Empty.").
    check(
        "parseStorageDump parses a real dump",
        ElementInspector.parseStorageDump("\"{\\\"theme\\\":\\\"dark\\\"}\"") == mapOf("theme" to "dark")
    )
    check(
        "parseStorageDump returns an empty map for empty storage",
        ElementInspector.parseStorageDump("\"{}\"") == emptyMap<String, String>()
    )
    check(
        "parseStorageDump returns null on the failure marker",
        ElementInspector.parseStorageDump("\"{\\\"__nova_storage_error\\\":\\\"1\\\"}\"") == null
    )
    check("parseStorageDump returns null on null input", ElementInspector.parseStorageDump(null) == null)

    // B15: a redirect to GET drops the body headers, keeps the rest.
    check(
        "stripBodyHeaders drops Content-Type/Content-Length, keeps the rest",
        HttpClientEngine.stripBodyHeaders(
            listOf("Content-Type" to "application/json", "Content-Length" to "42", "Accept" to "application/json", "X-Custom" to "v")
        ) == listOf("Accept" to "application/json", "X-Custom" to "v")
    )
    check(
        "stripBodyHeaders is case-insensitive",
        HttpClientEngine.stripBodyHeaders(listOf("content-type" to "a", "CONTENT-LENGTH" to "1")) == emptyList<Pair<String, String>>()
    )

    println("DevToolsTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
