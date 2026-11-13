package com.nova.browser.data

import android.app.Application
import android.content.Context
import com.nova.browser.extensions.ExtensionRuntime
import com.nova.browser.extensions.MAX_SCRIPT_SIZE_BYTES
import com.nova.browser.extensions.RunAt
import com.nova.browser.extensions.Userscript
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for extensions — user-installed userscripts plus the
 * enabled / per-site state of the built-in scripts.
 *
 * Storage (app files dir, on-device only, no account/server):
 * - `extensions/extensions.json` — one JSON object: a `scripts` array of
 *   user-script records (metadata + enabled + disabled hosts) and a
 *   `builtin` object of per-built-in-id overrides (enabled + disabled
 *   hosts). Built-ins ship as assets, so no script file is stored for them.
 * - `extensions/scripts/<id>.js` — the plain-text source of each user script.
 *
 * Deliberately NOT BrowserDb.kt (the task forbids editing it) and NOT
 * SharedPreferences: a script record is a small document, and JSON keeps
 * the metadata and the source file next to each other.
 *
 * Per-site model: a DISABLE list per script (see
 * [ExtensionRuntime.isEnabledForHost]). A host listed there — or a
 * subdomain of a listed host — never runs the script.
 *
 * Private mode (W6 S-5): the store is GUARDED, not just documented.
 * Every public method checks [isPrivateProcess] (the `:incognito`
 * process, same check as ErrorLog). In private mode reads return
 * built-ins-only with default state (no user scripts, no per-site or
 * sensitive-host state — those sets would reveal which hosts were
 * visited), and every write returns false. Private tabs therefore get
 * the two built-ins only (YouTube skipper, gated by the private
 * session's Settings toggle; Cookie Banner Hider), never user scripts.
 * The coordinator must still never route the Extensions screen from
 * PrivateActivity — the guard is defense-in-depth, not the UI rule.
 * All methods are @Synchronized and fail-open (corrupt JSON reads as
 * "nothing installed", never a crash).
 */
object ExtensionStore {

    private const val DIR_NAME = "extensions"
    private const val SCRIPTS_DIR_NAME = "scripts"
    private const val INDEX_NAME = "extensions.json"
    private const val BACKUP_NAME = "extensions.json.bak"

    /** True when the last index read found the primary index corrupt
     *  (N-12): the screen shows a "settings reset" note instead of
     *  silently re-enabling a built-in the user had disabled. Set by
     *  [readIndex]; cleared on the next clean read. */
    @Volatile
    var lastIndexCorrupt: Boolean = false
        private set

    /** Effective-scripts cache (B-7): loadEffectiveScripts does JSON +
     *  file + asset reads on every navigation when called from the
     *  injection hook (main thread). Cached per skipper-toggle state and
     *  invalidated on every store write. */
    @Volatile
    private var effectiveCache: List<Userscript>? = null
    @Volatile
    private var effectiveCacheSkipper: Boolean = false

    private fun invalidateEffectiveCache() {
        effectiveCache = null
    }

    /** Hosts that may NOT be allow-listed for sensitive pages (N-2):
     *  allowing "com" would allow the script on EVERY .com sensitive
     *  page. Single-label hosts are rejected by the dot rule; this set
     *  covers the well-known public suffixes. */
    private val PUBLIC_SUFFIXES = setOf(
        "com", "org", "net", "io", "co.uk", "com.au", "co.jp", "co.nz",
        "org.uk", "co.in", "com.br", "co.za", "com.cn", "net.au", "org.au"
    )

    /** True when [host] may be allow-listed for sensitive pages: a real
     *  host with at least one dot, not itself a public suffix. */
    fun isValidSensitiveAllowHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (!h.contains('.')) return false
        if (h in PUBLIC_SUFFIXES) return false
        if (h.startsWith('.') || h.endsWith('.')) return false
        return true
    }

    /** Per-built-in override (enabled + per-site off list + sensitive-page
     *  opt-ins). */
    data class BuiltinState(
        val enabled: Boolean,
        val disabledHosts: Set<String>,
        val allowedSensitiveHosts: Set<String> = emptySet()
    )

    /** True when this call runs in the :incognito process (W6 S-5). */
    fun isPrivateProcess(): Boolean =
        Application.getProcessName()?.endsWith(":incognito") == true

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)
    private fun scriptsDir(context: Context): File = File(dir(context), SCRIPTS_DIR_NAME)
    private fun indexFile(context: Context): File = File(dir(context), INDEX_NAME)

    @Synchronized
    fun loadInstalled(context: Context): List<Userscript> {
        // W6 S-5: private mode never sees user scripts.
        if (isPrivateProcess()) return emptyList()
        return try {
            val index = readIndex(context)
            val scripts = index.optJSONArray("scripts") ?: return emptyList()
            val out = mutableListOf<Userscript>()
            for (i in 0 until scripts.length()) {
                val obj = scripts.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                // W6 S-6: the id comes from JSON and meets the filesystem —
                // only slugify's alphabet is trusted; anything else is skipped.
                if (!ExtensionRuntime.isValidScriptId(id)) continue
                val file = File(scriptsDir(context), "$id.js")
                if (!file.exists()) continue
                val source = try { file.readText() } catch (_: Exception) { continue }
                val matches = jsonStringList(obj.optJSONArray("matches"))
                val runAt = if (obj.optString("runAt", "") == "document-start") {
                    RunAt.DOCUMENT_START
                } else {
                    RunAt.DOCUMENT_END
                }
                out.add(
                    Userscript(
                        id = id,
                        name = obj.optString("name", id),
                        version = obj.optString("version", "1.0"),
                        description = obj.optString("description", ""),
                        matches = matches,
                        runAt = runAt,
                        source = source,
                        builtIn = false,
                        enabled = obj.optBoolean("enabled", true),
                        disabledHosts = jsonStringList(obj.optJSONArray("disabledHosts")).toSet(),
                        allowedSensitiveHosts = jsonStringList(obj.optJSONArray("allowedSensitiveHosts")).toSet()
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Effective enabled/disabled state of a built-in script. Defaults:
     *  enabled, no per-site blocks, no sensitive-page opt-ins. In private
     *  mode always the default (W6 S-5 — stored per-site state must never
     *  be read from the private process). */
    @Synchronized
    fun builtinState(context: Context, id: String): BuiltinState {
        if (isPrivateProcess()) return BuiltinState(true, emptySet())
        return try {
            val builtin = readIndex(context).optJSONObject("builtin")
                ?.optJSONObject(id) ?: return BuiltinState(true, emptySet())
            BuiltinState(
                enabled = builtin.optBoolean("enabled", true),
                disabledHosts = jsonStringList(builtin.optJSONArray("disabledHosts")).toSet(),
                allowedSensitiveHosts = jsonStringList(builtin.optJSONArray("allowedSensitiveHosts")).toSet()
            )
        } catch (_: Exception) {
            BuiltinState(true, emptySet())
        }
    }

    /**
     * Everything the injection hook needs, in one call: built-ins from the
     * registry (with their stored enabled / per-site state applied, and the
     * YouTube skipper's Settings master switch applied on top) plus the
     * installed user scripts. [assetReader] reads an asset path and returns
     * null when it is missing (on Android:
     * `context.assets.open(path).bufferedReader().readText()`).
     */
    @Synchronized
    fun loadEffectiveScripts(
        context: Context,
        youtubeSkipperEnabled: Boolean,
        assetReader: (String) -> String?
    ): List<Userscript> {
        // W6 S-5: private mode gets built-ins only, with default state —
        // no user scripts and no stored per-site/sensitive state.
        if (isPrivateProcess()) {
            return ExtensionRuntime.builtInScripts(assetReader).map { script ->
                val effectiveEnabled = if (script.id == ExtensionRuntime.YOUTUBE_SKIPPER_ID) {
                    youtubeSkipperEnabled
                } else {
                    // B-11: apply the stored hider toggle in private mode
                    // too — the enabled flag alone reveals nothing about
                    // visited hosts (only the per-site/sensitive lists
                    // would, and those stay default here).
                    builtinEnabledFlag(context, script.id)
                }
                script.copy(enabled = effectiveEnabled)
            }
        }
        // B-7: cached per skipper-toggle state.
        val cached = effectiveCache
        if (cached != null && effectiveCacheSkipper == youtubeSkipperEnabled) return cached
        val builtIns = ExtensionRuntime.builtInScripts(assetReader).map { script ->
            val state = builtinState(context, script.id)
            val effectiveEnabled = if (script.id == ExtensionRuntime.YOUTUBE_SKIPPER_ID) {
                // B-10: the Settings toggle is the SINGLE owner of the
                // skipper. The store's per-script enabled override for
                // it is dead state the screen never writes — ANDing it
                // here meant the screen could show ON while the hook ran
                // OFF. (Per-site off + sensitive allow for the skipper
                // still come from the store below.)
                youtubeSkipperEnabled
            } else {
                state.enabled
            }
            script.copy(
                enabled = effectiveEnabled,
                disabledHosts = state.disabledHosts,
                allowedSensitiveHosts = state.allowedSensitiveHosts
            )
        }
        val result = builtIns + loadInstalled(context)
        effectiveCache = result
        effectiveCacheSkipper = youtubeSkipperEnabled
        return result
    }

    /** Stored enabled flag for a built-in, default true. Reads ONLY the
     *  flag — no per-site/sensitive lists — so it is safe to call from
     *  the private branch (B-11). */
    private fun builtinEnabledFlag(context: Context, id: String): Boolean {
        return try {
            readIndex(context).optJSONObject("builtin")
                ?.optJSONObject(id)?.optBoolean("enabled", true) ?: true
        } catch (_: Exception) {
            true
        }
    }

    /** Persist a validated user script (see [ExtensionRuntime.installFromText]).
     *  Returns false when the id already exists (no silent overwrite) or
     *  the write fails. Built-in ids are refused. */
    @Synchronized
    fun install(context: Context, script: Userscript): Boolean {
        if (isPrivateProcess()) return false
        if (script.builtIn) return false
        // W6 S-6: refuse ids outside slugify's alphabet before they meet
        // the filesystem.
        if (!ExtensionRuntime.isValidScriptId(script.id)) return false
        if (script.id == ExtensionRuntime.YOUTUBE_SKIPPER_ID ||
            script.id == ExtensionRuntime.COOKIE_HIDER_ID
        ) return false
        // N-13: never trust the caller — re-validate size, header and id
        // here, so a future caller bypassing installFromText cannot
        // persist an oversize or header-less script.
        if (script.source.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_SIZE_BYTES) return false
        if (ExtensionRuntime.installFromText(script.source, emptySet()) is ExtensionRuntime.InstallResult.Failure) return false
        if (ExtensionRuntime.slugify(script.name) != script.id) return false
        invalidateEffectiveCache()
        return try {
            val index = readIndex(context)
            val scripts = index.optJSONArray("scripts") ?: JSONArray()
            for (i in 0 until scripts.length()) {
                if (scripts.optJSONObject(i)?.optString("id") == script.id) return false
            }
            val scriptsDir = scriptsDir(context)
            if (!scriptsDir.exists()) scriptsDir.mkdirs()
            File(scriptsDir, "${script.id}.js").writeText(script.source)
            scripts.put(toJson(script))
            index.put("scripts", scripts)
            writeIndex(context, index)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Extensions", "Install failed for ${script.name}", e)
            false
        }
    }

    /** Delete a user script (source file + index record). Built-ins are not
     *  in the store, so they can never be deleted through here. */
    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        if (isPrivateProcess()) return false
        // N-1 (S-6 gap): validate the id before it meets the filesystem —
        // a crafted id ("../../…") must never reach File().
        if (!ExtensionRuntime.isValidScriptId(id)) return false
        if (id == ExtensionRuntime.YOUTUBE_SKIPPER_ID ||
            id == ExtensionRuntime.COOKIE_HIDER_ID
        ) return false
        invalidateEffectiveCache()
        return try {
            val index = readIndex(context)
            val scripts = index.optJSONArray("scripts") ?: return false
            var removed = false
            val kept = JSONArray()
            for (i in 0 until scripts.length()) {
                val obj = scripts.optJSONObject(i) ?: continue
                if (obj.optString("id") == id) removed = true else kept.put(obj)
            }
            if (!removed) return false
            index.put("scripts", kept)
            // B-16: write the index FIRST, then delete the file. The old
            // order deleted the file first — a failed index write left a
            // zombie record blocking reinstall forever. If the file delete
            // fails now, the orphan .js is never loaded (loadInstalled
            // requires an index entry) and the script can be reinstalled.
            writeIndex(context, index)
            if (!File(scriptsDir(context), "$id.js").delete()) {
                ErrorLog.log(context, "Extensions", "Delete removed the record but not the file for $id", null)
            }
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Extensions", "Delete failed for $id", e)
            false
        }
    }

    /** Master on/off for a user script, or for a built-in (stored as an
     *  override — the built-in asset itself is untouched). */
    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean {
        if (isPrivateProcess()) return false
        invalidateEffectiveCache()
        return try {
            val index = readIndex(context)
            if (setEnabledInArray(index, id, enabled)) {
                writeIndex(context, index)
                return true
            }
            // Not a user script: store/refresh the built-in override.
            val builtin = index.optJSONObject("builtin") ?: JSONObject()
            val entry = builtin.optJSONObject(id) ?: JSONObject()
            entry.put("enabled", enabled)
            builtin.put(id, entry)
            index.put("builtin", builtin)
            writeIndex(context, index)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Extensions", "setEnabled failed for $id", e)
            false
        }
    }

    /** Per-site off switch: [disabled] = true adds [host] to the script's
     *  disabled-hosts list, false removes it. Works for user scripts and
     *  built-ins alike. */
    @Synchronized
    fun setHostDisabled(context: Context, id: String, host: String, disabled: Boolean): Boolean {
        if (isPrivateProcess()) return false
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        invalidateEffectiveCache()
        return try {
            val index = readIndex(context)
            val scripts = index.optJSONArray("scripts")
            if (scripts != null) {
                for (i in 0 until scripts.length()) {
                    val obj = scripts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == id) {
                        obj.put("disabledHosts", updateHostList(obj.optJSONArray("disabledHosts"), h, disabled))
                        writeIndex(context, index)
                        return true
                    }
                }
            }
            val builtin = index.optJSONObject("builtin") ?: JSONObject()
            val entry = builtin.optJSONObject(id) ?: JSONObject()
            entry.put("disabledHosts", updateHostList(entry.optJSONArray("disabledHosts"), h, disabled))
            builtin.put(id, entry)
            index.put("builtin", builtin)
            writeIndex(context, index)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Extensions", "setHostDisabled failed for $id", e)
            false
        }
    }

    /** Sensitive-page opt-in (W6 S-1): [allowed] = true adds [host] to the
     *  script's allowed-sensitive-hosts list, false removes it. Without an
     *  entry here the script never runs on a sensitive page. Works for
     *  user scripts and built-ins alike. Refused in private mode. */
    @Synchronized
    fun setSensitiveHostAllowed(context: Context, id: String, host: String, allowed: Boolean): Boolean {
        if (isPrivateProcess()) return false
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        // N-2: a bare TLD / public suffix must never be allow-listed —
        // allowing "com" would allow the script on every .com sensitive
        // page. The screen pre-validates and explains; this is the backstop.
        if (allowed && !isValidSensitiveAllowHost(h)) return false
        invalidateEffectiveCache()
        return try {
            val index = readIndex(context)
            val scripts = index.optJSONArray("scripts")
            if (scripts != null) {
                for (i in 0 until scripts.length()) {
                    val obj = scripts.optJSONObject(i) ?: continue
                    if (obj.optString("id") == id) {
                        obj.put("allowedSensitiveHosts", updateHostList(obj.optJSONArray("allowedSensitiveHosts"), h, allowed))
                        writeIndex(context, index)
                        return true
                    }
                }
            }
            val builtin = index.optJSONObject("builtin") ?: JSONObject()
            val entry = builtin.optJSONObject(id) ?: JSONObject()
            entry.put("allowedSensitiveHosts", updateHostList(entry.optJSONArray("allowedSensitiveHosts"), h, allowed))
            builtin.put(id, entry)
            index.put("builtin", builtin)
            writeIndex(context, index)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Extensions", "setSensitiveHostAllowed failed for $id", e)
            false
        }
    }

    // ---- JSON plumbing (org.json ships with Android — no new dependency) ----

    private fun readIndex(context: Context): JSONObject {
        val file = indexFile(context)
        if (!file.exists()) {
            lastIndexCorrupt = false
            return JSONObject()
        }
        return try {
            val parsed = JSONObject(file.readText())
            lastIndexCorrupt = false
            parsed
        } catch (_: Exception) {
            // D-1: a corrupt index must not silently wipe the registry.
            // Fall back to the last good backup (written by writeIndex
            // before every overwrite); if that fails too, the registry
            // reads empty — and lastIndexCorrupt tells the screen to say
            // the settings were reset instead of failing silently.
            lastIndexCorrupt = true
            try {
                val backup = File(dir(context), BACKUP_NAME)
                if (backup.exists()) JSONObject(backup.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
        }
    }

    /** Atomic-ish write: temp file + rename, so a killed write never leaves
     *  a half-written index behind. Before overwriting, the current index
     *  is copied to .bak (D-1 fallback). The target is never deleted
     *  before the rename succeeds (N-15/B-16: the old fallback deleted
     *  the good index, then a failed rename lost everything). */
    private fun writeIndex(context: Context, index: JSONObject) {
        val d = dir(context)
        if (!d.exists()) d.mkdirs()
        val tmp = File(d, "$INDEX_NAME.tmp")
        tmp.writeText(index.toString())
        val target = indexFile(context)
        if (target.exists()) {
            try {
                target.copyTo(File(d, BACKUP_NAME), overwrite = true)
            } catch (_: Exception) { }
        }
        if (!tmp.renameTo(target)) {
            try {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            } catch (_: Exception) {
                // The old index is still intact — the write is lost, but
                // the registry is not.
            }
        }
        lastIndexCorrupt = false
    }

    private fun toJson(script: Userscript): JSONObject {
        return JSONObject().apply {
            put("id", script.id)
            put("name", script.name)
            put("version", script.version)
            put("description", script.description)
            put("matches", JSONArray(script.matches))
            put("runAt", if (script.runAt == RunAt.DOCUMENT_START) "document-start" else "document-end")
            put("enabled", script.enabled)
            put("disabledHosts", JSONArray(script.disabledHosts.toList()))
            put("allowedSensitiveHosts", JSONArray(script.allowedSensitiveHosts.toList()))
        }
    }

    private fun setEnabledInArray(index: JSONObject, id: String, enabled: Boolean): Boolean {
        val scripts = index.optJSONArray("scripts") ?: return false
        for (i in 0 until scripts.length()) {
            val obj = scripts.optJSONObject(i) ?: continue
            if (obj.optString("id") == id) {
                obj.put("enabled", enabled)
                return true
            }
        }
        return false
    }

    private fun updateHostList(existing: JSONArray?, host: String, disabled: Boolean): JSONArray {
        val hosts = jsonStringList(existing).toMutableSet()
        if (disabled) hosts.add(host) else hosts.remove(host)
        return JSONArray(hosts.toList())
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val s = array.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
