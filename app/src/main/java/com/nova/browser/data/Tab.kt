package com.nova.browser.data

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One browser tab. The WebView is created lazily by the activity and kept
 * alive while the tab exists, so switching tabs never loses scroll position
 * or page state. UI state is Compose state so the address bar, progress
 * and shield counts update without a separate ViewModel.
 *
 * A tab never writes history when [incognito] is true — that is enforced
 * wherever history is recorded, not just here.
 */
class Tab(
    val id: Long,
    url: String = "",
    title: String = "",
    val incognito: Boolean = false
) {
    var webView: WebView? = null

    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableStateOf(0)
    var isLoading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var blockedAds by mutableStateOf(0)
    var blockedTrackers by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
    var desktopMode by mutableStateOf(false)
    var findActive by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findResult by mutableStateOf("")

    /** True when the current URL was upgraded from http by HTTPS-only mode.
     *  The error overlay offers "Load with HTTP anyway" only in that case. */
    var wasUpgradedFromHttp by mutableStateOf(false)

    /** Set while an SSL-error dialog is open. One-shot: the handler is only
     *  valid until the user answers or the page is left. Compose state, so
     *  the dialog appears the moment the WebView reports the error. */
    var sslHandler by mutableStateOf<SslErrorHandler?>(null)
    var sslError by mutableStateOf<SslError?>(null)
    var sslErrorUrl by mutableStateOf<String?>(null)

    fun resetForNavigation(newUrl: String) {
        url = newUrl
        error = null
        blockedAds = 0
        blockedTrackers = 0
        sslHandler = null
        sslError = null
        sslErrorUrl = null
    }
}
