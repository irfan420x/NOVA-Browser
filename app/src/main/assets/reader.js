/* NOVA Reader — readability-lite extractor (assets/reader.js).
 *
 * Runs inside the page (DevTools-free, no new dependency). Finds the
 * largest text container, strips scripts/ads/nav/headers/footers, and
 * returns {title, text, html} — or null when no article is found.
 * Fail-open: any error returns null and the UI says so; the page itself
 * is never modified (extraction works on a CLONE).
 *
 * Honest limits: strict-CSP pages may block injection (then the caller
 * gets null); paywalled/truncated articles extract only what is in the
 * DOM; server-stitched ad paragraphs inside the article body are not
 * removed — only known ad/nav containers are stripped.
 *
 * NOTES (P-6 — do not over-engineer, just know this):
 * - STRIP_TAGS removes embedded media outright: VIDEO, AUDIO, IFRAME,
 *   SVG (and FORM/BUTTON/SELECT/INPUT/TEXTAREA). Reader is TEXT-ONLY
 *   by design — images, videos, embeds and captions are dropped, and
 *   the UI says so (see ReaderScreen.kt).
 * - isBad() matches class/id fragments, so a genuine container classed
 *   e.g. "related-articles" (or "recommend-...") is treated as
 *   boilerplate and dropped — including an <article> element carrying
 *   such a class. A site that names its article wrapper "related-..."
 *   will not extract.
 */
(function () {
    'use strict';
    if (window.__novaReaderExtract) return;

    // Class/id fragments that mark boilerplate, not article content.
    var BAD = /ad[s]?[-_]|advert|promo|sidebar|comment|share|social|newsletter|popup|modal|cookie|banner|related|recommend|subscribe|paywall|toolbar|widget/i;
    // Tags that are never article body.
    var STRIP_TAGS = ['SCRIPT', 'STYLE', 'NAV', 'HEADER', 'FOOTER', 'ASIDE', 'FORM', 'BUTTON', 'NOSCRIPT', 'SVG', 'IFRAME', 'SELECT', 'TEXTAREA', 'INPUT', 'VIDEO', 'AUDIO'];

    function isBad(el) {
        var cls = '';
        try { cls = (el.className && el.className.baseVal !== undefined) ? el.className.baseVal : (el.className || ''); } catch (e) { cls = ''; }
        var id = el.id || '';
        return BAD.test(cls + ' ' + id);
    }

    function isHidden(el) {
        try {
            var cs = window.getComputedStyle(el);
            return cs && (cs.display === 'none' || cs.visibility === 'hidden');
        } catch (e) { return false; }
    }

    function linkTextLength(el) {
        var n = 0;
        var links = el.getElementsByTagName('a');
        for (var i = 0; i < links.length; i++) n += (links[i].innerText || '').length;
        return n;
    }

    // Same rule as ReaderExtractor.scoreTextBlock (Kotlin): long, link-light wins.
    // Score = (len - links) * density — density ONCE (B-7: the JS used
    // density*density while the Kotlin mirror used density once; the
    // Kotlin formula is the tested one, so the JS matches it now).
    // <article>/<main> get a x1.5 boost AND win ties over generic divs.
    function score(el) {
        var text = el.innerText || '';
        var len = text.trim().length;
        if (len < 80) return 0;
        var links = linkTextLength(el);
        if (links >= len) return 0;
        var density = (len - links) / len;
        var s = (len - links) * density;
        var tag = el.tagName;
        if (tag === 'ARTICLE' || tag === 'MAIN') s *= 1.5;
        return s;
    }

    function isArticleOrMain(el) {
        var tag = el.tagName;
        return tag === 'ARTICLE' || tag === 'MAIN';
    }

    function cleanTitle(raw) {
        var t = (raw || '').trim();
        if (!t) return '';
        // "Headline - Site Name" / "Headline | Site Name" -> "Headline"
        var parts = t.split(/\s[|\-–—]\s/);
        if (parts.length > 1 && parts[0].trim().length >= 10) return parts[0].trim();
        return t;
    }

    window.__novaReaderExtract = function () {
        try {
            var doc = document;
            var best = null;
            var bestScore = 0;
            var cands = doc.querySelectorAll('article, main, [role="main"], div, section');
            for (var i = 0; i < cands.length; i++) {
                var el = cands[i];
                if (isBad(el) || isHidden(el)) continue;
                var s = score(el);
                // Prefer <article>/<main> over generic divs on ties (B-7).
                if (s > bestScore ||
                    (s === bestScore && best && isArticleOrMain(el) && !isArticleOrMain(best))) {
                    bestScore = s; best = el;
                }
            }
            if (!best || bestScore < 200) return null;

            // Work on a clone — the live page is never touched.
            var clone = best.cloneNode(true);
            var all = clone.querySelectorAll('*');
            for (var j = all.length - 1; j >= 0; j--) {
                var n = all[j];
                if (STRIP_TAGS.indexOf(n.tagName) >= 0 || isBad(n) || isHidden(n)) {
                    if (n.parentNode) n.parentNode.removeChild(n);
                }
            }
            // Unwrap links that only wrap images (keeps captions, drops chrome).
            var paras = [];
            var blocks = clone.querySelectorAll('p, h1, h2, h3, blockquote, li');
            for (var k = 0; k < blocks.length; k++) {
                var txt = (blocks[k].innerText || '').replace(/\s+/g, ' ').trim();
                if (txt.length < 20) continue;
                if (paras.length && paras[paras.length - 1] === txt) continue;
                paras.push(txt);
            }
            var text = paras.join('\n\n').trim();
            // A real article (B-7): >=200 chars AND >=1 block, OR >=2
            // blocks. The old rule (>=200 chars AND >=2 paragraphs)
            // rejected genuine single-paragraph long articles.
            if (!((text.length >= 200 && paras.length >= 1) || paras.length >= 2)) return null;
            return {
                title: cleanTitle(doc.title),
                text: text,
                html: clone.innerHTML
            };
        } catch (e) {
            return null;
        }
    };
})();
