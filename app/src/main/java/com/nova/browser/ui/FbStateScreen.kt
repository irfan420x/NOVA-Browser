package com.nova.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nova.browser.data.DownloadPublish
import com.nova.browser.data.ExtensionStore
import com.nova.browser.extensions.ExtensionRuntime
import com.nova.browser.extensions.FbStateFormat
import com.nova.browser.extensions.FbStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FBState screen (NOVA Browser v2.4, NOVA-EXTENSIONS.md §4.4–§4.7).
 *
 * EXACT SIGNATURE (Lane B wires navigation to this composable):
 *
 *     @Composable
 *     fun FbStateScreen(
 *         state: BrowserState,
 *         onBack: () -> Unit
 *     )
 *
 * - `state` is the SAME BrowserState ExtensionsScreen receives. It is
 *   used for `state.privateMode` (the private-mode refusal) and for
 *   `state.tabs` (reload every open facebook.com tab after import /
 *   soft logout, via WebView.reload() — the app's existing mechanism,
 *   as BrowserState.reloadCurrent does for the current tab).
 * - `onBack` navigates back to the Extensions screen.
 * - No other parameters. The screen reads its own enabled flag and
 *   sensitive-host allow entry from ExtensionStore (built-in id
 *   "fbstate", [ExtensionRuntime.FBSTATE_ID]) and applies the
 *   Expressive Dark theme from ui/Theme.kt (GlassTopAppBar, GlassCard).
 *
 * Behaviour: one large textarea holding the FBState data (both the
 * export output display and the import input), with Export JSON /
 * Base64 / Encrypted, Import, Copy / Paste / Save to file / Load from
 * file / Clear, and Soft Logout around it. Export writes into the
 * textarea only — it never auto-copies and never auto-downloads.
 *
 * PRIVACY: the extracted state is the user's OWN Facebook session. It
 * NEVER leaves the device: no upload, no network call, no logging of
 * cookie values, no analytics. Export is copy-to-clipboard /
 * save-to-file (Downloads/NOVA/Browser/) ONLY. Status lines carry
 * counts, never values.
 *
 * HONEST LIMITS (stated on screen, §4.2): CookieManager cannot tell
 * us secure/httpOnly/expiry, so export records them as null; HttpOnly
 * CANNOT be restored on import (WebView drops the attribute), so an
 * imported HttpOnly cookie becomes JavaScript-readable; import does
 * not check that the session works — the Facebook tab reloads and
 * shows the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FbStateScreen(
    state: BrowserState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPrivate = state.privateMode || ExtensionStore.isPrivateProcess()

    var text by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var cookieCount by remember { mutableStateOf<Int?>(null) }
    // "json" | "base64" | "encrypted" — decides the Save-to-file
    // filename + MIME type (§4.7).
    var exportFormat by rememberSaveable { mutableStateOf("json") }
    var replaceAll by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fbEnabled by remember { mutableStateOf<Boolean?>(null) }
    var fbAllowed by remember { mutableStateOf<Boolean?>(null) }
    var showAllowDialog by remember { mutableStateOf(false) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var pendingImportKind by remember { mutableStateOf<FbStateFormat.DetectedKind?>(null) }
    var showSoftLogoutConfirm by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importPasswordError by remember { mutableStateOf<String?>(null) }

    fun setStatus(message: String, isError: Boolean = false) {
        status = message
        statusIsError = isError
    }

    // Load the stored FBState state once (enabled flag + sensitive-host
    // opt-in). Disk reads on IO, like ExtensionsScreen.reload().
    LaunchedEffect(Unit) {
        val builtin = withContext(Dispatchers.IO) {
            ExtensionStore.builtinState(context, ExtensionRuntime.FBSTATE_ID)
        }
        fbEnabled = builtin.enabled
        fbAllowed = builtin.allowedSensitiveHosts.contains("facebook.com")
    }

    // First use (§4.6): no allow entry yet -> ask once, with the exact
    // confirmation text. Cancel closes the screen (onBack); the dialog
    // is shown again next time, because no entry was stored.
    LaunchedEffect(fbAllowed) {
        if (fbAllowed == false && !isPrivate && fbEnabled == true) showAllowDialog = true
    }

    fun doAllow() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ExtensionStore.setSensitiveHostAllowed(
                    context, ExtensionRuntime.FBSTATE_ID, "facebook.com", true
                )
            }
            if (ok) {
                fbAllowed = true
                showAllowDialog = false
            } else {
                showAllowDialog = false
                setStatus("Could not save the permission — try again.", true)
            }
        }
    }

    // Reload every open tab on facebook.com, using the app's existing
    // mechanism (WebView.reload(), as BrowserState.reloadCurrent does
    // for the current tab). No chrome.tabs exists here.
    fun reloadFacebookTabs() {
        for (tab in state.tabs) {
            if (FbStateManager.isFacebookUrl(tab.url)) {
                try {
                    tab.webView?.reload()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun handleImportOutcome(outcome: FbStateFormat.ImportOutcome) {
        when (outcome) {
            is FbStateFormat.ImportOutcome.NeedsPassword -> {
                // The plain-text path never produces this; an encrypted
                // payload that decrypts to another encrypted payload is
                // not valid FBState data.
                setStatus(FbStateFormat.ERR_INVALID, true)
            }
            is FbStateFormat.ImportOutcome.Error -> setStatus(outcome.message, true)
            is FbStateFormat.ImportOutcome.Ok -> {
                busy = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            FbStateManager.importCookies(outcome.cookies, replaceAll)
                        }
                        setStatus(
                            "Imported ${result.imported} Facebook cookies " +
                                "(${outcome.skipped} skipped — not facebook.com)."
                        )
                        reloadFacebookTabs()
                    } catch (e: Exception) {
                        setStatus(
                            "Import failed: ${e.message ?: "unknown error"}", true
                        )
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    fun doImport() {
        if (text.isBlank()) {
            setStatus(FbStateFormat.ERR_EMPTY, true)
            return
        }
        val kind = FbStateFormat.detect(text)
        when (kind) {
            FbStateFormat.DetectedKind.NOVA_ENCRYPTED,
            FbStateFormat.DetectedKind.C3C_HEX -> {
                pendingImportKind = kind
                importPassword = ""
                importPasswordError = null
                showImportPasswordDialog = true
            }
            else -> {
                busy = true
                val input = text
                scope.launch {
                    val outcome = withContext(Dispatchers.Default) {
                        FbStateFormat.parseImportText(input)
                    }
                    busy = false
                    handleImportOutcome(outcome)
                }
            }
        }
    }

    fun doExportJson() {
        busy = true
        scope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) { FbStateManager.exportCookies() }
                val json = withContext(Dispatchers.Default) {
                    FbStateFormat.encodeWrapper(cookies)
                }
                text = json
                cookieCount = cookies.size
                exportFormat = "json"
                setStatus("${cookies.size} Facebook cookies found")
            } catch (e: Exception) {
                setStatus("Could not read Facebook cookies: ${e.message ?: "unknown error"}", true)
            } finally {
                busy = false
            }
        }
    }

    fun doExportBase64() {
        busy = true
        scope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) { FbStateManager.exportCookies() }
                val encoded = withContext(Dispatchers.Default) {
                    FbStateFormat.encodeBase64(FbStateFormat.encodeWrapper(cookies))
                }
                text = encoded
                cookieCount = cookies.size
                exportFormat = "base64"
                setStatus("${cookies.size} Facebook cookies found")
            } catch (e: Exception) {
                setStatus("Could not read Facebook cookies: ${e.message ?: "unknown error"}", true)
            } finally {
                busy = false
            }
        }
    }

    fun doExportEncrypted(password: CharArray) {
        busy = true
        scope.launch {
            try {
                val cookies = withContext(Dispatchers.IO) { FbStateManager.exportCookies() }
                val envelope = withContext(Dispatchers.Default) {
                    FbStateFormat.encryptNova(FbStateFormat.encodeWrapper(cookies), password)
                }
                text = envelope
                cookieCount = cookies.size
                exportFormat = "encrypted"
                setStatus("${cookies.size} Facebook cookies found")
            } catch (e: Exception) {
                setStatus("Could not encrypt the export: ${e.message ?: "unknown error"}", true)
            } finally {
                busy = false
            }
        }
    }

    fun doCopy() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FBState", text))
            setStatus("Copied to clipboard.")
        } catch (_: Exception) {
            setStatus("Could not copy to clipboard.", true)
        }
    }

    fun doPaste() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (pasted.isNullOrEmpty()) {
                setStatus("Clipboard is empty.", true)
            } else {
                text = pasted
                setStatus("Pasted from clipboard.")
            }
        } catch (_: Exception) {
            setStatus("Could not read the clipboard.", true)
        }
    }

    fun doSave() {
        if (text.isEmpty()) {
            setStatus("Nothing to save — the text area is empty.", true)
            return
        }
        // §4.7 filenames + MIME types, by the last export format.
        val (fileName, mimeType) = when (exportFormat) {
            "base64" -> "fbstate-facebook.b64" to "text/plain"
            "encrypted" -> "fbstate-facebook.enc" to "text/plain"
            else -> "fbstate-facebook.json" to "application/json"
        }
        busy = true
        scope.launch {
            // The app's existing save pattern for generated files:
            // write a temp file in filesDir, then publish it to public
            // Downloads/NOVA/Browser/ via MediaStore (DownloadPublish —
            // scoped storage, no storage permission needed).
            val uri = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(context.filesDir, "fbstate-export.tmp")
                    tmp.writeText(text)
                    val published = DownloadPublish.publishToDownloads(
                        context, tmp, mimeType, fileName
                    )
                    try {
                        tmp.delete()
                    } catch (_: Exception) {
                    }
                    published
                } catch (_: Exception) {
                    null
                }
            }
            busy = false
            if (uri != null) {
                setStatus("Saved to Downloads/NOVA/Browser/$fileName")
            } else {
                setStatus("Could not save the file.", true)
            }
        }
    }

    fun doSoftLogout() {
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { FbStateManager.softLogout() }
                setStatus("Local FBState data deleted successfully.")
                reloadFacebookTabs()
            } catch (e: Exception) {
                setStatus("Soft logout failed: ${e.message ?: "unknown error"}", true)
            } finally {
                busy = false
            }
        }
    }

    // "Load from file" (§4.5): a system file picker, local file only.
    // Loading fills the textarea — it does NOT import directly, so the
    // user always sees what is about to be imported. 1 MB cap: FBState
    // data is kilobytes; anything bigger is not FBState data.
    val loadFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > MAX_FBSTATE_FILE_BYTES) null
                        else bytes.toString(Charsets.UTF_8)
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded == null) {
                setStatus(
                    "Could not read that file — it is empty, too large, or unreadable.", true
                )
            } else {
                text = loaded
                setStatus("Loaded file into the text area — review it before importing.")
            }
        }
    }

    val actionsEnabled = !busy && fbAllowed == true

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        GlassTopAppBar(
            title = { Text("FBState", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
            }
        )
        when {
            isPrivate -> {
                // §4.7: FBState is unavailable in :incognito. The screen
                // refuses to open there, and no cookie access is
                // attempted (FbStateManager is never called).
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "FBState is not available in private mode.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Private-mode cookies are a separate store; " +
                            "exporting them would leak a private session. " +
                            "No cookies were read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            fbEnabled == null || fbAllowed == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            fbEnabled == false -> {
                // §4.1: disabling FBState disables the whole feature.
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "FBState is disabled — enable it in Extensions to use it",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "The FBState row's switch in Extensions turns the " +
                            "whole feature off, including this screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header: cookie count + the honest-limit notes.
                    Text(
                        cookieCount?.let { "$it Facebook cookies found" }
                            ?: "Export your Facebook session, or paste FBState data to import it.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "FBState never sends your session anywhere — export is " +
                            "copy to clipboard or save to file, on this device only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Import does not check that the session works — the " +
                            "Facebook tab will reload and show the result.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Limit: HttpOnly cookies cannot be restored as HttpOnly — " +
                            "WebView cannot set that flag, so they become readable " +
                            "by page JavaScript after import. Secure and expiry " +
                            "are honoured when the imported data carries them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (fbAllowed == false) {
                        GlassCard {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "FBState needs your allow on facebook.com before it can run.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { showAllowDialog = true }) {
                                    Text("Allow FBState on Facebook…")
                                }
                            }
                        }
                    }

                    // The textarea: export output display AND import input.
                    GlassCard {
                        Column(Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                                label = { Text("FBState data") },
                                placeholder = {
                                    Text("Export output appears here — or paste FBState data to import.")
                                },
                                singleLine = false,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace)
                            )
                        }
                    }

                    // Export row.
                    GlassCard {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Export", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { doExportJson() },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("JSON") }
                                Button(
                                    onClick = { doExportBase64() },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Base64") }
                                Button(
                                    onClick = {
                                        exportPassword = ""
                                        exportPasswordConfirm = ""
                                        exportPasswordError = null
                                        showExportPasswordDialog = true
                                    },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Encrypted") }
                            }
                        }
                    }

                    // Import row.
                    GlassCard {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Import", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = replaceAll,
                                    onCheckedChange = { replaceAll = it },
                                    enabled = actionsEnabled
                                )
                                Text("Replace all current Facebook cookies first")
                            }
                            if (replaceAll) {
                                Text(
                                    "All current Facebook cookies will be deleted before " +
                                        "import. If the imported data is incomplete, you may " +
                                        "be logged out.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Button(
                                onClick = { doImport() },
                                enabled = actionsEnabled,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Import") }
                        }
                    }

                    // Clipboard + file row.
                    GlassCard {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Clipboard & file", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { doCopy() },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Copy to clipboard") }
                                OutlinedButton(
                                    onClick = { doPaste() },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Paste from clipboard") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { doSave() },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Save to file") }
                                OutlinedButton(
                                    onClick = { loadFileLauncher.launch(arrayOf("*/*")) },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Load from file") }
                            }
                            OutlinedButton(
                                onClick = {
                                    text = ""
                                    setStatus("Text area cleared.")
                                },
                                enabled = actionsEnabled,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Clear") }
                        }
                    }

                    // Soft logout.
                    OutlinedButton(
                        onClick = { showSoftLogoutConfirm = true },
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            "Delete local FBState data (logout without invalidating the session)"
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Soft Logout")
                    }

                    // Status line (counts only, never values).
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (statusIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    // ---- Dialogs (exact §4.6 / §4.7 text where the spec fixes it) ----

    if (showAllowDialog) {
        AlertDialog(
            onDismissRequest = { onBack() },
            title = { Text("Allow FBState on Facebook?") },
            text = {
                Text(
                    "FBState needs to run on facebook.com to export and import " +
                        "your Facebook session. Facebook is a sensitive site: its " +
                        "pages can contain your password and act as your logged-in " +
                        "account. FBState only reads and writes Facebook cookies " +
                        "through the app itself — it never sends them anywhere. " +
                        "Allow FBState on facebook.com (and its subdomains)?"
                )
            },
            confirmButton = {
                TextButton(onClick = { doAllow() }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text("Cancel") }
            }
        )
    }

    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showExportPasswordDialog = false },
            title = { Text("Export encrypted FBState data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text("Password (at least 8 characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = exportPasswordConfirm,
                        onValueChange = { exportPasswordConfirm = it },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    exportPasswordError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        exportPassword.length < FbStateFormat.MIN_PASSWORD_LENGTH ->
                            exportPasswordError = "Password must be at least 8 characters."
                        exportPassword != exportPasswordConfirm ->
                            exportPasswordError = "Passwords do not match."
                        else -> {
                            val password = exportPassword.toCharArray()
                            exportPassword = ""
                            exportPasswordConfirm = ""
                            showExportPasswordDialog = false
                            doExportEncrypted(password)
                        }
                    }
                }) { Text("Export") }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showImportPasswordDialog = false },
            title = { Text("Import encrypted FBState data") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    importPasswordError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (importPassword.isEmpty()) {
                        importPasswordError = "Enter the password."
                        return@TextButton
                    }
                    // No minimum length on import: a C3C-encrypted file
                    // may have been made with a shorter password.
                    val kind = pendingImportKind
                    val password = importPassword.toCharArray()
                    val input = text
                    importPassword = ""
                    showImportPasswordDialog = false
                    if (kind == null) {
                        setStatus(FbStateFormat.ERR_INVALID, true)
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.Default) {
                            FbStateFormat.decryptAndParse(kind, input, password)
                        }
                        busy = false
                        handleImportOutcome(outcome)
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportPasswordDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showSoftLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showSoftLogoutConfirm = false },
            title = { Text("Soft Logout") },
            text = {
                Text("Are you sure you want to delete local FBState data (soft logout)?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSoftLogoutConfirm = false
                    doSoftLogout()
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showSoftLogoutConfirm = false }) { Text("No") }
            }
        )
    }
}

/** "Load from file" size cap: FBState data is kilobytes; anything
 *  bigger is not FBState data. */
private const val MAX_FBSTATE_FILE_BYTES = 1024 * 1024
