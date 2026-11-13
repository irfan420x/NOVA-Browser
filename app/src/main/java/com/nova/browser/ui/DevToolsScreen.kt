package com.nova.browser.ui

import android.webkit.CookieManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.browser.devtools.ConsoleMessage
import com.nova.browser.devtools.CurlBuilder
import com.nova.browser.devtools.DevToolsHub
import com.nova.browser.devtools.ElementInspector
import com.nova.browser.devtools.JsonPretty
import com.nova.browser.devtools.NetworkLog
import com.nova.browser.devtools.UrlRedactor
import com.nova.browser.web.UrlUtils
import kotlinx.coroutines.delay

/**
 * The bridge the coordinator wires to the current tab's WebView.
 * Implemented in BrowserState/MainActivity (hook F) — everything here
 * runs on the main thread, so evaluateJavascript is legal. The dock
 * never touches a WebView directly.
 */
interface DevToolsHost {
    /** Evaluate [script] on the current tab's WebView; [onResult] gets the
     *  raw evaluateJavascript result (JSON-encoded, or null). Main thread. */
    fun evaluateJs(script: String, onResult: (String?) -> Unit)
    /** The current tab's URL, or null on the home screen. */
    fun currentUrl(): String?
}

/**
 * DevTools dock — built as a FULL SCREEN, not a bottom sheet: a drag-
 * resizable sheet over a live WebView is fragile (touch fights the page,
 * the WebView keeps its size), and the log lists need the full width.
 * The underlying WebView stays alive behind this screen, so closing the
 * dock returns to the page exactly as it was.
 *
 * Tabs: Console (log + JS REPL), Elements (tap-to-inspect), Network
 * (request log + Copy as cURL), Storage (cookies + web storage).
 * The dock is only reachable from the menu when Dev Mode is on (hook C)
 * — in Normal Mode none of this exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsScreen(host: DevToolsHost?, onClose: () -> Unit) {
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Console", "Elements", "Network", "Storage")
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text("DevTools", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close DevTools") }
                },
                actions = {
                    IconButton(onClick = { DevToolsHub.clearLogs() }) {
                        Icon(Icons.Filled.Delete, "Clear console and network logs")
                    }
                }
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Honest CSP note — injection is not magic (plan §6.1).
            Text(
                "On strict-CSP sites, injection is limited and inspect / console may not work. " +
                    "Use USB remote debugging (Dev Mode setting) for full DevTools on those sites.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            TabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = { Text(label) }
                    )
                }
            }
            when (tabIndex) {
                0 -> ConsoleTab(host)
                1 -> ElementsTab(host)
                2 -> NetworkTab()
                3 -> StorageTab(host)
            }
        }
    }
}

// ---- Console ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleTab(host: DevToolsHost?) {
    var levelFilter by rememberSaveable { mutableStateOf("all") }
    var replInput by rememberSaveable { mutableStateOf("") }
    var replRunning by remember { mutableStateOf(false) }
    // Live refresh: the log fills from onConsoleMessage while this screen
    // is open; poll twice a second — cheap, and no shared-state wiring
    // into BrowserState is needed.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(500); tick++ }
    }
    val messages = remember(tick, levelFilter) { DevToolsHub.consoleSnapshot(levelFilter) }


    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in listOf("all", "log", "info", "warn", "error", "debug")) {
                FilterChip(
                    selected = levelFilter == level,
                    onClick = { levelFilter = level },
                    label = { Text(level) }
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { DevToolsHub.consoleClear(); tick++ }) { Text("Clear") }
        }
        Text(
            "${messages.size} messages" +
                if (levelFilter != "all") " (filtered: $levelFilter)" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (messages.isEmpty()) {
            NebulaEmptyState(Icons.Filled.Code, "No console messages yet — load a page and its console output appears here.")
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages) { msg -> ConsoleRow(msg) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = replInput,
                onValueChange = { replInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("JS REPL — e.g. document.title") },
                singleLine = true,
                enabled = host != null && !replRunning
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val code = replInput.trim()
                    if (code.isEmpty() || host == null) return@Button
                    replRunning = true
                    DevToolsHub.consoleAddLocal("log", "> $code")
                    try {
                        host.evaluateJs(code) { raw ->
                            // B11: a null result means the script threw —
                            // show an error, not a bare "undefined".
                            val decoded = if (raw == null) {
                                "Error: JavaScript threw an exception"
                            } else {
                                JsonPretty.decodeJsStringResult(raw) ?: raw
                            }
                            DevToolsHub.consoleAddLocal("log", "< $decoded")
                            replRunning = false
                            tick++
                        }
                    } catch (e: Exception) {
                        DevToolsHub.consoleAddLocal("log", "< Error: ${e.message}")
                        replRunning = false
                        tick++
                    }
                    replInput = ""
                    tick++
                },
                enabled = host != null && !replRunning
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Run")
            }
        }
        if (host == null) {
            Text(
                "No page open — open a site first, then the REPL can run JavaScript on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Text(
            "REPL input and output stay in memory only — never written to a file or the error log.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleRow(msg: ConsoleMessage) {
    val color = when (msg.level) {
        "error" -> NebulaError
        "warn" -> Color(0xFFFBBF24)
        "info" -> NebulaSecondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg.level.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (msg.sourceId != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${msg.sourceId}:${msg.lineNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            SelectionContainer {
                Text(msg.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---- Elements ----

@Composable
private fun ElementsTab(host: DevToolsHost?) {
    var inspecting by rememberSaveable { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<ElementInspector.InspectedElement?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    // B2: closing the dock (or leaving this screen) while inspecting must
    // remove the page's click interceptor — otherwise every tap stays
    // intercepted and links stay dead until the dock reopens.
    val inspectingRef = rememberUpdatedState(inspecting)
    DisposableEffect(Unit) {
        onDispose {
            if (inspectingRef.value) {
                try { host?.evaluateJs(ElementInspector.STOP_JS) { _ -> } } catch (_: Exception) { }
            }
        }
    }

    // While inspecting, poll what the page stored (the click listener
    // writes window.__novaInspected; there is no JavascriptInterface).
    LaunchedEffect(inspecting) {
        while (inspecting) {
            delay(600)
            host?.evaluateJs(ElementInspector.READ_JS) { raw ->
                ElementInspector.parseInspected(raw)?.let { inspected = it }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Tap-to-inspect: turn it on, then tap any element on the page. " +
                "It is outlined in violet and its details show here. " +
                "Taps are intercepted while inspecting, so links won't navigate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (host == null) return@Button
                    if (!inspecting) {
                        host.evaluateJs(ElementInspector.INSTALL_JS) { raw ->
                            // B6: INSTALL_JS returns 'installed' / 'already'
                            // on success and 'error: …' when the page blocks
                            // injection (strict CSP) — never claim success
                            // then. A null result also means it failed.
                            val decoded = raw?.let { JsonPretty.decodeJsStringResult(it) ?: it } ?: ""
                            if (decoded.startsWith("error:") || decoded.isEmpty()) {
                                status = "Inspection is limited on this site — the page blocks it. Try USB remote debugging instead."
                            } else {
                                status = "Inspecting — tap an element on the page."
                                inspecting = true
                            }
                        }
                    } else {
                        host.evaluateJs(ElementInspector.STOP_JS) {
                            inspecting = false
                            inspected = null
                            status = "Inspecting stopped."
                        }
                    }
                },
                enabled = host != null
            ) { Text(if (inspecting) "Stop inspecting" else "Start inspecting") }
            if (inspecting) {
                OutlinedButton(onClick = {
                    host?.evaluateJs(ElementInspector.STOP_JS) {
                        inspecting = false
                        inspected = null
                        status = "Inspecting stopped."
                    }
                }) { Text("Stop") }
            }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = NebulaSecondary)
        }
        if (host == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No page open — open a site first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        val el = inspected
        if (el == null) {
            NebulaEmptyState(Icons.Filled.Code, "Nothing inspected yet.")
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InspectField("Tag", el.tag)
                    if (el.id.isNotBlank()) InspectField("id", "#${el.id}")
                    if (el.classes.isNotBlank()) InspectField("Classes", el.classes)
                    if (el.text.isNotBlank()) InspectField("Text", el.text)
                    if (el.html.isNotBlank()) {
                        Text("HTML", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        SelectionContainer {
                            Text(
                                el.html,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Row {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(UrlRedactor.redact(el.html)))
                        }) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy HTML")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectField(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

// ---- Network ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkTab() {
    var kindFilter by rememberSaveable { mutableStateOf("All") }
    var selected by remember { mutableStateOf<NetworkLog.Entry?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(500); tick++ }
    }
    val all = remember(tick) { DevToolsHub.networkSnapshot() }
    val filtered = remember(all, kindFilter) {
        when (kindFilter) {
            "Blocked" -> all.filter { it.blocked }
            "All" -> all
            else -> all.filter { it.kind == kindFilter }
        }
    }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Status and size are unavailable from the interception point — " +
                "shouldInterceptRequest only sees the request, not the response. " +
                "Blocked / allowed, method, kind and timing are recorded honestly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (kind in listOf("All", "Document", "Script", "Stylesheet", "Image", "Font", "Media", "Other", "Blocked")) {
                FilterChip(
                    selected = kindFilter == kind,
                    onClick = { kindFilter = kind },
                    label = { Text(kind) }
                )
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { DevToolsHub.networkClear(); tick++ }) { Text("Clear") }
        }
        Text(
            "${filtered.size} requests (${all.count { it.blocked }} blocked)" +
                // B16: the 200-cap drops silently no more — say how many
                // oldest entries are gone.
                if (DevToolsHub.networkDroppedCount() > 0) {
                    " — ${DevToolsHub.networkDroppedCount()} oldest dropped (log keeps the newest 200)"
                } else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            NebulaEmptyState(Icons.Filled.Code, "No requests logged yet — load a page and its requests appear here.")
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { entry ->
                    Card(
                        Modifier.fillMaxWidth()
                            .clickable { selected = entry }
                            .border(
                                if (entry.blocked) 1.dp else 0.dp,
                                if (entry.blocked) NebulaError else Color.Transparent,
                                RoundedCornerShape(20.dp)
                            )
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    entry.method,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (entry.blocked) NebulaError else NebulaSecondary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(entry.kind, style = MaterialTheme.typography.labelMedium)
                                if (entry.blocked) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "BLOCKED (${entry.blockedKind ?: "AD"})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = NebulaError
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    entry.durationMs?.let { "${it}ms" } ?: "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                entry.url,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2
                            )
                            if (entry.urlRedacted) {
                                Text(
                                    "Sensitive query value redacted",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NebulaSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
        selected?.let { entry ->
            Spacer(Modifier.height(8.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Request detail", fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text(entry.url, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${entry.method} · ${entry.kind} · " +
                            if (entry.blocked) "blocked (${entry.blockedKind ?: "AD"})" else "allowed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    CurlBuilder.build(entry.method, entry.url)
                                )
                            )
                        }) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy as cURL")
                        }
                        TextButton(onClick = { selected = null }) { Text("Close") }
                    }
                }
            }
        }
    }
}

// ---- Storage ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageTab(host: DevToolsHost?) {
    val url = host?.currentUrl()
    val pageHost = url?.let { UrlUtils.extractHost(it) }
    var cookies by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // B17: null = the dump itself failed (storage blocked/unavailable),
    // empty = genuinely no keys. The two must render differently.
    var localItems by remember { mutableStateOf<Map<String, String>?>(emptyMap()) }
    var sessionItems by remember { mutableStateOf<Map<String, String>?>(emptyMap()) }
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    // Token-like storage values are MASKED by default (W6 W3-5) — a dev
    // tool must not splash a session token across the screen. Reveal is
    // an explicit per-view toggle; values are never written to a file.
    var revealValues by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        // Cookies: CookieManager on the main thread (this is composition's thread).
        cookies = try {
            val raw = if (url.isNullOrBlank()) null else CookieManager.getInstance().getCookie(url)
            raw?.split(";")?.mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val idx = trimmed.indexOf('=')
                if (idx <= 0) trimmed to "" else trimmed.substring(0, idx) to trimmed.substring(idx + 1)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        host?.evaluateJs(ElementInspector.LOCAL_STORAGE_DUMP_JS) { raw ->
            localItems = ElementInspector.parseStorageDump(raw)
        }
        host?.evaluateJs(ElementInspector.SESSION_STORAGE_DUMP_JS) { raw ->
            sessionItems = ElementInspector.parseStorageDump(raw)
        }
    }

    LaunchedEffect(url, tick) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pageHost ?: "No page open", fontWeight = FontWeight.Bold)
                Text(
                    "Cookies + storage for this site only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh storage") }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = NebulaSecondary)
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Token-like values are masked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = revealValues,
                onClick = { revealValues = !revealValues },
                label = { Text(if (revealValues) "Hide values" else "Reveal values") }
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Cookies (${cookies.size})", fontWeight = FontWeight.Bold)
                if (cookies.isEmpty()) {
                    Text("No cookies for this site.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                cookies.forEach { (name, value) ->
                    StorageRow(
                        key = name,
                        // N-5: cookies are masked like storage values until
                        // the user explicitly reveals them.
                        value = if (revealValues) value else ElementInspector.maskStorageValue(name, value),
                        onDelete = {
                            // Expire the cookie, then flush. CookieManager
                            // has no delete-by-name API — expiring it is the
                            // documented way.
                            try {
                                val target = url ?: return@StorageRow
                                CookieManager.getInstance().setCookie(
                                    target,
                                    "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/"
                                )
                                CookieManager.getInstance().flush()
                                note = "Cookie '$name' expired."
                                tick++
                            } catch (e: Exception) {
                                note = "Couldn't delete cookie: ${e.message}"
                            }
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            try {
                                // D1: removeAllCookies() is profile-global —
                                // it would log the user out of EVERY site.
                                // Expire only this site's listed cookies.
                                val target = url ?: return@OutlinedButton
                                cookies.forEach { (name, _) ->
                                    CookieManager.getInstance().setCookie(
                                        target,
                                        "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/"
                                    )
                                }
                                CookieManager.getInstance().flush()
                                note = "Cookies for this site expired (${cookies.size})."
                                tick++
                            } catch (e: Exception) {
                                note = "Couldn't clear cookies: ${e.message}"
                            }
                        },
                        enabled = cookies.isNotEmpty()
                    ) { Text("Clear cookies") }
                }
            }
            item {
                Text("localStorage (${localItems?.size ?: "?"})", fontWeight = FontWeight.Bold)
                if (localItems == null) {
                    Text("Storage unavailable on this page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (localItems!!.isEmpty()) {
                    Text("Empty.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                localItems.orEmpty().forEach { (key, value) ->
                    StorageRow(
                        key = key,
                        value = if (revealValues) value else ElementInspector.maskStorageValue(key, value),
                        onDelete = {
                            host?.evaluateJs(ElementInspector.removeLocalItemJs(key)) { tick++ }
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        host?.evaluateJs(ElementInspector.CLEAR_LOCAL_STORAGE_JS) {
                            note = "localStorage cleared."
                            tick++
                        }
                    },
                    enabled = localItems.orEmpty().isNotEmpty()
                ) { Text("Clear localStorage") }
            }
            item {
                Text("sessionStorage (${sessionItems?.size ?: "?"})", fontWeight = FontWeight.Bold)
                if (sessionItems == null) {
                    Text("Storage unavailable on this page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (sessionItems!!.isEmpty()) {
                    Text("Empty.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sessionItems.orEmpty().forEach { (key, value) ->
                    StorageRow(
                        key = key,
                        value = if (revealValues) value else ElementInspector.maskStorageValue(key, value),
                        onDelete = {
                            host?.evaluateJs(ElementInspector.removeSessionItemJs(key)) { tick++ }
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        host?.evaluateJs(ElementInspector.CLEAR_SESSION_STORAGE_JS) {
                            note = "sessionStorage cleared."
                            tick++
                        }
                    },
                    enabled = sessionItems.orEmpty().isNotEmpty()
                ) { Text("Clear sessionStorage") }
            }
        }
        Text(
            "IndexedDB and the HTTP cache are not shown here — WebView exposes no API to list them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StorageRow(key: String, value: String, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SelectionContainer { Text(key, fontWeight = FontWeight.SemiBold) }
                SelectionContainer {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete $key") }
        }
    }
}
