package com.nova.browser.extensions

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * `.novaext` package installer (NOVA Browser v2.4, NOVA-EXTENSIONS.md §1).
 *
 * One zip = one [Userscript]. The zip is validated fully — in the exact
 * §1.5 order, with the exact user-facing strings from the §1.5 table —
 * BEFORE anything is written. A validated package is mapped onto the
 * existing model by synthesizing a canonical `// ==UserScript==` header
 * from the manifest and delegating to [ExtensionRuntime.installFromText]
 * (which re-checks size/id/duplicate, checks 21/26–28) and then to
 * [com.nova.browser.data.ExtensionStore.install] (called by the UI, not
 * here — this object never touches the store or the filesystem, so a
 * failed install leaves no partial script, index entry, or icon file).
 *
 * Pure Kotlin + `java.util.zip` — no Android imports, JVM-testable.
 *
 * JSON: the manifest is parsed by the minimal parser below, NOT by
 * `org.json`, on purpose. The JVM test runner's runtime classpath is
 * stdlib-only (android.jar is compile-only there), so an `org.json`
 * dependency would compile but throw ClassNotFoundException under test.
 * The parser accepts exactly the JSON shapes the manifest schema needs
 * (objects, arrays, strings with escapes, numbers, booleans, null) and
 * rejects malformed input, trailing garbage, and raw control characters
 * in strings — the same verdicts `org.json` would give for these inputs.
 *
 * No network: there is deliberately no URL-based install path here.
 * Local zip bytes only.
 */
object NovaExtZip {

    /** Whole-zip cap (§1.1). */
    const val MAX_ZIP_SIZE_BYTES = 512 * 1024

    /** manifest.json cap (§1.1). */
    const val MAX_MANIFEST_SIZE_BYTES = 16 * 1024

    /** icon.png cap (§1.1). */
    const val MAX_ICON_SIZE_BYTES = 128 * 1024

    /** Entry-count cap — zip-bomb backstop (§1.1). */
    const val MAX_ENTRIES = 8

    /** Per-entry decompressed-read cap while collecting entries. Above
     *  every individual cap (script 200 KB, icon 128 KB, manifest 16 KB),
     *  so a truncated read still trips the right per-file check. */
    private const val MAX_ENTRY_READ_BYTES = 256 * 1024

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private val VERSION_REGEX =
        Regex("""^[0-9]+\.[0-9]+(\.[0-9]+)?(-[A-Za-z0-9.-]+)?$""")

    /** The only permission a v2.4 package may declare (§1.4). */
    const val ALLOWED_PERMISSION = "sensitive-pages"

    sealed class ZipInstallResult {
        /** Validated and ready for the user-confirm review dialog.
         *  Nothing has been written yet. [iconBytes] is the validated
         *  `icon.png` (null when absent); the UI stores it at
         *  `extensions/icons/<id>.png` only after a successful store
         *  install. [wantsSensitivePages] is true when `permissions`
         *  contains `"sensitive-pages"` — review-only, it grants nothing. */
        data class Ready(
            val script: Userscript,
            val iconBytes: ByteArray?,
            val wantsSensitivePages: Boolean
        ) : ZipInstallResult()

        /** Validation failed — [reason] is the exact §1.5 string to show. */
        data class Failure(val reason: String) : ZipInstallResult()
    }

    /** Validated manifest fields (internal — the synthesized header is
     *  the public mapping). */
    data class Manifest(
        val name: String,
        val version: String,
        val description: String,
        val matches: List<String>,
        val runAt: String, // "document-start" | "document-end"
        val contentScript: String,
        val icon: String?,
        val permissions: List<String>
    )

    /**
     * Validate [zipBytes] per §1.5 (checks in table order) and, on success,
     * return the synthesized [Userscript] ready for review. Never throws —
     * every failure is a [ZipInstallResult.Failure] with the exact §1.5
     * string. [existingIds] is the set of already-installed script ids
     * (check 28).
     */
    fun installFromZip(
        zipBytes: ByteArray,
        existingIds: Set<String> = emptySet()
    ): ZipInstallResult {
        return try {
            installFromZipOrThrow(zipBytes, existingIds)
        } catch (_: Exception) {
            // Defence in depth: validation must never crash the caller.
            // (Expected failures return Failure above; this only catches
            // something the checks missed.)
            ZipInstallResult.Failure(
                "That file is not a .novaext package — choose a .novaext file."
            )
        }
    }

    private fun installFromZipOrThrow(
        zipBytes: ByteArray,
        existingIds: Set<String>
    ): ZipInstallResult {
        // #1 — readable ZIP (bad magic, corrupt, encrypted). The magic
        // check runs before the size check so a corrupt oversize file
        // reports #1, matching the table order.
        if (!looksLikeZip(zipBytes)) {
            return ZipInstallResult.Failure(
                "That file is not a .novaext package — choose a .novaext file."
            )
        }
        // #2 — whole-zip size.
        if (zipBytes.size > MAX_ZIP_SIZE_BYTES) {
            return ZipInstallResult.Failure("The .novaext file is larger than 512 KB.")
        }
        // Collect entries (names + bounded contents). A read failure here
        // (corrupt data, encrypted entry, bad CRC) is #1.
        val entries: List<ZipEntryData>
        try {
            entries = readEntries(zipBytes)
        } catch (_: ZipException) {
            return ZipInstallResult.Failure(
                "That file is not a .novaext package — choose a .novaext file."
            )
        } catch (_: IOException) {
            return ZipInstallResult.Failure(
                "That file is not a .novaext package — choose a .novaext file."
            )
        }
        // #3 — entry count.
        if (entries.size > MAX_ENTRIES) {
            return ZipInstallResult.Failure("The package has too many files (more than 8).")
        }
        // #4 — unsafe entry names.
        for (entry in entries) {
            if (isUnsafeEntryName(entry.name)) {
                return ZipInstallResult.Failure(
                    "The package contains an unsafe file path \"${entry.name}\"."
                )
            }
        }
        // #5 — allowlist (manifest.json, one .js, icon.png; no directories).
        for (entry in entries) {
            if (!isAllowedEntryName(entry.name)) {
                return ZipInstallResult.Failure(
                    "The package contains an unexpected file \"${entry.name}\" — only manifest.json, one .js script and icon.png are allowed."
                )
            }
        }
        // #6 — duplicate entry names (case-insensitive).
        val seenLower = mutableSetOf<String>()
        for (entry in entries) {
            val lower = entry.name.lowercase()
            if (!seenLower.add(lower)) {
                return ZipInstallResult.Failure(
                    "The package contains the file \"${entry.name}\" more than once."
                )
            }
        }
        val byName = entries.associateBy { it.name }
        // #7 — manifest.json missing.
        val manifestEntry = byName["manifest.json"]
            ?: return ZipInstallResult.Failure("manifest.json is missing from the package.")
        // #8 — manifest.json > 16 KB.
        if (manifestEntry.bytes.size > MAX_MANIFEST_SIZE_BYTES) {
            return ZipInstallResult.Failure("manifest.json is larger than 16 KB.")
        }
        // #9 — manifest.json is not valid JSON.
        val manifestText = try {
            manifestEntry.bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return ZipInstallResult.Failure("manifest.json is not valid JSON.")
        }
        val manifestJson = JsonParser.parse(manifestText)
            ?: return ZipInstallResult.Failure("manifest.json is not valid JSON.")
        val manifestObj = (manifestJson as? JsonValue.JObj)
            ?: return ZipInstallResult.Failure("manifest.json is not valid JSON.")
        // #10 — manifest_version missing or not 1.
        val versionField = manifestObj.fields["manifest_version"]
        val manifestVersion = (versionField as? JsonValue.JNum)?.raw
        if (manifestVersion != "1") {
            return ZipInstallResult.Failure("manifest_version must be 1.")
        }
        // #11 — name missing / empty / > 100 chars / multi-line.
        val name = (manifestObj.fields["name"] as? JsonValue.JStr)?.value
        if (name == null || name.isEmpty() || name.length > 100 || !isSingleLine(name)) {
            return ZipInstallResult.Failure(
                "The manifest needs a \"name\" (1–100 characters, one line)."
            )
        }
        // #12 — version missing / malformed.
        val versionRaw = manifestObj.fields["version"]
        if (versionRaw == null) {
            return ZipInstallResult.Failure("The manifest needs a \"version\" (like 1.0).")
        }
        val version = (versionRaw as? JsonValue.JStr)?.value
        if (version == null || !VERSION_REGEX.matches(version)) {
            val shown = version ?: versionRaw.toJsonText()
            return ZipInstallResult.Failure(
                "The version \"$shown\" is not valid — use numbers like 1.0 or 1.2.3."
            )
        }
        // #13 — description > 500 chars / multi-line (wrong type counts too).
        val descriptionRaw = manifestObj.fields["description"]
        val description = when (descriptionRaw) {
            null -> ""
            is JsonValue.JStr -> descriptionRaw.value
            else -> return ZipInstallResult.Failure(
                "The description is too long — keep it under 500 characters on one line."
            )
        }
        if (description.length > 500 || !isSingleLine(description)) {
            return ZipInstallResult.Failure(
                "The description is too long — keep it under 500 characters on one line."
            )
        }
        // #14 — matches missing / empty / > 20 entries.
        val matchesRaw = manifestObj.fields["matches"]
        val matches = when (matchesRaw) {
            is JsonValue.JArr -> matchesRaw.items.mapNotNull { (it as? JsonValue.JStr)?.value }
                .takeIf { it.size == matchesRaw.items.size }
            else -> null
        }
        if (matches == null || matches.isEmpty() || matches.size > 20) {
            return ZipInstallResult.Failure("The manifest needs at least one \"matches\" entry.")
        }
        // #15 — a matches entry fails the shape check.
        for (pattern in matches) {
            if (!isValidMatchPattern(pattern)) {
                return ZipInstallResult.Failure(
                    "\"$pattern\" is not a valid match pattern — use a shape like *://example.com/*."
                )
            }
        }
        // #16 — run-at present and not document-start / document-end.
        val runAtRaw = manifestObj.fields["run-at"]
        val runAt = when (runAtRaw) {
            null -> "document-end"
            is JsonValue.JStr -> runAtRaw.value
            else -> return ZipInstallResult.Failure(
                "\"run-at\" must be \"document-start\" or \"document-end\"."
            )
        }
        if (runAt != "document-start" && runAt != "document-end") {
            return ZipInstallResult.Failure("\"run-at\" must be \"document-start\" or \"document-end\".")
        }
        // #17 — content_script missing / empty / contains a path.
        val contentScript = (manifestObj.fields["content_script"] as? JsonValue.JStr)?.value
        if (contentScript.isNullOrEmpty() || '/' in contentScript ||
            '\\' in contentScript || contentScript == ".." || contentScript.startsWith("../")
        ) {
            return ZipInstallResult.Failure(
                "The manifest needs a \"content_script\" file (for example \"main.js\")."
            )
        }
        // #18 — the named script file is not in the zip.
        val scriptEntry = byName[contentScript]
            ?: return ZipInstallResult.Failure(
                "The script file \"$contentScript\" is missing from the package."
            )
        // #19 — more than one .js file.
        val jsCount = entries.count { it.name.endsWith(".js") }
        if (jsCount > 1) {
            return ZipInstallResult.Failure(
                "The package has more than one script file — one .novaext package installs one script."
            )
        }
        val scriptBytes = scriptEntry.bytes
        val scriptText = scriptBytes.toString(Charsets.UTF_8)
        // #20 — script carries its own // ==UserScript== header (the
        // manifest is the single source of truth).
        if (scriptText.lines().any { it.trim() == "// ==UserScript==" }) {
            return ZipInstallResult.Failure(
                "The script file contains a // ==UserScript== header — remove it; the manifest is the source of truth."
            )
        }
        // #21 — script size > 200 KB (same string as installFromText).
        if (scriptBytes.size > MAX_SCRIPT_SIZE_BYTES) {
            return ZipInstallResult.Failure("Script is larger than 200 KB and was rejected")
        }
        // #22 — icon present and not "icon.png".
        val iconRaw = manifestObj.fields["icon"]
        val iconName = when (iconRaw) {
            null -> null
            is JsonValue.JStr -> iconRaw.value
            else -> return ZipInstallResult.Failure("The \"icon\" must be \"icon.png\".")
        }
        if (iconName != null && iconName != "icon.png") {
            return ZipInstallResult.Failure("The \"icon\" must be \"icon.png\".")
        }
        // #23 / #24 — icon.png present: PNG magic, then size.
        val iconEntry = byName["icon.png"]
        if (iconEntry != null) {
            val iconBytes = iconEntry.bytes
            if (iconBytes.size < PNG_MAGIC.size ||
                !iconBytes.copyOf(PNG_MAGIC.size).contentEquals(PNG_MAGIC)
            ) {
                return ZipInstallResult.Failure("icon.png is not a PNG image.")
            }
            if (iconBytes.size > MAX_ICON_SIZE_BYTES) {
                return ZipInstallResult.Failure("The icon is larger than 128 KB.")
            }
        }
        // #25 — permissions contains a disallowed value (at most 5 entries;
        // the only allowed value is "sensitive-pages").
        val permissionsRaw = manifestObj.fields["permissions"]
        val permissions: List<String> = when (permissionsRaw) {
            null -> emptyList()
            is JsonValue.JArr -> {
                val values = permissionsRaw.items.map { it as? JsonValue.JStr }
                if (values.any { it == null }) {
                    return ZipInstallResult.Failure(
                        "The permission \"${permissionsRaw.toJsonText()}\" is not supported in v2.4 — the only permission a package may declare is \"sensitive-pages\"."
                    )
                }
                values.map { it!!.value }
            }
            else -> return ZipInstallResult.Failure(
                "The permission \"${permissionsRaw.toJsonText()}\" is not supported in v2.4 — the only permission a package may declare is \"sensitive-pages\"."
            )
        }
        for ((index, permission) in permissions.withIndex()) {
            // SPEC GAP (no exact string in the §1.5 table for "> 5
            // entries"): the 6th+ entry is reported with the check-25
            // string, naming that entry.
            if (index >= 5 || permission != ALLOWED_PERMISSION) {
                return ZipInstallResult.Failure(
                    "The permission \"$permission\" is not supported in v2.4 — the only permission a package may declare is \"sensitive-pages\"."
                )
            }
        }
        // Adapter (§1.6): synthesize the canonical header, then reuse the
        // existing install path unchanged — installFromText re-checks
        // size (21) and performs checks 26–28 in the same words.
        val manifest = Manifest(
            name = name, version = version, description = description,
            matches = matches, runAt = runAt, contentScript = contentScript,
            icon = iconName, permissions = permissions
        )
        val synthesizedSource = synthesizeHeader(manifest) + "\n" + scriptText
        return when (val installed = ExtensionRuntime.installFromText(synthesizedSource, existingIds)) {
            is ExtensionRuntime.InstallResult.Success -> ZipInstallResult.Ready(
                script = installed.script,
                iconBytes = iconEntry?.bytes,
                wantsSensitivePages = permissions.contains(ALLOWED_PERMISSION)
            )
            is ExtensionRuntime.InstallResult.Failure ->
                ZipInstallResult.Failure(installed.reason)
        }
    }

    /**
     * Synthesize the canonical `// ==UserScript==` header from a validated
     * manifest (§1.6 step 2). Values are already validated single-line, so
     * synthesis cannot inject a second header line. The synthesized source
     * (header + script) is what gets persisted, keeping
     * [UserscriptParser] the single source of truth for metadata.
     */
    fun synthesizeHeader(manifest: Manifest): String {
        return buildString {
            appendLine("// ==UserScript==")
            appendLine("// @name ${manifest.name}")
            appendLine("// @version ${manifest.version}")
            if (manifest.description.isNotEmpty()) {
                appendLine("// @description ${manifest.description}")
            }
            for (match in manifest.matches) {
                appendLine("// @match $match")
            }
            appendLine("// @run-at ${manifest.runAt}")
            append("// ==/UserScript==")
        }
    }

    /**
     * Shape check for one `@match` pattern (§1.3): `scheme://host/path`,
     * scheme `*`/`http`/`https`, non-empty host with no whitespace. A
     * pattern with no path is read as any path (valid). The glob semantics
     * themselves are unchanged from [MatchPattern] — this only checks the
     * shape the installer can parse.
     */
    fun isValidMatchPattern(pattern: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p.any { it.isWhitespace() }) return false
        val sep = p.indexOf("://")
        if (sep < 0) return false
        val scheme = p.substring(0, sep).lowercase()
        if (scheme != "*" && scheme != "http" && scheme != "https") return false
        val rest = p.substring(sep + 3)
        if (rest.isEmpty()) return false
        val host = rest.substringBefore('/').substringBefore('?')
        if (host.isEmpty()) return false
        return true
    }

    /** Single line: no `\n`, `\r`, or control characters (§1.3). */
    fun isSingleLine(value: String): Boolean {
        return value.none { it == '\n' || it == '\r' || it < ' ' || it == '\u007F' }
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return false
        // PK\x03\x04 (local file header), PK\x05\x06 (empty zip),
        // PK\x07\x08 (spanned). Anything else with a PK prefix is not a
        // readable zip for our purposes.
        return (bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
            (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) ||
            (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte())
    }

    private data class ZipEntryData(val name: String, val bytes: ByteArray)

    /** Read every entry's name + contents (bounded per entry). Throws
     *  [ZipException]/[IOException] on corrupt/encrypted data (§1.5 #1). */
    private fun readEntries(zipBytes: ByteArray): List<ZipEntryData> {
        val out = mutableListOf<ZipEntryData>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    // Read one byte past the cap so a truncated entry is
                    // still detectable (its size is then > every per-file
                    // cap and the right check fires).
                    val want = minOf(chunk.size, MAX_ENTRY_READ_BYTES + 1 - total)
                    if (want <= 0) break
                    val read = zin.read(chunk, 0, want)
                    if (read < 0) break
                    buf.write(chunk, 0, read)
                    total += read
                }
                out.add(ZipEntryData(name, buf.toByteArray()))
                zin.closeEntry()
            }
        }
        return out
    }

    /** §1.1 path-traversal guard: `..`, leading `/` or `\`, any `\`, or a
     *  drive prefix (`C:`). */
    fun isUnsafeEntryName(name: String): Boolean {
        if (".." in name) return true
        if (name.startsWith("/") || name.startsWith("\\")) return true
        if ('\\' in name) return true
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return true
        return false
    }

    /** §1.1 allowlist: exactly `manifest.json`, one `*.js`, or `icon.png`,
     *  all at the zip root (any `/` — including a trailing `/` on a
     *  directory entry — fails the allowlist). */
    fun isAllowedEntryName(name: String): Boolean {
        if ('/' in name) return false
        return name == "manifest.json" || name == "icon.png" || name.endsWith(".js")
    }

    // ---- Minimal JSON parser (manifest only) ----

    sealed class JsonValue {
        data class JObj(val fields: Map<String, JsonValue>) : JsonValue()
        data class JArr(val items: List<JsonValue>) : JsonValue()
        data class JStr(val value: String) : JsonValue()
        data class JNum(val raw: String) : JsonValue()
        data class JBool(val value: Boolean) : JsonValue()
        object JNull : JsonValue()

        /** Best-effort JSON text of this value (used only inside error
         *  strings for wrong-typed fields). */
        fun toJsonText(): String = when (this) {
            is JStr -> "\"$value\""
            is JNum -> raw
            is JBool -> value.toString()
            is JNull -> "null"
            is JArr -> "[${items.joinToString(", ") { it.toJsonText() }}]"
            is JObj -> "{${fields.entries.joinToString(", ") { (k, v) -> "\"$k\": ${v.toJsonText()}" }}}"
        }
    }

    /**
     * Strict-enough JSON parser: objects, arrays, strings (with escapes
     * incl. `\uXXXX`), numbers, `true`/`false`/`null`. Returns null on any
     * malformed input or trailing garbage. Depth-capped (32) so hostile
     * nesting cannot overflow the stack.
     */
    object JsonParser {
        fun parse(text: String): JsonValue? {
            val parser = Parser(text)
            return try {
                val value = parser.parseValue(0) ?: return null
                parser.skipWs()
                if (!parser.atEnd()) null else value
            } catch (_: Exception) {
                null
            }
        }

        private class Parser(private val text: String) {
            private var pos = 0

            fun atEnd(): Boolean = pos >= text.length

            fun skipWs() {
                while (pos < text.length && text[pos] in " \t\n\r") pos++
            }

            fun parseValue(depth: Int): JsonValue? {
                if (depth > 32) return null
                skipWs()
                if (atEnd()) return null
                return when (val c = text[pos]) {
                    '{' -> parseObject(depth)
                    '[' -> parseArray(depth)
                    '"' -> parseString()?.let { JsonValue.JStr(it) }
                    't' -> parseLiteral("true", JsonValue.JBool(true))
                    'f' -> parseLiteral("false", JsonValue.JBool(false))
                    'n' -> parseLiteral("null", JsonValue.JNull)
                    '-', in '0'..'9' -> parseNumber()
                    else -> null
                }
            }

            private fun parseLiteral(word: String, value: JsonValue): JsonValue? {
                if (!text.startsWith(word, pos)) return null
                pos += word.length
                return value
            }

            private fun parseNumber(): JsonValue? {
                val start = pos
                if (pos < text.length && text[pos] == '-') pos++
                var digits = 0
                while (pos < text.length && text[pos].isDigit()) {
                    pos++
                    digits++
                }
                if (digits == 0) return null
                // Fraction / exponent: accepted as a number, but a
                // manifest_version of 1.0 is NOT integer 1 (check #10).
                if (pos < text.length && text[pos] == '.') {
                    pos++
                    var frac = 0
                    while (pos < text.length && text[pos].isDigit()) {
                        pos++
                        frac++
                    }
                    if (frac == 0) return null
                }
                if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                    pos++
                    if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                    var exp = 0
                    while (pos < text.length && text[pos].isDigit()) {
                        pos++
                        exp++
                    }
                    if (exp == 0) return null
                }
                return JsonValue.JNum(text.substring(start, pos))
            }

            private fun parseString(): String? {
                if (atEnd() || text[pos] != '"') return null
                pos++
                val out = StringBuilder()
                while (pos < text.length) {
                    val c = text[pos]
                    when {
                        c == '"' -> {
                            pos++
                            return out.toString()
                        }
                        c == '\\' -> {
                            pos++
                            if (atEnd()) return null
                            when (val e = text[pos]) {
                                '"' -> out.append('"')
                                '\\' -> out.append('\\')
                                '/' -> out.append('/')
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (pos + 4 >= text.length) return null
                                    val hex = text.substring(pos + 1, pos + 5)
                                    val code = hex.toIntOrNull(16) ?: return null
                                    out.append(code.toChar())
                                    pos += 4
                                }
                                else -> return null
                            }
                            pos++
                        }
                        c < ' ' -> return null // raw control char: invalid JSON
                        else -> {
                            out.append(c)
                            pos++
                        }
                    }
                }
                return null // unterminated string
            }

            private fun parseObject(depth: Int): JsonValue? {
                pos++ // consume '{'
                val fields = LinkedHashMap<String, JsonValue>()
                skipWs()
                if (!atEnd() && text[pos] == '}') {
                    pos++
                    return JsonValue.JObj(fields)
                }
                while (true) {
                    skipWs()
                    val key = parseString() ?: return null
                    skipWs()
                    if (atEnd() || text[pos] != ':') return null
                    pos++
                    val value = parseValue(depth + 1) ?: return null
                    fields[key] = value // last wins (org.json behaviour)
                    skipWs()
                    if (atEnd()) return null
                    when (text[pos]) {
                        ',' -> {
                            pos++
                            continue
                        }
                        '}' -> {
                            pos++
                            return JsonValue.JObj(fields)
                        }
                        else -> return null
                    }
                }
            }

            private fun parseArray(depth: Int): JsonValue? {
                pos++ // consume '['
                val items = mutableListOf<JsonValue>()
                skipWs()
                if (!atEnd() && text[pos] == ']') {
                    pos++
                    return JsonValue.JArr(items)
                }
                while (true) {
                    val value = parseValue(depth + 1) ?: return null
                    items.add(value)
                    skipWs()
                    if (atEnd()) return null
                    when (text[pos]) {
                        ',' -> {
                            pos++
                            continue
                        }
                        ']' -> {
                            pos++
                            return JsonValue.JArr(items)
                        }
                        else -> return null
                    }
                }
            }
        }
    }
}
