package com.nova.browser.devtools

/**
 * Pure-Kotlin JSON helper — no Android imports, JVM-testable.
 *
 * A tiny recursive-descent parser + pretty-printer, plus the two small
 * string helpers the DevTools bridge needs:
 *  - [decodeJsStringResult]: unwrap what WebView.evaluateJavascript hands
 *    back (a JSON-encoded string, e.g. "\"{\\\"a\\\":1}\"").
 *  - [parseFlatStringMap]: parse a flat {"key":"value"} object, used for
 *    the inspector payload and the localStorage/sessionStorage dumps.
 *
 * Deliberately hand-rolled instead of org.json: org.json ships with
 * Android but is NOT on the JVM test classpath, so a test that used it
 * could not run in tests/run-tests.sh. This file must stay dependency-free.
 */
object JsonPretty {

    /**
     * Maximum nesting depth the parser accepts. A hostile server can
     * return `[[[[…` 10k deep; without a cap the recursive parser blows
     * the stack (StackOverflowError is an Error, not an Exception, so a
     * plain catch would miss it). Depth 100 is far beyond any real API
     * response or inspector payload. Over-deep input parses as null and
     * the caller falls back to showing the raw text.
     */
    const val MAX_DEPTH = 100

    /** Result of a parse: either a value or a human-readable error. */
    sealed class JsonValue {
        data class Obj(val entries: List<Pair<String, JsonValue>>) : JsonValue()
        data class Arr(val items: List<JsonValue>) : JsonValue()
        data class Str(val value: String) : JsonValue()
        data class Num(val raw: String) : JsonValue()
        data class Bool(val value: Boolean) : JsonValue()
        object Null : JsonValue()
    }

    /** Parse [json] or return null when it is not valid JSON. Never throws.
     *  Catches Throwable (not just Exception) so a StackOverflowError from
     *  hostile nesting can never escape — the depth cap should prevent it,
     *  this is the belt to its braces. */
    fun parse(json: String): JsonValue? {
        return try {
            val parser = Parser(json)
            val value = parser.parseValue()
            parser.skipWhitespace()
            if (parser.pos != json.length) null else value
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Pretty-print [json] with 2-space indent, or null when [json] is not
     * valid JSON (the caller then shows the raw body instead of failing).
     */
    fun pretty(json: String): String? {
        val value = parse(json.trim()) ?: return null
        val sb = StringBuilder()
        render(value, sb, 0)
        return sb.toString()
    }

    /** True when [json] parses as a JSON object or array. */
    fun isJson(json: String): Boolean {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
        return parse(trimmed) != null
    }

    /**
     * Parse a flat object whose values are all strings, e.g. the inspector
     * payload {"tag":"DIV","id":"main",...} or a storage dump. Returns null
     * when the JSON is not such an object (numbers/booleans/nulls as values
     * are coerced to their raw text, nested objects/arrays render compact).
     * Never throws.
     */
    fun parseFlatStringMap(json: String): Map<String, String>? {
        val value = parse(json.trim()) ?: return null
        val obj = value as? JsonValue.Obj ?: return null
        val out = LinkedHashMap<String, String>()
        for ((key, v) in obj.entries) {
            out[key] = when (v) {
                is JsonValue.Str -> v.value
                is JsonValue.Num -> v.raw
                is JsonValue.Bool -> v.value.toString()
                is JsonValue.Null -> ""
                else -> compact(v)
            }
        }
        return out
    }

    /**
     * Unwrap an evaluateJavascript result. The WebView returns the JS value
     * JSON-encoded: a JS string comes back wrapped in quotes with its
     * contents escaped, and JS null comes back as the literal `null`.
     * Returns the decoded string, or null when the JS value was null,
     * undefined, or the result was not a JSON string. Never throws.
     */
    fun decodeJsStringResult(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed == "null" || trimmed == "undefined" || trimmed.isEmpty()) return null
        val value = parse(trimmed) ?: return null
        return (value as? JsonValue.Str)?.value
    }

    /** Escape [s] for embedding inside a JSON string literal (quotes included). */
    fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /** Compact (single-line) rendering, used for nested values in flat maps. */
    fun compact(value: JsonValue): String {
        val sb = StringBuilder()
        render(value, sb, -1)
        return sb.toString()
    }

    /** Render with [indent] < 0 meaning compact (no newlines). */
    private fun render(value: JsonValue, sb: StringBuilder, indent: Int) {
        when (value) {
            is JsonValue.Obj -> {
                if (value.entries.isEmpty()) { sb.append("{}"); return }
                sb.append("{")
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(",")
                    if (indent >= 0) { sb.append("\n"); sb.append("  ".repeat(indent + 1)) }
                    sb.append("\"").append(escapeJsonString(k)).append("\":")
                    if (indent >= 0) sb.append(" ")
                    render(v, sb, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) { sb.append("\n"); sb.append("  ".repeat(indent)) }
                sb.append("}")
            }
            is JsonValue.Arr -> {
                if (value.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[")
                value.items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(",")
                    if (indent >= 0) { sb.append("\n"); sb.append("  ".repeat(indent + 1)) }
                    render(v, sb, if (indent >= 0) indent + 1 else -1)
                }
                if (indent >= 0) { sb.append("\n"); sb.append("  ".repeat(indent)) }
                sb.append("]")
            }
            is JsonValue.Str -> sb.append("\"").append(escapeJsonString(value.value)).append("\"")
            is JsonValue.Num -> sb.append(value.raw)
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Null -> sb.append("null")
        }
    }

    private class Parser(private val input: String) {
        var pos = 0
        private var depth = 0

        /** Enter one nesting level; throws when the depth cap is hit. */
        private fun enterDepth() {
            depth++
            if (depth > MAX_DEPTH) throw IllegalArgumentException("JSON nesting too deep (max $MAX_DEPTH)")
        }

        private fun exitDepth() {
            depth--
        }

        fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= input.length) throw IllegalArgumentException("unexpected end of JSON")
            return when (val c = input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected character '$c' at $pos")
            }
        }

        private fun expect(word: String) {
            if (!input.startsWith(word, pos)) throw IllegalArgumentException("expected $word at $pos")
            pos += word.length
        }

        private fun parseObject(): JsonValue.Obj {
            enterDepth()
            try {
                pos++ // {
                val entries = ArrayList<Pair<String, JsonValue>>()
                skipWhitespace()
                if (pos < input.length && input[pos] == '}') { pos++; return JsonValue.Obj(entries) }
                while (true) {
                    skipWhitespace()
                    if (pos >= input.length || input[pos] != '"') throw IllegalArgumentException("expected key at $pos")
                    val key = parseString()
                    skipWhitespace()
                    if (pos >= input.length || input[pos] != ':') throw IllegalArgumentException("expected ':' at $pos")
                    pos++
                    val value = parseValue()
                    entries.add(key to value)
                    skipWhitespace()
                    if (pos >= input.length) throw IllegalArgumentException("unterminated object")
                    when (input[pos]) {
                        ',' -> { pos++; continue }
                        '}' -> { pos++; return JsonValue.Obj(entries) }
                        else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                    }
                }
            } finally {
                exitDepth()
            }
        }

        private fun parseArray(): JsonValue.Arr {
            enterDepth()
            try {
                pos++ // [
                val items = ArrayList<JsonValue>()
                skipWhitespace()
                if (pos < input.length && input[pos] == ']') { pos++; return JsonValue.Arr(items) }
                while (true) {
                    items.add(parseValue())
                    skipWhitespace()
                    if (pos >= input.length) throw IllegalArgumentException("unterminated array")
                    when (input[pos]) {
                        ',' -> { pos++; continue }
                        ']' -> { pos++; return JsonValue.Arr(items) }
                        else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                    }
                }
            } finally {
                exitDepth()
            }
        }

        private fun parseString(): String {
            // input[pos] == '"'
            pos++
            val sb = StringBuilder()
            while (pos < input.length) {
                val c = input[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= input.length) throw IllegalArgumentException("unterminated escape")
                        when (val e = input[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) throw IllegalArgumentException("bad unicode escape")
                                val hex = input.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape")
                                pos += 4
                                // Surrogate pair: a high surrogate followed by \uXXXX low.
                                if (code in 0xD800..0xDBFF && pos + 6 <= input.length &&
                                    input[pos] == '\\' && input[pos + 1] == 'u'
                                ) {
                                    val low = input.substring(pos + 2, pos + 6).toIntOrNull(16)
                                    if (low != null && low in 0xDC00..0xDFFF) {
                                        pos += 6
                                        sb.append(
                                            String(
                                                Character.toChars(
                                                    0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                                                )
                                            )
                                        )
                                    } else {
                                        sb.append(code.toChar())
                                    }
                                } else {
                                    sb.append(code.toChar())
                                }
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw IllegalArgumentException("control char in string")
                        sb.append(c)
                    }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (pos < input.length && input[pos] == '-') pos++
            while (pos < input.length && input[pos].isDigit()) pos++
            if (pos < input.length && input[pos] == '.') {
                pos++
                if (pos >= input.length || !input[pos].isDigit()) throw IllegalArgumentException("bad number")
                while (pos < input.length && input[pos].isDigit()) pos++
            }
            if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
                pos++
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
                if (pos >= input.length || !input[pos].isDigit()) throw IllegalArgumentException("bad exponent")
                while (pos < input.length && input[pos].isDigit()) pos++
            }
            val raw = input.substring(start, pos)
            if (raw == "-" || raw.isEmpty()) throw IllegalArgumentException("bad number")
            return JsonValue.Num(raw)
        }
    }
}
