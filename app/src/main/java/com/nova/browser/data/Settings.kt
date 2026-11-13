package com.nova.browser.data

import android.content.Context

/** Search engines offered in Settings. Google is the default (user's choice). */
enum class SearchEngine(val id: String, val label: String) {
    GOOGLE("google", "Google"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    BING("bing", "Bing"),
    BRAVE("brave", "Brave Search");

    companion object {
        fun fromId(id: String?): SearchEngine =
            values().firstOrNull { it.id == id } ?: GOOGLE
    }
}

/** All user settings live in one SharedPreferences file ("nova_browser").
 *  Plain getters/setters — no DataStore, no new dependencies in v1.0.
 *
 *  [private] selects the separate private-mode file
 *  ("nova_browser_private"). The :incognito process must never write
 *  per-site choices (ad-block-off hosts, HTTPS exceptions) into the
 *  normal file — those sets reveal which sites were visited privately.
 *  Only display/search prefs are copied from the normal file at the
 *  start of a private session (see [copySafeFrom]); the per-site sets
 *  start empty and are wiped again by [clearPrivateSessionData]. */
class Settings(context: Context, private: Boolean = false) {
    private val prefs = context.getSharedPreferences(
        if (private) "nova_browser_private" else "nova_browser",
        Context.MODE_PRIVATE
    )

    var darkTheme: Boolean
        get() = prefs.getBoolean("ui_dark_theme", true)
        set(v) = prefs.edit().putBoolean("ui_dark_theme", v).apply()

    var searchEngine: SearchEngine
        get() = SearchEngine.fromId(prefs.getString("search_engine", null))
        set(v) = prefs.edit().putString("search_engine", v.id).apply()

    /** Global ad-block switch. Per-site exceptions live in [adBlockOffHosts]. */
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean("adblock_enabled", true)
        set(v) = prefs.edit().putBoolean("adblock_enabled", v).apply()

    /** YouTube Ad Skipper — auto-skips pre-roll and mid-roll video ads on
     *  YouTube by clicking Skip and seeking past unskippable ads. It is its
     *  own toggle (independent of the global ad-block switch) and defaults
     *  on. It never blocks the ad request — the ad starts, then is skipped
     *  instantly — which is the less-detectable approach against YouTube's
     *  anti-adblock wall. See assets/youtube-skipper.js. */
    var youtubeAdSkipper: Boolean
        get() = prefs.getBoolean("youtube_ad_skipper", true)
        set(v) = prefs.edit().putBoolean("youtube_ad_skipper", v).apply()

    /** Dev Mode: shows the DevTools / HTTP Client menu entries and enables
     *  DevTools recording (console + network logs). Off by default.
     *  Deliberately NOT copied to a private session (see copySafeFrom):
     *  DevTools must never record in :incognito. */
    var devMode: Boolean
        get() = prefs.getBoolean("dev_mode", false)
        set(v) = prefs.edit().putBoolean("dev_mode", v).apply()

    /** USB remote debugging (chrome://inspect). OFF by default, only
     *  meaningful when Dev Mode is on, and only with the user's explicit
     *  consent (Settings shows a warning). Never on by default, and never
     *  copied to a private session. */
    var webContentsDebugging: Boolean
        get() = prefs.getBoolean("web_contents_debugging", false)
        set(v) = prefs.edit().putBoolean("web_contents_debugging", v).apply()

    /** Suspend background tabs: free memory by unloading tabs idle for
     *  10 minutes (or on memory pressure) — they reload when you switch
     *  back. Form results and tabs with a download running are never
     *  unloaded (W1/W1-1). */
    var suspendBackgroundTabs: Boolean
        get() = prefs.getBoolean("suspend_background_tabs", true)
        set(v) = prefs.edit().putBoolean("suspend_background_tabs", v).apply()

    /** Upgrade http to https, except localhost/LAN hosts. */
    var httpsOnly: Boolean
        get() = prefs.getBoolean("https_only", true)
        set(v) = prefs.edit().putBoolean("https_only", v).apply()

    /** New tabs open in desktop mode by default. */
    var desktopModeDefault: Boolean
        get() = prefs.getBoolean("desktop_mode_default", false)
        set(v) = prefs.edit().putBoolean("desktop_mode_default", v).apply()

    /** Page text size, percent (50..200). */
    var textZoom: Int
        get() = prefs.getInt("text_zoom", 100).coerceIn(50, 200)
        set(v) = prefs.edit().putInt("text_zoom", v.coerceIn(50, 200)).apply()

    /** Hosts where the user turned JavaScript off (per-site toggle,
     *  W4). Mirrors [adBlockOffHosts]: the set reveals visited sites,
     *  so it is never copied to a private session. */
    var jsOffHosts: Set<String>
        get() = prefs.getStringSet("js_off_hosts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("js_off_hosts", v.toSet()).apply()

    fun isJsOffFor(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return jsOffHosts.any { off -> h == off || h.endsWith(".$off") }
    }

    fun setJsOffFor(host: String, off: Boolean) {
        val set = jsOffHosts.toMutableSet()
        if (off) set.add(host.lowercase()) else set.remove(host.lowercase())
        jsOffHosts = set
    }

    /** Hosts where the user turned desktop mode on (per-site toggle,
     *  W4). Same privacy rule as [jsOffHosts]. */
    var desktopModeHosts: Set<String>
        get() = prefs.getStringSet("desktop_mode_hosts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("desktop_mode_hosts", v.toSet()).apply()

    fun isDesktopModeFor(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return desktopModeHosts.any { on -> h == on || h.endsWith(".$on") }
    }

    fun setDesktopModeFor(host: String, on: Boolean) {
        val set = desktopModeHosts.toMutableSet()
        if (on) set.add(host.lowercase()) else set.remove(host.lowercase())
        desktopModeHosts = set
    }

    /** Search suggestions in the address bar (W4): as you type,
     *  keystrokes are sent to the active search engine to fetch
     *  suggestions. Off = history/bookmark suggestions only. Default
     *  ON. A display/search pref — safe to copy to a private session
     *  (the engine itself forces privateMode there). */
    var searchSuggestions: Boolean
        get() = prefs.getBoolean("search_suggestions", true)
        set(v) = prefs.edit().putBoolean("search_suggestions", v).apply()

    /** Reader-mode text size, percent (50..200). Persisted across
     *  articles (W4). Display pref — safe to copy to a private session. */
    var readerTextSize: Int
        get() = prefs.getInt("reader_text_size", 100).coerceIn(50, 200)
        set(v) = prefs.edit().putInt("reader_text_size", v.coerceIn(50, 200)).apply()

    /** Clear browsing data on exit (W4): history, cookies and cache are
     *  wiped when the browser closes cleanly. Does NOT run when the app
     *  is swiped away or killed — only a clean finish. Default OFF.
     *  Not copied to a private session: private sessions already wipe
     *  on close, unconditionally. */
    var clearOnExit: Boolean
        get() = prefs.getBoolean("clear_on_exit", false)
        set(v) = prefs.edit().putBoolean("clear_on_exit", v).apply()

    /** Hosts where the user turned the ad blocker off (per-site toggle). */
    var adBlockOffHosts: Set<String>
        get() = prefs.getStringSet("adblock_off_hosts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("adblock_off_hosts", v.toSet()).apply()

    fun isAdBlockOffFor(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return adBlockOffHosts.any { off ->
            h == off || h.endsWith(".$off")
        }
    }

    fun setAdBlockOffFor(host: String, off: Boolean) {
        val set = adBlockOffHosts.toMutableSet()
        if (off) set.add(host.lowercase()) else set.remove(host.lowercase())
        adBlockOffHosts = set
    }

    /** Hosts where the user accepted loading plain http after an
     *  HTTPS-only upgrade failed (per-site, explicit choice only). */
    var httpsExceptionHosts: Set<String>
        get() = prefs.getStringSet("https_exception_hosts", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("https_exception_hosts", v.toSet()).apply()

    fun isHttpsException(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return httpsExceptionHosts.any { ex -> h == ex || h.endsWith(".$ex") }
    }

    fun addHttpsException(host: String) {
        val set = httpsExceptionHosts.toMutableSet()
        set.add(host.lowercase())
        httpsExceptionHosts = set
    }

    /** Custom filter rules added by the user (one rule per line, same
     *  syntax as assets/adblock.txt). Parsed on top of the bundled list. */
    var customFilterRules: String
        get() = prefs.getString("custom_filter_rules", "") ?: ""
        set(v) = prefs.edit().putString("custom_filter_rules", v).apply()

    /** First-run flag: default home shortcuts are seeded once. */
    var shortcutsSeeded: Boolean
        get() = prefs.getBoolean("shortcuts_seeded", false)
        set(v) = prefs.edit().putBoolean("shortcuts_seeded", v).apply()

    /** The user was already asked about the default-browser role. */
    var defaultBrowserPromptShown: Boolean
        get() = prefs.getBoolean("default_browser_prompt_shown", false)
        set(v) = prefs.edit().putBoolean("default_browser_prompt_shown", v).apply()

    /** Copies only the prefs that are safe to share with a private
     *  session — display and search choices, not per-site history.
     *  Deliberately NOT copied: adBlockOffHosts, httpsExceptionHosts,
     *  jsOffHosts and desktopModeHosts (they reveal which sites were
     *  visited), clearOnExit (private sessions wipe on close anyway),
     *  and shortcutsSeeded. */
    fun copySafeFrom(other: Settings) {
        darkTheme = other.darkTheme
        searchEngine = other.searchEngine
        adBlockEnabled = other.adBlockEnabled
        youtubeAdSkipper = other.youtubeAdSkipper
        suspendBackgroundTabs = other.suspendBackgroundTabs
        httpsOnly = other.httpsOnly
        desktopModeDefault = other.desktopModeDefault
        textZoom = other.textZoom
        customFilterRules = other.customFilterRules
        searchSuggestions = other.searchSuggestions
        readerTextSize = other.readerTextSize
    }

    /** Drops the per-site choices of a private session. Called at the
     *  start of a private session (in case the previous one was killed
     *  instead of closed) — the full wipe on close is in
     *  PrivateActivity.onDestroy. */
    fun clearPrivateSessionData() {
        adBlockOffHosts = emptySet()
        httpsExceptionHosts = emptySet()
        jsOffHosts = emptySet()
        desktopModeHosts = emptySet()
    }
}
