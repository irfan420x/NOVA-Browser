package com.nova.browser.extensions

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM unit tests for the `.novaext` zip installer ([NovaExtZip]) — plain
 * main() + check(), no framework, mirroring tests/ExtensionsTest.kt.
 * Listed in tests/run-tests.sh. Standalone run:
 *   kotlinc ... NovaExtZip.kt Userscript.kt ExtensionRuntime.kt UrlUtils.kt ZipInstallTest.kt
 *   java -cp ... com.nova.browser.extensions.ZipInstallTestKt
 * Exits non-zero on any failure. No real cookie/secret values anywhere —
 * placeholders only.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private fun failureReason(result: NovaExtZip.ZipInstallResult): String? =
    (result as? NovaExtZip.ZipInstallResult.Failure)?.reason

private fun isReady(result: NovaExtZip.ZipInstallResult): Boolean =
    result is NovaExtZip.ZipInstallResult.Ready

private fun readyScript(result: NovaExtZip.ZipInstallResult): Userscript? =
    (result as? NovaExtZip.ZipInstallResult.Ready)?.script

/** Build a zip in memory from (name -> bytes) entries, in order. */
private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
        for ((name, bytes) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(bytes)
            zos.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun manifestJson(
    name: String? = "Force Dark Example",
    version: String? = "1.0",
    description: String? = "Forces a dark background on example.com pages.",
    matches: List<String>? = listOf("*://example.com/*", "*://*.example.com/*"),
    runAt: String? = "document-end",
    contentScript: String? = "main.js",
    icon: String? = null,
    permissions: List<String>? = null,
    manifestVersion: Int? = 1,
    extra: String? = null
): String {
    val sb = StringBuilder("{")
    if (manifestVersion != null) sb.append("\"manifest_version\": $manifestVersion, ")
    if (name != null) sb.append("\"name\": ${jsonStr(name)}, ")
    if (version != null) sb.append("\"version\": ${jsonStr(version)}, ")
    if (description != null) sb.append("\"description\": ${jsonStr(description)}, ")
    if (matches != null) {
        sb.append("\"matches\": [${matches.joinToString(", ") { jsonStr(it) }}], ")
    }
    if (runAt != null) sb.append("\"run-at\": ${jsonStr(runAt)}, ")
    if (contentScript != null) sb.append("\"content_script\": ${jsonStr(contentScript)}, ")
    if (icon != null) sb.append("\"icon\": ${jsonStr(icon)}, ")
    if (permissions != null) {
        sb.append("\"permissions\": [${permissions.joinToString(", ") { jsonStr(it) }}], ")
    }
    if (extra != null) sb.append("$extra, ")
    var text = sb.toString().trimEnd().removeSuffix(",") + "}"
    return text
}

private fun jsonStr(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private const val SCRIPT_BODY = "document.body.style.background = '#000';\n"

private fun validZip(
    manifest: String = manifestJson(),
    script: String = SCRIPT_BODY,
    scriptName: String = "main.js",
    iconBytes: ByteArray? = null
): ByteArray {
    val entries = mutableListOf(
        "manifest.json" to manifest.toByteArray(Charsets.UTF_8),
        scriptName to script.toByteArray(Charsets.UTF_8)
    )
    if (iconBytes != null) entries.add("icon.png" to iconBytes)
    return zipOf(*entries.toTypedArray())
}

private val PNG_BYTES: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        "fake-png-payload".toByteArray(Charsets.UTF_8)

fun main() {
    // --- Valid zip installs ---
    val valid = NovaExtZip.installFromZip(validZip())
    check("valid zip installs", isReady(valid))
    val script = readyScript(valid)
    check("valid zip id is the slugified name", script?.id == "force-dark-example")
    check("valid zip keeps the name", script?.name == "Force Dark Example")
    check("valid zip keeps the version", script?.version == "1.0")
    check("valid zip keeps the description", script?.description == "Forces a dark background on example.com pages.")
    check("valid zip keeps both matches", script?.matches == listOf("*://example.com/*", "*://*.example.com/*"))
    check("valid zip keeps document-end", script?.runAt == RunAt.DOCUMENT_END)
    check("valid zip script is enabled by default", script?.enabled == true)
    check("valid zip script is not built-in", script?.builtIn == false)
    check("valid zip without icon returns no icon bytes",
        (valid as? NovaExtZip.ZipInstallResult.Ready)?.iconBytes == null)
    check("valid zip without sensitive-pages does not want them",
        (valid as? NovaExtZip.ZipInstallResult.Ready)?.wantsSensitivePages == false)

    // Header synthesis round-trip: the persisted source parses back to
    // the manifest's metadata (UserscriptParser is the single source of
    // truth for metadata).
    val parsed = script?.let { UserscriptParser.parse(it.source) }
    check("synthesized source parses", parsed?.metadata != null)
    check("synthesized source round-trips the name", parsed?.metadata?.name == "Force Dark Example")
    check("synthesized source round-trips the version", parsed?.metadata?.version == "1.0")
    check("synthesized source round-trips the matches",
        parsed?.metadata?.matches == listOf("*://example.com/*", "*://*.example.com/*"))
    check("synthesized source round-trips document-end", parsed?.metadata?.runAt == RunAt.DOCUMENT_END)
    check("synthesized source starts with the canonical header",
        script?.source?.startsWith("// ==UserScript==\n// @name Force Dark Example\n") == true)
    check("synthesized source keeps the script body",
        script?.source?.endsWith(SCRIPT_BODY) == true)

    // --- Header synthesis mapping (manifest -> canonical header) ---
    val header = NovaExtZip.synthesizeHeader(
        NovaExtZip.Manifest(
            name = "My Script", version = "2.0-beta.1", description = "Does things",
            matches = listOf("*://a.example/*", "https://b.example/x/*"),
            runAt = "document-start", contentScript = "main.js",
            icon = "icon.png", permissions = listOf("sensitive-pages")
        )
    )
    val expectedHeader = "// ==UserScript==\n" +
        "// @name My Script\n" +
        "// @version 2.0-beta.1\n" +
        "// @description Does things\n" +
        "// @match *://a.example/*\n" +
        "// @match https://b.example/x/*\n" +
        "// @run-at document-start\n" +
        "// ==/UserScript=="
    check("header synthesis maps manifest to the canonical header", header == expectedHeader)
    val noDescHeader = NovaExtZip.synthesizeHeader(
        NovaExtZip.Manifest(
            name = "Plain", version = "1.0", description = "",
            matches = listOf("*://*/*"), runAt = "document-end",
            contentScript = "main.js", icon = null, permissions = emptyList()
        )
    )
    check("header synthesis omits an empty description",
        !noDescHeader.contains("@description") &&
            noDescHeader == "// ==UserScript==\n// @name Plain\n// @version 1.0\n" +
            "// @match *://*/*\n// @run-at document-end\n// ==/UserScript==")

    // document-start + sensitive-pages + icon all flow through.
    val full = NovaExtZip.installFromZip(
        validZip(
            manifest = manifestJson(runAt = "document-start", icon = "icon.png", permissions = listOf("sensitive-pages")),
            iconBytes = PNG_BYTES
        )
    )
    check("document-start zip installs", isReady(full))
    check("document-start is kept", readyScript(full)?.runAt == RunAt.DOCUMENT_START)
    check("sensitive-pages permission is reported for review",
        (full as? NovaExtZip.ZipInstallResult.Ready)?.wantsSensitivePages == true)
    check("valid icon bytes are returned",
        (full as? NovaExtZip.ZipInstallResult.Ready)?.iconBytes?.contentEquals(PNG_BYTES) == true)

    // Unknown manifest fields are ignored (forward compatibility).
    check("unknown manifest fields are ignored",
        isReady(NovaExtZip.installFromZip(validZip(manifest = manifestJson(extra = "\"future_field\": {\"x\": [1, 2, 3]}")))))
    // No description / no run-at: defaults apply.
    val minimal = NovaExtZip.installFromZip(
        validZip(manifest = manifestJson(description = null, runAt = null))
    )
    check("missing description defaults to empty", readyScript(minimal)?.description == "")
    check("missing run-at defaults to document-end", readyScript(minimal)?.runAt == RunAt.DOCUMENT_END)

    // --- §1.5 error table: each major rejection, exact string ---
    check("#1 not a zip (random bytes) is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip("hello, not a zip".toByteArray())) ==
            "That file is not a .novaext package — choose a .novaext file.")
    check("#1 empty file is rejected as not a package",
        failureReason(NovaExtZip.installFromZip(ByteArray(0))) ==
            "That file is not a .novaext package — choose a .novaext file.")
    check("#1 install never throws on garbage",
        NovaExtZip.installFromZip(byteArrayOf(0x50, 0x4B, 0x03)) is NovaExtZip.ZipInstallResult.Failure)

    // #2 oversize zip (incompressible random payload so the zip itself is > 512 KB).
    val random = java.util.Random(42)
    val bigPayload = ByteArray(600_000).also { random.nextBytes(it) }
    check("#2 zip larger than 512 KB is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(script = String(bigPayload, Charsets.ISO_8859_1)))) ==
            "The .novaext file is larger than 512 KB.")

    // #3 more than 8 entries.
    val manyEntries = mutableListOf<Pair<String, ByteArray>>(
        "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
        "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8)
    )
    for (i in 0 until 7) manyEntries.add("extra$i.js" to "x();".toByteArray())
    check("#3 more than 8 entries is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(zipOf(*manyEntries.toTypedArray()))) ==
            "The package has too many files (more than 8).")

    // #4 path traversal entry.
    check("#4 traversal entry is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                zipOf(
                    "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
                    "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8),
                    "../evil.js" to "x();".toByteArray()
                )
            )
        ) == "The package contains an unsafe file path \"../evil.js\".")
    check("#4 backslash entry is rejected",
        failureReason(
            NovaExtZip.installFromZip(
                zipOf(
                    "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
                    "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8),
                    "sub\\evil.js" to "x();".toByteArray()
                )
            )
        )?.startsWith("The package contains an unsafe file path") == true)

    // #5 unexpected file / subdirectory.
    check("#5 unexpected file is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                zipOf(
                    "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
                    "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8),
                    "README.txt" to "hi".toByteArray()
                )
            )
        ) == "The package contains an unexpected file \"README.txt\" — only manifest.json, one .js script and icon.png are allowed.")
    check("#5 nested file is rejected (no subdirectories)",
        failureReason(
            NovaExtZip.installFromZip(
                zipOf(
                    "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
                    "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8),
                    "sub/main.js" to "x();".toByteArray()
                )
            )
        )?.contains("unexpected file \"sub/main.js\"") == true)

    // #6 duplicate entry name (case-insensitive).
    check("#6 duplicate entry is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                zipOf(
                    "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
                    "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8),
                    "Main.js" to "x();".toByteArray()
                )
            )
        ) == "The package contains the file \"Main.js\" more than once.")

    // #7 manifest.json missing.
    check("#7 missing manifest is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(zipOf("main.js" to SCRIPT_BODY.toByteArray()))) ==
            "manifest.json is missing from the package.")

    // #8 manifest.json > 16 KB (oversize via an unknown field — the
    // manifest stays otherwise valid).
    val hugeManifest = manifestJson(extra = "\"pad\": \"${"x".repeat(17_000)}\"")
    check("#8 manifest larger than 16 KB is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = hugeManifest))) ==
            "manifest.json is larger than 16 KB.")

    // #9 manifest.json is not valid JSON.
    check("#9 invalid JSON is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = "{not json"))) ==
            "manifest.json is not valid JSON.")
    check("#9 trailing garbage after the JSON object is rejected",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson() + " garbage"))) ==
            "manifest.json is not valid JSON.")

    // #10 manifest_version missing or not 1.
    check("#10 manifest_version 2 is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(manifestVersion = 2)))) ==
            "manifest_version must be 1.")
    check("#10 missing manifest_version is rejected",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(manifestVersion = null)))) ==
            "manifest_version must be 1.")

    // #11 name missing / empty / > 100 chars / multi-line.
    check("#11 missing name is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(name = null)))) ==
            "The manifest needs a \"name\" (1–100 characters, one line).")
    check("#11 name over 100 chars is rejected",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(name = "x".repeat(101))))) ==
            "The manifest needs a \"name\" (1–100 characters, one line).")

    // #12 version missing / malformed.
    check("#12 missing version uses the absent-version string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(version = null)))) ==
            "The manifest needs a \"version\" (like 1.0).")
    check("#12 malformed version is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(version = "v1")))) ==
            "The version \"v1\" is not valid — use numbers like 1.0 or 1.2.3.")
    check("#12 three-part and pre-release versions are accepted",
        isReady(NovaExtZip.installFromZip(validZip(manifest = manifestJson(version = "2.0-beta.1")))))

    // #13 description too long.
    check("#13 description over 500 chars is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(validZip(manifest = manifestJson(description = "x".repeat(501))))
        ) == "The description is too long — keep it under 500 characters on one line.")

    // #14 matches missing / empty / > 20.
    check("#14 missing matches is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(matches = null)))) ==
            "The manifest needs at least one \"matches\" entry.")
    check("#14 empty matches is rejected",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(matches = emptyList())))) ==
            "The manifest needs at least one \"matches\" entry.")
    check("#14 more than 20 matches is rejected",
        failureReason(
            NovaExtZip.installFromZip(
                validZip(manifest = manifestJson(matches = (0 until 21).map { "*://host$it.example/*" }))
            )
        ) == "The manifest needs at least one \"matches\" entry.")

    // #15 a matches entry fails the shape check.
    check("#15 pattern without :// is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(validZip(manifest = manifestJson(matches = listOf("example.com/*"))))
        ) == "\"example.com/*\" is not a valid match pattern — use a shape like *://example.com/*.")
    check("#15 ftp scheme is rejected",
        failureReason(
            NovaExtZip.installFromZip(validZip(manifest = manifestJson(matches = listOf("ftp://example.com/*"))))
        ) == "\"ftp://example.com/*\" is not a valid match pattern — use a shape like *://example.com/*.")
    check("#15 pattern with no path is valid (read as /*)",
        isReady(NovaExtZip.installFromZip(validZip(manifest = manifestJson(matches = listOf("*://example.com"))))))

    // #16 run-at present and not document-start / document-end.
    check("#16 bad run-at is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(runAt = "document-idle")))) ==
            "\"run-at\" must be \"document-start\" or \"document-end\".")

    // #17 content_script missing / empty / contains a path.
    check("#17 missing content_script is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(contentScript = null)))) ==
            "The manifest needs a \"content_script\" file (for example \"main.js\").")
    check("#17 content_script with a path is rejected",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(contentScript = "sub/main.js")))) ==
            "The manifest needs a \"content_script\" file (for example \"main.js\").")

    // #18 the named script file is not in the zip.
    check("#18 named script missing is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(contentScript = "other.js")))) ==
            "The script file \"other.js\" is missing from the package.")

    // #19 more than one .js file.
    check("#19 second script file is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                zipOf(
                    "manifest.json" to manifestJson().toByteArray(Charsets.UTF_8),
                    "main.js" to SCRIPT_BODY.toByteArray(Charsets.UTF_8),
                    "extra.js" to "x();".toByteArray()
                )
            )
        ) == "The package has more than one script file — one .novaext package installs one script.")

    // #20 script carries its own // ==UserScript== header.
    check("#20 script with its own header is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                validZip(script = "// ==UserScript==\n// @name X\n// ==/UserScript==\nx();\n")
            )
        ) == "The script file contains a // ==UserScript== header — remove it; the manifest is the source of truth.")

    // #21 script > 200 KB.
    val bigScript = "x".repeat(MAX_SCRIPT_SIZE_BYTES + 1)
    check("#21 script larger than 200 KB is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(script = bigScript))) ==
            "Script is larger than 200 KB and was rejected")

    // #22 icon present and not "icon.png".
    check("#22 icon field naming another file is rejected with the exact string",
        failureReason(NovaExtZip.installFromZip(validZip(manifest = manifestJson(icon = "logo.png")))) ==
            "The \"icon\" must be \"icon.png\".")

    // #23 icon.png present and not a PNG.
    check("#23 non-PNG icon is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                validZip(manifest = manifestJson(icon = "icon.png"), iconBytes = "not a png".toByteArray())
            )
        ) == "icon.png is not a PNG image.")

    // #24 icon.png > 128 KB.
    val bigIcon = PNG_BYTES + ByteArray(NovaExtZip.MAX_ICON_SIZE_BYTES + 1 - PNG_BYTES.size) { 0 }
    check("#24 icon larger than 128 KB is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(
                validZip(manifest = manifestJson(icon = "icon.png"), iconBytes = bigIcon)
            )
        ) == "The icon is larger than 128 KB.")

    // #25 permissions contains a disallowed value.
    check("#25 disallowed permission is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(validZip(manifest = manifestJson(permissions = listOf("cookies"))))
        ) == "The permission \"cookies\" is not supported in v2.4 — the only permission a package may declare is \"sensitive-pages\".")
    check("#25 the only allowed permission is sensitive-pages",
        isReady(NovaExtZip.installFromZip(validZip(manifest = manifestJson(permissions = listOf("sensitive-pages"))))))

    // #26–28 via installFromText (same words as the text path).
    check("#27 name colliding with a built-in is rejected",
        failureReason(
            NovaExtZip.installFromZip(validZip(manifest = manifestJson(name = "YouTube Ad Skipper")))
        ) == "A built-in script already uses the name \"YouTube Ad Skipper\"")
    check("#28 duplicate id is rejected with the exact string",
        failureReason(
            NovaExtZip.installFromZip(validZip(), existingIds = setOf("force-dark-example"))
        ) == "A script with the id \"force-dark-example\" is already installed — delete it first to reinstall")
    check("#28 a different id installs fine alongside existing ids",
        isReady(NovaExtZip.installFromZip(validZip(), existingIds = setOf("some-other-script"))))

    // Match-pattern shape helper, direct unit checks.
    check("match shape: * scheme is valid", NovaExtZip.isValidMatchPattern("*://example.com/*"))
    check("match shape: https scheme is valid", NovaExtZip.isValidMatchPattern("https://example.com/news/*"))
    check("match shape: no :// is invalid", !NovaExtZip.isValidMatchPattern("example.com/*"))
    check("match shape: ftp is invalid", !NovaExtZip.isValidMatchPattern("ftp://example.com/*"))
    check("match shape: empty host is invalid", !NovaExtZip.isValidMatchPattern("*:///*"))
    check("match shape: whitespace is invalid", !NovaExtZip.isValidMatchPattern("*://exa mple.com/*"))
    check("match shape: no path is valid", NovaExtZip.isValidMatchPattern("*://example.com"))

    // The installed script actually matches its declared scope (reuse
    // scriptsForUrl — injection itself is unchanged, this only proves
    // the synthesized script carries the manifest scope).
    val installed = readyScript(valid)
    check("installed script matches its @match host",
        installed?.matchesUrl("https://example.com/page") == true)
    check("installed script does not match other hosts",
        installed?.matchesUrl("https://other.org/") == false)

    // --- The shipped example package (Lane D) ---
    // extensions/example-dark-reader.novaext must install cleanly
    // through the same path — this proves the file users actually pick
    // up is a valid package, not just that in-memory zips pass.
    // Resolved relative to the repo root when run via run-tests.sh, or
    // via the absolute workspace path for a standalone run elsewhere.
    val shippedCandidates = listOf(
        File("extensions/example-dark-reader.novaext"),
        File((System.getProperty("user.home") ?: "") + "/workspace/nova-browser/extensions/example-dark-reader.novaext")
    )
    val shippedFile = shippedCandidates.firstOrNull { it.isFile }
    check("shipped example .novaext exists in the repo", shippedFile != null)
    if (shippedFile != null) {
        val shippedBytes = shippedFile.readBytes()
        check("shipped example .novaext is non-empty and under the 512 KB cap",
            shippedBytes.isNotEmpty() && shippedBytes.size <= 512 * 1024)
        val shipped = NovaExtZip.installFromZip(shippedBytes)
        check("shipped example .novaext installs", isReady(shipped))
        val shippedScript = readyScript(shipped)
        check("shipped example id is example-dark-reader", shippedScript?.id == "example-dark-reader")
        check("shipped example keeps its name", shippedScript?.name == "Example Dark Reader")
        check("shipped example matches example.com",
            shippedScript?.matchesUrl("https://example.com/") == true)
    }

    println("ZipInstallTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
