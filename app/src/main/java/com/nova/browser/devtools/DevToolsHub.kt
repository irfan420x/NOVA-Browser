package com.nova.browser.devtools

/**
 * The shared DevTools singletons — the one place the hooks in the shared
 * files (NovaClients.kt) and the dock screens meet.
 *
 * - [console]: fed by NovaWebChromeClient.onConsoleMessage (hook A).
 * - [network]: fed by NovaWebViewClient.shouldInterceptRequest (hook B).
 *
 * Both stores are thread-safe and capped at 200 entries; neither touches
 * a WebView, so the background-thread recorder call is safe.
 *
 * PRIVACY (W6 W3-4): the logs are NOT recorded all the time. Recording
 * is gated on Dev Mode ([devModeEnabled], set by the coordinator from
 * the Dev Mode toggle) — a console message can contain a page secret
 * (`console.log(token)`), so nothing is captured while the user is in
 * Normal Mode. The logs are also bound to one tab at a time
 * ([bindToTab]): opening the dock for a different tab clears both logs,
 * so one tab's messages never show in another tab's dock. Turning Dev
 * Mode off clears everything ([setDevMode(false)]). The stores stay
 * in-memory only — nothing here is ever written to a file.
 * The :incognito process has its own Hub instance (good — no cross-mode
 * leak), and the coordinator must never enable recording there.
 */
object DevToolsHub {
    // N-7: the stores are private — only the hub may touch them, so every
    // read/write goes through the Dev Mode / tab gate below. The dock
    // screens use the consoleSnapshot/consoleClear/... passthroughs.
    private val console = ConsoleStore()
    private val network = NetworkLog()

    /** True only while Dev Mode is on. Recording is a no-op when false. */
    @Volatile
    var devModeEnabled: Boolean = false
        private set

    /** Tab the logs currently belong to, or null when unbound. */
    @Volatile
    private var activeTabId: Long? = null

    /**
     * True when this process is the :incognito process. Recording and
     * HTTP history must never be enabled there (W6 N-2) — the hub's
     * protection is code, not just a doc comment.
     *
     * Deliberately JVM-pure (no android.* import): this file is compiled
     * by the JVM test suite. On Android /proc/self/cmdline holds the
     * process name (e.g. "com.nova.browser:incognito"); on the JVM test
     * host it holds the java binary path, which never ends in
     * ":incognito", so tests see false. A read failure fails CLOSED
     * (treated as incognito — recording refused) rather than risking a
     * leak in the private process.
     */
    fun isIncognitoProcess(): Boolean {
        return try {
            val raw = java.io.File("/proc/self/cmdline").readBytes()
                .toString(Charsets.UTF_8)
            raw.substringBefore('\u0000').endsWith(":incognito")
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Called by the coordinator when the Dev Mode toggle changes.
     * Turning Dev Mode off wipes both logs immediately.
     */
    fun setDevMode(enabled: Boolean) {
        // N-2: refuse to enable recording in the :incognito process.
        if (enabled && isIncognitoProcess()) return
        devModeEnabled = enabled
        if (!enabled) clearAll()
    }

    /**
     * Bind the logs to [tabId] — called by the coordinator when the dock
     * opens. Switching to a different tab clears both logs first, so the
     * dock never shows another tab's console/network data. Re-binding
     * the SAME tab is a no-op (the logs are kept) — the dock must not
     * open empty just because the coordinator binds before opening it.
     * Binding for the first time (from null) DOES clear: logs recorded
     * while unbound may mix several tabs, so they must never be shown
     * as one tab's logs.
     */
    fun bindToTab(tabId: Long) {
        // B1: only a same-tab re-bind keeps the logs. The original code
        // cleared on every bind, so the dock always opened empty.
        if (activeTabId != tabId) {
            console.clear()
            network.clear()
        }
        activeTabId = tabId
    }

    /** Tab the logs are currently bound to (null = unbound). */
    fun boundTabId(): Long? = activeTabId

    /**
     * Record a console message — the hook calls this, not
     * [console] directly. No-op unless Dev Mode is on. [tabId] is the tab
     * the message came from; messages from a tab other than the bound one
     * are dropped (the dock only ever shows its own tab).
     */
    fun recordConsole(tabId: Long, level: String, message: String, sourceId: String?, lineNumber: Int) {
        if (!devModeEnabled) return
        if (isIncognitoProcess()) return
        if (activeTabId != null && activeTabId != tabId) return
        console.add(level, message, sourceId, lineNumber)
    }

    /**
     * Record a network request — the hook calls this, not [network]
     * directly. Same gating as [recordConsole]. Returns the stored entry,
     * or null when the record was dropped. Thread-safe: safe to call from
     * the WebView worker threads inside shouldInterceptRequest.
     */
    fun recordNetwork(
        tabId: Long,
        url: String,
        method: String,
        blocked: Boolean = false,
        blockedKind: String? = null,
        isMainFrame: Boolean = false,
        durationMs: Long? = null
    ): NetworkLog.Entry? {
        if (!devModeEnabled) return null
        if (isIncognitoProcess()) return null
        if (activeTabId != null && activeTabId != tabId) return null
        return network.record(url, method, blocked, blockedKind, isMainFrame, durationMs)
    }

    /** Clear both logs (dock "Clear all" / Dev Mode exit). */
    fun clearAll() {
        console.clear()
        network.clear()
        activeTabId = null
    }

    /** Clear both logs WITHOUT unbinding the tab (dock "Clear all" button).
     *  Unlike [clearAll], the binding survives — after a clear, recording
     *  still belongs to the same tab (W5 D2). */
    fun clearLogs() {
        console.clear()
        network.clear()
    }

    /** Read-only access for the dock screens (stores are private, N-7). */
    fun consoleSnapshot(levelFilter: String): List<ConsoleMessage> = console.snapshot(levelFilter)
    fun consoleClear() = console.clear()
    fun consoleAddLocal(level: String, message: String) = console.addLocal(level, message)
    fun networkSnapshot(): List<NetworkLog.Entry> = network.snapshot()
    fun networkClear() = network.clear()
    /** B16: how many entries the 200-cap has dropped since the last clear. */
    fun networkDroppedCount(): Long = network.droppedCount()
}
