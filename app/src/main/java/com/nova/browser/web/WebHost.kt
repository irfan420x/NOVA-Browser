package com.nova.browser.web

import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * What the WebView clients need from the activity. The clients themselves
 * live in NovaClients.kt and know nothing about the activity — they call
 * back through this interface, which MainActivity implements with the
 * classic onActivityResult / onRequestPermissionsResult paths (the
 * Activity Result API + FragmentActivity permission crash from the other
 * apps must never come back, and WebView callbacks predate that API).
 */
interface WebHost {
    /** A page wants to download a file. [tab] is the tab the download
     *  came from — the custom downloader (W4) clears its
     *  hasActiveTransfer flag when the download ends, so the tab can
     *  suspend again. */
    fun onDownloadStart(
        url: String, userAgent: String?, contentDisposition: String?,
        mimeType: String?, contentLength: Long, tab: com.nova.browser.data.Tab
    )

    /** A page opened a file picker (<input type=file>). The activity must
     *  launch a picker (from [params], so accept types and capture work)
     *  and deliver the result to [callback], or deliver null to cancel. */
    fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    )

    /** A page wants camera/mic (WebKit permission request). The activity
     *  must check the Android permission first, then grant or deny. */
    fun onPermissionRequest(request: PermissionRequest)

    /** A page wants the device location. */
    fun onGeolocation(origin: String, callback: GeolocationPermissions.Callback)

    /** A page called window.open() with a user gesture. Return a WebView
     *  for the new tab, or null to block the popup. */
    fun onCreateWindow(): WebView?

    /** Open an external intent (tel:, mailto:, market:, ...). */
    fun openExternal(intent: Intent): Boolean
}
