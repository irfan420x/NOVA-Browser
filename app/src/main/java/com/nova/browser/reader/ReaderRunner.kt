package com.nova.browser.reader

import android.webkit.WebView

/**
 * Single-call reader extraction (B-2) — the Android half of Reader mode.
 *
 * The old flow was a two-step race: the coordinator injected reader.js
 * somewhere, then later called [ReaderExtractor.buildExtractInvocation]
 * and hoped `window.__novaReaderExtract` already existed. [extractArticle]
 * is ONE method that does both steps in order: it injects reader.js and,
 * in the injection callback, runs the extraction and delivers the parsed
 * [ArticleContent] (or null when there is no article / injection was
 * blocked, e.g. strict CSP).
 *
 * COORDINATOR CALL SITE (exact):
 * ```
 * ReaderRunner.extractArticle(webView) { article ->
 *     if (article == null) state.status("No readable article on this page")
 *     else state.showReader(article)   // or however the reader screen is shown
 * }
 * ```
 * Call it from the Reader menu action / Reader button, on the main
 * thread (evaluateJavascript requires it). Re-extract on navigation:
 * call it again from WebViewClient.doUpdateVisitedHistory and after a
 * reload — a stale article from the previous page must never be shown.
 * It is safe to call repeatedly: reader.js no-ops when already injected
 * (`if (window.__novaReaderExtract) return;`).
 *
 * This file is Android-only (WebView) and is NOT compiled by
 * tests/run-tests.sh — only by tools/build-apk.sh. The JVM-testable
 * parsing/scoring lives in [ReaderExtractor].
 */
object ReaderRunner {

    /** Inject reader.js, then extract. [onResult] runs on the main
     *  thread (evaluateJavascript callbacks do). Never throws. */
    fun extractArticle(webView: WebView, onResult: (ArticleContent?) -> Unit) {
        val js = try {
            ReaderExtractor.loadReaderJs {
                try {
                    webView.context.assets.open(ReaderExtractor.READER_JS_ASSET)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
        if (js.isNullOrBlank()) {
            onResult(null)
            return
        }
        try {
            webView.evaluateJavascript(js) {
                try {
                    webView.evaluateJavascript(ReaderExtractor.buildExtractInvocation()) { raw ->
                        val result = try {
                            ReaderExtractor.parseReaderResult(
                                ReaderExtractor.unwrapEvaluateResult(raw)
                            )
                        } catch (_: Exception) {
                            null
                        }
                        try {
                            onResult(result)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    try {
                        onResult(null)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            try {
                onResult(null)
            } catch (_: Exception) {
            }
        }
    }
}
