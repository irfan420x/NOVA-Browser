package com.nova.browser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.nova.browser.data.ErrorLog
import com.nova.browser.ui.BrowserState
import com.nova.browser.ui.NovaBrowserApp
import com.nova.browser.web.BrowserHost

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
    }

    override fun onPause() {
        super.onPause()
        state.saveTabs()
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
        if (isFinishing) state.onCleared()
        super.onDestroy()
    }
}
