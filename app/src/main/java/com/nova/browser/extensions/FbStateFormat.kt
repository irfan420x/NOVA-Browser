package com.nova.browser.extensions

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * FBState data format + crypto (NOVA Browser v2.4, NOVA-EXTENSIONS.md §4).
 *
 * Pure Kotlin, JVM-testable (see tests/FbStateTest.kt). No Android
 * imports, no new dependency: only the JDK (javax.crypto, java.util)
 * plus a small hand-rolled JSON parser/serializer below.
 *
 * WHY hand-rolled JSON instead of org.json: the JVM test runner
 * (tests/run-tests.sh) runs tests with `java -cp OUT:STDLIB` — the
 * android.jar that carries org.json is compile-classpath only there,
 * so org.json would be a NoClassDefFoundError at test runtime. The
 * parser here covers exactly what FBState data needs (objects,
 * arrays, strings, numbers, booleans, null) and nothing more.
 *
 * Formats (see also v2.4-work/c3c-analysis.md — behaviour reference
 * only, no C3C code):
 * - NOVA export: versioned wrapper
 *   {"format":"nova-fbstate","version":1,"exportedAt":"<ISO-8601 UTC>",
 *    "cookies":[{key,value,domain,path,hostOnly,secure,httpOnly,expires}]}
 *   `secure`/`httpOnly`/`expires` are null on export — CookieManager
 *   cannot tell us them, and NOVA does not fabricate them (C3C
 *   fabricated creation/lastAccessed as "now"; those fields are
 *   omitted entirely here).
 * - Import also accepts: the C3C bare JSON array, base64 of either JSON
 *   shape, the NOVA encrypted envelope, and C3C's hex AES-CTR ciphertext.
 *
 * Encrypted export (NOVA, §4.3-Q3): PBKDF2-HMAC-SHA256 (random 16-byte
 * salt, 100,000 iterations) -> AES-256-GCM (random 12-byte IV), one
 * line: `NOVA-FBSTATE-ENC-v1:<base64(salt || iv || ciphertext)>`.
 * The password is never stored, never logged, and held only for the
 * operation; minimum 8 characters.
 *
 * C3C encrypted import (hex, AES-CTR, key = SHA-256(password), fixed
 * counter [0,…,0,1]): supported on IMPORT ONLY, for old C3C data.
 * NOVA never produces it. AES-CTR has no authentication tag, so a
 * wrong password yields garbage that fails JSON parsing — that failure
 * is reported as "Invalid password.", exactly as C3C reported it.
 *
 * HARD LIMITS (platform, not bugs — stated on screen too):
 * - CookieManager returns only `name=value; name=value` strings: no
 *   secure/httpOnly/expiry flags. Export records those as null.
 * - HttpOnly CANNOT be set through CookieManager.setCookie — WebView
 *   drops the attribute. An imported HttpOnly cookie becomes
 *   JavaScript-readable after import. [buildSetCookieString] therefore
 *   never emits an HttpOnly attribute.
 * - A cookie value containing "; " is ambiguous in the CookieManager
 *   string format and may parse incorrectly; there is no better source
 *   in WebView.
 *
 * PRIVACY: this file never logs, never sends anything anywhere. Cookie
 * values pass through these functions only in memory, on device.
 */
object FbStateFormat {

    const val FORMAT = "nova-fbstate"
    const val VERSION = 1
    const val ENCRYPTED_PREFIX = "NOVA-FBSTATE-ENC-v1:"
    const val CANONICAL_DOMAIN = ".facebook.com"
    const val MIN_PASSWORD_LENGTH = 8
    const val PBKDF2_ITERATIONS = 100_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12

    /** One year, for the import expiry fallback (§4.3-Q2). */
    const val ONE_YEAR_MS = 365L * 24 * 60 * 60 * 1000

    // Exact user-facing error strings (NOVA-EXTENSIONS.md §4.5).
    const val ERR_EMPTY = "Please input FBState data first."
    const val ERR_INVALID = "Invalid FBState data."
    const val ERR_PASSWORD = "Invalid password."
    const val ERR_NEWER_VERSION =
        "This data is from a newer FBState version — update NOVA Browser to import it."

    /** One Facebook cookie, as carried by the FBState formats. */
    data class FbStateCookie(
        val key: String,
        val value: String,
        /** Always [CANONICAL_DOMAIN] after parse; null only before it. */
        val domain: String? = CANONICAL_DOMAIN,
        val path: String = "/",
        val hostOnly: Boolean? = null,
        /** null = unknown (CookieManager cannot tell us). Never false on export. */
        val secure: Boolean? = null,
        /** null = unknown. Accepted on import but NEVER applied on write
         *  (WebView drops HttpOnly in setCookie — see the file doc). */
        val httpOnly: Boolean? = null,
        /** Epoch seconds, or null = unknown. */
        val expires: Long? = null
    )

    /** What [detect] found in a textarea, in §4.5 auto-detection order. */
    enum class DetectedKind {
        EMPTY, NOVA_ENCRYPTED, JSON, BASE64, C3C_HEX, INVALID
    }

    sealed class ImportOutcome {
        /** Encrypted input: the caller must prompt for a password, then
         *  call [decryptAndParse]. */
        data class NeedsPassword(val kind: DetectedKind) : ImportOutcome()

        /** Parsed cookies + how many items were skipped (bad domain,
         *  missing key/value, or a non-object element). */
        data class Ok(val cookies: List<FbStateCookie>, val skipped: Int) : ImportOutcome()

        data class Error(val message: String) : ImportOutcome()
    }

    // ---- Export encoding ----

    /** Current time as ISO-8601 UTC (e.g. 2026-11-24T15:00:00.123Z). */
    fun nowIsoUtc(): String = java.time.Instant.now().toString()

    /** The NOVA wrapper JSON, pretty-printed with tab indentation
     *  (matching C3C's JSON.stringify(data, null, "\t")). */
    fun encodeWrapper(
        cookies: List<FbStateCookie>,
        exportedAtIso: String = nowIsoUtc()
    ): String {
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "exportedAt" to exportedAtIso,
            "cookies" to cookies.map { c ->
                linkedMapOf<String, Any?>(
                    "key" to c.key,
                    "value" to c.value,
                    "domain" to (c.domain ?: CANONICAL_DOMAIN),
                    "path" to c.path,
                    "hostOnly" to c.hostOnly,
                    "secure" to c.secure,
                    "httpOnly" to c.httpOnly,
                    "expires" to c.expires
                )
            }
        )
        return JsonWriter.write(root)
    }

    fun encodeBase64(json: String): String =
        Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    // ---- Import detection + parsing (no password needed) ----

    /** Auto-detection, §4.5 order: NOVA envelope, JSON, base64-of-JSON,
     *  hex ciphertext, else INVALID. Never throws. */
    fun detect(text: String): DetectedKind {
        val input = text.trim()
        if (input.isEmpty()) return DetectedKind.EMPTY
        if (input.startsWith(ENCRYPTED_PREFIX)) return DetectedKind.NOVA_ENCRYPTED
        if (parsesAsJson(input)) return DetectedKind.JSON
        val decoded = tryBase64DecodeToString(input)
        if (decoded != null && parsesAsJson(decoded)) return DetectedKind.BASE64
        if (isHexCiphertext(input)) return DetectedKind.C3C_HEX
        return DetectedKind.INVALID
    }

    private fun parsesAsJson(text: String): Boolean {
        return try {
            JsonParser(text).parse()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Parse plaintext input (JSON wrapper, bare array, or base64 of
     *  either). Encrypted input returns [ImportOutcome.NeedsPassword];
     *  use [decryptAndParse] for that. Never throws. */
    fun parseImportText(text: String): ImportOutcome {
        val input = text.trim()
        if (input.isEmpty()) return ImportOutcome.Error(ERR_EMPTY)
        if (input.startsWith(ENCRYPTED_PREFIX)) {
            return ImportOutcome.NeedsPassword(DetectedKind.NOVA_ENCRYPTED)
        }
        val parsed = try {
            JsonParser(input).parse()
        } catch (_: Exception) {
            null
        }
        if (parsed != null) return parseJsonValue(parsed)
        val decoded = tryBase64DecodeToString(input)
        if (decoded != null) {
            val fromB64 = try {
                JsonParser(decoded).parse()
            } catch (_: Exception) {
                null
            }
            if (fromB64 != null) return parseJsonValue(fromB64)
        }
        if (isHexCiphertext(input)) {
            return ImportOutcome.NeedsPassword(DetectedKind.C3C_HEX)
        }
        return ImportOutcome.Error(ERR_INVALID)
    }

    /** Decrypt encrypted input with [password], then parse the plaintext
     *  as JSON. Wrong password -> [ImportOutcome.Error] with
     *  [ERR_PASSWORD]. Never throws. */
    fun decryptAndParse(
        kind: DetectedKind,
        text: String,
        password: CharArray
    ): ImportOutcome {
        val input = text.trim()
        return when (kind) {
            DetectedKind.NOVA_ENCRYPTED -> {
                // AES-GCM authenticates: a null here IS a wrong password
                // (or corrupt data, reported the same way).
                val plain = decryptNovaEnvelope(input, password)
                    ?: return ImportOutcome.Error(ERR_PASSWORD)
                when (val out = parseImportText(plain)) {
                    is ImportOutcome.NeedsPassword -> ImportOutcome.Error(ERR_INVALID)
                    else -> out
                }
            }
            DetectedKind.C3C_HEX -> {
                val plain = decryptC3cHex(input, password)
                    ?: return ImportOutcome.Error(ERR_PASSWORD)
                // AES-CTR has no tag: a wrong password yields garbage
                // that fails JSON parsing. C3C reported that as
                // "Invalid password." — so do we.
                when (val out = parseImportText(plain)) {
                    is ImportOutcome.Error -> ImportOutcome.Error(ERR_PASSWORD)
                    is ImportOutcome.NeedsPassword -> ImportOutcome.Error(ERR_INVALID)
                    else -> out
                }
            }
            else -> parseImportText(input)
        }
    }

    private fun parseJsonValue(value: Any?): ImportOutcome {
        return when (value) {
            is List<*> -> parseCookieArray(value)
            is Map<*, *> -> {
                if (value["format"] != FORMAT) return ImportOutcome.Error(ERR_INVALID)
                val version = value["version"]
                val vNum: Long = when (version) {
                    is Long -> version
                    is Int -> version.toLong()
                    is Double -> if (version == kotlin.math.floor(version)) version.toLong()
                    else return ImportOutcome.Error(ERR_INVALID)
                    else -> return ImportOutcome.Error(ERR_INVALID)
                }
                if (vNum > VERSION) return ImportOutcome.Error(ERR_NEWER_VERSION)
                if (vNum != VERSION.toLong()) return ImportOutcome.Error(ERR_INVALID)
                val cookiesRaw = value["cookies"] as? List<*>
                    ?: return ImportOutcome.Error(ERR_INVALID)
                parseCookieArray(cookiesRaw)
            }
            else -> ImportOutcome.Error(ERR_INVALID)
        }
    }

    private fun parseCookieArray(items: List<*>): ImportOutcome {
        var skipped = 0
        val cookies = mutableListOf<FbStateCookie>()
        for (item in items) {
            val cookie = parseCookieItem(item)
            if (cookie == null) skipped++ else cookies.add(cookie)
        }
        return ImportOutcome.Ok(cookies, skipped)
    }

    /**
     * One item of a bare array / wrapper `cookies` array. Returns null
     * (item skipped + counted) when: not an object; `key` missing/empty
     * or not a string; `value` missing or not a string (empty string
     * IS allowed); `domain`, when present, is not `facebook.com` or a
     * subdomain of it. `path` defaults to "/". Every accepted item is
     * normalised to the canonical domain [CANONICAL_DOMAIN].
     *
     * The domain check is deliberately stricter than C3C's bare
     * endsWith("facebook.com") (which would accept "notfacebook.com"):
     * the domain must be exactly "facebook.com" or end with
     * ".facebook.com". Every accepted domain still ends with
     * "facebook.com", as §4.5 requires.
     */
    fun parseCookieItem(raw: Any?): FbStateCookie? {
        val obj = raw as? Map<*, *> ?: return null
        val key = obj["key"] as? String ?: return null
        if (key.isEmpty()) return null
        val value = obj["value"] as? String ?: return null
        when (val domainRaw = obj["domain"]) {
            null -> { /* defaults to the canonical domain below */ }
            is String -> {
                val d = domainRaw.lowercase()
                if (d != "facebook.com" && !d.endsWith(".facebook.com")) return null
            }
            else -> return null
        }
        val path = (obj["path"] as? String)?.takeIf { it.isNotEmpty() } ?: "/"
        return FbStateCookie(
            key = key,
            value = value,
            domain = CANONICAL_DOMAIN,
            path = path,
            hostOnly = obj["hostOnly"] as? Boolean,
            secure = obj["secure"] as? Boolean,
            httpOnly = obj["httpOnly"] as? Boolean,
            expires = parseExpires(obj["expires"])
        )
    }

    /** Expiry: a JSON number is epoch seconds (values above 10^12 are
     *  treated as milliseconds); an ISO-8601 string is parsed; anything
     *  else is unknown (null). */
    private fun parseExpires(raw: Any?): Long? {
        return when (raw) {
            null -> null
            is Long -> normalizeEpochSeconds(raw)
            is Int -> normalizeEpochSeconds(raw.toLong())
            is Double -> normalizeEpochSeconds(raw.toLong())
            is String -> try {
                java.time.Instant.parse(raw).epochSecond
            } catch (_: Exception) {
                null
            }
            else -> null
        }
    }

    private fun normalizeEpochSeconds(v: Long): Long =
        if (v > 1_000_000_000_000L) v / 1000 else v

    // ---- CookieManager string parsing / merging (pure part of §4.2) ----

    /**
     * Parse one CookieManager.getCookie(url) string (`name=value;
     * name=value`). Split on ';', split each pair on the FIRST '='
     * (values may contain '='); a pair with no '=' is skipped. Values
     * are kept verbatim — no URL-decoding, no re-encoding.
     */
    fun parseCookieHeader(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf('=')
            if (idx <= 0) continue
            out.add(trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1))
        }
        return out
    }

    /**
     * Merge the per-URL cookie lists from the three export URLs
     * (www, bare, m — in that order): dedupe by cookie name, first
     * occurrence wins. Unknown flags are recorded as null (§4.3-Q2).
     */
    fun mergeExportCookies(vararg perUrl: List<Pair<String, String>>): List<FbStateCookie> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<FbStateCookie>()
        for (list in perUrl) {
            for ((name, value) in list) {
                if (!seen.add(name)) continue
                out.add(
                    FbStateCookie(
                        key = name,
                        value = value,
                        domain = CANONICAL_DOMAIN,
                        path = "/",
                        hostOnly = false,
                        secure = null,
                        httpOnly = null,
                        expires = null
                    )
                )
            }
        }
        return out
    }

    // ---- Import application (merge vs replace, §4.3-Q5) ----

    /**
     * Apply an import to the current cookie list (pure function).
     * Merge (default): imported cookies overwrite same key+path and
     * are appended when new; every other current cookie is untouched.
     * Replace-all: the current list is discarded first. Domain is
     * always the canonical [CANONICAL_DOMAIN], so key+path is the
     * merge identity.
     */
    fun applyImport(
        existing: List<FbStateCookie>,
        imported: List<FbStateCookie>,
        replaceAll: Boolean
    ): List<FbStateCookie> {
        if (replaceAll) return imported.toList()
        val merged = existing.toMutableList()
        for (item in imported) {
            val idx = merged.indexOfFirst { it.key == item.key && it.path == item.path }
            if (idx >= 0) merged[idx] = item else merged.add(item)
        }
        return merged
    }

    // ---- CookieManager write string (§4.2) ----

    /** RFC-1123 GMT date, as CookieManager's Expires attribute wants it. */
    fun formatRfc1123(epochMs: Long): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date(epochMs))
    }

    /**
     * The `setCookie(url, string)` value for one imported cookie:
     * `name=value; Domain=.facebook.com; Path=/; Expires=<date>[; Secure]`.
     * A valid FUTURE expiry is honoured; otherwise +365 days from
     * import time (§4.3-Q2). `secure: true` is honoured. HttpOnly is
     * NEVER emitted: WebView drops the attribute, so writing it would
     * be a lie — the imported cookie becomes JavaScript-readable.
     */
    fun buildSetCookieString(cookie: FbStateCookie, nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        sb.append(cookie.key).append('=').append(cookie.value)
        sb.append("; Domain=").append(CANONICAL_DOMAIN)
        sb.append("; Path=").append(cookie.path.ifEmpty { "/" })
        val expiryMs = cookie.expires?.let { it * 1000 }?.takeIf { it > nowMs }
            ?: (nowMs + ONE_YEAR_MS)
        sb.append("; Expires=").append(formatRfc1123(expiryMs))
        if (cookie.secure == true) sb.append("; Secure")
        return sb.toString()
    }

    // ---- Base64 / hex helpers ----

    fun tryBase64DecodeToString(text: String): String? {
        return try {
            val compact = text.filterNot { it.isWhitespace() }
            if (compact.isEmpty() || compact.length % 4 != 0) return null
            val bytes = Base64.getDecoder().decode(compact)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** C3C hex-ciphertext shape: even length, hex digits only. */
    fun isHexCiphertext(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || t.length % 2 != 0) return false
        return t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun hexToBytes(hex: String): ByteArray? {
        val t = hex.trim()
        if (t.isEmpty() || t.length % 2 != 0) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(t.length / 2) { i ->
            ((t[i * 2].digitToInt(16) shl 4) + t[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(chars[(b.toInt() ushr 4) and 0xF])
            sb.append(chars[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    // ---- Crypto (§4.3-Q3) ----

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * NOVA encrypted export. Throws [IllegalArgumentException] when the
     * password is shorter than [MIN_PASSWORD_LENGTH]. The returned
     * envelope is one line: `NOVA-FBSTATE-ENC-v1:<base64(salt||iv||ct)>`.
     */
    fun encryptNova(plaintext: String, password: CharArray): String {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Password must be at least 8 characters."
        }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val keyBytes = pbkdf2(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = salt + iv + ct
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypt a NOVA envelope. Returns null on wrong password, corrupt
     * base64, or a truncated/tampered envelope (GCM authentication
     * failure) — never throws for bad input.
     */
    fun decryptNovaEnvelope(envelope: String, password: CharArray): String? {
        return try {
            val b64 = envelope.trim().removePrefix(ENCRYPTED_PREFIX)
            val combined = Base64.getDecoder().decode(b64)
            // salt + IV + at least the 16-byte GCM tag.
            if (combined.size < SALT_BYTES + IV_BYTES + 16) return null
            val salt = combined.copyOfRange(0, SALT_BYTES)
            val iv = combined.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
            val ct = combined.copyOfRange(SALT_BYTES + IV_BYTES, combined.size)
            val keyBytes = pbkdf2(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decrypt C3C's hex AES-CTR ciphertext (IMPORT ONLY — NOVA never
     * produces this): key = SHA-256(UTF-8(password)), counter =
     * 16-byte array with last byte 1, no IV/salt stored. Returns null
     * on malformed hex or crypto failure — never throws. NOTE: a wrong
     * password returns garbage, not null (CTR has no tag); the caller
     * ([decryptAndParse]) reports that as "Invalid password." when the
     * garbage fails JSON parsing.
     */
    fun decryptC3cHex(hex: String, password: CharArray): String? {
        return try {
            val ct = hexToBytes(hex) ?: return null
            // String(password) copies the password into an immutable
            // String for the digest; it lives only for this call.
            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(String(password).toByteArray(Charsets.UTF_8))
            val counter = ByteArray(16).also { it[15] = 1 }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(counter))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Minimal JSON parser — objects, arrays, strings (with escapes incl.
 * \uXXXX), numbers, booleans, null. Throws [IllegalArgumentException]
 * on malformed input. Only what FBState data needs; not a general
 * JSON library (see the file doc for why org.json is not used here).
 */
internal class JsonParser(private val text: String) {
    private var pos = 0

    fun parse(): Any? {
        skipWs()
        val value = parseValue()
        skipWs()
        if (pos != text.length) throw IllegalArgumentException("Trailing content after JSON value")
        return value
    }

    private fun skipWs() {
        while (pos < text.length && text[pos] in " \t\n\r") pos++
    }

    private fun parseValue(): Any? {
        if (pos >= text.length) throw IllegalArgumentException("Unexpected end of JSON")
        return when (val c = text[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> {
                expect("true")
                true
            }
            'f' -> {
                expect("false")
                false
            }
            'n' -> {
                expect("null")
                null
            }
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected character '$c' at $pos")
        }
    }

    private fun expect(word: String) {
        if (!text.startsWith(word, pos)) throw IllegalArgumentException("Expected '$word'")
        pos += word.length
    }

    private fun parseObject(): Map<String, Any?> {
        pos++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (pos < text.length && text[pos] == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            if (pos >= text.length || text[pos] != '"') {
                throw IllegalArgumentException("Expected object key at $pos")
            }
            val key = parseString()
            skipWs()
            if (pos >= text.length || text[pos] != ':') {
                throw IllegalArgumentException("Expected ':' at $pos")
            }
            pos++
            skipWs()
            map[key] = parseValue()
            skipWs()
            when {
                pos < text.length && text[pos] == ',' -> pos++
                pos < text.length && text[pos] == '}' -> {
                    pos++
                    return map
                }
                else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        pos++ // '['
        val list = mutableListOf<Any?>()
        skipWs()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            skipWs()
            list.add(parseValue())
            skipWs()
            when {
                pos < text.length && text[pos] == ',' -> pos++
                pos < text.length && text[pos] == ']' -> {
                    pos++
                    return list
                }
                else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening '"'
        val sb = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) throw IllegalArgumentException("Unterminated escape")
                    when (val e = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) {
                                throw IllegalArgumentException("Truncated \\u escape")
                            }
                            val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("Bad \\u escape")
                            pos += 4
                            sb.append(code.toChar())
                        }
                        else -> throw IllegalArgumentException("Bad escape '\\$e'")
                    }
                }
                else -> {
                    if (c.code < 0x20) {
                        throw IllegalArgumentException("Unescaped control character in string")
                    }
                    sb.append(c)
                }
            }
        }
        throw IllegalArgumentException("Unterminated string")
    }

    private fun parseNumber(): Any {
        val start = pos
        if (pos < text.length && text[pos] == '-') pos++
        while (pos < text.length &&
            (text[pos] in '0'..'9' || text[pos] == '.' || text[pos] == 'e' ||
                text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')
        ) pos++
        val token = text.substring(start, pos)
        if (token.isEmpty() || token == "-") throw IllegalArgumentException("Bad number at $start")
        return if (token.contains('.') || token.contains('e') || token.contains('E')) {
            token.toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '$token'")
        } else {
            token.toLongOrNull() ?: token.toDoubleOrNull()
            ?: throw IllegalArgumentException("Bad number '$token'")
        }
    }
}

/**
 * Minimal JSON writer — tab-indented pretty output, matching
 * JSON.stringify(value, null, "\t") (C3C's export shape).
 */
internal object JsonWriter {

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Int -> sb.append(value.toString())
            is Double -> {
                if (value.isFinite() && value == kotlin.math.floor(value) &&
                    value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()
                ) {
                    sb.append(value.toLong().toString())
                } else {
                    sb.append(value.toString())
                }
            }
            is String -> writeString(sb, value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val entries = value.entries.toList()
                for ((i, entry) in entries.withIndex()) {
                    indent(sb, depth + 1)
                    writeString(sb, entry.key.toString())
                    sb.append(": ")
                    writeValue(sb, entry.value, depth + 1)
                    if (i < entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                indent(sb, depth)
                sb.append("}")
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                for ((i, item) in value.withIndex()) {
                    indent(sb, depth + 1)
                    writeValue(sb, item, depth + 1)
                    if (i < value.size - 1) sb.append(",")
                    sb.append("\n")
                }
                indent(sb, depth)
                sb.append("]")
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        repeat(depth) { sb.append('\t') }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }
}
