package com.nova.browser.web

/**
 * Pure-Kotlin validation of intent:// URLs before they are handed to
 * Android's Intent.parseUri() / startActivity().
 *
 * SECURITY (F02): the old parseIntentUrl() passed Intent.parseUri()
 * results straight to startActivity() with no validation — a malicious
 * page could launch unexpected external activities (explicit component,
 * selector, non-http data, dangerous fallback, smuggled extras) or
 * drive an app launch with no user gesture at all.
 *
 * This validator parses the intent:// URL string itself (java.net-free
 * except for nothing Android-specific — no android.* imports at all),
 * so the whole decision is JVM-testable. The Android layer
 * (NovaClients / BrowserHost, coordinator-owned) must call
 * [validate] FIRST and only call Intent.parseUri() + startActivity()
 * for [Decision.Allow]; see the hook spec in the F02 report.
 *
 * ALLOWED EXTRAS: none, except `S.browser_fallback_url` (a String
 * extra). Every other extra — S./B./b./c./d./f./i./l./s. with any
 * other name — is rejected. Documented trade-off: a legit deep link
 * that carries an extra payload (e.g. a referrer) is rejected rather
 * than launched. Fail closed: an intent URL we cannot fully
 * understand is not launched.
 *
 * ALLOWED FIELDS (inside `#Intent;...;end`): action, category, type,
 * scheme, package, launchFlags. `component` and the `SEL` selector
 * marker are always rejected. Any other field is rejected as unknown.
 *
 * DATA RULE: the data URI (scheme + the part after `intent://`) must
 * be http(s). That rejects javascript:, file:, content:, tel:, geo:
 * and every custom scheme smuggled in as intent data. tel:/mailto:/
 * sms:/market: are handled by SEPARATE branches in
 * shouldOverrideUrlLoading and are not this validator's concern —
 * only intent:// URLs reach here.
 *
 * FALLBACK RULE: browser_fallback_url, when present, must decode to
 * an http(s) URL with a host. A package + valid fallback launches the
 * app when present and loads the fallback in the browser when the app
 * is missing (the Android layer implements the fallback-on-missing
 * step — see hook spec). A package with NO valid fallback never
 * launches silently: it returns [Decision.NeedsConfirmation].
 *
 * GESTURE RULE: intent navigation with no user gesture
 * (WebResourceRequest.hasGesture == false) never launches silently:
 * it returns [Decision.NeedsConfirmation] even when everything else
 * is valid. The confirmation UI lives in BrowserHost (coordinator).
 */
object IntentValidator {

    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** The only extra allowed on an intent:// URL. */
    const val FALLBACK_EXTRA = "browser_fallback_url"

    /** A validated intent:// URL, ready for the Android layer to turn
     *  into a real Intent (via Intent.parseUri on the ORIGINAL url —
     *  never rebuild an Intent from these fields by hand, so the
     *  launched Intent is exactly the one that was validated). */
    data class ParsedIntent(
        /** Always ACTION_VIEW (anything else is rejected). */
        val action: String,
        /** http or https, or null when the intent carries no data. */
        val scheme: String?,
        /** "scheme://<data part>", or null when there is no data part. */
        val dataUri: String?,
        /** Target package, or null. */
        val packageName: String?,
        /** Decoded browser_fallback_url, or null. Always http(s). */
        val fallbackUrl: String?,
        /** Categories from the intent (e.g. BROWSABLE), if any. */
        val categories: List<String>,
        /** The original intent:// URL, verbatim. */
        val originalUrl: String
    )

    sealed class Decision {
        /** Safe to launch (via Intent.parseUri(originalUrl)); if the
         *  package app is missing and [ParsedIntent.fallbackUrl] is
         *  set, the Android layer loads the fallback in the browser
         *  instead of showing "No app can open this link". */
        data class Allow(val intent: ParsedIntent) : Decision()

        /** Do NOT launch silently — show the BrowserHost confirmation
         *  dialog (package name and URL, Allow/Deny) and launch only
         *  if the user taps Allow. [packageName] is null when the
         *  intent names no package (e.g. a no-gesture navigation to
         *  a plain http(s) intent URL) — the dialog then names the
         *  URL instead. [reason] is for the error/status log only,
         *  never shown as a package name. */
        data class NeedsConfirmation(
            val packageName: String?,
            /** Parsed data URI (http(s)), or null for a fallback-only
             *  intent — the Android layer needs this to tell "load the
             *  fallback" apart from "launch the app" after the user
             *  taps Allow, without re-validating (a package-without-
             *  fallback intent re-validates to NeedsConfirmation even
             *  with a gesture, so re-validating in onAllow would
             *  silently do nothing). */
            val dataUri: String?,
            val fallbackUrl: String?,
            val originalUrl: String,
            val reason: String
        ) : Decision()

        /** Do not launch, do not ask — drop the navigation. [reason]
         *  is a short machine-readable-ish explanation for the log;
         *  it never echoes the full URL or extra values. */
        data class Reject(val reason: String) : Decision()
    }

    /**
     * What the Android layer must do with a validated intent, once the
     * user has allowed it (an [Decision.Allow], or a
     * [Decision.NeedsConfirmation] the user tapped Allow on).
     *
     * AUDIT 2 (F02-A): the old hook decided "load the fallback" from
     * `dataUri == null && fallbackUrl != null` alone — which is also
     * true for a PACKAGE-ONLY intent (package + fallback, no data),
     * the standard "open in app" shape. Such an intent loaded its
     * fallback in the browser and NEVER launched the app, even after
     * the user tapped Allow. The launch decision must consider the
     * package: only a fallback-only intent (no data AND no package)
     * loads the fallback directly; anything with a package or data
     * tries the app first and falls back only when the app is missing.
     */
    sealed class LaunchAction {
        /** Try to launch the app via Intent.parseUri(originalUrl); if
         *  the app is missing and a fallback exists, load the fallback
         *  in the browser instead. */
        object LaunchApp : LaunchAction()

        /** Nothing to launch — load this fallback URL in the browser. */
        data class LoadFallback(val url: String) : LaunchAction()
    }

    /** Pure launch decision for a validated intent. Never throws. */
    fun launchAction(
        dataUri: String?,
        fallbackUrl: String?,
        packageName: String?
    ): LaunchAction {
        if (dataUri != null || packageName != null) return LaunchAction.LaunchApp
        val fallback = fallbackUrl
        if (fallback != null) return LaunchAction.LoadFallback(fallback)
        // Unreachable for a validated decision (parse() rejects an
        // intent with no data, package or fallback) — fail towards
        // trying the app, whose parseUri/startActivity failure is
        // caught and logged by the hook, rather than inventing a load.
        return LaunchAction.LaunchApp
    }

    /**
     * Validate an intent:// URL.
     *
     * @param url the raw URL from shouldOverrideUrlLoading.
     * @param hasGesture WebResourceRequest.hasGesture — true only when
     *   the navigation came from a real user tap.
     */
    fun validate(url: String, hasGesture: Boolean): Decision {
        val parsed = parse(url) ?: return Decision.Reject(parseFailureReason(url))
        // parse() returns null only for malformed input; the reason is
        // recomputed cheaply below via parseDetailed — keep the single
        // path instead: parseOrReject does the work.
        return decide(parsed, hasGesture)
    }

    // ---- internals ----

    private data class RawIntent(
        val action: String?,
        val scheme: String?,
        val dataUri: String?,
        val packageName: String?,
        val fallbackUrl: String?,
        val categories: List<String>,
        val originalUrl: String
    )

    /** Last parse failure, for the Reject reason. Single-threaded use
     *  (WebView client runs on the main thread; tests are sequential),
     *  so a small holder is safe and keeps parse() simple. */
    private var lastFailure: String = "malformed intent URL"

    private fun fail(reason: String): Nothing? {
        lastFailure = reason
        return null
    }

    private fun parseFailureReason(@Suppress("UNUSED_PARAMETER") url: String): String = lastFailure

    /**
     * Parse the intent:// URL string. Returns null (with [lastFailure]
     * set) when the URL is malformed or fails a hard rule. Soft rules
     * (package-without-fallback, no gesture) are NOT decided here —
     * they become NeedsConfirmation in [decide].
     */
    private fun parse(url: String): RawIntent? {
        lastFailure = "malformed intent URL"
        if (url.isBlank()) return fail("empty intent URL")
        if (url.any { it.code < 0x20 || it.code == 0x7F }) return fail("intent URL contains control characters")
        // Scheme must be "intent:" (case-insensitive). Accept both
        // "intent://<data>#Intent;...;end" and "intent:#Intent;...;end".
        if (!url.startsWith("intent:", ignoreCase = true)) return fail("not an intent:// URL")
        val rest = url.substring("intent:".length)
        val dataPart: String
        val fragment: String
        if (rest.startsWith("//")) {
            val afterSlashes = rest.substring(2)
            val marker = afterSlashes.indexOf("#Intent;")
            if (marker < 0) return fail("missing #Intent; marker")
            dataPart = afterSlashes.substring(0, marker)
            fragment = afterSlashes.substring(marker + "#Intent;".length)
        } else if (rest.startsWith("#Intent;")) {
            dataPart = ""
            fragment = rest.substring("#Intent;".length)
        } else {
            return fail("malformed intent URL (expected intent:// or intent:#Intent;)")
        }
        if (dataPart.contains('#') || dataPart.contains(' ') || dataPart.contains('\t')) {
            return fail("malformed intent data part")
        }
        // Fragment must end with ";end" (a trailing ";" after end is tolerated).
        val body = when {
            fragment == "end" -> ""
            fragment.endsWith(";end") -> fragment.dropLast(";end".length)
            fragment.endsWith(";end;") -> fragment.dropLast(";end;".length)
            else -> return fail("unterminated intent (missing ;end)")
        }

        var action: String? = null
        var scheme: String? = null
        var packageName: String? = null
        var type: String? = null
        var launchFlags: String? = null
        var component: String? = null
        var hasSelector = false
        var fallbackUrl: String? = null
        var fallbackSeen = false
        val categories = mutableListOf<String>()

        if (body.isNotEmpty()) {
            for (token in body.split(';')) {
                if (token.isEmpty()) return fail("malformed intent field (empty token)")
                if (token == "SEL") {
                    // Selector marker: everything after it belongs to a
                    // selector Intent. Any selector is rejected.
                    hasSelector = true
                    continue
                }
                val eq = token.indexOf('=')
                if (eq < 0) return fail("malformed intent field (no '='): $token")
                val key = token.substring(0, eq)
                val value = token.substring(eq + 1)
                if (key.isEmpty()) return fail("malformed intent field (empty key)")
                when {
                    // --- extras: only S.browser_fallback_url is allowed ---
                    isExtraKey(key) -> {
                        val prefix = key.substring(0, 2)
                        val name = key.substring(2)
                        if (prefix != "S." || name != FALLBACK_EXTRA) {
                            return fail("unexpected extra: $prefix$name")
                        }
                        if (fallbackSeen) return fail("duplicate browser_fallback_url")
                        fallbackSeen = true
                        val decoded = percentDecode(value)
                            ?: return fail("browser_fallback_url is not valid percent-encoding")
                        fallbackUrl = decoded
                    }
                    key == "action" -> {
                        if (action != null) return fail("duplicate action")
                        if (value.isEmpty() || value.any { it.isWhitespace() }) return fail("malformed action")
                        action = value
                    }
                    key == "scheme" -> {
                        if (scheme != null) return fail("duplicate scheme")
                        if (!isValidScheme(value)) return fail("malformed scheme")
                        scheme = value.lowercase()
                    }
                    key == "package" -> {
                        if (packageName != null) return fail("duplicate package")
                        if (!isValidPackageName(value)) return fail("malformed package name")
                        packageName = value
                    }
                    key == "category" -> {
                        if (value.isEmpty() || value.any { it.isWhitespace() }) return fail("malformed category")
                        categories.add(value)
                    }
                    key == "type" -> {
                        if (type != null) return fail("duplicate type")
                        if (value.isEmpty() || value.any { it.isWhitespace() }) return fail("malformed type")
                        type = value
                    }
                    key == "launchFlags" -> {
                        if (launchFlags != null) return fail("duplicate launchFlags")
                        // Decimal or 0x-prefixed hex; anything else is malformed.
                        val ok = value.toIntOrNull() != null ||
                            (value.startsWith("0x", ignoreCase = true) &&
                                value.substring(2).toLongOrNull(16) != null)
                        if (!ok) return fail("malformed launchFlags")
                        launchFlags = value
                    }
                    key == "component" -> {
                        if (component != null) return fail("duplicate component")
                        component = value
                    }
                    else -> return fail("unknown intent field: $key")
                }
            }
        }

        // ---- hard rules (reject) ----
        if (hasSelector) return fail("intent has a selector (SEL)")
        if (component != null) return fail("intent has an explicit component")
        if (action == null) return fail("intent has no action")
        if (action != ACTION_VIEW) return fail("intent action is not ACTION_VIEW: $action")
        if (scheme != null && scheme != "http" && scheme != "https") {
            return fail("intent data scheme is not http(s): $scheme")
        }
        if (dataPart.isNotEmpty() && scheme == null) {
            return fail("intent has data but no scheme")
        }
        var dataUri: String? = null
        if (dataPart.isNotEmpty()) {
            // scheme is http(s) here (checked above).
            if (dataPart.startsWith("/")) return fail("malformed intent data part")
            dataUri = "$scheme://$dataPart"
            if (!hasHttpHost(dataUri)) return fail("intent data URL has no host")
        }
        if (fallbackUrl != null) {
            if (fallbackUrl.isBlank()) return fail("empty browser_fallback_url")
            if (fallbackUrl.contains("#Intent;")) return fail("browser_fallback_url contains a nested intent")
            if (!hasHttpHost(fallbackUrl)) return fail("browser_fallback_url is not an http(s) URL with a host")
        }
        if (dataUri == null && packageName == null && fallbackUrl == null) {
            return fail("intent has no data, package or fallback — nothing to launch")
        }
        return RawIntent(
            action = action, scheme = scheme, dataUri = dataUri,
            packageName = packageName, fallbackUrl = fallbackUrl,
            categories = categories, originalUrl = url
        )
    }

    /** Soft rules: package-without-fallback and no-gesture become
     *  NeedsConfirmation; everything that survived parse() and has a
     *  gesture is allowed. */
    private fun decide(raw: RawIntent, hasGesture: Boolean): Decision {
        val parsed = ParsedIntent(
            action = raw.action!!, scheme = raw.scheme, dataUri = raw.dataUri,
            packageName = raw.packageName, fallbackUrl = raw.fallbackUrl,
            categories = raw.categories, originalUrl = raw.originalUrl
        )
        if (raw.packageName != null && raw.fallbackUrl == null) {
            return Decision.NeedsConfirmation(
                packageName = raw.packageName, dataUri = raw.dataUri, fallbackUrl = null,
                originalUrl = raw.originalUrl,
                reason = "package without a valid http(s) fallback — user confirmation required"
            )
        }
        if (!hasGesture) {
            return Decision.NeedsConfirmation(
                packageName = raw.packageName, dataUri = raw.dataUri, fallbackUrl = raw.fallbackUrl,
                originalUrl = raw.originalUrl,
                reason = "intent navigation without a user gesture — user confirmation required"
            )
        }
        return Decision.Allow(parsed)
    }

    /** Extra keys are "<prefix>.<name>" where prefix is one of the
     *  Android Intent.parseUri extra prefixes: S. (String), B./b.
     *  (boolean/byte), c. (char), d. (double), f. (float), i. (int),
     *  l. (long), s. (short). */
    private fun isExtraKey(key: String): Boolean {
        if (key.length < 3 || key[1] != '.') return false
        return when (key[0]) {
            'S', 'B', 'b', 'c', 'd', 'f', 'i', 'l', 's' -> true
            else -> false
        }
    }

    private fun isValidScheme(value: String): Boolean {
        if (value.isEmpty()) return false
        if (!value[0].isLetter()) return false
        return value.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }

    /** Android package name: dot-separated segments, each starting
     *  with a letter and containing letters/digits/underscore. At
     *  least one dot (a bare word is not a package). */
    private fun isValidPackageName(value: String): Boolean {
        if (value.isEmpty() || value.startsWith(".") || value.endsWith(".")) return false
        if (!value.contains('.')) return false
        return value.split('.').all { segment ->
            segment.isNotEmpty() && segment[0].isLetter() &&
                segment.all { it.isLetterOrDigit() || it == '_' }
        }
    }

    /** True when [url] is http:// or https:// with a non-empty host
     *  and no whitespace/control characters. */
    private fun hasHttpHost(url: String): Boolean {
        val lower = url.lowercase()
        val rest = when {
            lower.startsWith("http://") -> url.substring("http://".length)
            lower.startsWith("https://") -> url.substring("https://".length)
            else -> return false
        }
        if (rest.isEmpty()) return false
        if (url.any { it.code < 0x20 || it.code == 0x7F || it.isWhitespace() }) return false
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@')
        return host.isNotEmpty()
    }

    /**
     * Percent-decode like android.net.Uri.decode (NOT
     * java.net.URLDecoder: '+' stays '+', it is NOT a space in a URL).
     * Returns null on a malformed % sequence.
     */
    private fun percentDecode(value: String): String? {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hi = value[i + 1].digitToIntOrNull(16) ?: return null
                val lo = value[i + 2].digitToIntOrNull(16) ?: return null
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                // Non-ASCII chars are encoded as UTF-8 bytes.
                val encoded = c.toString().toByteArray(Charsets.UTF_8)
                for (b in encoded) bytes.add(b)
                i++
            }
        }
        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
