package com.nova.browser.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.browser.data.ErrorLog
import com.nova.browser.data.ExtensionStore
import com.nova.browser.extensions.ExtensionRuntime
import com.nova.browser.extensions.NovaExtZip
import com.nova.browser.extensions.Userscript
import com.nova.browser.web.BrowserHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color

/**
 * Extensions — manage built-in and user-installed userscripts.
 *
 * A userscript runs with FULL PAGE PRIVILEGES (W6 S-2): it can read page
 * content — including passwords — and use the page's own network access to
 * send it anywhere. There is no native bridge to app code, but that does
 * not stop exfiltration through the page. Installing a script is therefore
 * a trust decision: the install flow shows the script's full @match scope
 * and the privilege warning, and the user confirms before anything is
 * stored. Sensitive pages (login/banking, or any page with a password
 * field) are default-off for every script — a host must be explicitly
 * allowed per script in the per-site section.
 *
 * - Built-ins (YouTube Ad Skipper, Cookie Banner Hider) cannot be deleted,
 *   only disabled. The skipper's switch here IS the Settings toggle
 *   (Settings.youtubeAdSkipper is its master switch); the Cookie Hider has
 *   its own stored toggle.
 * - User scripts are installed from a URL (fetched with HttpURLConnection,
 *   30 s timeout), pasted text, or a local `.novaext` zip (system file
 *   picker → NovaExtZip validation → the same review dialog). All paths
 *   validate the header (@name and at least one @match required) and
 *   reject scripts over 200 KB; zip installs additionally validate the
 *   manifest, the package layout, and the icon (NOVA-EXTENSIONS.md §1.5).
 * - Per-site on/off is a DISABLE list per script: a listed host (or its
 *   subdomains) never runs the script.
 *
 * Normal-mode screen only: the store lives in the shared files dir, so the
 * coordinator must not route to it from PrivateActivity (a host typed in
 * private mode would otherwise leak into the store).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    state: BrowserState,
    onBack: () -> Unit,
    // v2.4: opens the FBState screen (Lane C's FbStateScreen, routed in
    // BrowserScreen). Defaults to a no-op so existing call sites compile.
    onOpenFbState: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    // B-20: null = still loading (first frame must not flash the
    // "built-in scripts are missing" empty state before reload runs).
    var entries by remember { mutableStateOf<List<ExtEntry>?>(null) }
    // B-12: install input + expanded rows survive rotation. Expanded ids
    // are a List (not a Set) because List<String> has a built-in
    // rememberSaveable saver and Set<String> does not; the toggle below
    // guards against duplicates.
    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var newHostText by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var viewSource by remember { mutableStateOf<Userscript?>(null) }
    var deleteTarget by remember { mutableStateOf<Userscript?>(null) }
    var addMode by rememberSaveable { mutableStateOf<String?>(null) } // "url" | "paste" | null
    var addInput by rememberSaveable { mutableStateOf("") }
    // Install review (W6 S-2): a parsed script waits here until the user
    // has seen its full @match scope + the privilege warning and confirms.
    // (A Userscript is not saveable, so an in-progress install is lost on
    // rotation — nothing is stored yet, so that is safe, just annoying.)
    var pendingInstall by remember { mutableStateOf<Userscript?>(null) }
    // v2.4 zip install: the validated package behind pendingInstall, when
    // the review is for a .novaext file (null for URL/paste installs).
    // Carries the validated icon bytes + the sensitive-pages declaration
    // for the review dialog, and the icon write after a confirmed install.
    var pendingZipInstall by remember { mutableStateOf<NovaExtZip.ZipInstallResult.Ready?>(null) }
    var pendingZipFileName by remember { mutableStateOf<String?>(null) }
    var pendingZipSizeKb by remember { mutableStateOf(0) }
    var sensitiveHostText by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // B-15: allowing a script on a sensitive host needs confirmation.
    var confirmSensitiveAllow by remember { mutableStateOf<Pair<ExtEntry, String>?>(null) }
    // B-18: the in-flight URL fetch, so dismissing the add dialog cancels it.
    var fetchJob by remember { mutableStateOf<Job?>(null) }

    fun assetReader(path: String): String? = try {
        context.assets.open(path).bufferedReader().readText().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun reload() {
        // N-9: the screen is never routed from PrivateActivity (menu is
        // gated), but if it ever is, refuse to show store state there.
        if (ExtensionStore.isPrivateProcess()) {
            status = "Extensions are not available in private mode"
            entries = emptyList()
            return
        }
        // L5: the index + every script file are disk reads — do them on
        // IO, not on the main thread, or a large script list janks the
        // screen open. State is assigned back on the main thread.
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val builtIns = ExtensionRuntime.builtInScripts(::assetReader)
                val userScripts = ExtensionStore.loadInstalled(context)
                val built = mutableListOf<ExtEntry>()
                for (script in builtIns) {
                    val builtin = ExtensionStore.builtinState(context, script.id)
                    val effectiveEnabled = if (script.id == ExtensionRuntime.YOUTUBE_SKIPPER_ID) {
                        // Master switch lives in Settings; this row edits that toggle.
                        state.settings.youtubeAdSkipper
                    } else {
                        builtin.enabled
                    }
                    val disabled = builtin.disabledHosts
                    built.add(ExtEntry(script.copy(disabledHosts = disabled, allowedSensitiveHosts = builtin.allowedSensitiveHosts), isBuiltIn = true, effectiveEnabled))
                }
                for (script in userScripts) {
                    built.add(ExtEntry(script, isBuiltIn = false, script.enabled))
                }
                built
            }
            entries = list
            // N-12: the stored index was corrupt (backup restored if possible)
            // — say so instead of silently showing reset state.
            if (ExtensionStore.lastIndexCorrupt) {
                status = "Extension settings were reset — the saved list was damaged"
            }
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    // v2.4: a .novaext file picked in the system file picker arrives via
    // BrowserState.pendingNovaExt (BrowserHost deposits it). Validate it
    // fully BEFORE anything is written; on success show the same review
    // dialog as a URL/paste install (full @match scope first — install
    // is a trust decision), on failure show the exact §1.5 error string
    // in the status line. Waits for the script list so the duplicate-id
    // check sees every installed id. The pending file is consumed
    // (cleared) either way — dismissing the review loses the pending
    // install, and nothing is stored, same as today.
    val pendingZip = state.pendingNovaExt
    LaunchedEffect(pendingZip, entries) {
        val picked = pendingZip ?: return@LaunchedEffect
        val loaded = entries ?: return@LaunchedEffect // list still loading
        state.pendingNovaExt = null
        if (ExtensionStore.isPrivateProcess()) {
            status = "Extensions are not available in private mode"
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            NovaExtZip.installFromZip(picked.bytes, loaded.map { it.script.id }.toSet())
        }
        when (result) {
            is NovaExtZip.ZipInstallResult.Ready -> {
                pendingInstall = result.script
                pendingZipInstall = result
                pendingZipFileName = picked.fileName
                pendingZipSizeKb = (picked.bytes.size + 1023) / 1024
            }
            is NovaExtZip.ZipInstallResult.Failure -> {
                status = "Install failed: ${result.reason}"
            }
        }
    }

    /** Open the system file picker for a `.novaext` package (v2.4, local
     *  zip only — no network fetch). Classic onActivityResult path, like
     *  the WebView file chooser: the result lands in
     *  MainActivity.onActivityResult → BrowserHost.handleActivityResult,
     *  which deposits the bytes in state.pendingNovaExt. */
    fun launchZipPicker() {
        val activity = context as? Activity
        if (activity == null) {
            status = "Couldn't open the file picker"
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed")
            )
        }
        try {
            activity.startActivityForResult(intent, BrowserHost.NOVAEXT_PICK_REQUEST_CODE)
        } catch (_: Exception) {
            status = "Couldn't open the file picker"
        }
    }

    /** B-14: a host input must be a bare host — no scheme, path, port or
     *  whitespace. Anything else can never match an extracted host and
     *  would sit in the list as a silent no-op. */
    fun isValidHostInput(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty()) return false
        if (h.contains("://") || h.contains('/') || h.contains(':') ||
            h.contains(' ') || h.contains('?') || h.contains('#')
        ) return false
        if (h.startsWith('.') || h.endsWith('.')) return false
        return true
    }

    fun setEnabled(entry: ExtEntry, enabled: Boolean) {
        if (entry.script.id == ExtensionRuntime.YOUTUBE_SKIPPER_ID) {
            state.settings.youtubeAdSkipper = enabled
            status = if (enabled) "\"${entry.script.name}\" enabled" else "\"${entry.script.name}\" disabled"
            refreshKey++
        } else {
            // B-5: check the store result — in private mode (or on any
            // I/O failure) the write is a no-op, and the UI must not
            // claim success. L5: the write runs on IO.
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    ExtensionStore.setEnabled(context, entry.script.id, enabled)
                }
                status = if (ok) {
                    if (enabled) "\"${entry.script.name}\" enabled" else "\"${entry.script.name}\" disabled"
                } else {
                    "Couldn't save — \"${entry.script.name}\" was not changed"
                }
                refreshKey++
            }
        }
    }

    fun addHost(entry: ExtEntry, host: String) {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return
        if (!isValidHostInput(h)) {
            status = "\"$h\" is not a host — enter a bare host like example.com (no scheme, path or port)"
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ExtensionStore.setHostDisabled(context, entry.script.id, h, true)
            }
            newHostText = newHostText - entry.script.id
            status = if (ok) "\"${entry.script.name}\" will not run on $h"
            else "Couldn't save — \"${entry.script.name}\" was not changed"
            refreshKey++
        }
    }

    fun removeHost(entry: ExtEntry, host: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ExtensionStore.setHostDisabled(context, entry.script.id, host, false)
            }
            if (!ok) status = "Couldn't save — \"${entry.script.name}\" was not changed"
            refreshKey++
        }
    }

    fun addSensitiveHost(entry: ExtEntry, host: String) {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return
        if (!isValidHostInput(h)) {
            status = "\"$h\" is not a host — enter a bare host like bank.example.com"
            return
        }
        // N-2: a bare TLD / public suffix is too broad to allow.
        if (!ExtensionStore.isValidSensitiveAllowHost(h)) {
            status = "\"$h\" is too broad — allow a full host like bank.example.com, not a top-level domain"
            return
        }
        // B-15: one tap grants a full-privilege script access to a
        // banking/login page — confirm first.
        confirmSensitiveAllow = entry to h
    }

    fun confirmAddSensitiveHost(entry: ExtEntry, host: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ExtensionStore.setSensitiveHostAllowed(context, entry.script.id, host, true)
            }
            sensitiveHostText = sensitiveHostText - entry.script.id
            status = if (ok) "\"${entry.script.name}\" is now allowed on sensitive pages at $host (and its subdomains)"
            else "Couldn't save — \"${entry.script.name}\" was not changed"
            refreshKey++
        }
    }

    fun removeSensitiveHost(entry: ExtEntry, host: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ExtensionStore.setSensitiveHostAllowed(context, entry.script.id, host, false)
            }
            if (!ok) status = "Couldn't save — \"${entry.script.name}\" was not changed"
            refreshKey++
        }
    }

    // Parse + validate only. Nothing is stored until the user confirms in
    // the review dialog (W6 S-2).
    fun doInstall(source: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ExtensionRuntime.installFromText(source, entries.orEmpty().map { it.script.id }.toSet())
            }
            when (result) {
                is ExtensionRuntime.InstallResult.Success -> {
                    pendingInstall = result.script
                    addMode = null
                    addInput = ""
                }
                is ExtensionRuntime.InstallResult.Failure -> {
                    status = "Install failed: ${result.reason}"
                }
            }
        }
    }

    fun doConfirmInstall() {
        val script = pendingInstall ?: return
        val zipReady = pendingZipInstall
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ExtensionStore.install(context, script)
            }
            if (ok && zipReady?.iconBytes != null) {
                // §1.6: store a validated icon next to the script file,
                // only after a successful install. The icon is cosmetic:
                // a failed icon write is logged and the install still
                // counts as done.
                withContext(Dispatchers.IO) {
                    runCatching {
                        val iconsDir = File(context.filesDir, "extensions/icons")
                        if (!iconsDir.exists()) iconsDir.mkdirs()
                        File(iconsDir, "${script.id}.png").writeBytes(zipReady.iconBytes)
                    }.onFailure { e ->
                        ErrorLog.log(context, "Extensions", "Icon save failed for ${script.name}", e)
                    }
                }
            }
            status = if (ok) "\"${script.name}\" installed"
            else "Could not save \"${script.name}\""
            pendingInstall = null
            pendingZipInstall = null
            pendingZipFileName = null
            refreshKey++
        }
    }

    fun doInstallFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            status = "Enter a script URL first"
            return
        }
        fetchJob?.cancel()
        fetchJob = scope.launch {
            status = "Downloading script…"
            val fetched = withContext(Dispatchers.IO) {
                runCatching { ExtensionRuntime.fetchScriptText(trimmed) }
            }
            fetched.onSuccess {
                // B-18: clear the stale "Downloading script…" status —
                // success shows the review dialog, not a stale status.
                status = null
                doInstall(it)
            }
            fetched.onFailure { e ->
                status = "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassTopAppBar(
                title = { Text("Extensions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            GlassCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    "Scripts run with full page privileges: they can read page content — " +
                        "including passwords — and act on pages as you (click, submit forms), " +
                        "and send what they read anywhere the page can reach. " +
                        "Install only scripts you trust.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            if (status != null) {
                Text(status!!, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addInput = ""; addMode = "url" }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add from URL")
                }
                OutlinedButton(onClick = { addInput = ""; addMode = "paste" }) {
                    Text("Paste script")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { launchZipPicker() }) {
                    Text("Install from ZIP")
                }
            }
            Spacer(Modifier.height(12.dp))
            // v2.4 FBState (Lane C): a built-in row with an "Open FBState"
            // action (§6 item 3). The FBState screen itself refuses to
            // open when FBState is disabled (§4.1) — this button only
            // navigates there.
            GlassCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("FBState", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Export and import your Facebook session cookies, on this device only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onOpenFbState) { Text("Open FBState") }
                }
            }
            Spacer(Modifier.height(12.dp))
            val loadedEntries = entries
            if (loadedEntries == null) {
                // B-20: still loading — show nothing instead of the
                // "built-ins are missing" empty state.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (loadedEntries.isEmpty()) {
                NebulaEmptyState(
                    Icons.Filled.Extension,
                    "No extensions installed — the built-in scripts are missing. " +
                        "Reinstalling the app restores them."
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(loadedEntries, key = { it.script.id }) { entry ->
                        ExtensionRow(
                            entry = entry,
                            expanded = expandedIds.contains(entry.script.id),
                            onToggleExpand = {
                                expandedIds = if (expandedIds.contains(entry.script.id)) {
                                    expandedIds - entry.script.id
                                } else {
                                    expandedIds + entry.script.id
                                }
                            },
                            hostText = newHostText[entry.script.id] ?: "",
                            onHostTextChange = {
                                newHostText = newHostText + (entry.script.id to it)
                            },
                            onAddHost = { addHost(entry, newHostText[entry.script.id] ?: "") },
                            onRemoveHost = { removeHost(entry, it) },
                            sensitiveHostText = sensitiveHostText[entry.script.id] ?: "",
                            onSensitiveHostTextChange = {
                                sensitiveHostText = sensitiveHostText + (entry.script.id to it)
                            },
                            onAddSensitiveHost = { addSensitiveHost(entry, sensitiveHostText[entry.script.id] ?: "") },
                            onRemoveSensitiveHost = { removeSensitiveHost(entry, it) },
                            onToggle = { setEnabled(entry, it) },
                            onViewSource = { viewSource = entry.script },
                            onDelete = { deleteTarget = entry.script }
                        )
                    }
                }
            }
        }
    }

    // ---- View source (read-only) ----
    val viewing = viewSource
    if (viewing != null) {
        // B-21: cap the rendered text — a 200 KB script in one Text
        // composable is layout jank. The full source stays installed;
        // only the preview is truncated.
        val sourcePreview = viewing.source.let {
            if (it.length > 8000) it.take(8000) + "\n… (truncated — ${it.length} characters total)"
            else it
        }
        AlertDialog(
            onDismissRequest = { viewSource = null },
            title = { Text(viewing.name) },
            text = {
                Text(
                    sourcePreview,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().height(320.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { viewSource = null }) { Text("Close") }
            }
        )
    }

    // ---- Delete user script (confirm) ----
    val deleting = deleteTarget
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${deleting.name}\"?") },
            text = { Text("The script will stop running on every site. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    // B-5: check the delete result — a failed delete must
                    // not be reported as done. L5: the delete runs on IO.
                    val target = deleting
                    deleteTarget = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            ExtensionStore.delete(context, target.id)
                        }
                        if (ok) {
                            // §2.3: a zip-installed script's stored icon
                            // goes with it (best-effort; the store itself
                            // is untouched — this is the UI-side file op).
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    File(context.filesDir, "extensions/icons/${target.id}.png").delete()
                                }
                            }
                        }
                        status = if (ok) "\"${target.name}\" deleted"
                        else "Couldn't delete \"${target.name}\""
                        refreshKey++
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ---- Sensitive-site allow (confirm, B-15) ----
    val pendingAllow = confirmSensitiveAllow
    if (pendingAllow != null) {
        val (allowEntry, allowHost) = pendingAllow
        AlertDialog(
            onDismissRequest = { confirmSensitiveAllow = null },
            title = { Text("Allow on sensitive pages?") },
            text = {
                Text(
                    "\"${allowEntry.script.name}\" will run on login and banking pages at " +
                        "$allowHost (and its subdomains). It will be able to read passwords " +
                        "and act as you there. Only allow hosts you fully trust with this script.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSensitiveAllow = null
                    confirmAddSensitiveHost(allowEntry, allowHost)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSensitiveAllow = null }) { Text("Cancel") }
            }
        )
    }

    // ---- Install review (W6 S-2): full @match scope + privilege warning ----
    val reviewing = pendingInstall
    if (reviewing != null) {
        AlertDialog(
            onDismissRequest = {
                pendingInstall = null
                pendingZipInstall = null
                pendingZipFileName = null
            },
            title = { Text("Install \"${reviewing.name}\"?") },
            text = {
                Column {
                    Text(
                        "This script will run with full page privileges on:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    reviewing.matches.forEach { match ->
                        Text(
                            "• $match",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "It can read page content — including passwords — and act on pages " +
                            "as you (click, submit forms), and send it anywhere the page can " +
                            "reach. It never runs on login or banking pages unless you " +
                            "explicitly allow a host for it. Install only if you trust the author of this script.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "v${reviewing.version} · " +
                            if (reviewing.runAt == com.nova.browser.extensions.RunAt.DOCUMENT_START) "document-start" else "document-end",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // v2.4 zip installs (§2.1): source file + the extra
                    // sensitive-pages warning when the package declares it.
                    val zipReady = pendingZipInstall
                    if (zipReady != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Installed from file \"${pendingZipFileName ?: "package.novaext"}\" · $pendingZipSizeKb KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (zipReady.wantsSensitivePages) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "This extension asks to run on login and banking pages. It will still need your explicit allow for each host.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doConfirmInstall() }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingInstall = null
                    pendingZipInstall = null
                    pendingZipFileName = null
                }) { Text("Cancel") }
            }
        )
    }

    // ---- Add script (URL fetch or paste) ----
    val mode = addMode
    if (mode != null) {
        val isUrl = mode == "url"
        AlertDialog(
            // B-18: dismissing mid-fetch cancels the fetch, so the review
            // dialog cannot pop up after the user walked away.
            onDismissRequest = { fetchJob?.cancel(); addMode = null },
            title = { Text(if (isUrl) "Add script from URL" else "Paste script") },
            text = {
                Column {
                    Text(
                        if (isUrl) "The script is downloaded and checked before installing. " +
                            "It needs a @name and at least one @match in its header."
                        else "Paste the full script text, including its // ==UserScript== header. " +
                            "It needs a @name and at least one @match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addInput,
                        onValueChange = { addInput = it },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (isUrl) Modifier else Modifier.height(280.dp)),
                        placeholder = {
                            Text(if (isUrl) "https://example.com/script.user.js" else "// ==UserScript==\n// @name …")
                        },
                        singleLine = isUrl
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isUrl) doInstallFromUrl(addInput) else doInstall(addInput)
                }) { Text("Install") }
            },
            dismissButton = {
                // M5: Cancel must cancel the in-flight fetch too — the
                // dialog's onDismissRequest already does, but the button
                // did not, so the install-review dialog popped up after
                // the user had walked away.
                TextButton(onClick = { fetchJob?.cancel(); addMode = null }) { Text("Cancel") }
            }
        )
    }
}

private data class ExtEntry(
    val script: Userscript,
    val isBuiltIn: Boolean,
    val effectiveEnabled: Boolean
)

@Composable
private fun ExtensionRow(
    entry: ExtEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    hostText: String,
    onHostTextChange: (String) -> Unit,
    onAddHost: () -> Unit,
    onRemoveHost: (String) -> Unit,
    sensitiveHostText: String,
    onSensitiveHostTextChange: (String) -> Unit,
    onAddSensitiveHost: () -> Unit,
    onRemoveSensitiveHost: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onViewSource: () -> Unit,
    onDelete: () -> Unit
) {
    val script = entry.script
    val matchSummary = when {
        script.matches.isEmpty() -> "Matches nothing — runs nowhere"
        script.matches.size == 1 -> script.matches.first()
        else -> "${script.matches.first()} (+${script.matches.size - 1} more)"
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        script.name + if (entry.isBuiltIn) "  •  Built-in" else "",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "v${script.version} · ${if (script.runAt == com.nova.browser.extensions.RunAt.DOCUMENT_START) "document-start" else "document-end"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (script.description.isNotBlank()) {
                        Text(
                            script.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        matchSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Switch(checked = entry.effectiveEnabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewSource) {
                    Icon(Icons.Filled.Visibility, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Source")
                }
                OutlinedButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        null, Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (script.disabledHosts.isEmpty()) "Per-site" else "Per-site (${script.disabledHosts.size} off)"
                    )
                }
                if (!entry.isBuiltIn) {
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
            if (expanded) {
                Text(
                    "Sites where this script is switched off. A listed host (and its subdomains) never runs the script.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = onHostTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("example.com") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAddHost, enabled = hostText.isNotBlank()) { Text("Add") }
                }
                script.disabledHosts.sorted().forEach { host ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(host, Modifier.weight(1f))
                        IconButton(onClick = { onRemoveHost(host) }) {
                            Icon(Icons.Filled.Close, "Remove $host")
                        }
                    }
                }
                if (script.disabledHosts.isEmpty()) {
                    Text(
                        "Not switched off anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sensitive sites (login / banking pages)",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "This script never runs on a login or banking page, or on any page " +
                        "with a password field, unless you allow the host here. " +
                        "An allowed host covers the host and its subdomains. " +
                        "Only allow hosts you fully trust with this script.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = sensitiveHostText,
                        onValueChange = onSensitiveHostTextChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("bank.example.com") },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAddSensitiveHost, enabled = sensitiveHostText.isNotBlank()) { Text("Allow") }
                }
                script.allowedSensitiveHosts.sorted().forEach { host ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(host, Modifier.weight(1f))
                        IconButton(onClick = { onRemoveSensitiveHost(host) }) {
                            Icon(Icons.Filled.Close, "Remove $host")
                        }
                    }
                }
                if (script.allowedSensitiveHosts.isEmpty()) {
                    Text(
                        "Not allowed on any sensitive site.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
