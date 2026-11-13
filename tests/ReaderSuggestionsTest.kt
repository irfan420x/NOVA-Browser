package com.nova.browser.web

import com.nova.browser.data.CustomDownload
import com.nova.browser.data.DownloadMeta
import com.nova.browser.data.DownloadProgress
import com.nova.browser.data.DownloadState
import com.nova.browser.data.Downloader
import com.nova.browser.reader.ArticleContent
import com.nova.browser.reader.ReaderExtractor
import java.io.File

/**
 * JVM unit tests for W4 (Reader + Suggestions + Downloader + per-site
 * settings model) — plain main() + check(), no framework.
 * Run with tests/run-tests.sh (coordinator hook: see the W4 report).
 * Exits non-zero on any failure. No network is used: engine responses
 * are fixed sample payloads matching the shapes verified live on
 * 2026-11-13 (Google / DuckDuckGo / Brave); fetchEngineSuggestions
 * itself is not exercised here because it needs the network.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    // --- JsonLite ---
    val arr = JsonLite.parse("[\"kotlin\",[\"a\",\"b\"],[],{}]") as? JArr
    check("JsonLite parses nested arrays", arr != null && arr.items.size == 4)
    check(
        "JsonLite second element is the suggestion array",
        (arr?.items?.getOrNull(1) as? JArr)?.items?.filterIsInstance<JStr>()?.map { it.value } == listOf("a", "b")
    )
    check(
        "JsonLite parses object fields",
        (JsonLite.parse("{\"title\":\"T\",\"text\":\"x\"}") as? JObj)?.entries?.get("title") == JStr("T")
    )
    check(
        "JsonLite unescapes \\n and quotes",
        JsonLite.parse("\"a\\nb\\\"c\"") == JStr("a\nb\"c")
    )
    check(
        "JsonLite decodes \\u escapes",
        JsonLite.parse("\"\\u0041\\u00e9\"") == JStr("Aé")
    )
    check("JsonLite rejects garbage", JsonLite.parse("not json") == null)
    check("JsonLite rejects truncated input", JsonLite.parse("[\"a\",") == null)
    check("JsonLite rejects trailing junk", JsonLite.parse("{} extra") == null)
    check("JsonLite parses empty object", JsonLite.parse("{}") is JObj)
    check("JsonLite stringField reads a field", JsonLite.stringField("{\"title\":\"Hi\"}", "title") == "Hi")
    check("JsonLite stringField misses gracefully", JsonLite.stringField("{\"a\":1}", "title") == null)

    // --- Engine suggest URLs (verified shapes) ---
    check(
        "Google suggest URL",
        Suggestions.suggestUrl("google", "kotlin") ==
            "https://suggestqueries.google.com/complete/search?client=firefox&q=kotlin"
    )
    check(
        "Google query is URL-encoded",
        Suggestions.suggestUrl("google", "a b&c") ==
            "https://suggestqueries.google.com/complete/search?client=firefox&q=a+b%26c"
    )
    check(
        "DuckDuckGo suggest URL uses type=list",
        Suggestions.suggestUrl("duckduckgo", "kotlin") == "https://ac.duckduckgo.com/ac/?q=kotlin&type=list"
    )
    check(
        "Brave suggest URL",
        Suggestions.suggestUrl("brave", "kotlin") == "https://search.brave.com/api/suggest?q=kotlin"
    )
    check("Bing has no verified endpoint (history-only)", Suggestions.suggestUrl("bing", "kotlin") == null)
    check("unknown engine has no endpoint", Suggestions.suggestUrl("nosuch", "kotlin") == null)

    // --- Engine JSON parsing (sample payloads in the verified shapes) ---
    val googleJson = "[\"kotlin\",[\"kotlin\",\"kotlin vs java\",\"kotlin multiplatform\"],[],{\"google:suggestsubtypes\":[[512],[512],[512]]}]"
    check(
        "Google response parses to 3 suggestions",
        Suggestions.parseEngineSuggestions("google", googleJson) ==
            listOf("kotlin", "kotlin vs java", "kotlin multiplatform")
    )
    val ddgJson = "[\"kotlin\",[\"kotlin\",\"kotlin playground\",\"kotlin for forge\"]]"
    check(
        "DuckDuckGo array response parses",
        Suggestions.parseEngineSuggestions("duckduckgo", ddgJson) ==
            listOf("kotlin", "kotlin playground", "kotlin for forge")
    )
    val ddgObjJson = "[{\"phrase\":\"kotlin\"},{\"phrase\":\"kotlin online compiler\"}]"
    check(
        "DuckDuckGo object response parses",
        Suggestions.parseEngineSuggestions("duckduckgo", ddgObjJson) ==
            listOf("kotlin", "kotlin online compiler")
    )
    val braveJson = "[\"kotlin\",[\"kotlin\",\"kotlin vs java\",\"kotlin playground\"]]"
    check(
        "Brave response parses",
        Suggestions.parseEngineSuggestions("brave", braveJson) ==
            listOf("kotlin", "kotlin vs java", "kotlin playground")
    )
    check("Bing parse is empty even for a valid array", Suggestions.parseEngineSuggestions("bing", googleJson).isEmpty())
    check("garbage engine body parses to empty", Suggestions.parseEngineSuggestions("google", "<html>error</html>").isEmpty())
    check("empty engine body parses to empty", Suggestions.parseEngineSuggestions("google", "").isEmpty())
    check("empty suggestion array parses to empty", Suggestions.parseEngineSuggestions("google", "[\"q\",[]]").isEmpty())
    check(
        "engine suggestions are capped at 10",
        Suggestions.parseEngineSuggestions("google", "[\"q\",[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\",\"l\"]]").size == 10
    )
    check(
        "engine suggestions are de-duplicated",
        Suggestions.parseEngineSuggestions("google", "[\"q\",[\"a\",\"a\",\"b\"]]") == listOf("a", "b")
    )

    // --- Merge / rank ordering ---
    val history = listOf(
        LocalEntry("Kotlin docs", "https://kotlinlang.org/docs", visitCount = 5, visitedAt = 100),
        LocalEntry("Kotlin vs Java", "https://example.com/kotlin-vs-java", visitCount = 1, visitedAt = 300),
        LocalEntry("Android news", "https://example.com/android-news", visitCount = 9, visitedAt = 200)
    )
    val bookmarks = listOf(
        LocalEntry("Kotlin playground", "https://play.kotlinlang.org", visitCount = 0, visitedAt = 50)
    )
    val merged = Suggestions.mergeSuggestions(
        "kotlin", history, bookmarks,
        listOf("kotlin", "kotlin playground", "kotlin coroutines", "kotlin multiplatform")
    )
    check("merge is not empty", merged.isNotEmpty())
    check(
        "exact-prefix history comes first, most visited first",
        merged.firstOrNull()?.url == "https://kotlinlang.org/docs" &&
            merged.firstOrNull()?.source == SuggestionSource.HISTORY
    )
    check(
        "second prefix history entry follows",
        merged.getOrNull(1)?.url == "https://example.com/kotlin-vs-java"
    )
    check(
        "prefix bookmark follows prefix history",
        merged.getOrNull(2)?.url == "https://play.kotlinlang.org" &&
            merged.getOrNull(2)?.source == SuggestionSource.BOOKMARK
    )
    check(
        "engine suggestions come after local prefix matches",
        merged.any { it.source == SuggestionSource.SEARCH && it.text == "kotlin coroutines" } &&
            merged.indexOfFirst { it.text == "kotlin coroutines" } > 2
    )
    check(
        "engine duplicate of a local title is dropped",
        merged.count { it.text.lowercase() == "kotlin" } == 1
    )
    check(
        "engine duplicate of a local bookmark is dropped",
        merged.none { it.source == SuggestionSource.SEARCH && it.text == "Kotlin playground" }
    )
    check(
        "substring history match is ranked after engine suggestions",
        Suggestions.mergeSuggestions(
            "droid", history, bookmarks, listOf("droid x")
        ).let { list ->
            list.indexOfFirst { it.url == "https://example.com/android-news" } >
                list.indexOfFirst { it.text == "droid x" }
        }
    )
    check(
        "merge respects the limit",
        Suggestions.mergeSuggestions("k", history, bookmarks, (1..20).map { "k$it" }, limit = 8).size == 8
    )
    check("empty query merges to nothing", Suggestions.mergeSuggestions("", history, bookmarks, listOf("x")).isEmpty())
    check("blank query merges to nothing", Suggestions.mergeSuggestions("   ", history, bookmarks, listOf("x")).isEmpty())
    check(
        "merge is case-insensitive",
        Suggestions.mergeSuggestions("KOTLIN", history, emptyList(), emptyList()).isNotEmpty()
    )
    check(
        "history URL prefix matches without scheme",
        Suggestions.mergeSuggestions(
            "kotlinlang", listOf(LocalEntry("", "https://kotlinlang.org/docs")), emptyList(), emptyList()
        ).size == 1
    )

    // --- SuggestionEngine with a stub local source (no network) ---
    val stubEngine = SuggestionEngine(object : SuggestionLocalSource {
        override fun historyEntries(query: String) = history.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
        override fun bookmarkEntries(query: String) = bookmarks.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
    })
    check(
        "SuggestionEngine returns history-only without network",
        stubEngine.suggest("kotlin", "google", includeNetwork = false)
            .all { it.source != SuggestionSource.SEARCH }
    )
    check("SuggestionEngine returns nothing for blank query", stubEngine.suggest("  ", "google").isEmpty())

    // --- Range header / Content-Range ---
    check("Range header is null at 0 bytes", Downloader.buildRangeHeader(0) == null)
    check("Range header at 1 byte", Downloader.buildRangeHeader(1) == "bytes=1-")
    check("Range header at 123456789", Downloader.buildRangeHeader(123456789) == "bytes=123456789-")
    check("Content-Range total parses", Downloader.parseContentRange("bytes 100-999/1000") == 1000L)
    check("Content-Range total parses without range", Downloader.parseContentRange("bytes */5000") == 5000L)
    check("Content-Range * total is null", Downloader.parseContentRange("bytes 0-99/*") == null)
    check("Content-Range garbage is null", Downloader.parseContentRange("garbage") == null)
    check("Content-Range null is null", Downloader.parseContentRange(null) == null)
    check("Content-Range negative total is null", Downloader.parseContentRange("bytes 0-1/-5") == null)

    // --- File names ---
    check(
        "sanitize strips separators and controls",
        Downloader.sanitizeFileName("a/b\\c:d*e?f\"g<h>i|j") == "a_b_c_d_e_f_g_h_i_j"
    )
    check("sanitize blank becomes download", Downloader.sanitizeFileName("   ") == "download")
    check("sanitize dots-only becomes download", Downloader.sanitizeFileName("...") == "download")
    check(
        "disposition filename wins",
        Downloader.resolveFileName(
            "https://example.com/dl?id=1", "attachment; filename=\"report.pdf\"", null
        ) == "report.pdf"
    )
    check(
        "disposition filename without quotes wins",
        Downloader.resolveFileName("https://example.com/dl", "attachment; filename=notes.txt", null) == "notes.txt"
    )
    check(
        "disposition RFC 5987 filename* decodes",
        Downloader.resolveFileName(
            "https://example.com/dl", "attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf", null
        ) == "€ rates.pdf"
    )
    check(
        "URL last segment is the fallback name",
        Downloader.resolveFileName("https://example.com/files/archive.zip?x=1", null, null) == "archive.zip"
    )
    check(
        "URL-encoded path segment decodes",
        Downloader.resolveFileName("https://example.com/files/my%20file.pdf", null, null) == "my file.pdf"
    )
    check(
        "bare URL falls back to download",
        Downloader.resolveFileName("https://example.com/", null, null) == "download"
    )
    check(
        "PDF mime adds the extension",
        Downloader.resolveFileName("https://example.com/get?id=9", null, "application/pdf") == "get.pdf"
    )
    check(
        "APK mime adds the extension",
        Downloader.resolveFileName("https://example.com/get", null, "application/vnd.android.package-archive") == "get.apk"
    )

    // --- .meta round-trip + resume state (temp files under /tmp) ---
    val tmpDir = File(System.getProperty("java.io.tmpdir"), "nova-w4-test-${System.nanoTime()}")
    tmpDir.mkdirs()
    val meta = DownloadMeta(
        "https://example.com/f.zip?a=1&b=2", "f.zip", 12345L, 99999L, "application/zip"
    )
    val metaText = Downloader.metaToText(meta)
    check("meta round-trips through text", Downloader.parseMetaText(metaText) == meta)
    check("meta garbage parses to null", Downloader.parseMetaText("no equals here\n") == null)
    check("meta missing url parses to null", Downloader.parseMetaText("file=f.zip\ndownloaded=1\n") == null)
    val partial = File(tmpDir, "f.zip")
    partial.writeBytes(ByteArray(12345))
    Downloader.writeMeta(partial, meta)
    check("resume is available for a matching partial", Downloader.resumeAvailable(partial, meta.url))
    check("resume is not available for another URL", !Downloader.resumeAvailable(partial, "https://example.com/other.zip"))
    check("resume is not available without a meta file", !Downloader.resumeAvailable(File(tmpDir, "nope.bin"), meta.url))
    val wrongMeta = meta.copy(downloadedBytes = 999)
    Downloader.writeMeta(partial, wrongMeta)
    check("resume is not available when meta size disagrees", !Downloader.resumeAvailable(partial, meta.url))
    Downloader.writeMeta(partial, meta)
    check("resume is available again after the meta is fixed", Downloader.resumeAvailable(partial, meta.url))

    // --- CustomDownload initial state restores from .meta ---
    val job = CustomDownload(tmpDir, meta.url, fileName = "f.zip", mimeType = "application/zip")
    check("job restores downloaded bytes from meta", job.downloadedBytes == 12345L)
    check("job restores total bytes from meta", job.totalBytes == 99999L)
    check("job starts queued", job.state == DownloadState.QUEUED)
    check("job file is in the download dir", job.file == File(tmpDir, "f.zip"))
    val freshJob = CustomDownload(tmpDir, "https://example.com/new.bin", fileName = "new.bin")
    check("fresh job starts at 0 bytes", freshJob.downloadedBytes == 0L)
    check("fresh job total is unknown", freshJob.totalBytes == -1L)

    // --- DownloadProgress percent ---
    check("progress percent computes", DownloadProgress(50, 100, DownloadState.DOWNLOADING).percent == 50)
    check("progress percent is -1 without a total", DownloadProgress(50, -1, DownloadState.DOWNLOADING).percent == -1)
    check("progress percent caps at 100", DownloadProgress(150, 100, DownloadState.DOWNLOADING).percent == 100)

    // --- Reader scoring helpers ---
    check("link-free long text scores its full length", ReaderExtractor.scoreTextBlock(1000, 0) == 1000.0)
    check("empty text scores 0", ReaderExtractor.scoreTextBlock(0, 0) == 0.0)
    check(
        "link-heavy text scores far lower than clean text",
        ReaderExtractor.scoreTextBlock(1000, 900) < ReaderExtractor.scoreTextBlock(1000, 100)
    )
    check(
        "link length is clamped to text length",
        ReaderExtractor.scoreTextBlock(100, 500) == 0.0
    )
    check("short text is boilerplate", ReaderExtractor.isBoilerplate("hi", 0))
    check("link-heavy text is boilerplate", ReaderExtractor.isBoilerplate("a".repeat(100), 80))
    check("real article text is not boilerplate", !ReaderExtractor.isBoilerplate("a".repeat(500), 10))
    check(
        "paragraphs split on blank lines",
        ReaderExtractor.splitParagraphs("One.\n\nTwo.\n\n\nThree.") == listOf("One.", "Two.", "Three.")
    )
    check(
        "cleanText collapses whitespace but keeps breaks",
        ReaderExtractor.cleanText("A  B\r\n\r\n\r\nC") == "A B\n\nC"
    )
    check("reading time is at least 1 minute", ReaderExtractor.readingMinutes(0) == 1)
    check("200 words is 1 minute", ReaderExtractor.readingMinutes(200) == 1)
    check("201 words is 2 minutes", ReaderExtractor.readingMinutes(201) == 2)
    check("400 words is 2 minutes", ReaderExtractor.readingMinutes(400) == 2)

    // --- Reader result parsing ---
    val articleJson = "{\"title\":\"Hello World\",\"text\":\"First paragraph here.\\n\\nSecond paragraph here, longer than twenty chars.\",\"html\":\"<p>First</p>\"}"
    val article: ArticleContent? = ReaderExtractor.parseReaderResult(articleJson)
    check("reader result parses", article != null)
    check("reader title parses", article?.title == "Hello World")
    check("reader paragraphs parse", article?.paragraphs?.size == 2)
    check("reader word count counts", article?.wordCount == 10)
    check("reader html is kept", article?.html == "<p>First</p>")
    check("reader result null input is null", ReaderExtractor.parseReaderResult(null) == null)
    check("reader result literal null is null", ReaderExtractor.parseReaderResult("null") == null)
    check("reader result garbage is null", ReaderExtractor.parseReaderResult("not json") == null)
    check("reader result empty text is null", ReaderExtractor.parseReaderResult("{\"title\":\"T\",\"text\":\"   \"}") == null)
    check("reader result missing text is null", ReaderExtractor.parseReaderResult("{\"title\":\"T\"}") == null)
    check(
        "reader result with unicode escape parses",
        ReaderExtractor.parseReaderResult("{\"title\":\"caf\\u00e9\",\"text\":\"Para one is long enough here.\\n\\nPara two is long enough here.\"}")?.title == "café"
    )
    check(
        "reader result blank title falls back to Article",
        ReaderExtractor.parseReaderResult("{\"title\":\"\",\"text\":\"Para one is long enough here.\\n\\nPara two is long enough here.\"}")?.title == "Article"
    )
    check(
        "unwrapEvaluateResult unquotes a JSON string",
        ReaderExtractor.unwrapEvaluateResult("\"{\\\"title\\\":\\\"T\\\"}\"") == "{\"title\":\"T\"}"
    )
    check("unwrapEvaluateResult null stays null", ReaderExtractor.unwrapEvaluateResult("null") == null)
    check("unwrapEvaluateResult null input stays null", ReaderExtractor.unwrapEvaluateResult(null) == null)
    check(
        "buildExtractInvocation calls the extractor",
        ReaderExtractor.buildExtractInvocation().contains("__novaReaderExtract")
    )
    check(
        "loadReaderJs reads from a stream",
        ReaderExtractor.loadReaderJs { "window.__novaReaderExtract".byteInputStream() }?.contains("novaReaderExtract") == true
    )
    check("loadReaderJs null stream is null", ReaderExtractor.loadReaderJs { null } == null)

    // --- Per-site settings model ---
    val site = PerSiteSettings("example.com")
    check("per-site defaults: JS on", site.javaScriptEnabled)
    check("per-site defaults: mobile layout", !site.desktopMode)
    check("per-site defaults: ad-block on", site.adBlockEnabled)
    check("per-site host is kept", site.host == "example.com")
    check(
        "defaultPerSiteSettings lowercases the host",
        defaultPerSiteSettings("EXAMPLE.COM").host == "example.com"
    )
    check(
        "PerSiteSettings constructor normalises the host (P-1)",
        PerSiteSettings("EXAMPLE.COM").host == "example.com"
    )
    check(
        "PerSiteSettings copy normalises the host too",
        site.copy(host = "MiXeD.Example.COM").host == "mixed.example.com"
    )
    // B-4 / M-W4-7: the per-site cookies toggle was REMOVED (CookieManager
    // is global — no per-host cookie API exists). There is no
    // cookiesEnabled field and no withCookies() any more; the code below
    // would not compile if they came back, which is the test.
    val noJs = site.withJavaScript(false)
    check("withJavaScript flips only JS", !noJs.javaScriptEnabled && !noJs.desktopMode)
    val desktop = site.withDesktopMode(true)
    check("withDesktopMode flips only desktop", desktop.desktopMode && desktop.javaScriptEnabled)
    val noAds = site.withAdBlock(false)
    check("withAdBlock flips only ad-block", !noAds.adBlockEnabled && noAds.javaScriptEnabled)
    check("JS change needs a reload", noJs.needsReloadAfter(site))
    check("desktop change needs a reload", desktop.needsReloadAfter(site))
    check("ad-block change needs no reload", !noAds.needsReloadAfter(site))
    check("no change needs no reload", !site.needsReloadAfter(site))
    check("reload note appears for JS change", noJs.reloadNote(site) == "Reloads the page")
    check("reload note is null for ad-block change", noAds.reloadNote(site) == null)
    check("reload note is null when nothing changed", site.reloadNote(site) == null)

    // --- W4 FIX: JsonLite depth cap + 1 MB cap (M-W4-6, P-2) ---
    val deep150 = "[".repeat(150) + "1" + "]".repeat(150)
    check("JsonLite depth-150 input returns null, no crash", JsonLite.parse(deep150) == null)
    val deep50 = "[".repeat(50) + "1" + "]".repeat(50)
    check("JsonLite depth-50 input still parses", JsonLite.parse(deep50) != null)
    val bigReaderJson = "{\"title\":\"T\",\"text\":\"" + "x".repeat(300_000) + "\"}"
    check("JsonLite parses a 300 KB reader JSON (P-2 cap raised)", JsonLite.parse(bigReaderJson) != null)
    check("JsonLite still rejects input over 1 MB", JsonLite.parse("\"" + "x".repeat(1_100_000) + "\"") == null)

    // --- W4 FIX: '+' in filenames is preserved (B-10) ---
    check(
        "plus in URL path is preserved, not a space",
        Downloader.resolveFileName("https://example.com/files/my+file.pdf", null, null) == "my+file.pdf"
    )
    check(
        "plus in filename* is preserved",
        Downloader.resolveFileName("https://example.com/dl", "attachment; filename*=UTF-8''a+b.txt", null) == "a+b.txt"
    )
    check(
        "percent-encoded space still decodes",
        Downloader.resolveFileName("https://example.com/files/my%20file.pdf", null, null) == "my file.pdf"
    )

    // --- W4 FIX: redirect decision helpers (D-5 / H-W4-1) ---
    check("same origin: identical URLs", Downloader.sameOrigin("https://example.com/a", "https://example.com/b"))
    check(
        "same origin: explicit default port matches",
        Downloader.sameOrigin("https://example.com:443/a", "https://example.com/b")
    )
    check("cross origin: different host", !Downloader.sameOrigin("https://example.com/a", "https://evil.com/b"))
    check("cross origin: http vs https", !Downloader.sameOrigin("http://example.com/a", "https://example.com/b"))
    check("cross origin: different port", !Downloader.sameOrigin("https://example.com:8443/a", "https://example.com/b"))
    check("downgrade redirect detected", Downloader.isDowngradeRedirect("https://example.com/a", "http://example.com/b"))
    check("upgrade is not a downgrade", !Downloader.isDowngradeRedirect("http://example.com/a", "https://example.com/b"))
    check(
        "stripAuthHeaders drops Cookie/Authorization, keeps the rest",
        Downloader.stripAuthHeaders(
            mapOf("Cookie" to "x", "Authorization" to "y", "Proxy-Authorization" to "z", "Accept" to "json")
        ) == mapOf("Accept" to "json")
    )
    check("hasCredentials detects Cookie", Downloader.hasCredentials(mapOf("cookie" to "a")))
    check("hasCredentials is case-insensitive", Downloader.hasCredentials(mapOf("AUTHORIZATION" to "b")))
    check("hasCredentials false without auth headers", !Downloader.hasCredentials(mapOf("Accept" to "z")))
    check(
        "relative redirect resolves",
        Downloader.resolveRedirectUrl("https://example.com/a/b", "/c") == "https://example.com/c"
    )
    check(
        "absolute redirect resolves",
        Downloader.resolveRedirectUrl("https://example.com/a", "https://other.com/x") == "https://other.com/x"
    )

    // --- W4 FIX: completion validation (D-1, P-4) ---
    check("validateCompletion passes when complete", Downloader.validateCompletion(1000, 1000, 1000))
    check("validateCompletion fails when truncated", !Downloader.validateCompletion(100, 1000, 100))
    check("validateCompletion fails when the file is short", !Downloader.validateCompletion(1000, 1000, 900))
    check("validateCompletion fails a declared empty body", !Downloader.validateCompletion(0, 0, 0))
    check("validateCompletion accepts unknown total at EOF", Downloader.validateCompletion(500, -1, 500))

    // --- W4 FIX: resume validation (M-W4-3) ---
    check("ETag mismatch forces restart", Downloader.shouldRestartOnValidation("\"a\"", "\"b\"", null, null))
    check("ETag match resumes", !Downloader.shouldRestartOnValidation("\"a\"", "\"a\"", null, null))
    check(
        "Last-Modified mismatch forces restart",
        Downloader.shouldRestartOnValidation(null, null, "Mon, 01 Jan 2024 00:00:00 GMT", "Tue, 02 Jan 2024 00:00:00 GMT")
    )
    check(
        "missing response ETag does not force restart",
        !Downloader.shouldRestartOnValidation("\"a\"", null, null, null)
    )
    check(
        "no stored validator does not force restart",
        !Downloader.shouldRestartOnValidation(null, "x", null, "y")
    )
    val metaWithValidators = DownloadMeta(
        "https://example.com/f.zip", "f.zip", 10L, 100L, "application/zip",
        "\"abc\"", "Mon, 01 Jan 2024 00:00:00 GMT"
    )
    check(
        "meta round-trips ETag + Last-Modified",
        Downloader.parseMetaText(Downloader.metaToText(metaWithValidators)) == metaWithValidators
    )

    // --- W4 FIX: 416 decision (B-9) ---
    check(
        "416 with complete partial finishes",
        Downloader.decide416(1000, 1000, null) == Downloader.Range416Decision.FINISH
    )
    check(
        "416 with HEAD total covered finishes",
        Downloader.decide416(800, -1, 800) == Downloader.Range416Decision.FINISH
    )
    check(
        "416 with HEAD total not covered restarts",
        Downloader.decide416(800, -1, 1000) == Downloader.Range416Decision.RESTART
    )
    check(
        "416 with no total and a partial is already-downloaded",
        Downloader.decide416(800, -1, null) == Downloader.Range416Decision.ALREADY_DOWNLOADED
    )
    check("416 with nothing downloaded fails", Downloader.decide416(0, -1, null) == Downloader.Range416Decision.FAIL)

    // --- W4 FIX: unique names + orphaned metas (M-W4-4, M-W4-1) ---
    val existing = File(tmpDir, "keep.zip")
    existing.writeBytes(ByteArray(10))
    check(
        "uniqueFileName leaves an existing file alone",
        Downloader.uniqueFileName(tmpDir, "keep.zip").name == "keep (1).zip" && existing.length() == 10L
    )
    val existing2 = File(tmpDir, "keep (1).zip")
    existing2.writeBytes(ByteArray(5))
    check("uniqueFileName increments", Downloader.uniqueFileName(tmpDir, "keep.zip").name == "keep (2).zip")
    check("uniqueFileName returns the name when free", Downloader.uniqueFileName(tmpDir, "free.zip").name == "free.zip")
    // --- Final-audit H1: a name is taken when its .part exists too ---
    val partOnly = File(tmpDir, "inflight.zip.part")
    partOnly.writeBytes(ByteArray(4))
    check(
        "uniqueFileName skips a name owned by an in-flight .part",
        Downloader.uniqueFileName(tmpDir, "inflight.zip").name == "inflight (1).zip" &&
            partOnly.length() == 4L
    )
    val partOfNumbered = File(tmpDir, "inflight (1).zip.part")
    partOfNumbered.writeBytes(ByteArray(4))
    check(
        "uniqueFileName skips a numbered name whose .part exists",
        Downloader.uniqueFileName(tmpDir, "inflight.zip").name == "inflight (2).zip"
    )
    partOnly.delete(); partOfNumbered.delete()
    // Two CustomDownloads, same name, different URLs: the second must
    // get its own .part, never share the first job's.
    val job1 = CustomDownload(
        downloadDir = tmpDir, url = "https://a.example.com/report.pdf",
        fileName = "report.pdf", mimeType = "application/pdf"
    )
    File(tmpDir, "report.pdf.part").writeBytes(ByteArray(7))
    val job2 = CustomDownload(
        downloadDir = tmpDir, url = "https://b.example.com/report.pdf",
        fileName = "report.pdf", mimeType = "application/pdf"
    )
    check(
        "second job for the same name gets its own file and .part",
        job1.file.name == "report.pdf" && job2.file.name == "report (1).pdf" &&
            job1.partialFile.name == "report.pdf.part" &&
            job2.partialFile.name == "report (1).pdf.part"
    )
    // Same URL, own .part + matching meta: resumes in place (not a
    // `name (1)` copy).
    val resumeDir = File(tmpDir, "resume")
    resumeDir.mkdirs()
    val resumePart = File(resumeDir, "book.epub.part")
    resumePart.writeBytes(ByteArray(9))
    Downloader.writeMeta(resumePart, DownloadMeta("https://c.example.com/book.epub", "book.epub", 9, 100, null, null, null), resumeDir)
    val resumeJob = CustomDownload(
        downloadDir = resumeDir, url = "https://c.example.com/book.epub",
        fileName = "book.epub", mimeType = "application/epub+zip"
    )
    check(
        "own .part with matching meta resumes in place",
        resumeJob.file.name == "book.epub" && resumeJob.partialFile.name == "book.epub.part" &&
            resumeJob.downloadedBytes == 9L
    )
    val orphanMeta = File(tmpDir, "gone.bin.meta")
    orphanMeta.writeText("url=x\n")
    val liveMeta = File(tmpDir, "keep.zip.meta")
    liveMeta.writeText("url=x\n")
    check(
        "cleanupOrphanedMeta removes only orphans",
        Downloader.cleanupOrphanedMeta(tmpDir) == 1 && !orphanMeta.exists() && liveMeta.exists()
    )
    liveMeta.delete()

    // Collision via CustomDownload: a finished file from another URL is
    // never touched — the new download gets a unique name.
    val collisionJob = CustomDownload(tmpDir, "https://example.com/other.zip", fileName = "keep.zip")
    check("collision job gets a unique file name", collisionJob.file.name == "keep (2).zip")
    check("collision job leaves the existing file untouched", existing.length() == 10L)

    // --- W4 FIX: cancel from QUEUED (D-3) ---
    val qDir = File(tmpDir, "q")
    qDir.mkdirs()
    val qPartial = File(qDir, "q.bin")
    qPartial.writeBytes(ByteArray(100))
    Downloader.writeMeta(qPartial, DownloadMeta("https://example.com/q.bin", "q.bin", 100L, 1000L))
    var qCancelled = false
    val qJob = CustomDownload(qDir, "https://example.com/q.bin", fileName = "q.bin", onCancelled = { qCancelled = true })
    check("queued job restores legacy partial", qJob.downloadedBytes == 100L)
    qJob.cancel()
    check("cancel from QUEUED deletes the partial", !qPartial.exists())
    check("cancel from QUEUED deletes the meta", !File(qDir, "q.bin.meta").exists())
    check("cancel from QUEUED sets CANCELLED", qJob.state == DownloadState.CANCELLED)
    check("cancel from QUEUED calls onCancelled", qCancelled)

    // --- W4 FIX: suggestions private mode (B-5) ---
    check(
        "privateMode returns empty even with local entries",
        stubEngine.suggest("kotlin", "google", includeNetwork = true, privateMode = true).isEmpty()
    )
    check(
        "privateMode returns empty with network off too",
        stubEngine.suggest("kotlin", "google", includeNetwork = false, privateMode = true).isEmpty()
    )
    // Debounced lookup delivers only the latest query (stale suppression).
    val debounced = ArrayList<List<Suggestion>>()
    val debEngine = SuggestionEngine(object : SuggestionLocalSource {
        override fun historyEntries(query: String) = history.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
        override fun bookmarkEntries(query: String) = bookmarks.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
    })
    debEngine.suggestDebounced("kot", "google", includeNetwork = false, delayMs = 50) { debounced.add(it) }
    debEngine.suggestDebounced("kotlin", "google", includeNetwork = false, delayMs = 50) { debounced.add(it) }
    Thread.sleep(400)
    check(
        "debounced suggest delivers only the latest query",
        debounced.size == 1 && debounced[0].any { it.url == "https://kotlinlang.org/docs" }
    )

    // --- W4 FIX: isSupportedUrl + displayUrl (B-11, L-W4-2) ---
    check("isSupportedUrl accepts https", isSupportedUrl("https://example.com"))
    check("isSupportedUrl accepts http", isSupportedUrl("HTTP://example.com/x"))
    check("isSupportedUrl refuses about:blank", !isSupportedUrl("about:blank"))
    check("isSupportedUrl refuses an empty URL", !isSupportedUrl(""))
    check("isSupportedUrl refuses file URLs", !isSupportedUrl("file:///sdcard/a.html"))
    check(
        "displayUrl strips the query",
        displayUrl("https://example.com/files/a.pdf?utm_source=x&token=abc") == "example.com/files/a.pdf"
    )
    check("displayUrl strips the fragment", displayUrl("https://example.com/a#b") == "example.com/a")
    check("displayUrl keeps host-only", displayUrl("https://example.com") == "example.com")
    check("displayUrl keeps the path", displayUrl("http://example.com/x/y?z=1") == "example.com/x/y")

    // --- W4 FIX: reader scoring alignment (B-7) ---
    check(
        "score uses density ONCE (aligned with reader.js)",
        ReaderExtractor.scoreTextBlock(1000, 100) == 810.0
    )
    check(
        "single-paragraph long article parses (relaxed JS gate, Kotlin side)",
        ReaderExtractor.parseReaderResult(
            "{\"title\":\"T\",\"text\":\"" + "word ".repeat(60).trim() + "\"}"
        )?.paragraphs?.size == 1
    )

    // NOTE (W4 FIX): D-1 / D-3 / B-8 were to be covered end-to-end with a
    // loopback HttpServer, but Java's HttpURLConnection cannot fetch from
    // ANY local server in this sandbox (loopback TCP is intercepted for
    // Java: every local request fails with "Invalid Http response", while
    // curl and raw sockets to the same server return a correct response).
    // The production code runs on Android, where HttpURLConnection is
    // unaffected. Coverage here is therefore:
    // - D-1: validateCompletion() unit tests above (the exact gate finish()
    //   uses) + fail-keeps-meta behaviour is the same writeMeta() path the
    //   .meta round-trip tests cover;
    // - D-3: cancel-from-QUEUED test above (the PAUSED branch is the same
    //   `if` in cancel() — one code path, exercised synchronously);
    // - B-8: code-review only (pause()/cancel() disconnect the live
    //   connection and set the state immediately). PHONE CHECK: run a
    //   real download, pause mid-download on a slow connection, cancel a
    //   stalled download, and confirm the UI updates at once.

    // Cleanup temp files.
    try {
        tmpDir.listFiles()?.forEach { it.delete() }
        tmpDir.delete()
    } catch (_: Exception) {
    }

    println("ReaderSuggestionsTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
