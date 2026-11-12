package com.nova.browser.ui

import android.app.DownloadManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.data.DownloadItem
import com.nova.browser.data.Downloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Downloads list. Live status comes from DownloadManager and is polled
 * once a second while anything is active. Files live in
 * Downloads/NOVA/Browser/ so NOVA Files shows them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(state: BrowserState) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() { refreshKey++ }

    LaunchedEffect(refreshKey) {
        items = Downloads.list(context, state.db)
    }
    // Poll while downloads are active so progress moves without a tap.
    LaunchedEffect(items.any { it.isActive }) {
        if (items.any { it.isActive }) {
            while (true) {
                delay(1000)
                items = Downloads.list(context, state.db)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = { state.screen = BrowserScreen.BROWSER }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (status != null) {
                Text(status!!, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NebulaEmptyState(
                        Icons.Filled.Download,
                        "No downloads yet.\nFiles you download go to Downloads / NOVA / Browser."
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.row.dmId }) { item ->
                        DownloadRow(
                            item = item,
                            onOpen = {
                                if (!Downloads.open(context, item)) {
                                    status = "Couldn't open ${item.row.fileName}"
                                }
                            },
                            onShare = {
                                if (!Downloads.share(context, item)) {
                                    status = "Couldn't share ${item.row.fileName}"
                                }
                            },
                            onCancel = {
                                scope.launch {
                                    Downloads.cancel(context, item.row.dmId)
                                    delay(300)
                                    refresh()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    if (Downloads.remove(context, state.db, item)) {
                                        status = "Deleted ${item.row.fileName}"
                                    } else {
                                        status = "Couldn't delete ${item.row.fileName}"
                                    }
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when {
        item.isDone -> "Complete"
        item.isFailed -> "Failed"
        item.status == DownloadManager.STATUS_PAUSED -> "Paused"
        item.status == DownloadManager.STATUS_PENDING -> "Waiting…"
        item.bytesTotal > 0 -> "Downloading… ${percent(item)}%"
        else -> "Downloading…"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.row.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusText + sizeText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.isDone) {
                    IconButton(onClick = onOpen) { Icon(Icons.Filled.OpenInNew, "Open") }
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
                }
                if (item.isActive) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
            if (item.isActive && item.bytesTotal > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { item.bytesDownloaded.toFloat() / item.bytesTotal.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun percent(item: DownloadItem): Int =
    if (item.bytesTotal > 0) {
        ((item.bytesDownloaded * 100) / item.bytesTotal).toInt().coerceIn(0, 100)
    } else 0

private fun sizeText(item: DownloadItem): String {
    if (item.bytesTotal <= 0) return ""
    val done = formatBytes(item.bytesDownloaded)
    val total = formatBytes(item.bytesTotal)
    return " · $done of $total"
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
