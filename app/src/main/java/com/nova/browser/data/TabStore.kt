package com.nova.browser.data

import android.content.Context
import android.os.Looper
import android.view.ViewGroup

/**
 * W1 — Persistence for tab groups + recently-closed tabs, and the
 * background-tab suspension executor.
 *
 * Storage is a dedicated SharedPreferences file ("nova_browser_tab_store")
 * — NOT BrowserDb (untouched) and NOT the main "nova_browser" prefs, so
 * group/closed-tab data can be wiped independently and never mixes with
 * per-site private-mode choices. All data is on-device; incognito tabs
 * are never recorded here.
 *
 * Group membership persistence: tab ids are session-local, so
 * [saveGroups] stores each group's member tab URLs (see
 * TabStoreCodec.encodeGroups) and [loadGroups] re-links them to the live
 * tabs by URL, setting [Tab.groupId]. A tab whose URL changed since the
 * last save will not re-link — that is the honest limit of URL-based
 * re-linking until BrowserState.saveTabs can persist groupId directly
 * (hook spec HOOK-E in the W1 report).
 *
 * PRIVATE MODE (W6 H-3): when [isPrivate] is true (the :incognito
 * process), every persistence method is a no-op — group membership and
 * recently-closed entries are private-tab URLs, and writing them into
 * the shared prefs file would leak them into normal mode. Groups live
 * in memory only in a private session (the switcher loads nothing).
 */
class TabStore(private val context: Context, private val isPrivate: Boolean = false) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Tab groups ----

    /** Load groups and re-link them to [tabs] by member URL. Tabs that
     *  re-link get their [Tab.groupId] set. Groups whose tabs are all
     *  gone are still returned (an empty group survives its tabs).
     *  In private mode returns an empty list (H-3). */
    fun loadGroups(tabs: List<Tab>): List<TabGroup> {
        if (isPrivate) return emptyList()
        val encoded = prefs.getString(KEY_GROUPS, "") ?: ""
        val groups = TabStoreCodec.decodeGroups(encoded)
        if (groups.isEmpty() || tabs.isEmpty()) return groups
        // D-4: re-link by URL only when exactly one live tab has it —
        // associateBy kept the LAST tab for a duplicated URL, so the
        // other tab silently lost its group (and a URL in two groups
        // landed in both groups' tabIds while groupId picked one).
        val byUrl = tabs.filter { it.url.isNotBlank() }.groupBy { it.url }
        val byId = tabs.associateBy { it.id }
        return groups.map { group ->
            val ids = group.memberUrls.mapNotNull { url -> byUrl[url]?.singleOrNull()?.id }
            ids.forEach { id -> byId[id]?.groupId = group.id }
            group.copy(tabIds = ids)
        }
    }

    /** Save groups. Member URLs are derived from the live tabs' groupId,
     *  so callers must set [Tab.groupId] before calling this.
     *  No-op in private mode (H-3). */
    fun saveGroups(groups: List<TabGroup>, tabs: List<Tab>) {
        if (isPrivate) return
        val withMembers = groups.map { group ->
            val urls = tabs.filter { it.groupId == group.id }
                .map { it.url }.filter { it.isNotBlank() }
            group.copy(memberUrls = urls)
        }
        prefs.edit().putString(KEY_GROUPS, TabStoreCodec.encodeGroups(withMembers)).apply()
    }

    fun clearGroups() {
        if (isPrivate) return
        prefs.edit().remove(KEY_GROUPS).apply()
    }

    // ---- Recently-closed tabs ----

    fun loadClosed(): List<ClosedTab> {
        if (isPrivate) return emptyList()
        val encoded = prefs.getString(KEY_CLOSED, "") ?: ""
        return TabStoreCodec.decodeClosed(encoded)
            .filter { !it.incognito && it.url.isNotBlank() }
            .take(RecentlyClosedStack.MAX_CLOSED)
    }

    fun saveClosed(stack: List<ClosedTab>) {
        if (isPrivate) return
        // P-4: commit(), not apply() — a kill within milliseconds of
        // closing a tab must not lose the entry.
        prefs.edit()
            .putString(KEY_CLOSED, TabStoreCodec.encodeClosed(stack.take(RecentlyClosedStack.MAX_CLOSED)))
            .commit()
    }

    /** Record a closed tab. Incognito and blank-URL tabs are refused. */
    fun pushClosed(entry: ClosedTab) {
        if (isPrivate) return
        if (entry.incognito || entry.url.isBlank()) return
        saveClosed(RecentlyClosedStack.push(loadClosed(), entry))
    }

    fun removeClosedAt(index: Int) {
        if (isPrivate) return
        saveClosed(RecentlyClosedStack.popAt(loadClosed(), index))
    }

    fun clearClosed() {
        if (isPrivate) return
        prefs.edit().remove(KEY_CLOSED).commit()
    }

    /** D-3: when a group is deleted, clear its id from closed entries —
     *  otherwise a ClosedTab still holding the old groupId restores into
     *  the NEXT group that reuses the id (TabGroups.nextId is max+1). */
    fun clearClosedGroup(groupId: Long) {
        if (isPrivate) return
        val stack = loadClosed()
        if (stack.none { it.groupId == groupId }) return
        saveClosed(stack.map { if (it.groupId == groupId) it.copy(groupId = null) else it })
    }

    /** W1-1 — True while ANY download enqueued by this app is still
     *  running or pending (DownloadManager query; no permission
     *  needed for the app's own downloads). There is no per-tab
     *  completion callback, so TabSuspension uses this global check:
     *  while a download runs anywhere, no tab is suspended, and once
     *  nothing runs the per-tab [Tab.hasActiveTransfer] flags are
     *  cleared (self-clearing — nothing else can observe completion).
     *  B-3: STATUS_PAUSED is NOT in the set — one paused download
     *  blocked all suspension indefinitely. A paused download is not
     *  actively transferring; the per-tab flag still exempts the tab
     *  that started it until the next clear. Any query failure fails
     *  CLOSED to "a download is active" (true): a broken query must
     *  never unblock suspension mid-download. */
    fun hasActiveDownload(): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE)
                as? android.app.DownloadManager ?: return true
            val query = android.app.DownloadManager.Query().setFilterByStatus(
                android.app.DownloadManager.STATUS_RUNNING or
                    android.app.DownloadManager.STATUS_PENDING
            )
            dm.query(query)?.use { cursor -> cursor.count > 0 } ?: true
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        const val PREFS_NAME = "nova_browser_tab_store"
        const val KEY_GROUPS = "tab_groups_v1"
        const val KEY_CLOSED = "recently_closed_v1"
    }
}

/**
 * Destroys background tabs' WebViews to free memory, keeping tab
 * metadata so switching back restores the page. MUST be called on the
 * main thread (WebView.destroy is main-thread-only) — onTrimMemory runs
 * on the main thread, and the idle path must post to it.
 *
 * Safety (mirrors TabSuspensionPolicy): never the current tab, never an
 * incognito tab's WebView while the private session is live, never a tab
 * that is already discarded or has no WebView — and, since W1-1, never
 * a POST-result tab ([Tab.wasPost]: restore is a plain GET) or a tab
 * with an active transfer ([Tab.hasActiveTransfer], plus the global
 * DownloadManager check in [suspendBackgroundTabs]). After suspension
 * [Tab.initialUrlLoaded] is reset to false so the existing
 * BrowserState.ensureWebView path reloads tab.url when the tab is
 * selected again — no BrowserState change needed for the recreate half.
 */
object TabSuspension {

    /** Destroy one tab's WebView and mark it discarded. No-op when the
     *  tab has no WebView. Callers must have checked the policy first
     *  (or use [suspendBackgroundTabs]). Main thread only — enforced
     *  (W6 H-4): WebView.destroy() from a background thread crashes. */
    fun suspendTab(tab: Tab) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw IllegalStateException("suspendTab must run on the main thread")
        }
        val webView = tab.webView ?: return
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {
            // A WebView mid-teardown must not take the browser down.
        }
        tab.webView = null
        tab.discarded = true
        tab.initialUrlLoaded = false
        tab.isLoading = false
        tab.progress = 0
        // B-7 / W6 L-4: a suspended tab reloads as a fresh visit — its
        // nav state must not claim a history that no longer exists, and
        // an SSL dialog / find bar must not outlive the dead WebView.
        tab.canGoBack = false
        tab.canGoForward = false
        tab.sslHandler = null
        tab.sslError = null
        tab.sslErrorUrl = null
        tab.findActive = false
        tab.findQuery = ""
        tab.findResult = ""
    }

    /**
     * Suspend every background tab the policy selects. Returns how many
     * WebViews were destroyed. [trimLevel] is the ComponentCallbacks2
     * level from onTrimMemory, or -1 for a periodic idle check.
     * [suspendEnabled] is Settings.suspendBackgroundTabs (hook HOOK-B).
     *
     * W1-1: when [context] is supplied (the onTrimMemory hook passes
     * applicationContext), a DownloadManager query runs first — while
     * ANY app download is active, NOTHING is suspended (a running
     * download must never have its tab's WebView destroyed under it);
     * when nothing is active, stale per-tab hasActiveTransfer flags
     * are cleared. POST-result tabs are exempt inside the policy
     * itself ([Tab.wasPost]), so they are safe even when [context] is
     * null (periodic idle checks).
     *
     * W4: [hasActiveCustomDownload] is the same gate for the custom
     * (pause/resume) downloader, which DownloadManager never sees —
     * a running custom download blocks suspension exactly like a
     * DownloadManager one. The coordinator (MainActivity) passes
     * state.hasActiveCustomDownload.
     */
    fun suspendBackgroundTabs(
        tabs: List<Tab>,
        currentTabId: Long?,
        trimLevel: Int,
        suspendEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        context: Context? = null,
        hasActiveCustomDownload: Boolean = false
    ): Int {
        if (!suspendEnabled) return 0
        // W4: a running custom download blocks suspension on every path
        // (including the context=null idle path) — the DownloadManager
        // check below cannot see it.
        if (hasActiveCustomDownload) return 0
        if (context != null) {
            if (TabStore(context).hasActiveDownload()) return 0
            tabs.forEach { it.hasActiveTransfer = false }
        }
        val candidates = tabs.map {
            TabSuspensionPolicy.SuspendableTab(
                id = it.id,
                incognito = it.incognito,
                lastUsedMs = it.lastUsedMs,
                discarded = it.discarded,
                hasWebView = it.webView != null,
                wasPost = it.wasPost,
                hasActiveTransfer = it.hasActiveTransfer
            )
        }
        val toSuspend = TabSuspensionPolicy.tabsToSuspend(
            candidates, currentTabId, nowMs, trimLevel, suspendEnabled
        ).map { it.id }.toSet()
        var count = 0
        for (tab in tabs) {
            if (toSuspend.contains(tab.id)) {
                suspendTab(tab)
                count++
            }
        }
        return count
    }

    /** Mark a tab as just used (selected). Called by the switcher's
     *  select handler; the BrowserState.selectTab hook (HOOK-C) does the
     *  same for selections made elsewhere. */
    fun markTabUsed(tab: Tab, nowMs: Long = System.currentTimeMillis()) {
        tab.touch(nowMs)
    }
}
