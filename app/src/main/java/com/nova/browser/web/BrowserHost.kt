package com.nova.browser.web

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import com.nova.browser.PrivateActivity
import com.nova.browser.data.Downloads
import com.nova.browser.data.ErrorLog
import com.nova.browser.data.Tab
import com.nova.browser.ui.BrowserState
import java.io.File

/**
 * Shared delegate for MainActivity and PrivateActivity: everything that
 * touches the Android framework around the WebView — file chooser,
 * camera/mic permission requests, geolocation prompt, downloads, external
 * intents, the default-browser role, and the private activity.
 *
 * File chooser and WebView permission requests use the classic
 * onActivityResult / onRequestPermissionsResult paths on purpose: the
 * Activity Result API + FragmentActivity combination crashes on launch
 * (it bit Dialer/Contacts/Gallery), and both activities here extend
 * ComponentActivity.
 */
class BrowserHost(
    private val activity: ComponentActivity,
    private val state: BrowserState
) : WebHost {
    private val context: Context get() = activity

    companion object {
        const val FILE_CHOOSER_REQUEST_CODE = 1001
        const val PERMISSION_REQUEST_CODE = 1002
        const val ROLE_REQUEST_CODE = 1003

        /** Marker file the :incognito process writes (its pid) so the
         *  normal activity can tell a live private session from a stale one. */
        const val PRIVATE_MARKER_FILE = "private_session_active"

        /** Intent action that tells PrivateActivity to close itself (wipe). */
        const val CLOSE_PRIVATE_ACTION = "nova.browser.action.CLOSE_PRIVATE"
    }

    private var pendingUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    /** Create a WebView for a tab and wire it up (settings, clients,
     *  download listener, find listener). */
    fun createWebView(tab: Tab): WebView {
        val webView = WebView(context)
        NovaWebView.configure(webView, tab, state, this)
        return webView
    }

    // ---- WebHost ----

    override fun onDownloadStart(
        url: String, userAgent: String?, contentDisposition: String?,
        mimeType: String?, contentLength: Long
    ) {
        // The cookie comes from this process's CookieManager, so downloads
        // behind a login still work — including in private mode, where the
        // system DownloadManager can't read the incognito cookies itself.
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val id = Downloads.enqueue(
            context, state.db, url, userAgent, contentDisposition, mimeType, contentLength, cookie
        )
        if (id < 0) {
            state.status("Download failed to start")
        } else {
            state.status("Download started")
        }
    }

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ) {
        // A stale callback must always be answered, or the page hangs.
        pendingUploadCallback?.onReceiveValue(null)
        pendingUploadCallback = callback
        try {
            val intent = params.createIntent()
            activity.startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
        } catch (_: ActivityNotFoundException) {
            pendingUploadCallback = null
            callback.onReceiveValue(null)
            state.status("No app can pick files")
        } catch (e: Exception) {
            pendingUploadCallback = null
            callback.onReceiveValue(null)
            ErrorLog.log(context, "Browser", "File chooser failed", e)
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val androidPerms = mutableListOf<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            androidPerms.add(Manifest.permission.CAMERA)
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            androidPerms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (androidPerms.isEmpty()) {
            // Protected media ID and similar need no Android permission.
            request.grant(request.resources)
            return
        }
        pendingPermissionRequest = request
        ActivityCompat.requestPermissions(
            activity, androidPerms.toTypedArray(), PERMISSION_REQUEST_CODE
        )
    }

    override fun onGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        AlertDialog.Builder(context)
            .setTitle("Share your location?")
            .setMessage("$origin wants to know your location.")
            .setPositiveButton("Allow") { _, _ -> callback.invoke(origin, true, false) }
            .setNegativeButton("Don't allow") { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    override fun onCreateWindow(): WebView? {
        // window.open() with a user gesture: a real new tab. If the tab
        // limit is hit, newTab() returns the current tab instead of making
        // one — never hand the transport the current tab's WebView, or the
        // popup would hijack the page the user is on.
        val before = state.tabs.size
        val tab = state.newTab("")
        if (state.tabs.size == before) {
            state.status("Too many tabs open — close one first")
            return null
        }
        return state.ensureWebView(tab)
    }

    override fun openExternal(intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            state.status("No app can open this link")
            false
        }
    }

    // ---- Activity results ----

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_CHOOSER_REQUEST_CODE -> {
                val callback = pendingUploadCallback
                pendingUploadCallback = null
                if (callback != null) {
                    val uris = if (resultCode == Activity.RESULT_OK && data != null) {
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                    } else {
                        null
                    }
                    callback.onReceiveValue(uris)
                }
            }
            ROLE_REQUEST_CODE -> {
                // The role result is re-checked on resume via isDefaultBrowser().
            }
        }
    }

    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode != PERMISSION_REQUEST_CODE) return
        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        val granted = request.resources.filter { resource ->
            val androidPerm = when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null // needs no Android permission — grant it
            }
            androidPerm == null || run {
                val index = permissions.indexOf(androidPerm)
                index >= 0 && grantResults[index] == PackageManager.PERMISSION_GRANTED
            }
        }.toTypedArray()
        if (granted.isEmpty()) request.deny() else request.grant(granted)
    }

    // ---- Default browser role ----

    fun requestDefaultBrowserRole() {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
            state.status = "Default browser can't be set on this device"
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            state.status = "NOVA Browser is already the default browser"
            return
        }
        try {
            activity.startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER),
                ROLE_REQUEST_CODE
            )
        } catch (e: Exception) {
            ErrorLog.log(context, "Browser", "Default browser request failed", e)
            state.status = "Couldn't open the default browser request"
        }
    }

    fun isDefaultBrowser(): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return try { roleManager.isRoleHeld(RoleManager.ROLE_BROWSER) } catch (_: Exception) { false }
    }

    // ---- Private session ----

    fun openPrivateActivity() {
        context.startActivity(Intent(context, PrivateActivity::class.java))
    }

    /** Tell the private activity to close itself (it wipes on finish).
     *  The marker disappears when it finishes; clear the flag now so the
     *  tab switcher stops offering the private section. */
    fun closePrivateActivity() {
        val intent = Intent(context, PrivateActivity::class.java).apply {
            action = CLOSE_PRIVATE_ACTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        state.privateSessionActive = false
    }

    /** Re-check whether the :incognito process is still alive (the marker
     *  file holds its pid). Call from MainActivity.onResume — a marker
     *  left by a killed process is deleted so it never shows a phantom
     *  private session. */
    fun refreshPrivateSessionState() {
        val marker = File(context.filesDir, PRIVATE_MARKER_FILE)
        if (!marker.exists()) {
            state.privateSessionActive = false
            return
        }
        val pid = try { marker.readText().trim().toInt() } catch (_: Exception) { null }
        val alive = pid != null && File("/proc/$pid").exists()
        if (!alive) {
            try { marker.delete() } catch (_: Exception) { }
        }
        state.privateSessionActive = alive
    }

    // ---- Intents ----

    /** External links, shared text, and launcher shortcuts. A plain
     *  launch intent (no action) does nothing — the app just comes forward. */
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return
                openUrlInTab(url)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                openUrlInTab(text)
            }
            "nova.browser.action.NEW_TAB" -> state.newTab("")
            "nova.browser.action.NEW_PRIVATE_TAB" -> {
                if (activity is PrivateActivity) {
                    state.newTab("", incognito = true)
                } else {
                    openPrivateActivity()
                }
            }
        }
    }

    private fun openUrlInTab(url: String) {
        // Don't leave a blank home tab behind when the app was opened for a link.
        val onlyTab = state.tabs.singleOrNull()
        if (onlyTab != null && onlyTab.url.isBlank()) {
            state.loadInput(url)
        } else {
            state.newTab(url)
        }
    }
}
