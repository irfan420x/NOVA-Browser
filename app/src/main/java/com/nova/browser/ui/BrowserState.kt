package com.nova.browser.ui

import android.content.Context
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
import com.nova.browser.data.DownloadRow
import com.nova.browser.data.Downloads
import com.nova.browser.data.ErrorLog
import com.nova.browser.data.SavedTab
import com.nova.browser.data.Settings
import com.nova.browser.data.Tab
import com.nova.browser.web.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class BrowserScreen { BROWSER, TABS, BOOKMARKS, DOWNLOADS, SETTINGS, ERROR_LOG }

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
    var adBlock: AdBlockEngine = try {
        FilterLists.load(context, settings.customFilterRules)
    } catch (e: Exception) {
        ErrorLog.log(context, "Browser", "Failed to load filter lists", e)
        AdBlockEngine.parse("")
    }
        private set

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val tabs = mutableStateListOf<Tab>()
    var currentTabId by mutableStateOf<Long?>(null)
    val currentTab: Tab? get() = tabs.firstOrNull { it.id == currentTabId }

    /** Downloads started in private mode. They live only in memory — the
     *  shared downloads table is never written from a private session, so
     *  a private download never appears in the normal Downloads list. */
    val privateDownloads = mutableStateListOf<DownloadRow>()

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
        currentTabId = tab.id
        screen = BrowserScreen.BROWSER
    }

    fun ensureWebView(tab: Tab): WebView {
        tab.webView?.let { return it }
        val webView = webViewFactory(tab)
        tab.webView = webView
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

    /** Toggle the bookmark for the tab's URL. Returns the new state. */
    fun toggleBookmark(tab: Tab): Boolean {
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
                .map { SavedTab(it.url, it.title, 0) }
            db.saveTabs(saved)
        } catch (_: Exception) { }
    }

    private fun restoreTabs() {
        val saved = try { db.loadTabs() } catch (_: Exception) { emptyList() }
        if (saved.isEmpty()) {
            newTab("")
            return
        }
        saved.forEach { newTab(it.url, title = it.title, select = false) }
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
        ioScope.cancel()
    }
}
