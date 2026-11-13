package com.nova.browser.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nova.browser.adblock.AdBlockEngine
import com.nova.browser.adblock.FilterLists
import com.nova.browser.data.BrowserDb
import com.nova.browser.data.CustomDownload
import com.nova.browser.data.DownloadProgress
import com.nova.browser.data.DownloadRow
import com.nova.browser.data.DownloadState
import com.nova.browser.data.Downloads
import com.nova.browser.data.ErrorLog
import com.nova.browser.data.SavedTab
import com.nova.browser.data.Settings
import com.nova.browser.data.Tab
import com.nova.browser.data.TabStore
import com.nova.browser.data.TabSuspension
import com.nova.browser.reader.ArticleContent
import com.nova.browser.web.LocalEntry
import com.nova.browser.web.NovaWebView
import com.nova.browser.web.PerSiteSettings
import com.nova.browser.web.Suggestion
import com.nova.browser.web.SuggestionEngine
import com.nova.browser.web.SuggestionLocalSource
import com.nova.browser.web.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class BrowserScreen { BROWSER, TABS, BOOKMARKS, DOWNLOADS, SETTINGS, ERROR_LOG, EXTENSIONS, DEVTOOLS, HTTP_CLIENT }

/** One custom (pause/resume) download job (W4). Held in memory by
 *  BrowserState so DownloadsScreen can list it while it runs AND after
 *  it finishes — Downloads.list() only knows DownloadManager rows, so a
 *  MediaStore-published custom download would otherwise vanish from the
 *  list even though the file is in Downloads/NOVA/Browser/.
 *
 *  LIMIT: the list is session-scoped. If the app is killed, completed
 *  custom downloads disappear from the app's list (the file stays on
 *  disk, visible in NOVA Files). DownloadManager downloads do not have
 *  this limit — their rows live in the DB.
 *
 *  [latestProgress] is written from the downloader worker thread and
 *  read by the Downloads screen's 1-second poll — no Compose state is
 *  needed for the numbers. [publishedUri] is the MediaStore Uri of the
 *  published file (normal mode only); the private copy in filesDir /
 *  cacheDir is kept either way so a failed publish never destroys the
 *  only copy.
 *
 *  [id] is unique per job (H1): the Downloads list keys rows by it,
 *  never by URL+name — two jobs for the same URL+name must never
 *  share a LazyColumn key. */
class CustomDownloadJob(
    val download: CustomDownload,
    val fileName: String,
    val url: String,
    val privateMode: Boolean
) {
    val id: String = java.util.UUID.randomUUID().toString()
    @Volatile var latestProgress: DownloadProgress? = null
    @Volatile var done: Boolean = false
    @Volatile var publishedUri: Uri? = null

    /** The file as it actually exists on disk — [CustomDownload] may
     *  uniquify the name (`name (1).ext`) on a collision, so the
     *  requested [fileName] is not always the real name (L6). */
    val displayName: String get() = download.file.name
}

/** Default home shortcuts seeded on first run (pinned bookmarks). */
val DEFAULT_SHORTCUTS = listOf(
    "Google" to "https://www.google.com",
    "YouTube" to "https://www.youtube.com",
    "Wikipedia" to "https://www.wikipedia.org",
    "GitHub" to "https://github.com"
)

/**
 * Owns everything the UI needs: tabs, the current screen, settings, the
 * database and the ad-block engine. Created once per activity by
 * MainActivity / PrivateActivity, which supply the WebView factory.
 *
 * In [privateMode] (PrivateActivity, its own process) tabs are never
 * saved, history is never recorded and the state is wiped when the last
 * tab closes ([onPrivateEmpty] fires).
 */
class BrowserState(
    val context: Context,
    val privateMode: Boolean = false,
    private val webViewFactory: (Tab) -> WebView,
    private val onPrivateEmpty: (() -> Unit)? = null
) {
    /** Private mode uses its own SharedPreferences file (see Settings), so
     *  per-site choices made privately never leak into normal mode. Display
     *  prefs (theme, search engine, text size, ad-block on/off, HTTPS-only,
     *  desktop default, custom rules) are copied from the normal file each
     *  time a private session starts, so the private browser looks and
     *  behaves like the user's browser — without inheriting the site
     *  exceptions, which would reveal which sites were visited privately. */
    val settings = Settings(context, privateMode)
    val db = BrowserDb(context)
    val tabStore = TabStore(context, privateMode)
    var adBlock: AdBlockEngine = try {
        FilterLists.load(context, settings.customFilterRules)
    } catch (e: Exception) {
        ErrorLog.log(context, "Browser", "Failed to load filter lists", e)
        AdBlockEngine.parse("")
    }
        private set

    // REMOVED in v2.0 (W6 N-3): the standalone skipper injector is gone —
    // the skipper is registry-owned and injected by NovaClients
    // .injectExtensions, gated by settings.youtubeAdSkipper AND the
    // sensitive-page rule. The old youtubeSkipperJs/shouldInjectSkipper
    // bypassed S-1 on YouTube login pages.

    /** Bridge for the DevTools dock. Called on the main thread only
     *  (the dock calls it from composition / Main-dispatcher coroutines),
     *  so evaluateJavascript is legal here. */
    val devToolsHost: DevToolsHost = object : DevToolsHost {
        override fun evaluateJs(script: String, onResult: (String?) -> Unit) {
            val webView = currentTab?.webView
            if (webView == null) { onResult(null); return }
            try {
                webView.evaluateJavascript(script) { result -> onResult(result) }
            } catch (_: Exception) { onResult(null) }
        }
        override fun currentUrl(): String? = currentTab?.url?.takeIf { it.isNotBlank() }
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val tabs = mutableStateListOf<Tab>()
    var currentTabId by mutableStateOf<Long?>(null)
    val currentTab: Tab? get() = tabs.firstOrNull { it.id == currentTabId }

    /** Downloads started in private mode. They live only in memory — the
     *  shared downloads table is never written from a private session, so
     *  a private download never appears in the normal Downloads list. */
    val privateDownloads = mutableStateListOf<DownloadRow>()

    /** Custom (pause/resume) download jobs (W4) — in-progress AND
     *  completed. See [CustomDownloadJob]. */
    val customDownloads = mutableStateListOf<CustomDownloadJob>()

    /** True while any custom download job is actually transferring (W4)
     *  — fed to the tab-suspension gate so a tab is never suspended
     *  mid-download. H3: a PAUSED job is NOT transferring (same reasoning
     *  as the DM STATUS_PAUSED exclusion) — counting it would disable
     *  suspension forever for a forgotten paused download. */
    val hasActiveCustomDownload: Boolean
        get() = customDownloads.any {
            it.download.state == DownloadState.DOWNLOADING ||
                it.download.state == DownloadState.QUEUED
        }

    /** Search-suggestion engine (W4). The local source reads history +
     *  bookmarks from the DB — suggestDebounced runs it on a worker
     *  thread, never the main thread. In private mode the engine
     *  returns nothing (the caller passes tab.incognito), so no
     *  keystroke leaves the device and no private history can leak
     *  into the dropdown. */
    val suggestionEngine = SuggestionEngine(object : SuggestionLocalSource {
        override fun historyEntries(query: String): List<LocalEntry> =
            try {
                db.searchHistory(query, 20)
                    .map { LocalEntry(it.title, it.url, it.visitCount, it.visitedAt) }
            } catch (_: Exception) { emptyList() }

        override fun bookmarkEntries(query: String): List<LocalEntry> =
            try {
                db.listBookmarks()
                    .filter {
                        it.title.contains(query, ignoreCase = true) ||
                            it.url.contains(query, ignoreCase = true)
                    }
                    .take(20)
                    .map { LocalEntry(it.title, it.url, 1, it.createdAt) }
            } catch (_: Exception) { emptyList() }
    })

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Post [action] to the main thread. Download callbacks run on the
     *  downloader worker thread (H2) — Compose state (status, the jobs
     *  list) must only be touched from the main thread, so BrowserHost
     *  routes its onResult/onCancelled bodies through here. */
    fun postOnMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    /** True once a private session has started closing (H2). Set at the
     *  top of PrivateActivity.onDestroy, before jobs are cancelled and
     *  dirs are wiped: any download failure after this point must NOT
     *  fall back to DownloadManager, or a private file would land on
     *  disk after the wipe already ran. */
    @Volatile var privateSessionClosing: Boolean = false

    /** Address-bar suggestions from the engine (W4). Written from the
     *  engine's worker thread — always posted to the main thread first
     *  (see [postSuggestions]). */
    var addressSuggestions by mutableStateOf<List<Suggestion>>(emptyList())
        private set

    fun postSuggestions(results: List<Suggestion>) {
        mainHandler.post { addressSuggestions = results }
    }

    fun clearSuggestions() {
        suggestionEngine.cancelPending()
        addressSuggestions = emptyList()
    }

    /** Reader mode (W4): the article currently shown, or null when the
     *  reader is closed. Set by the menu action via ReaderRunner;
     *  cleared when the underlying page navigates away (NovaClients
     *  re-extracts and closes it on a null result). */
    var readerArticle by mutableStateOf<ArticleContent?>(null)

    /** The URL [readerArticle] was extracted from (W4) — NovaClients
     *  re-extracts only when the page URL differs from this, so the
     *  reader is not re-extracted on every onPageFinished. */
    var readerUrl by mutableStateOf<String?>(null)

    /** Per-site settings sheet (W4). [siteSettingsPrevious] is the state
     *  before the latest change — the coordinator keeps it so the sheet
     *  can show its reload note and the reload fires once, debounced. */
    var siteSettingsOpen by mutableStateOf(false)
    var siteSettingsPrevious by mutableStateOf<PerSiteSettings?>(null)

    /** W4: debounced reload for per-site changes (JavaScript and
     *  desktop mode need a reload to take effect). Each call re-arms
     *  the 300 ms timer, so rapid toggles collapse into one reload.
     *
     *  M6: the reload fires only if the same tab is still current and
     *  still on the same host — without that, toggling then switching
     *  tabs within 300 ms reloads the OTHER tab. Cancel it on sheet
     *  dismiss, tab switch and onCleared (see cancelPendingSiteReload). */
    private var siteReloadRunnable: Runnable? = null

    fun reloadAfterSiteChangeDebounced(host: String) {
        siteReloadRunnable?.let { mainHandler.removeCallbacks(it) }
        val tabId = currentTabId
        val r = Runnable {
            siteReloadRunnable = null
            val tab = currentTab
            if (tab != null && tab.id == tabId &&
                com.nova.browser.web.UrlUtils.extractHost(tab.url ?: "") == host) {
                reloadCurrent()
            }
        }
        siteReloadRunnable = r
        mainHandler.postDelayed(r, 300)
    }

    /** M6: drop a pending site-settings reload (sheet dismissed, tab
     *  switched, browser cleared) so it can never reload the wrong tab. */
    fun cancelPendingSiteReload() {
        siteReloadRunnable?.let { mainHandler.removeCallbacks(it) }
        siteReloadRunnable = null
    }

    /** Per-site settings for [host], from the Settings sets (W4). */
    fun perSiteSettingsFor(host: String): PerSiteSettings = PerSiteSettings(
        host = host,
        javaScriptEnabled = !settings.isJsOffFor(host),
        desktopMode = settings.isDesktopModeFor(host),
        adBlockEnabled = !settings.isAdBlockOffFor(host)
    )

    /** Apply a per-site change (W4): persist the sets in the current
     *  Settings file (the NORMAL file in a normal session, the PRIVATE
     *  file in a private session — the sheet IS reachable from
     *  PrivateActivity, and per-site choices made privately must stay
     *  in the private file that is wiped with the session — L4), then
     *  apply JavaScript + desktop mode to the live WebView. Ad-block
     *  applies on the next load, so it needs no live change. The caller
     *  reloads once (debounced) when [PerSiteSettings.needsReloadAfter]
     *  says so. */
    fun applyPerSiteSettings(next: PerSiteSettings) {
        settings.setJsOffFor(next.host, !next.javaScriptEnabled)
        settings.setDesktopModeFor(next.host, next.desktopMode)
        settings.setAdBlockOffFor(next.host, !next.adBlockEnabled)
        val tab = currentTab ?: return
        val webView = tab.webView ?: return
        try { webView.settings.javaScriptEnabled = next.javaScriptEnabled } catch (_: Exception) { }
        if (next.desktopMode != tab.desktopMode) {
            tab.desktopMode = next.desktopMode
            try { NovaWebView.applyDesktopMode(webView, tab) } catch (_: Exception) { }
        }
    }

    var screen by mutableStateOf(BrowserScreen.BROWSER)
    var menuExpanded by mutableStateOf(false)
    var shieldOpen by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var findBarForTabId by mutableStateOf<Long?>(null)
    var addressEditing by mutableStateOf(false)

    /** Which tab the Bookmarks screen opens on (0 = Bookmarks, 1 = History). */
    var bookmarksTab by mutableStateOf(0)

    /** True while the :incognito process is alive with private tabs. */
    var privateSessionActive by mutableStateOf(false)

    private var nextTabId = 1L
    private val maxTabs = 12

    init {
        if (privateMode) {
            // Fresh private session: drop any per-site choices left behind
            // by a previous private session that was killed instead of
            // closed, then copy the safe display prefs from normal mode.
            settings.clearPrivateSessionData()
            settings.copySafeFrom(Settings(context))
            newTab("")
        } else {
            restoreTabs()
        }
        // Home shortcuts are seeded into the SHARED bookmarks table — never
        // from a private session.
        if (!privateMode && !settings.shortcutsSeeded) {
            DEFAULT_SHORTCUTS.forEach { (title, url) ->
                db.addBookmark(url, title)
                db.setPinned(url, true)
            }
            settings.shortcutsSeeded = true
        }
    }

    // ---- Tabs ----

    fun newTab(url: String = "", title: String = "", incognito: Boolean = false, select: Boolean = true): Tab {
        val makeIncognito = incognito || privateMode
        if (tabs.size >= maxTabs) {
            status("Too many tabs open — close one first")
            return currentTab ?: tabs.first()
        }
        val tab = Tab(
            id = nextTabId++,
            url = url,
            title = title,
            incognito = makeIncognito
        )
        tab.desktopMode = settings.desktopModeDefault
        tabs.add(tab)
        if (select) {
            currentTabId = tab.id
            screen = BrowserScreen.BROWSER
        }
        if (!makeIncognito) saveTabs()
        return tab
    }

    fun closeTab(tab: Tab) {
        if (!tab.incognito && tab.url.isNotBlank()) tabStore.pushClosed(tab.toClosedTab())
        val index = tabs.indexOf(tab)
        val wasCurrent = tab.id == currentTabId
        tabs.remove(tab)
        destroyWebView(tab)
        if (!tab.incognito) saveTabs()
        if (tabs.isEmpty()) {
            if (privateMode) {
                onPrivateEmpty?.invoke()
                return
            }
            newTab("")
            return
        }
        if (wasCurrent) {
            val next = tabs.getOrNull(index.coerceAtMost(tabs.size - 1)) ?: tabs.last()
            currentTabId = next.id
        }
    }

    fun selectTab(tab: Tab) {
        TabSuspension.markTabUsed(tab)
        // M6: a site-settings reload scheduled for the old tab must not
        // fire for the new one.
        cancelPendingSiteReload()
        currentTabId = tab.id
        screen = BrowserScreen.BROWSER
    }

    fun ensureWebView(tab: Tab): WebView {
        tab.webView?.let { return it }
        val webView = webViewFactory(tab)
        tab.webView = webView
        // P-3: the tab is live again only now — clear the Suspended badge
        // once the WebView actually exists, not on select.
        tab.discarded = false
        // A tab created with a URL (restored session, external link, new
        // window) has no WebView yet — the fresh WebView must be told to
        // load the URL, or the tab stays blank forever. loadUrl() and
        // loadHttpAnyway() do their own load, so they set
        // initialUrlLoaded first to avoid a double load here.
        if (tab.url.isNotBlank() && !tab.initialUrlLoaded) {
            tab.initialUrlLoaded = true
            try {
                webView.loadUrl(tab.url)
            } catch (e: Exception) {
                tab.error = "Couldn't load this page"
                ErrorLog.log(context, "Browser", "ensureWebView initial load failed for ${tab.url}", e)
            }
        }
        return webView
    }

    private fun destroyWebView(tab: Tab) {
        try {
            (tab.webView?.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView?.destroy()
        } catch (_: Exception) { }
        tab.webView = null
    }

    // ---- Navigation ----

    /** Load a full URL in [tab], applying the HTTPS-only upgrade first. */
    fun loadUrl(tab: Tab, rawUrl: String) {
        var url = rawUrl.trim()
        if (url.isEmpty()) return
        var upgraded = false
        if (settings.httpsOnly && UrlUtils.shouldUpgradeToHttps(url) &&
            !settings.isHttpsException(UrlUtils.extractHost(url))
        ) {
            url = url.replaceFirst("http://", "https://", ignoreCase = true)
            upgraded = true
        }
        tab.wasUpgradedFromHttp = upgraded
        tab.url = url
        tab.error = null
        // The load below is the initial load for this URL — mark it so
        // ensureWebView() does not load the same URL a second time.
        tab.initialUrlLoaded = true
        try {
            ensureWebView(tab).loadUrl(url)
        } catch (e: Exception) {
            tab.error = "Couldn't load this page"
            ErrorLog.log(context, "Browser", "loadUrl failed for $url", e)
        }
    }

    /** Resolve address-bar input (URL or search) and load it in the current tab. */
    fun loadInput(input: String) {
        val url = UrlUtils.resolveInput(input, settings.searchEngine.id)
        if (url.isEmpty()) return
        val tab = currentTab ?: newTab()
        loadUrl(tab, url)
        screen = BrowserScreen.BROWSER
    }

    fun goHome() {
        val tab = currentTab ?: newTab()
        tab.url = ""
        tab.title = ""
        tab.error = null
        tab.wasUpgradedFromHttp = false
        try {
            tab.webView?.loadUrl("about:blank")
        } catch (_: Exception) { }
        screen = BrowserScreen.BROWSER
    }

    fun reloadCurrent() {
        try { currentTab?.webView?.reload() } catch (_: Exception) { }
    }

    /** Retry the current page over plain http (only offered when the
     *  HTTPS-only upgrade is what failed). Records an explicit per-site
     *  exception so the next visit does not upgrade again. */
    fun loadHttpAnyway(tab: Tab) {
        val host = UrlUtils.extractHost(tab.url) ?: return
        settings.addHttpsException(host)
        val httpUrl = tab.url.replaceFirst("https://", "http://", ignoreCase = true)
        tab.wasUpgradedFromHttp = false
        tab.url = httpUrl
        tab.error = null
        tab.initialUrlLoaded = true
        try {
            ensureWebView(tab).loadUrl(httpUrl)
        } catch (e: Exception) {
            tab.error = "Couldn't load this page"
            ErrorLog.log(context, "Browser", "loadHttpAnyway failed for $httpUrl", e)
        }
    }

    fun recordVisit(url: String, title: String) {
        ioScope.launch { db.recordVisit(url, title) }
    }

    // ---- Bookmarks ----

    /** Toggle the bookmark for the tab's URL. Returns the new state.
     *  P-2 (W6 baseline): a private tab must never write the shared
     *  bookmarks table — the star is also hidden in private mode. */
    fun toggleBookmark(tab: Tab): Boolean {
        if (privateMode || tab.incognito) return false
        val url = tab.url
        if (url.isBlank()) return false
        return if (db.isBookmarked(url)) {
            db.removeBookmark(url)
            false
        } else {
            db.addBookmark(url, tab.title.ifBlank { url })
            true
        }
    }

    fun isBookmarked(url: String): Boolean =
        url.isNotBlank() && db.isBookmarked(url)

    // ---- Tab sessions ----

    fun saveTabs() {
        if (privateMode) return
        try {
            val saved = tabs
                .filter { !it.incognito && it.url.isNotBlank() }
                .map { SavedTab(it.url, it.title, 0, it.groupId) }
            db.saveTabs(saved)
        } catch (_: Exception) { }
    }

    private fun restoreTabs() {
        val saved = try { db.loadTabs() } catch (_: Exception) { emptyList() }
        if (saved.isEmpty()) {
            newTab("")
            return
        }
        saved.forEach {
            val tab = newTab(it.url, title = it.title, select = false)
            // D-2: group membership survives a kill via the tabs table —
            // loadGroups' URL re-link is only the fallback for tabs whose
            // URL changed since the last save.
            tab.groupId = it.groupId
        }
        // Persist once more now that every restored tab carries its group.
        saveTabs()
        currentTabId = tabs.firstOrNull()?.id
    }

    // ---- Browsing data ----

    /** Clear history / cookies+site data / cache. Each part is independent
     *  and confirmed by the caller before this runs. In private mode there
     *  is no private history to clear — and "Clear history" must never
     *  wipe the NORMAL history from a private session, so the history
     *  part is silently skipped there (the Settings row is hidden too). */
    fun clearBrowsingData(clearHistory: Boolean, clearCookies: Boolean, clearCache: Boolean) {
        try {
            if (clearHistory && !privateMode) db.clearHistory()
            if (clearCookies) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                currentTab?.webView?.clearFormData()
            }
            if (clearCache) {
                currentTab?.webView?.clearCache(true)
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Browser", "Clear browsing data failed", e)
        }
    }

    fun status(msg: String) {
        status = msg
    }

    /** Rebuild the ad-block engine after the user edits custom rules. */
    fun reloadFilterLists() {
        try {
            adBlock = FilterLists.load(context, settings.customFilterRules)
        } catch (e: Exception) {
            ErrorLog.log(context, "Browser", "Reload filter lists failed", e)
        }
    }

    fun onCleared() {
        cancelPendingSiteReload()
        ioScope.cancel()
    }
}
