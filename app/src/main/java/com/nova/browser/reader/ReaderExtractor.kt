package com.nova.browser.reader

import com.nova.browser.web.JObj
import com.nova.browser.web.JStr
import com.nova.browser.web.JsonLite
import java.io.InputStream

/**
 * Reader mode — pure-Kotlin side.
 *
 * The extractor itself is assets/reader.js (a readability-lite script that
 * runs inside the page and returns {title, text, html} or null). This file
 * holds the JVM-testable logic around it: building the evaluateJavascript
 * invocation, unwrapping its result, parsing the JSON it returns, and the
 * text-density scoring helpers the JS mirrors (kept here so the scoring
 * rule is unit-tested in Kotlin even though the JS does the live scoring).
 *
 * Fail-open everywhere: a page with no article returns null and the UI
 * says "No readable article on this page" — the browser never crashes
 * and never shows an empty reader.
 */
data class ArticleContent(
    val title: String,
    val text: String,
    val paragraphs: List<String>,
    val html: String? = null,
    val sourceUrl: String? = null
) {
    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
    val readingMinutes: Int get() = ReaderExtractor.readingMinutes(wordCount)
}

object ReaderExtractor {

    /** Asset file name (loaded with `-A assets` by the build script). */
    const val READER_JS_ASSET = "reader.js"

    /** Load assets/reader.js. [openAsset] is a lambda so this stays free of
     *  Android imports (the coordinator passes `{ context.assets.open(...) }`).
     *  Returns null when the asset is missing/unreadable — the caller then
     *  reports "Reader mode isn't available" instead of crashing. */
    fun loadReaderJs(openAsset: () -> InputStream?): String? {
        return try {
            openAsset()?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** JS to pass to WebView.evaluateJavascript: runs the extractor (already
     *  injected by the caller) and returns its result as a JSON string, or
     *  the literal string "null" when there is no article / anything throws.
     *  evaluateJavascript wraps a string return in JSON quotes — see
     *  [unwrapEvaluateResult].
     *
     *  Do NOT call this directly from a fresh page load: injection is a
     *  two-step race (B-2). Use [ReaderRunner.extractArticle] (reader/
     *  ReaderRunner.kt), the single-call helper that injects reader.js
     *  and runs this invocation in the injection callback. */
    fun buildExtractInvocation(): String {
        return "(function(){try{" +
            "var r=(window.__novaReaderExtract?window.__novaReaderExtract():null);" +
            "return r?JSON.stringify(r):'null';" +
            "}catch(e){return 'null';}})();"
    }

    /** Undo evaluateJavascript's JSON-quoting of a string result: a quoted
     *  JSON string is unescaped to its value; "null"/blank/invalid input
     *  returns null. A non-string result is returned as-is. */
    fun unwrapEvaluateResult(value: String?): String? {
        if (value == null) return null
        val t = value.trim()
        if (t.isEmpty() || t == "null") return null
        val parsed = JsonLite.parse(t)
        return if (parsed is JStr) parsed.value else t
    }

    /** Parse the extractor's JSON ({title, text, html?}) into an article.
     *  Returns null for null/blank/"null"/malformed input or when the
     *  extracted text is empty. Never throws. */
    fun parseReaderResult(json: String?): ArticleContent? {
        if (json.isNullOrBlank() || json.trim() == "null") return null
        val obj = JsonLite.parse(json) as? JObj ?: return null
        val title = (obj.entries["title"] as? JStr)?.value?.trim().orEmpty()
        val text = (obj.entries["text"] as? JStr)?.value ?: return null
        val html = (obj.entries["html"] as? JStr)?.value
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return null
        val paragraphs = splitParagraphs(cleaned)
        if (paragraphs.isEmpty()) return null
        return ArticleContent(
            title = title.ifBlank { "Article" },
            text = cleaned,
            paragraphs = paragraphs,
            html = html?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Text-density score for a candidate container: long, link-light text
     *  scores highest. [textLength] is the container's visible text length,
     *  [linkTextLength] the length of the text inside its links.
     *  Score = (text − links) × (1 − linkRatio). A nav/sidebar (mostly
     *  links) scores near zero; a real article scores in the thousands.
     *
     *  ALIGNED WITH reader.js (B-7): the JS `score()` uses exactly this
     *  formula — density ONCE, not density² — plus a ×1.5 boost for
     *  <article>/<main> and a tie-break preferring them. This Kotlin
     *  function is the formula under test; the JS must not drift from it.
     */
    fun scoreTextBlock(textLength: Int, linkTextLength: Int): Double {
        if (textLength <= 0) return 0.0
        val links = linkTextLength.coerceIn(0, textLength)
        val density = (textLength - links).toDouble() / textLength
        return (textLength - links) * density
    }

    /** Boilerplate heuristic: very short text, or text that is mostly
     *  links (nav, related-links, tag clouds). Used by the JS-side
     *  candidate filter; mirrored here for tests. */
    fun isBoilerplate(text: String, linkTextLength: Int): Boolean {
        val len = text.trim().length
        if (len < 40) return true
        if (len == 0) return true
        return linkTextLength.toDouble() / len > 0.7
    }

    /** Split extracted text into paragraphs (blank-line separated), dropping
     *  empties and trimming each paragraph. */
    fun splitParagraphs(text: String): List<String> {
        return text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Normalise extracted text: CRLF → LF, collapse 3+ newlines to two,
     *  collapse horizontal whitespace runs, trim. Paragraph breaks survive. */
    fun cleanText(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("[ \\t\\u00A0]+"), " ")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }

    /** Reading time at ~200 words per minute, minimum 1 minute. */
    fun readingMinutes(wordCount: Int): Int {
        if (wordCount <= 0) return 1
        return ((wordCount + 199) / 200).coerceAtLeast(1)
    }
}
