package com.nova.browser.ui

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.browser.data.HttpHistory
import com.nova.browser.data.HttpHistoryEntry
import com.nova.browser.data.SavedHttpRequest
import com.nova.browser.devtools.CurlBuilder
import com.nova.browser.devtools.HttpClientEngine
import com.nova.browser.devtools.HttpRequestData
import com.nova.browser.devtools.HttpResult
import com.nova.browser.devtools.JsonPretty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color

/**
 * HTTP Client — a mini-Postman that runs on the phone (plan §6.3).
 *
 * Transport is java.net.HttpURLConnection (see HttpClientEngine) — OkHttp
 * is NOT in tools/deps.txt and no new dependency may be added. Requests
 * run on Dispatchers.IO; the UI never blocks. History + saved requests
 * live in HttpHistory's own SQLite file (http_history.db), never in
 * BrowserDb. No auth helpers beyond a raw Authorization header the user
 * types as a normal header row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpClientScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val historyStore = remember { HttpHistory(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var method by rememberSaveable { mutableStateOf("GET") }
    var url by rememberSaveable { mutableStateOf("") }
    // D3: header rows survive rotation via a Saver (a header is stored as
    // one "Name: value" line; the first ':' is the separator — header
    // names never contain ':').
    val headersSaver = Saver<SnapshotStateList<Pair<String, String>>, List<String>>(
        save = { list -> list.map { (k, v) -> "$k: $v" } },
        restore = { saved ->
            saved.mapNotNull { line ->
                val i = line.indexOf(':')
                if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
            }.toMutableStateList()
        }
    )
    val headers = rememberSaveable(saver = headersSaver) { mutableStateListOf<Pair<String, String>>() }
    var body by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HttpResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prettyJson by rememberSaveable { mutableStateOf(true) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var savedTick by remember { mutableStateOf(0) }
    var listTab by rememberSaveable { mutableStateOf("History") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    // B10: history/saved lists are read on Dispatchers.IO into state —
    // up to 50 rows x 1 MB bodies must never be read during composition.
    // They refresh whenever savedTick changes (send/save/delete).
    var historyRows by remember { mutableStateOf<List<HttpHistoryEntry>>(emptyList()) }
    var savedRows by remember { mutableStateOf<List<SavedHttpRequest>>(emptyList()) }
    LaunchedEffect(savedTick) {
        withContext(Dispatchers.IO) {
            historyRows = historyStore.listHistory()
            savedRows = historyStore.listSaved()
        }
    }

    fun currentRequest() = HttpRequestData(
        method = method,
        url = url,
        headers = headers.filter { it.first.isNotBlank() },
        body = body
    )

    fun send() {
        val request = currentRequest()
        val validationError = request.validate()
        if (validationError != null) {
            error = validationError
            result = null // B12: never show a stale response under a new error
            return
        }
        error = null
        result = null
        sending = true
        scope.launch(Dispatchers.IO) {
            val res = HttpClientEngine.execute(request)
            historyStore.addHistory(request)
            withContext(Dispatchers.Main) {
                result = res
                sending = false
                savedTick++
                if (res.error != null) error = res.error
            }
        }
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text("HTTP Client", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                }
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Method + URL ----
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                        FilterChip(
                            selected = method == m,
                            onClick = { method = m },
                            label = { Text(m) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://api.example.com/users") },
                    label = { Text("URL") },
                    singleLine = true
                )
            }
            // ---- Headers ----
            item {
                Text("Headers", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                if (headers.isEmpty()) {
                    Text(
                        "No headers. Add one for Authorization, Content-Type, etc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                headers.forEachIndexed { index, (name, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { headers[index] = it to value },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Name") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { headers[index] = name to it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Value") },
                            singleLine = true
                        )
                        IconButton(onClick = { headers.removeAt(index) }) {
                            Icon(Icons.Filled.Close, "Remove header")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedButton(onClick = { headers.add("" to "") }) { Text("Add header") }
            }
            // ---- Body ----
            item {
                Text("Body", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("{\"key\": \"value\"}  — sent as-is; set Content-Type yourself") },
                    minLines = 3,
                    maxLines = 8
                )
                Text(
                    "Body is sent as-is for POST / PUT / PATCH. No Content-Type is added for you — add the header if the API needs one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if ((method == "GET" || method == "HEAD") && body.isNotBlank()) {
                    Text(
                        "Bodies are ignored for GET / HEAD requests — the engine drops them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NebulaError
                    )
                }
            }
            // ---- Send ----
            item {
                Button(
                    onClick = { send() },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sending) "Sending…" else "Send")
                }
                // B14: a request to a hanging endpoint used to run until
                // the 30 s timeout with no way out — Cancel disconnects
                // the socket, so the send returns promptly with an error
                // instead of blocking the screen.
                if (sending) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { HttpClientEngine.cancelActive() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancel request") }
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = NebulaError, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // ---- Response ----
            result?.let { res ->
                item {
                    Text("Response", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (res.statusCode < 0) "No response" else res.statusCode.toString(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        res.statusCode < 0 -> NebulaError
                                        res.statusCode < 300 -> NebulaSuccess
                                        res.statusCode < 400 -> NebulaSecondary
                                        else -> NebulaError
                                    }
                                )
                                Spacer(Modifier.width(12.dp))
                                Text("${res.durationMs} ms", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (res.truncated) {
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "body truncated at 1 MB",
                                        color = NebulaError,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            if (res.authStripped) {
                                Text(
                                    "Auth headers were stripped on a cross-origin redirect — the token was NOT sent to the redirect target.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NebulaSecondary
                                )
                            }
                            if (res.finalUrl != url.trim()) {
                                Text(
                                    "Final URL: ${res.finalUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (res.headers.isNotEmpty()) {
                                Text("Headers", fontWeight = FontWeight.SemiBold)
                                res.headers.forEach { (k, v) ->
                                    SelectionContainer {
                                        Text(
                                            "$k: $v",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = prettyJson,
                                    onClick = { prettyJson = !prettyJson },
                                    label = { Text("Pretty JSON") },
                                    enabled = JsonPretty.isJson(res.body)
                                )
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(CurlBuilder.build(currentRequest())))
                                }) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy as cURL")
                                }
                            }
                            Text(
                                "Copied cURL redacts auth header values and sensitive URL params, and leaves out secret-bearing bodies — it will not authenticate as you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Text(
                                    if (prettyJson) JsonPretty.pretty(res.body) ?: res.body else res.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
                // ---- Save ----
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = saveName,
                            onValueChange = { saveName = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Name for this request") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (saveName.isBlank()) {
                                    error = "Give the request a name first."
                                } else if (historyStore.saveRequest(saveName, currentRequest())) {
                                    saveName = ""
                                    savedTick++
                                    error = null
                                } else {
                                    error = "Couldn't save that request."
                                }
                            }
                        ) { Text("Save") }
                    }
                }
            }
            // ---- History / Saved ----
            item {
                Text("History & saved", fontWeight = FontWeight.Bold)
                Text(
                    "History stores what you sent (URL, headers, body). Auth header values are redacted before saving, but bodies are stored as-is — don't send passwords or tokens you wouldn't want stored on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = listTab == "History", onClick = { listTab = "History" }, label = { Text("History") })
                    FilterChip(selected = listTab == "Saved", onClick = { listTab = "Saved" }, label = { Text("Saved") })
                    Spacer(Modifier.weight(1f))
                    if (listTab == "History") {
                        TextButton(onClick = { confirmClearHistory = true }) { Text("Clear") }
                    }
                }
            }
            if (listTab == "History") {
                if (historyRows.isEmpty()) {
                    item { Text("No requests sent yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(historyRows) { row ->
                        HistoryRow(
                            title = "${row.method}  ${row.url}",
                            onLoad = {
                                method = row.method
                                url = row.url
                                headers.clear()
                                // B3: stored auth values are "<redacted>" —
                                // never re-send the literal word as a token.
                                val dropped = row.headers.filter { it.second == "<redacted>" }
                                headers.addAll(row.headers.filter { it.second != "<redacted>" })
                                body = row.body
                                result = null
                                error = if (dropped.isNotEmpty()) "Auth header was redacted in storage — re-enter it." else null
                            },
                            onDelete = { historyStore.deleteHistory(row.id); savedTick++ }
                        )
                    }
                }
            } else {
                if (savedRows.isEmpty()) {
                    item { Text("No saved requests yet — send one, name it, and tap Save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(savedRows) { row ->
                        HistoryRow(
                            title = "${row.name} — ${row.method} ${row.url}",
                            onLoad = {
                                method = row.method
                                url = row.url
                                headers.clear()
                                // B3: stored auth values are "<redacted>" —
                                // never re-send the literal word as a token.
                                val dropped = row.headers.filter { it.second == "<redacted>" }
                                headers.addAll(row.headers.filter { it.second != "<redacted>" })
                                body = row.body
                                result = null
                                error = if (dropped.isNotEmpty()) "Auth header was redacted in storage — re-enter it." else null
                            },
                            onDelete = { historyStore.deleteSaved(row.id); savedTick++ }
                        )
                    }
                }
            }
        }
        if (confirmClearHistory) {
            AlertDialog(
                onDismissRequest = { confirmClearHistory = false },
                title = { Text("Clear history?") },
                text = { Text("This deletes all history entries. Saved requests are kept.") },
                confirmButton = {
                    TextButton(onClick = {
                        historyStore.clearHistory()
                        savedTick++
                        confirmClearHistory = false
                    }) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun HistoryRow(title: String, onLoad: () -> Unit, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            TextButton(onClick = onLoad) { Text("Load") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }
}
