package com.nova.browser

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nova.browser.data.ErrorLog
import com.nova.browser.ui.BrowserState
import com.nova.browser.ui.NovaBrowserApp
import com.nova.browser.web.BrowserHost
import java.io.File

/**
 * Private browsing, in its own process (`:incognito`, its own task).
 *
 * Isolation is real, not a flag: `setDataDirectorySuffix("incognito")`
 * gives this process its own WebView data directory (cookies, cache,
 * storage), tabs are never saved, history is never recorded, and when
 * the activity finishes it wipes the data directory and kills the
 * process. Pressing Back on the private home page closes the session —
 * same as Chrome incognito; "Switch to normal tabs" leaves the session
 * alive in its own task instead.
 *
 * singleTask (see the manifest) is load-bearing here: without it a
 * second "New private tab" (menu, tab switcher, launcher shortcut)
 * created a second instance in the SAME process, which called
 * setDataDirectorySuffix("incognito") again after WebViews existed and
 * wiped the live app_webview_incognito directory out from under the
 * running session. With singleTask the existing instance is reused and
 * the intent arrives via onNewIntent.
 */
class PrivateActivity : ComponentActivity() {
    private lateinit var state: BrowserState
    private lateinit var host: BrowserHost

    companion object {
        /** setDataDirectorySuffix may only be called once per process,
         *  before any WebView exists. Guarded so a second onCreate in
         *  the same process can never call it again. */
        @Volatile
        private var dataDirSuffixSet = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // MUST run before any WebView exists in this process, and only once.
        if (!dataDirSuffixSet) {
            WebView.setDataDirectorySuffix("incognito")
            dataDirSuffixSet = true
        }
        super.onCreate(savedInstanceState)
        // Wipe private WebView data left by a previous run that was
        // killed — but NEVER while a private session is alive in this
        // process (its WebViews are using that directory right now).
        if (!isPrivateSessionAlive()) {
            try {
                File(applicationInfo.dataDir, "app_webview_incognito").deleteRecursively()
            } catch (_: Exception) { }
            // L7: same for private custom downloads — a killed session
            // leaves its cacheDir/Downloads behind (partials, metas and
            // finished files). Only safe here, at a fresh process start:
            // a live session's downloads are in this same directory.
            try {
                File(cacheDir, "Downloads").deleteRecursively()
            } catch (_: Exception) { }
        }        // Marker so the normal activity can offer "Private tabs are open".
        // The pid lets it tell a live session from a stale marker.
        try {
            File(filesDir, BrowserHost.PRIVATE_MARKER_FILE)
                .writeText(Process.myPid().toString())
        } catch (_: Exception) { }
        state = BrowserState(
            context = applicationContext,
            privateMode = true,
            webViewFactory = { tab -> host.createWebView(tab) },
            onPrivateEmpty = { finish() }
        )
        host = BrowserHost(this, state)
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
        // "Close all" from the normal activity's tab switcher.
        if (intent?.action == BrowserHost.CLOSE_PRIVATE_ACTION) {
            finish()
            return
        }
        // NEW_PRIVATE_TAB from a cold start (launcher shortcut): the init
        // blank tab IS the new tab — calling handleIntent here used to
        // open a second blank tab on top of it.
        if (intent?.action != BrowserHost.NEW_PRIVATE_TAB_ACTION) {
            host.handleIntent(intent)
        }
        setContent {
            NovaBrowserApp(
                state = state,
                onRequestDefaultBrowser = { host.requestDefaultBrowserRole() },
                isDefaultBrowser = { host.isDefaultBrowser() },
                onOpenPrivateTab = { state.newTab("", incognito = true) },
                onClosePrivateTabs = { finish() },
                onSwitchToNormal = {
                    // Bring the normal task forward; this task stays alive
                    // behind it, so the private session survives.
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                },
                onSwitchToPrivate = { /* already in private mode */ }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            BrowserHost.CLOSE_PRIVATE_ACTION -> finish()
            BrowserHost.NEW_PRIVATE_TAB_ACTION -> state.newTab("", incognito = true)
            // A plain relaunch (tapping the app icon / "Private tabs are
            // open") just brings the session forward — it must NOT create
            // a new blank tab.
            null -> { /* come forward, nothing to open */ }
            else -> host.handleIntent(intent)
        }
    }

    /** True when the marker file holds the pid of a process that is
     *  still alive (this process's own pid counts). */
    private fun isPrivateSessionAlive(): Boolean {
        val marker = File(filesDir, BrowserHost.PRIVATE_MARKER_FILE)
        if (!marker.exists()) return false
        val pid = try { marker.readText().trim().toInt() } catch (_: Exception) { return false }
        return File("/proc/$pid").exists()
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
        super.onDestroy()
        if (isFinishing) {
            // H2: FIRST cancel every running custom download and raise
            // the closing flag, BEFORE deleting anything. A download
            // that fails after the wipe would otherwise fall back to
            // DownloadManager and land a private file on disk after the
            // session is supposedly wiped. cancel() is synchronous for
            // the state change (PAUSED/QUEUED die at once; a running
            // worker is disconnected and cleans up its own partial).
            state.privateSessionClosing = true
            try {
                for (job in state.customDownloads.toList()) job.download.cancel()
            } catch (_: Exception) { }
            // Wipe everything the private session left behind, then kill
            // this throwaway process so nothing can linger in memory.
            // That includes the private prefs file (per-site choices made
            // privately) and the private error log (entries can contain
            // URLs) — both live in the app data directory, shared with
            // the normal process.
            try { File(filesDir, BrowserHost.PRIVATE_MARKER_FILE).delete() } catch (_: Exception) { }
            try {
                File(applicationInfo.dataDir, "app_webview_incognito").deleteRecursively()
            } catch (_: Exception) { }
            try {
                File(File(applicationInfo.dataDir, "shared_prefs"), "nova_browser_private.xml").delete()
            } catch (_: Exception) { }
            try { File(filesDir, "error-log-private.txt").delete() } catch (_: Exception) { }
            // W4: private custom downloads live in cacheDir/Downloads
            // and are never published — delete them with the session.
            // (Normal custom downloads live in filesDir/Downloads and
            // must NOT be touched here — the app data dir is shared
            // between the two processes.)
            try { File(cacheDir, "Downloads").deleteRecursively() } catch (_: Exception) { }
            // P-1 (W6 baseline): private DownloadManager downloads live
            // in the app-private external files dir — delete them with
            // the session too, or the file survives the wipe.
            try {
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
                    File(dir, "NOVA/Browser").deleteRecursively()
                }
            } catch (_: Exception) { }
            try { state.onCleared() } catch (_: Exception) { }
            Process.killProcess(Process.myPid())
        }
    }
}
