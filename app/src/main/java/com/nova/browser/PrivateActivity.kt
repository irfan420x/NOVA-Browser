package com.nova.browser

import android.content.Intent
import android.os.Bundle
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
 */
class PrivateActivity : ComponentActivity() {
    private lateinit var state: BrowserState
    private lateinit var host: BrowserHost

    override fun onCreate(savedInstanceState: Bundle?) {
        // MUST run before any WebView exists in this process.
        WebView.setDataDirectorySuffix("incognito")
        super.onCreate(savedInstanceState)
        // Wipe any private WebView data left by a previous run that was killed.
        try {
            File(applicationInfo.dataDir, "app_webview_incognito").deleteRecursively()
        } catch (_: Exception) { }
        // Marker so the normal activity can offer "Private tabs are open".
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
        host.handleIntent(intent)
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
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            BrowserHost.CLOSE_PRIVATE_ACTION -> finish()
            // A plain relaunch (e.g. tapping "Private tab" again) means a
            // new private tab.
            null -> state.newTab("", incognito = true)
            else -> host.handleIntent(intent)
        }
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
            // Wipe everything the private session left behind, then kill
            // this throwaway process so nothing can linger in memory.
            try { File(filesDir, BrowserHost.PRIVATE_MARKER_FILE).delete() } catch (_: Exception) { }
            try {
                File(applicationInfo.dataDir, "app_webview_incognito").deleteRecursively()
            } catch (_: Exception) { }
            try { state.onCleared() } catch (_: Exception) { }
            Process.killProcess(Process.myPid())
        }
    }
}
