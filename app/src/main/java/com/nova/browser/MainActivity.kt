package com.nova.browser

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nova.browser.data.ClearOnExit
import com.nova.browser.data.Downloader
import com.nova.browser.data.ErrorLog
import com.nova.browser.devtools.DevToolsHub
import com.nova.browser.ui.BrowserState
import com.nova.browser.ui.NovaBrowserApp
import com.nova.browser.web.BrowserHost
import com.nova.browser.web.Suggestions
import java.io.File

/**
 * Normal browsing. Extends ComponentActivity (NOT FragmentActivity — the
 * Activity Result API + FragmentActivity permission crash hit
 * Dialer/Contacts/Gallery); file chooser and permission results come back
 * through the classic onActivityResult / onRequestPermissionsResult paths
 * in [BrowserHost].
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: BrowserState
    private lateinit var host: BrowserHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The factory lambda captures the host lazily — it is only invoked
        // when a tab first needs its WebView, long after onCreate finishes.
        state = BrowserState(
            context = applicationContext,
            webViewFactory = { tab -> host.createWebView(tab) }
        )
        host = BrowserHost(this, state)
        // W4 startup, once: drop orphaned .meta sidecars left by a
        // killed custom download, and route suggestion-engine debug
        // output to logcat (the engine itself never logs).
        try {
            Downloader.cleanupOrphanedMeta(File(applicationContext.filesDir, "Downloads"))
        } catch (_: Exception) { }
        // M1: a custom download's private copy is deleted right after a
        // successful publish, but a kill between publish and delete (or
        // a publish failure) leaves finished files in filesDir/Downloads
        // with no live job and no UI — sweep them at startup. Only
        // finished files: .part/.meta are a killed download's resume
        // data and are left alone (the .meta cleanup above handles
        // orphaned metas).
        try {
            File(applicationContext.filesDir, "Downloads").listFiles()?.forEach { f ->
                if (f.isFile && !f.name.endsWith(Downloader.PART_SUFFIX) &&
                    !f.name.endsWith(Downloader.META_SUFFIX)) {
                    try { f.delete() } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
        Suggestions.onDebugLog = { Log.d("Suggestions", it) }
        // DevTools (W3): enable recording only in normal mode — this is
        // MainActivity, never :incognito (the hub refuses there anyway).
        // Bind to the current tab so pre-dock activity is recorded for it.
        DevToolsHub.setDevMode(state.settings.devMode)
        if (state.settings.devMode) {
            state.currentTab?.let { DevToolsHub.bindToTab(it.id) }
        }
        // Log the crash, then let the system do its normal crash handling
        // (kill the process) — a handler that swallows the crash would
        // leave the app alive in a broken state.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ErrorLog.log(
                    applicationContext, "Browser",
                    "Uncaught: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    throwable
                )
            } catch (_: Exception) { }
            previousHandler?.uncaughtException(thread, throwable)
        }
        host.refreshPrivateSessionState()
        host.handleIntent(intent)
        setContent {
            NovaBrowserApp(
                state = state,
                onRequestDefaultBrowser = { host.requestDefaultBrowserRole() },
                isDefaultBrowser = { host.isDefaultBrowser() },
                onOpenPrivateTab = { host.openPrivateActivity(newTab = true) },
                onClosePrivateTabs = { host.closePrivateActivity() },
                onSwitchToNormal = { /* already in normal mode */ },
                onSwitchToPrivate = { host.openPrivateActivity(newTab = false) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        host.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        host.refreshPrivateSessionState()
        // W1: periodic idle sweep — suspend tabs idle for 10+ minutes.
        // B-3: pass the context so the DownloadManager check runs here
        // too — the idle path used to pass null, so a once-flagged tab
        // never had its hasActiveTransfer flag cleared.
        // W4: a running custom download blocks suspension like a
        // DownloadManager one (the DM check cannot see it).
        com.nova.browser.data.TabSuspension.suspendBackgroundTabs(
            state.tabs, state.currentTabId, -1,
            state.settings.suspendBackgroundTabs, context = applicationContext,
            hasActiveCustomDownload = state.hasActiveCustomDownload)
    }

    override fun onPause() {
        super.onPause()
        state.saveTabs()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // W1: main thread only (WebView.destroy). context= lets TabSuspension
        // check DownloadManager: while any download runs, nothing suspends (W1-1).
        // W4: same gate for custom downloads (invisible to DownloadManager).
        com.nova.browser.data.TabSuspension.suspendBackgroundTabs(
            state.tabs, state.currentTabId, level,
            state.settings.suspendBackgroundTabs, context = applicationContext,
            hasActiveCustomDownload = state.hasActiveCustomDownload)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        host.handleActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        host.handlePermissionResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        if (isFinishing) {
            state.onCleared()
            // W4 clear-on-exit: history + cookies + cache are wiped when
            // the browser closes cleanly. Does NOT run when the app is
            // swiped away or killed — only a clean finish reaches here
            // with isFinishing=true. Never from PrivateActivity (private
            // sessions wipe unconditionally on close already).
            if (state.settings.clearOnExit) {
                try {
                    ClearOnExit.wipe(this) {
                        try { state.currentTab?.webView?.clearCache(true) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
        }
        super.onDestroy()
    }
}
