package com.nova.browser.devtools

/**
 * Tap-to-inspect + storage JavaScript — pure Kotlin (string constants and
 * small builders), JVM-testable. No Android imports.
 *
 * How inspect works (no JavascriptInterface, so nothing to wire in the
 * clients):
 *  1. [INSTALL_JS] adds a capture-phase click listener. The clicked element
 *     gets a violet outline, navigation is prevented, and a JSON summary is
 *     stored in `window.__novaInspected`.
 *  2. The dock polls [READ_JS] with evaluateJavascript (main thread) and
 *     parses the result with [parseInspected].
 *  3. [STOP_JS] removes the listener and the outline.
 *
 * The JS is defensive: any failure leaves the page working (the install
 * script is wrapped in try/catch and is idempotent via the
 * `window.__novaInspectInstalled` guard, so re-injection on SPA
 * navigations is harmless).
 *
 * Parsing uses [JsonPretty] (hand-rolled), NOT org.json — org.json is not
 * on the JVM test classpath.
 */
object ElementInspector {

    /** What the dock shows for the last tapped element. */
    data class InspectedElement(
        val tag: String,
        val id: String,
        val classes: String,
        val text: String,
        /** First ~500 chars of outerHTML. */
        val html: String
    )

    /** Install the capture-phase click listener + highlight. Idempotent. */
    const val INSTALL_JS = """(function(){try{
if(window.__novaInspectInstalled){return 'already';}
window.__novaInspectInstalled=true;
window.__novaInspected=null;
window.__novaInspectPrev=null;
window.__novaInspectHandler=function(e){
  try{
    e.preventDefault();e.stopPropagation();
    var el=e.target;
    if(window.__novaInspectPrev&&window.__novaInspectPrev.style){
      window.__novaInspectPrev.style.outline='';
    }
    if(el&&el.style){el.style.outline='3px solid #8B5CF6';window.__novaInspectPrev=el;}
    var txt=(el.innerText||el.textContent||'').replace(/\s+/g,' ').trim().slice(0,200);
    var html=(el.outerHTML||'').slice(0,500);
    window.__novaInspected=JSON.stringify({
      tag:el.tagName||'',
      id:el.id||'',
      classes:(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString(),
      text:txt,
      html:html
    });
  }catch(err){window.__novaInspected=JSON.stringify({tag:'?',id:'',classes:'',text:'inspect failed: '+err,html:''});}
};
document.addEventListener('click',window.__novaInspectHandler,true);
return 'installed';
}catch(err){return 'error: '+err;}})();"""

    /** Remove the listener, clear the outline and the stored payload. */
    const val STOP_JS = """(function(){try{
if(window.__novaInspectHandler){document.removeEventListener('click',window.__novaInspectHandler,true);window.__novaInspectHandler=null;}
if(window.__novaInspectPrev&&window.__novaInspectPrev.style){window.__novaInspectPrev.style.outline='';window.__novaInspectPrev=null;}
window.__novaInspectInstalled=false;
window.__novaInspected=null;
return 'stopped';
}catch(err){return 'error: '+err;}})();"""

    /** Read the stored payload: a JSON string, or JS null when nothing tapped yet. */
    const val READ_JS = "window.__novaInspected||null"

    /**
     * Parse an evaluateJavascript result for [READ_JS]. The WebView returns
     * the JS string JSON-encoded, so first unwrap it with
     * [JsonPretty.decodeJsStringResult], then parse the flat object.
     * Returns null when nothing has been tapped yet or the payload is bad.
     * Never throws.
     */
    fun parseInspected(jsResult: String?): InspectedElement? {
        return try {
            val json = JsonPretty.decodeJsStringResult(jsResult) ?: return null
            val map = JsonPretty.parseFlatStringMap(json) ?: return null
            val tag = map["tag"].orEmpty()
            if (tag.isEmpty()) return null
            InspectedElement(
                tag = tag,
                id = map["id"].orEmpty(),
                classes = map["classes"].orEmpty(),
                text = map["text"].orEmpty(),
                html = map["html"].orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- Storage dumps (evaluateJavascript, main thread) ----

    /** Returns a JSON string of the whole localStorage object. On
     *  failure returns a JSON object with the [STORAGE_ERROR_KEY] marker
     *  (B17) — the old `{}` made a failed dump indistinguishable from
     *  genuinely empty storage, so the screen lied "Empty.". */
    const val LOCAL_STORAGE_DUMP_JS =
        """(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k);}return JSON.stringify(o);}catch(e){return JSON.stringify({__nova_storage_error:'1'});}})();"""

    /** Returns a JSON string of the whole sessionStorage object. */
    const val SESSION_STORAGE_DUMP_JS =
        """(function(){try{var o={};for(var i=0;i<sessionStorage.length;i++){var k=sessionStorage.key(i);o[k]=sessionStorage.getItem(k);}return JSON.stringify(o);}catch(e){return JSON.stringify({__nova_storage_error:'1'});}})();"""

    /** Marker key a failed dump carries (see above). A page could in
     *  theory store this exact key itself — the screen would then say
     *  "unavailable" for a storage that does hold it. Namespaced and
     *  unlikely; accepted. */
    const val STORAGE_ERROR_KEY = "__nova_storage_error"

    /** Parse a storage dump result into key -> value. Null when the dump
     *  itself failed (B17) — the screen shows "unavailable" instead of
     *  "Empty."; an empty map means the storage genuinely has no keys. */
    fun parseStorageDump(jsResult: String?): Map<String, String>? {
        return try {
            val json = JsonPretty.decodeJsStringResult(jsResult) ?: return null
            val map = JsonPretty.parseFlatStringMap(json) ?: return null
            if (map.containsKey(STORAGE_ERROR_KEY)) null else map
        } catch (_: Exception) {
            null
        }
    }

    /** JS that removes one localStorage key. [key] is JSON-escaped. */
    fun removeLocalItemJs(key: String): String =
        "try{localStorage.removeItem(\"${JsonPretty.escapeJsonString(key)}\");'ok';}catch(e){'error: '+e;}"

    /** JS that removes one sessionStorage key. [key] is JSON-escaped. */
    fun removeSessionItemJs(key: String): String =
        "try{sessionStorage.removeItem(\"${JsonPretty.escapeJsonString(key)}\");'ok';}catch(e){'error: '+e;}"

    /** JS that clears localStorage for this origin. */
    const val CLEAR_LOCAL_STORAGE_JS = "try{localStorage.clear();'ok';}catch(e){'error: '+e;}"

    /** JS that clears sessionStorage for this tab. */
    const val CLEAR_SESSION_STORAGE_JS = "try{sessionStorage.clear();'ok';}catch(e){'error: '+e;}"

    // ---- Value masking (W6 W3-5) ----

    /** Storage keys whose values are treated as secrets. */
    private val SENSITIVE_KEY_SUBSTRINGS = listOf(
        "token", "secret", "password", "passwd", "auth", "apikey", "api_key", "session"
    )

    /** True when a storage [key] looks like it holds a secret. */
    fun isSensitiveStorageKey(key: String): Boolean {
        val lower = key.lowercase()
        return SENSITIVE_KEY_SUBSTRINGS.any { lower.contains(it) }
    }

    /**
     * True when [value] looks like a token: a JWT (three base64url parts),
     * a `Bearer …` value, or a long high-entropy string (≥ 20 chars of
     * letters/digits/`_-./+=` with both cases or digits mixed in). Short
     * plain values ("dark", "en", "1") never match.
     */
    fun looksLikeToken(value: String): Boolean {
        val v = value.trim()
        if (v.length < 20) return false
        if (v.startsWith("Bearer ", ignoreCase = true)) return true
        if (v.startsWith("eyJ") && v.count { it == '.' } == 2) return true
        if (!v.all { it.isLetterOrDigit() || it in "_-./+=" }) return false
        val hasLower = v.any { it.isLowerCase() }
        val hasUpper = v.any { it.isUpperCase() }
        val hasDigit = v.any { it.isDigit() }
        // At least two character classes — rules out "aaaaaaaaaaaaaaaaaaaa".
        return listOf(hasLower, hasUpper, hasDigit).count { it } >= 2
    }

    /**
     * Mask a storage value for display: token-like values (by key or by
     * shape) show only the first 4 and last 2 characters
     * (`abcd••••…yz`); short secrets are fully masked. Ordinary values
     * pass through untouched. The stored value itself is never changed —
     * this is display-only, and delete still works on the key.
     */
    fun maskStorageValue(key: String, value: String): String {
        if (!isSensitiveStorageKey(key) && !looksLikeToken(value)) return value
        if (value.length <= 8) return "••••••••"
        return value.take(4) + "••••" + value.takeLast(2)
    }

    /** Apply [maskStorageValue] to a whole storage dump. */
    fun maskStorageDump(dump: Map<String, String>): Map<String, String> =
        dump.mapValues { (key, value) -> maskStorageValue(key, value) }
}
