package com.nova.browser.ui

import android.content.Intent
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nova.browser.data.Tab
import com.nova.browser.devtools.DevToolsHub
import com.nova.browser.reader.ReaderRunner
import com.nova.browser.web.NovaWebView
import com.nova.browser.web.UrlUtils
import com.nova.browser.web.isSupportedUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** App root: theme + screen router + status toast + SSL dialog. */
@Composable
fun NovaBrowserApp(
    state: BrowserState,
    onRequestDefaultBrowser: () -> Unit,
    isDefaultBrowser: () -> Boolean,
    onOpenPrivateTab: () -> Unit,
    onClosePrivateTabs: () -> Unit,
    onSwitchToNormal: () -> Unit,
    onSwitchToPrivate: () -> Unit = { }
) {
    NovaTheme(darkTheme = state.settings.darkTheme) {
        // Glass Premium (v2.1): the gradient + glow canvas sits behind
        // everything; the root Surface is transparent and every screen's
        // Scaffold uses a transparent container color so it shows through.
        GlassBackground(darkTheme = state.settings.darkTheme) {
        Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
            // Back from any sub-screen returns to the browser, never out of the app.
            if (state.screen != BrowserScreen.BROWSER) {
                BackHandler { state.screen = BrowserScreen.BROWSER }
            }
            when (state.screen) {
                BrowserScreen.BROWSER -> BrowserContent(state, onOpenPrivateTab)
                BrowserScreen.TABS -> TabSwitcherScreen(
                    state, onOpenPrivateTab, onClosePrivateTabs, onSwitchToNormal,
                    onSwitchToPrivate
                )
                BrowserScreen.BOOKMARKS -> BookmarksScreen(state)
                BrowserScreen.DOWNLOADS -> DownloadsScreen(state)
                BrowserScreen.SETTINGS -> SettingsScreen(
                    state, onRequestDefaultBrowser, isDefaultBrowser
                )
                BrowserScreen.ERROR_LOG -> ErrorLogScreen(
                    onBack = { state.screen = BrowserScreen.BROWSER }
                )
                BrowserScreen.EXTENSIONS -> ExtensionsScreen(
                    state,
                    onBack = { state.screen = BrowserScreen.BROWSER }
                )
                BrowserScreen.DEVTOOLS -> DevToolsScreen(
                    host = state.devToolsHost,
                    onClose = { state.screen = BrowserScreen.BROWSER }
                )
                BrowserScreen.HTTP_CLIENT -> HttpClientScreen(
                    onBack = { state.screen = BrowserScreen.BROWSER }
                )
            }

            // M2: the reader belongs to one tab. Switching to an
            // already-loaded tab fires no navigation event, so without
            // this the previous tab's article would stay plastered over
            // the new tab until the user presses Back. Close it on tab
            // switch (the reader is cheap to re-open from the menu).
            LaunchedEffect(state.currentTabId) {
                state.readerArticle = null
                state.readerUrl = null
            }

            // W4: Reader mode overlays the browser while an article is
            // open (it has its own BackHandler that closes it).
            val article = state.readerArticle
            if (article != null) {
                ReaderScreen(
                    article = article,
                    onClose = { state.readerArticle = null; state.readerUrl = null },
                    initialTextSize = state.settings.readerTextSize,
                    onTextSizeChange = { state.settings.readerTextSize = it }
                )
            }

            // W4: per-site settings sheet. previousSettings is the state
            // when the sheet OPENED (set by the menu action) — the sheet
            // shows its reload note once JS/desktop changed since then,
            // and every reload-needing change re-arms the debounced
            // reload (300 ms after the last change). "Reload page" stays
            // manual-only.
            if (state.siteSettingsOpen) {
                val sheetUrl = state.currentTab?.url ?: ""
                val sheetHost = UrlUtils.extractHost(sheetUrl) ?: ""
                val sheetCurrent = state.perSiteSettingsFor(sheetHost)
                val sheetPrevious = state.siteSettingsPrevious ?: sheetCurrent
                SiteSettingsSheet(
                    host = sheetHost,
                    settings = sheetCurrent,
                    onSettingsChange = { next ->
                        state.applyPerSiteSettings(next)
                        if (next.needsReloadAfter(sheetPrevious)) {
                            state.reloadAfterSiteChangeDebounced(sheetHost)
                        }
                    },
                    onDismiss = {
                        // M6: a reload scheduled by the last toggle must
                        // not fire after the sheet is gone.
                        state.cancelPendingSiteReload()
                        state.siteSettingsOpen = false
                        state.siteSettingsPrevious = null
                    },
                    onReloadPage = { state.reloadCurrent() },
                    previousSettings = sheetPrevious,
                    pageUrl = sheetUrl
                )
            }

            // Status toast (auto-clears after 3s).
            val status = state.status
            LaunchedEffect(status) {
                if (status != null) {
                    delay(3000)
                    state.status = null
                }
            }
            if (status != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.padding(bottom = 140.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(20.dp)
                            )
                            .border(
                                1.dp, MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(status, color = Color.White)
                    }
                }
            }

            // SSL-error dialog for the current tab.
            val tab = state.currentTab
            if (tab?.sslHandler != null) {
                SslDialog(tab)
            }
        }
        }
    }
}

/** The browser screen: web content (or home) + find bar + bottom bar. */
@Composable
fun BrowserContent(state: BrowserState, onOpenPrivateTab: () -> Unit) {
    val tab = state.currentTab

    // Back order: SSL dialog > find bar > address editing > menu > shield
    // > page back. When nothing is left to handle, the handler is disabled
    // and the system back press exits the activity (normal behaviour).
    val canHandleBack = tab?.sslHandler != null || tab?.findActive == true || state.addressEditing ||
        state.menuExpanded || state.shieldOpen || tab?.canGoBack == true
    BackHandler(enabled = canHandleBack) {
        when {
            tab?.sslHandler != null -> {
                // Back from the SSL dialog means "don't load this page" —
                // the dialog must not trap the user, and the progress bar
                // must not keep spinning afterwards.
                try { tab.sslHandler?.cancel() } catch (_: Exception) { }
                tab.sslHandler = null
                tab.sslError = null
                tab.sslErrorUrl = null
                tab.isLoading = false
            }
            tab?.findActive == true -> {
                tab.findActive = false
                try { tab.webView?.clearMatches() } catch (_: Exception) { }
            }
            state.addressEditing -> state.addressEditing = false
            state.menuExpanded -> state.menuExpanded = false
            state.shieldOpen -> state.shieldOpen = false
            tab?.canGoBack == true -> {
                try { tab.webView?.goBack() } catch (_: Exception) { }
            }
        }
    }

    Scaffold(
        bottomBar = { BrowserBottomBar(state, tab, onOpenPrivateTab) },
        containerColor = Color.Transparent
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tab?.isLoading == true) {
                LinearProgressIndicator(
                    progress = { (tab.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface
                )
            }
            Box(Modifier.weight(1f)) {
                if (tab == null || tab.url.isBlank()) {
                    HomeScreen(state)
                } else {
                    WebViewHost(tab, ensureWebView = { state.ensureWebView(it) })
                }
                tab?.error?.let { ErrorOverlay(tab, state) }
            }
            if (tab?.findActive == true) {
                FindBar(tab)
            }
        }
    }

    if (state.shieldOpen) {
        ShieldSheet(state, tab)
    }
}

/**
 * Hosts a tab's WebView inside Compose. The WebView is owned by the tab,
 * not by this composable: when the user leaves the browser screen the
 * container is disposed but the WebView survives, and it is re-attached
 * here when they come back. Switching tabs swaps the attached WebView.
 */
@Composable
fun WebViewHost(tab: Tab, ensureWebView: (Tab) -> WebView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val webView = ensureWebView(tab)
            if (container.childCount == 0 || container.getChildAt(0) !== webView) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.removeAllViews()
                container.addView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

/** Bottom bar: address row (shield, URL field, bookmark, menu) + nav row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserBottomBar(state: BrowserState, tab: Tab?, onOpenPrivateTab: () -> Unit) {
    val context = LocalContext.current
    var addressText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    // Glass Premium (v2.1): the bottom bar is translucent glass with a 1dp
    // top hairline, so web content shows through slightly beneath it.
    val hairline = MaterialTheme.colorScheme.outline
    LaunchedEffect(state.addressEditing) {
        if (state.addressEditing) focusRequester.requestFocus()
    }

    fun shareCurrentPage() {
        val url = tab?.url?.takeIf { it.isNotBlank() } ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share page"))
    }

    Column(
        Modifier.background(MaterialTheme.colorScheme.surface)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    hairline, Offset(0f, stroke / 2f),
                    Offset(size.width, stroke / 2f), strokeWidth = stroke
                )
            }
    ) {
        // W4: address-bar suggestions appear ABOVE the bottom bar — the
        // bar sits at the screen bottom, so a dropdown below the field
        // would be off-screen. Shown only while the user is editing.
        val suggestions = state.addressSuggestions
        if (state.addressEditing && suggestions.isNotEmpty()) {
            AddressSuggestionsDropdown(
                suggestions = suggestions,
                onSuggestionClick = { suggestion ->
                    state.clearSuggestions()
                    state.addressEditing = false
                    state.loadInput(suggestion.url ?: suggestion.text)
                }
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShieldButton(state, tab)
            Box(
                Modifier.weight(1f).padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        if (!state.addressEditing) {
                            addressText = tab?.url ?: ""
                            state.addressEditing = true
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                if (state.addressEditing) {
                    TextField(
                        value = addressText,
                        onValueChange = {
                            addressText = it
                            // W4: debounced suggestions (history/bookmarks
                            // + the active engine's network suggestions).
                            // The engine runs on a worker thread and posts
                            // results to the main thread; in private mode
                            // it returns nothing (no keystroke leaves the
                            // device).
                            if (it.isBlank()) {
                                state.clearSuggestions()
                            } else {
                                state.suggestionEngine.suggestDebounced(
                                    query = it,
                                    engineId = state.settings.searchEngine.id,
                                    includeNetwork = state.settings.searchSuggestions,
                                    privateMode = tab?.incognito == true,
                                    delayMs = 300
                                ) { results -> state.postSuggestions(results) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused) state.clearSuggestions()
                            },
                        placeholder = { Text("Search or enter address") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (addressText.isNotBlank()) {
                                state.loadInput(addressText)
                            }
                            state.clearSuggestions()
                            state.addressEditing = false
                        })
                    )
                } else {
                    Text(
                        tab?.let {
                            if (it.url.isBlank()) "Search or enter address"
                            else it.title.ifBlank { it.url }
                        } ?: "Search or enter address",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (tab?.url.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
            // isBookmarked() is a DB query — never run it during
            // composition (it ran on every frame while a page loaded).
            // Cache it per URL and update it directly on toggle.
            var bookmarked by remember { mutableStateOf(false) }
            val tabUrl = tab?.url
            LaunchedEffect(tabUrl) {
                bookmarked = tabUrl?.let {
                    withContext(Dispatchers.IO) { state.isBookmarked(it) }
                } ?: false
            }
            // P-2 (W6 baseline): the bookmark star is hidden in private
            // mode — a private tab must never write the shared bookmarks
            // table (toggleBookmark guards it too).
            if (!state.privateMode) {
                IconButton(onClick = {
                    tab?.let {
                        val on = state.toggleBookmark(it)
                        bookmarked = on
                        state.status(if (on) "Bookmarked" else "Bookmark removed")
                    }
                }) {
                    Icon(
                        if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                        "Bookmark",
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                IconButton(onClick = { state.menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, "Menu")
                }
                DropdownMenu(
                    expanded = state.menuExpanded,
                    onDismissRequest = { state.menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("New tab") },
                        onClick = { state.menuExpanded = false; state.newTab("") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("New private tab") },
                        onClick = {
                            state.menuExpanded = false
                            if (state.privateMode) state.newTab("", incognito = true)
                            else onOpenPrivateTab()
                        },
                        leadingIcon = { Icon(Icons.Filled.Lock, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Desktop site") },
                        // L1: on a blank/home tab there is no host to
                        // persist per-site, so the toggle would revert
                        // on the next navigation — disable it until the
                        // tab has a page (same pattern as Find in page).
                        enabled = tab?.url?.isNotBlank() == true,
                        onClick = {
                            state.menuExpanded = false
                            tab?.let {
                                it.desktopMode = !it.desktopMode
                                // W4: the menu toggle is per-tab, but the
                                // per-site set is what onPageStarted
                                // applies on the next navigation — keep
                                // them in sync or the toggle reverts on
                                // reload.
                                UrlUtils.extractHost(it.url)?.let { host ->
                                    state.settings.setDesktopModeFor(host, it.desktopMode)
                                }
                                it.webView?.let { wv ->
                                    NovaWebView.applyDesktopMode(wv, it)
                                    wv.reload()
                                }
                                state.status(
                                    if (it.desktopMode) "Desktop site on" else "Desktop site off"
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.DesktopWindows, null) },
                        trailingIcon = {
                            if (tab?.desktopMode == true) Icon(Icons.Filled.Check, null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reader mode") },
                        // No readable article → the extractor reports it;
                        // the item stays enabled so the user can try on
                        // any page with content.
                        enabled = tab?.url?.isNotBlank() == true,
                        onClick = {
                            state.menuExpanded = false
                            val webView = tab?.webView
                            val url = tab?.url
                            if (webView != null && url != null) {
                                ReaderRunner.extractArticle(webView) { article ->
                                    if (article == null) {
                                        state.status("No readable article on this page")
                                    } else {
                                        state.readerArticle = article
                                        state.readerUrl = url
                                    }
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Article, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Site settings") },
                        // Per-site settings only exist for real web pages.
                        enabled = tab?.url?.let { isSupportedUrl(it) } == true,
                        onClick = {
                            state.menuExpanded = false
                            val url = tab?.url ?: return@DropdownMenuItem
                            val host = UrlUtils.extractHost(url) ?: return@DropdownMenuItem
                            state.siteSettingsPrevious = state.perSiteSettingsFor(host)
                            state.siteSettingsOpen = true
                        },
                        leadingIcon = { Icon(Icons.Filled.Tune, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Find in page") },
                        // A home tab has no page to search — the find bar
                        // would open over the home screen and do nothing.
                        enabled = tab?.url?.isNotBlank() == true,
                        onClick = {
                            state.menuExpanded = false
                            tab?.let { it.findActive = true }
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Share page") },
                        onClick = { state.menuExpanded = false; shareCurrentPage() },
                        leadingIcon = { Icon(Icons.Filled.Share, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks") },
                        onClick = {
                            state.menuExpanded = false
                            state.bookmarksTab = 0
                            state.screen = BrowserScreen.BOOKMARKS
                        },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        onClick = {
                            state.menuExpanded = false
                            state.bookmarksTab = 1
                            state.screen = BrowserScreen.BOOKMARKS
                        },
                        leadingIcon = { Icon(Icons.Filled.History, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Downloads") },
                        onClick = { state.menuExpanded = false; state.screen = BrowserScreen.DOWNLOADS },
                        leadingIcon = { Icon(Icons.Filled.Download, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { state.menuExpanded = false; state.screen = BrowserScreen.SETTINGS },
                        leadingIcon = { Icon(Icons.Filled.Settings, null) }
                    )
                    if (!state.privateMode) {
                        DropdownMenuItem(
                            text = { Text("Extensions") },
                            onClick = { state.menuExpanded = false; state.screen = BrowserScreen.EXTENSIONS },
                            leadingIcon = { Icon(Icons.Filled.Extension, null) }
                        )
                    }
                    if (!state.privateMode) {
                        DropdownMenuItem(
                            text = { Text("DevTools") },
                            onClick = {
                                state.menuExpanded = false
                                state.currentTab?.let { DevToolsHub.bindToTab(it.id) }
                                state.screen = BrowserScreen.DEVTOOLS
                            },
                            leadingIcon = { Icon(Icons.Filled.Code, null) },
                            enabled = state.settings.devMode
                        )
                        DropdownMenuItem(
                            text = { Text("HTTP Client") },
                            onClick = { state.menuExpanded = false; state.screen = BrowserScreen.HTTP_CLIENT },
                            leadingIcon = { Icon(Icons.Filled.Send, null) },
                            enabled = state.settings.devMode
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Error log") },
                        onClick = { state.menuExpanded = false; state.screen = BrowserScreen.ERROR_LOG },
                        leadingIcon = { Icon(Icons.Filled.BugReport, null) }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { try { tab?.webView?.goBack() } catch (_: Exception) { } },
                enabled = tab?.canGoBack == true
            ) { Icon(Icons.Filled.ArrowBack, "Back") }
            IconButton(
                onClick = { try { tab?.webView?.goForward() } catch (_: Exception) { } },
                enabled = tab?.canGoForward == true
            ) { Icon(Icons.Filled.ArrowForward, "Forward") }
            IconButton(onClick = { state.reloadCurrent() }) {
                Icon(Icons.Filled.Refresh, "Reload")
            }
            IconButton(onClick = { state.goHome() }) {
                Icon(Icons.Filled.Home, "Home")
            }
            IconButton(onClick = { state.screen = BrowserScreen.TABS }) {
                TabCountBadge(count = state.tabs.size)
            }
        }
    }
}

/** Tab counter button (Chrome-style bordered square with the count). */
@Composable
private fun TabCountBadge(count: Int) {
    Box(
        Modifier.size(28.dp)
            .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelMedium)
    }
}

/** Shield icon with the blocked ads+trackers count for the current page. */
@Composable
private fun ShieldButton(state: BrowserState, tab: Tab?) {
    val blocked = (tab?.blockedAds ?: 0) + (tab?.blockedTrackers ?: 0)
    Box {
        IconButton(onClick = { state.shieldOpen = true }) {
            Icon(
                Icons.Filled.Shield, "Ad blocker",
                tint = if (state.settings.adBlockEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (blocked > 0) {
            Box(
                Modifier.align(Alignment.TopEnd)
                    .background(NebulaPrimary, CircleShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    blocked.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** Shield sheet: what was blocked on this page + the per-site toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldSheet(state: BrowserState, tab: Tab?) {
    val host = tab?.url?.let { UrlUtils.extractHost(it) }
    val off = state.settings.isAdBlockOffFor(host)
    ModalBottomSheet(onDismissRequest = { state.shieldOpen = false }) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Text("Ad blocker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                host ?: "This page",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Ads blocked", (tab?.blockedAds ?: 0).toString(), Modifier.weight(1f))
                StatCard("Trackers blocked", (tab?.blockedTrackers ?: 0).toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Block ads on this site", fontWeight = FontWeight.SemiBold)
                    Text(
                        "When off, ads and trackers load on this site only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = !off,
                    onCheckedChange = { checked ->
                        if (host != null) {
                            state.settings.setAdBlockOffFor(host, !checked)
                            state.shieldOpen = false
                            state.reloadCurrent()
                        }
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${state.adBlock.ruleCount} filter rules loaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Find-in-page bar above the bottom bar. */
@Composable
fun FindBar(tab: Tab) {
    val hairline = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    hairline, Offset(0f, stroke / 2f),
                    Offset(size.width, stroke / 2f), strokeWidth = stroke
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = tab.findQuery,
            onValueChange = {
                tab.findQuery = it
                try { tab.webView?.findAllAsync(it) } catch (_: Exception) { }
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Find in page") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
        Text(
            tab.findResult,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(onClick = { try { tab.webView?.findNext(false) } catch (_: Exception) { } }) {
            Icon(Icons.Filled.KeyboardArrowUp, "Previous match")
        }
        IconButton(onClick = { try { tab.webView?.findNext(true) } catch (_: Exception) { } }) {
            Icon(Icons.Filled.KeyboardArrowDown, "Next match")
        }
        IconButton(onClick = {
            tab.findActive = false
            try { tab.webView?.clearMatches() } catch (_: Exception) { }
        }) {
            Icon(Icons.Filled.Close, "Close find")
        }
    }
}

/** Full-screen error card when a page fails to load. */
@Composable
fun ErrorOverlay(tab: Tab, state: BrowserState) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.CloudOff, null, Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Couldn't load this page",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tab.error ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { state.reloadCurrent() }) { Text("Reload") }
            if (tab.wasUpgradedFromHttp) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { state.loadHttpAnyway(tab) }) {
                    Text("Load with HTTP anyway")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This site failed over HTTPS. Loading over HTTP is not encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { state.goHome() }) { Text("Go home") }
        }
    }
}

/** SSL-error dialog — the user decides, the browser never proceeds alone. */
@Composable
fun SslDialog(tab: Tab) {
    val errorText = when (tab.sslError?.primaryError) {
        SslError.SSL_EXPIRED -> "its certificate has expired"
        SslError.SSL_IDMISMATCH -> "its certificate is for a different site"
        SslError.SSL_UNTRUSTED -> "its certificate isn't trusted"
        SslError.SSL_NOTYETVALID -> "its certificate isn't valid yet"
        SslError.SSL_DATE_INVALID -> "its certificate has an invalid date"
        else -> "its certificate has a problem"
    }
    AlertDialog(
        onDismissRequest = { /* the user must answer — no silent dismiss */ },
        title = { Text("This site isn't secure") },
        text = {
            Text(
                "The connection to ${tab.sslErrorUrl ?: "this site"} can't be trusted — " +
                    "$errorText. Your connection could be intercepted. " +
                    "Only continue if you trust this site."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                try { tab.sslHandler?.proceed() } catch (_: Exception) { }
                tab.sslHandler = null
                tab.sslError = null
                tab.sslErrorUrl = null
            }) { Text("Continue anyway") }
        },
        dismissButton = {
            TextButton(onClick = {
                try { tab.sslHandler?.cancel() } catch (_: Exception) { }
                tab.sslHandler = null
                tab.sslError = null
                tab.sslErrorUrl = null
                tab.isLoading = false
            }) { Text("Go back") }
        }
    )
}
