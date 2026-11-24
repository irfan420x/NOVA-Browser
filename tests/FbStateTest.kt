package com.nova.browser.extensions

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM unit tests for the FBState format + crypto layer
 * (FbStateFormat.kt) — plain main() + check(), no framework.
 * Same conventions as tests/ExtensionsTest.kt. Run with
 * tests/run-tests.sh once the coordinator adds this file (and
 * FbStateFormat.kt) to its compile list; until then it compiles and
 * runs standalone with kotlinc (see the report).
 *
 * Covers: NOVA wrapper round-trip, C3C bare-array import, base64
 * variant, encrypted round-trip (correct password works, wrong
 * password fails), C3C AES-CTR-hex import-decrypt, merge-vs-replace
 * logic, and validation rejections.
 *
 * NO real cookie values anywhere in this file — placeholders only
 * ("1000...", "42%3A..."), as in NOVA-EXTENSIONS.md §4.3.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private typealias Cookie = FbStateFormat.FbStateCookie

private val PLACEHOLDER_COOKIES = listOf(
    Cookie("c_user", "1000...", ".facebook.com", "/", false, null, null, null),
    Cookie("xs", "42%3A...", ".facebook.com", "/", false, null, null, null)
)

private const val WRAPPER_JSON = """{
	"format": "nova-fbstate",
	"version": 1,
	"exportedAt": "2026-11-24T15:00:00.000Z",
	"cookies": [
		{
			"key": "c_user",
			"value": "1000...",
			"domain": ".facebook.com",
			"path": "/",
			"hostOnly": false,
			"secure": null,
			"httpOnly": null,
			"expires": null
		}
	]
}"""

private const val C3C_BARE_ARRAY = """[
	{
		"key": "c_user",
		"value": "1000...",
		"domain": "facebook.com",
		"path": "/",
		"hostOnly": false,
		"creation": "2026-11-24T15:00:00.000Z",
		"lastAccessed": "2026-11-24T15:00:00.000Z"
	},
	{
		"key": "xs",
		"value": "42%3A...",
		"domain": "facebook.com",
		"path": "/",
		"hostOnly": false,
		"creation": "2026-11-24T15:00:00.000Z",
		"lastAccessed": "2026-11-24T15:00:00.000Z"
	}
]"""

/** Build a C3C-style hex ciphertext independently (raw JCE, same
 *  algorithm spec as c3c-analysis.md §2): key = SHA-256(password),
 *  AES-CTR, fixed counter [0,…,0,1]. The production code never
 *  encrypts this way — this helper exists only so the import-decrypt
 *  path has a known-good ciphertext to read. */
private fun c3cEncryptHex(plaintext: String, password: String): String {
    val key = MessageDigest.getInstance("SHA-256")
        .digest(password.toByteArray(Charsets.UTF_8))
    val counter = ByteArray(16).also { it[15] = 1 }
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
    return FbStateFormat.bytesToHex(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
}

private fun rfc1123(epochMs: Long): String {
    val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("GMT")
    return fmt.format(java.util.Date(epochMs))
}

fun main() {
    // --- NOVA wrapper round-trip ---
    val wrapper = FbStateFormat.encodeWrapper(
        PLACEHOLDER_COOKIES, "2026-11-24T15:00:00.000Z"
    )
    check("wrapper declares the NOVA format", wrapper.contains("\"format\": \"nova-fbstate\""))
    check("wrapper version is 1", wrapper.contains("\"version\": 1"))
    check("wrapper keeps the given exportedAt", wrapper.contains("2026-11-24T15:00:00.000Z"))
    check("wrapper is tab-indented like C3C", wrapper.contains("\n\t\"cookies\""))
    check("wrapper never fabricates creation/lastAccessed",
        !wrapper.contains("creation") && !wrapper.contains("lastAccessed"))
    check("wrapper records unknown flags as null (never false, never a fake date)",
        wrapper.contains("\"secure\": null") && wrapper.contains("\"expires\": null"))
    val roundTrip = FbStateFormat.parseImportText(wrapper)
    check("wrapper round-trips to Ok",
        roundTrip is FbStateFormat.ImportOutcome.Ok)
    val roundTripOk = roundTrip as? FbStateFormat.ImportOutcome.Ok
    check("wrapper round-trip keeps both cookies",
        roundTripOk?.cookies == PLACEHOLDER_COOKIES)
    check("wrapper round-trip skips nothing", roundTripOk?.skipped == 0)
    check("a hand-written wrapper parses the same way",
        (FbStateFormat.parseImportText(WRAPPER_JSON)
            as? FbStateFormat.ImportOutcome.Ok)?.cookies?.size == 1)

    // Values with '=', quotes and backslashes survive the round-trip.
    val tricky = listOf(Cookie("tricky", "a=b\"c\\d", ".facebook.com", "/", false, null, null, null))
    val trickyOut = FbStateFormat.parseImportText(FbStateFormat.encodeWrapper(tricky))
        as? FbStateFormat.ImportOutcome.Ok
    check("cookie values with '=' quotes and backslashes round-trip",
        trickyOut?.cookies == tricky)

    // --- C3C bare-array import ---
    val bare = FbStateFormat.parseImportText(C3C_BARE_ARRAY)
    check("C3C bare array parses to Ok", bare is FbStateFormat.ImportOutcome.Ok)
    val bareOk = bare as? FbStateFormat.ImportOutcome.Ok
    check("C3C bare array keeps both cookies", bareOk?.cookies?.size == 2)
    check("C3C bare array normalises the domain to .facebook.com",
        bareOk?.cookies?.all { it.domain == ".facebook.com" } == true)
    check("C3C bare array keeps the placeholder values",
        bareOk?.cookies?.map { it.key to it.value } ==
            listOf("c_user" to "1000...", "xs" to "42%3A..."))
    check("C3C bare array skips nothing", bareOk?.skipped == 0)
    check("empty bare array is Ok with zero cookies",
        (FbStateFormat.parseImportText("[]") as? FbStateFormat.ImportOutcome.Ok)
            ?.cookies?.isEmpty() == true)

    // --- Base64 variant ---
    val b64Wrapper = FbStateFormat.encodeBase64(wrapper)
    check("base64 of the wrapper detects as BASE64",
        FbStateFormat.detect(b64Wrapper) == FbStateFormat.DetectedKind.BASE64)
    check("base64 of the wrapper parses to the same cookies",
        (FbStateFormat.parseImportText(b64Wrapper)
            as? FbStateFormat.ImportOutcome.Ok)?.cookies == PLACEHOLDER_COOKIES)
    val b64Bare = FbStateFormat.encodeBase64(C3C_BARE_ARRAY)
    check("base64 of the C3C bare array parses too",
        (FbStateFormat.parseImportText(b64Bare)
            as? FbStateFormat.ImportOutcome.Ok)?.cookies?.size == 2)

    // --- NOVA encrypted round-trip ---
    val password = "correct-horse-battery".toCharArray()
    val envelope = FbStateFormat.encryptNova(wrapper, password)
    check("NOVA envelope has the versioned prefix",
        envelope.startsWith(FbStateFormat.ENCRYPTED_PREFIX))
    check("NOVA envelope detects as NOVA_ENCRYPTED",
        FbStateFormat.detect(envelope) == FbStateFormat.DetectedKind.NOVA_ENCRYPTED)
    check("NOVA envelope decrypts with the correct password",
        FbStateFormat.decryptNovaEnvelope(envelope, password) == wrapper)
    check("NOVA envelope fails with the wrong password",
        FbStateFormat.decryptNovaEnvelope(envelope, "wrong-password".toCharArray()) == null)
    check("NOVA envelope fails when tampered",
        FbStateFormat.decryptNovaEnvelope(envelope.dropLast(4) + "AAAA", password) == null)
    check("two encryptions of the same text differ (random salt + IV)",
        FbStateFormat.encryptNova(wrapper, password) != envelope)
    check("decryptAndParse of the envelope returns the cookies",
        (FbStateFormat.decryptAndParse(
            FbStateFormat.DetectedKind.NOVA_ENCRYPTED, envelope, password
        ) as? FbStateFormat.ImportOutcome.Ok)?.cookies == PLACEHOLDER_COOKIES)
    check("decryptAndParse with the wrong password reports Invalid password.",
        (FbStateFormat.decryptAndParse(
            FbStateFormat.DetectedKind.NOVA_ENCRYPTED, envelope, "wrong-password".toCharArray()
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_PASSWORD)
    var shortPasswordRejected = false
    try {
        FbStateFormat.encryptNova(wrapper, "short".toCharArray())
    } catch (_: IllegalArgumentException) {
        shortPasswordRejected = true
    }
    check("encryptNova rejects a password under 8 characters", shortPasswordRejected)
    check("decryptNovaEnvelope rejects a non-envelope",
        FbStateFormat.decryptNovaEnvelope("abcd", password) == null)

    // --- C3C AES-CTR-hex import-decrypt ---
    val c3cPassword = "c3c-password"
    val c3cHex = c3cEncryptHex(C3C_BARE_ARRAY, c3cPassword)
    check("C3C hex is lowercase hex, even length",
        c3cHex.length % 2 == 0 && c3cHex.all { it in '0'..'9' || it in 'a'..'f' })
    check("C3C hex detects as C3C_HEX",
        FbStateFormat.detect(c3cHex) == FbStateFormat.DetectedKind.C3C_HEX)
    check("C3C hex decrypts with the right password",
        FbStateFormat.decryptC3cHex(c3cHex, c3cPassword.toCharArray()) == C3C_BARE_ARRAY)
    check("C3C hex decrypts to garbage (not null) with the wrong password",
        FbStateFormat.decryptC3cHex(c3cHex, "nope-nope-nope".toCharArray()) != C3C_BARE_ARRAY)
    check("decryptAndParse of C3C hex returns the cookies",
        (FbStateFormat.decryptAndParse(
            FbStateFormat.DetectedKind.C3C_HEX, c3cHex, c3cPassword.toCharArray()
        ) as? FbStateFormat.ImportOutcome.Ok)?.cookies?.size == 2)
    check("decryptAndParse of C3C hex with the wrong password reports Invalid password.",
        (FbStateFormat.decryptAndParse(
            FbStateFormat.DetectedKind.C3C_HEX, c3cHex, "nope-nope-nope".toCharArray()
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_PASSWORD)
    check("decryptC3cHex rejects odd-length hex",
        FbStateFormat.decryptC3cHex("abc", c3cPassword.toCharArray()) == null)
    check("decryptC3cHex rejects non-hex text",
        FbStateFormat.decryptC3cHex("zzzz", c3cPassword.toCharArray()) == null)
    check("uppercase hex also decrypts (C3C regex allows A-F)",
        FbStateFormat.decryptC3cHex(c3cHex.uppercase(), c3cPassword.toCharArray()) == C3C_BARE_ARRAY)

    // --- Merge vs replace (pure function, §4.3-Q5) ---
    val existing = listOf(
        Cookie("c_user", "1000-old", ".facebook.com", "/", false, null, null, null),
        Cookie("sb", "keep-me", ".facebook.com", "/", false, null, null, null)
    )
    val imported = listOf(
        Cookie("c_user", "1000-new", ".facebook.com", "/", false, null, null, null),
        Cookie("xs", "42%3A...", ".facebook.com", "/", false, null, null, null)
    )
    val merged = FbStateFormat.applyImport(existing, imported, replaceAll = false)
    check("merge overwrites the same key",
        merged.first { it.key == "c_user" }.value == "1000-new")
    check("merge keeps untouched cookies (sb survives)",
        merged.any { it.key == "sb" && it.value == "keep-me" })
    check("merge appends new cookies",
        merged.any { it.key == "xs" })
    check("merge keeps the order: existing first, new appended",
        merged.map { it.key } == listOf("c_user", "sb", "xs"))
    val replaced = FbStateFormat.applyImport(existing, imported, replaceAll = true)
    check("replace-all discards the current cookies",
        replaced == imported && replaced.none { it.key == "sb" })
    check("merge is path-sensitive (same key, other path is a second cookie)",
        FbStateFormat.applyImport(
            listOf(Cookie("x", "1", ".facebook.com", "/", false, null, null, null)),
            listOf(Cookie("x", "2", ".facebook.com", "/other", false, null, null, null)),
            replaceAll = false
        ).size == 2)

    // --- Validation rejections ---
    check("empty textarea reports Please input FBState data first.",
        (FbStateFormat.parseImportText("   ")
            as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_EMPTY)
    check("non-array JSON reports Invalid FBState data.",
        (FbStateFormat.parseImportText("{\"foo\": 1}")
            as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("a bare JSON string reports Invalid FBState data.",
        (FbStateFormat.parseImportText("\"hello\"")
            as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("malformed JSON reports Invalid FBState data.",
        (FbStateFormat.parseImportText("{not json")
            as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("garbage text reports Invalid FBState data.",
        (FbStateFormat.parseImportText("hello world, not fbstate")
            as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("garbage text detects as INVALID",
        FbStateFormat.detect("hello world, not fbstate") == FbStateFormat.DetectedKind.INVALID)
    check("blank text detects as EMPTY",
        FbStateFormat.detect("  \n ") == FbStateFormat.DetectedKind.EMPTY)
    check("a JSON wrapper with version 2 reports the newer-version error",
        (FbStateFormat.parseImportText(
            """{"format":"nova-fbstate","version":2,"exportedAt":"2026-11-24T15:00:00.000Z","cookies":[]}"""
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_NEWER_VERSION)
    check("a wrapper without a version reports Invalid FBState data.",
        (FbStateFormat.parseImportText(
            """{"format":"nova-fbstate","cookies":[]}"""
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("a wrapper whose cookies is not an array reports Invalid FBState data.",
        (FbStateFormat.parseImportText(
            """{"format":"nova-fbstate","version":1,"cookies":{}}"""
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("an object with another format reports Invalid FBState data.",
        (FbStateFormat.parseImportText(
            """{"format":"something-else","version":1,"cookies":[]}"""
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)
    check("base64 of invalid JSON reports Invalid FBState data.",
        (FbStateFormat.parseImportText(
            FbStateFormat.encodeBase64("{\"foo\": 1}")
        ) as? FbStateFormat.ImportOutcome.Error)?.message == FbStateFormat.ERR_INVALID)

    // Per-item skips: bad domain, lookalike domain, missing key/value,
    // non-object element — all skipped AND counted.
    val mixed = """[
        {"key": "c_user", "value": "1000...", "domain": ".facebook.com", "path": "/"},
        {"key": "evil", "value": "x", "domain": "evil.com", "path": "/"},
        {"key": "lookalike", "value": "x", "domain": "notfacebook.com", "path": "/"},
        {"key": "sub", "value": "x", "domain": "www.facebook.com", "path": "/"},
        {"key": "", "value": "x", "domain": ".facebook.com"},
        {"value": "x", "domain": ".facebook.com"},
        {"key": "novalue", "domain": ".facebook.com"},
        {"key": "emptyvalue", "value": "", "domain": ".facebook.com"},
        "not-an-object",
        {"key": "nodomain", "value": "x"}
    ]"""
    val mixedOut = FbStateFormat.parseImportText(mixed) as? FbStateFormat.ImportOutcome.Ok
    check("per-item skips keep the good cookies",
        mixedOut?.cookies?.map { it.key } == listOf("c_user", "sub", "emptyvalue", "nodomain"))
    check("per-item skips count the bad items (evil, lookalike, empty key, no key, no value, string)",
        mixedOut?.skipped == 6)
    check("an item without a domain defaults to .facebook.com",
        mixedOut?.cookies?.first { it.key == "nodomain" }?.domain == ".facebook.com")
    check("an item with an empty-string value is accepted",
        mixedOut?.cookies?.first { it.key == "emptyvalue" }?.value == "")
    check("an item without a path defaults to /",
        mixedOut?.cookies?.first { it.key == "nodomain" }?.path == "/")

    // --- CookieManager string parsing (§4.2) ---
    val header = FbStateFormat.parseCookieHeader("c_user=1000...; xs=42%3A...; a=b=c")
    check("cookie header splits into name/value pairs",
        header == listOf("c_user" to "1000...", "xs" to "42%3A...", "a" to "b=c"))
    check("a value containing '=' keeps the rest after the first '='",
        FbStateFormat.parseCookieHeader("k=a=b=c") == listOf("k" to "a=b=c"))
    check("a pair with no '=' is skipped",
        FbStateFormat.parseCookieHeader("c_user=1000...; novalue; xs=1") ==
            listOf("c_user" to "1000...", "xs" to "1"))
    check("null/blank cookie header parses to empty",
        FbStateFormat.parseCookieHeader(null).isEmpty() &&
            FbStateFormat.parseCookieHeader("  ").isEmpty())

    // Export merge: three URLs, first occurrence of a name wins.
    val mergedExport = FbStateFormat.mergeExportCookies(
        listOf("a" to "www-a", "b" to "www-b"),
        listOf("b" to "bare-b", "c" to "bare-c"),
        listOf("a" to "m-a", "d" to "m-d")
    )
    check("export merge dedupes by name, first URL wins",
        mergedExport.map { it.key to it.value } == listOf(
            "a" to "www-a", "b" to "www-b", "c" to "bare-c", "d" to "m-d"
        ))
    check("export merge records unknown flags as null",
        mergedExport.all { it.secure == null && it.httpOnly == null && it.expires == null })
    check("export merge uses the canonical domain for every cookie",
        mergedExport.all { it.domain == ".facebook.com" })

    // --- setCookie string (§4.2 / §4.3-Q2) ---
    val nowMs = 1_794_000_000_000L // fixed instant, deterministic test
    val plain = FbStateFormat.buildSetCookieString(
        Cookie("c_user", "1000...", ".facebook.com", "/", false, null, null, null), nowMs
    )
    check("setCookie string carries the canonical domain",
        plain.contains("Domain=.facebook.com"))
    check("setCookie string carries Path=/", plain.contains("Path=/"))
    check("setCookie string falls back to +365 days when expiry is unknown",
        plain.contains("Expires=" + rfc1123(nowMs + FbStateFormat.ONE_YEAR_MS)))
    check("setCookie string has no Secure when secure is unknown",
        !plain.contains("Secure"))
    val secure = FbStateFormat.buildSetCookieString(
        Cookie("c_user", "1000...", ".facebook.com", "/", false, true, null, null), nowMs
    )
    check("setCookie string honours secure: true", secure.contains("; Secure"))
    val httpOnly = FbStateFormat.buildSetCookieString(
        Cookie("c_user", "1000...", ".facebook.com", "/", false, true, true, null), nowMs
    )
    check("setCookie string NEVER emits HttpOnly (WebView drops it)",
        !httpOnly.contains("HttpOnly"))
    val futureSec = nowMs / 1000 + 30L * 24 * 60 * 60
    val withExpiry = FbStateFormat.buildSetCookieString(
        Cookie("c_user", "1000...", ".facebook.com", "/", false, null, null, futureSec), nowMs
    )
    check("setCookie string honours a valid future expiry",
        withExpiry.contains("Expires=" + rfc1123(futureSec * 1000)))
    val pastSec = nowMs / 1000 - 1000
    val withPastExpiry = FbStateFormat.buildSetCookieString(
        Cookie("c_user", "1000...", ".facebook.com", "/", false, null, null, pastSec), nowMs
    )
    check("setCookie string falls back to +365 days for a past expiry",
        withPastExpiry.contains("Expires=" + rfc1123(nowMs + FbStateFormat.ONE_YEAR_MS)))

    // --- detect() kinds ---
    check("detect: NOVA envelope", FbStateFormat.detect(envelope) == FbStateFormat.DetectedKind.NOVA_ENCRYPTED)
    check("detect: JSON wrapper", FbStateFormat.detect(wrapper) == FbStateFormat.DetectedKind.JSON)
    check("detect: JSON bare array", FbStateFormat.detect(C3C_BARE_ARRAY) == FbStateFormat.DetectedKind.JSON)
    check("detect: base64", FbStateFormat.detect(b64Wrapper) == FbStateFormat.DetectedKind.BASE64)
    check("detect: hex", FbStateFormat.detect(c3cHex) == FbStateFormat.DetectedKind.C3C_HEX)
    check("detect: odd-length hex is not a ciphertext",
        FbStateFormat.detect("abc") == FbStateFormat.DetectedKind.INVALID)
    check("detect: non-hex text is not a ciphertext",
        FbStateFormat.detect("zzzz") == FbStateFormat.DetectedKind.INVALID)

    // --- hex helpers ---
    check("bytesToHex/hexToBytes round-trip",
        FbStateFormat.hexToBytes(FbStateFormat.bytesToHex(byteArrayOf(0, 1, 127, -1, -128)))
            ?.contentEquals(byteArrayOf(0, 1, 127, -1, -128)) == true)
    check("hexToBytes rejects odd length", FbStateFormat.hexToBytes("abc") == null)
    check("hexToBytes rejects non-hex", FbStateFormat.hexToBytes("zz") == null)
    check("isHexCiphertext accepts mixed-case hex",
        FbStateFormat.isHexCiphertext("aB12Cd34"))
    check("isHexCiphertext rejects a 1-char string",
        !FbStateFormat.isHexCiphertext("a"))

    println("FbStateTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
