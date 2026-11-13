package com.nova.browser.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nova.browser.data.DownloadItem
import com.nova.browser.data.DownloadState
import com.nova.browser.data.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color

/**
 * Downloads list. Live status comes from DownloadManager and is polled
 * once a second while anything is active. Files live in
 * Downloads/NOVA/Browser/ so NOVA Files shows them.
 *
 * W4: custom (pause/resume) downloads are listed above the
 * DownloadManager rows, from BrowserState.customDownloads (in-memory —
 * see CustomDownloadJob for the session-scope limit). They offer
 * Pause/Resume/Cancel while running and Open/Share/Delete when done.
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

    // The list query (DB + DownloadManager) runs off the main thread —
    // it ran on the UI thread on every poll tick (once a second while
    // anything was active). In private mode only the in-memory private
    // downloads are shown, never the shared table.
    LaunchedEffect(refreshKey) {
        items = withContext(Dispatchers.IO) {
            Downloads.list(
                context, state.db,
                extraRows = if (state.privateMode) state.privateDownloads else emptyList(),
                includeDbRows = !state.privateMode
            )
        }
    }
    // Poll while downloads are active so progress moves without a tap.
    // W4: a running custom job keeps the poll alive too — its progress
    // is read live from volatile fields during each recomposition, and
    // the items reassignment below is what retriggers it (the job's
    // `done` flag is not Compose state). H3: the poll uses the same
    // definition as the suspension gate — a PAUSED job must not keep
    // the loop alive forever.
    val anyActive = items.any { it.isActive } || state.hasActiveCustomDownload
    LaunchedEffect(anyActive) {
        if (anyActive) {
            while (true) {
                delay(1000)
                items = withContext(Dispatchers.IO) {
                    Downloads.list(
                        context, state.db,
                        extraRows = if (state.privateMode) state.privateDownloads else emptyList(),
                        includeDbRows = !state.privateMode
                    )
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassTopAppBar(
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
            // W4: custom (pause/resume) downloads, in-progress and done.
            val customJobs = state.customDownloads
            if (customJobs.isNotEmpty()) {
                Text(
                    "Custom downloads",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(customJobs, key = { it.id }) { job ->
                        CustomDownloadRow(
                            job = job,
                            onPause = { job.download.pause() },
                            onResume = { job.download.resume() },
                            onCancel = { job.download.cancel() },
                            onOpen = {
                                if (!openCustomJob(context, job)) {
                                    status = "Couldn't open ${job.fileName}"
                                }
                            },
                            onShare = {
                                if (!shareCustomJob(context, job)) {
                                    status = "Couldn't share ${job.fileName}"
                                }
                            },
                            onDelete = {
                                deleteCustomJob(context, job)
                                state.customDownloads.remove(job)
                                status = "Deleted ${job.fileName}"
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (items.isEmpty() && customJobs.isEmpty()) {
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
                                    Downloads.cancel(context, state.db, item.row.dmId)
                                    if (state.privateMode) {
                                        state.privateDownloads.removeAll { it.dmId == item.row.dmId }
                                    }
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
                                    if (state.privateMode) {
                                        state.privateDownloads.removeAll { it.dmId == item.row.dmId }
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
    GlassCard(Modifier.fillMaxWidth()) {
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

/** One custom (pause/resume) download (W4). Progress comes from the
 *  job's latest onProgress snapshot; the screen's 1-second poll
 *  recomposes while the job is active. */
@Composable
private fun CustomDownloadRow(
    job: CustomDownloadJob,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val downloadState = job.download.state
    val progress = job.latestProgress
    val isActive = downloadState == DownloadState.DOWNLOADING ||
        downloadState == DownloadState.QUEUED ||
        downloadState == DownloadState.PAUSED
    val statusText = when (downloadState) {
        DownloadState.COMPLETED -> "Complete" + if (job.privateMode) " (private)" else ""
        DownloadState.FAILED -> "Failed"
        DownloadState.CANCELLED -> "Cancelled"
        DownloadState.PAUSED -> "Paused"
        DownloadState.QUEUED -> "Waiting…"
        DownloadState.DOWNLOADING -> {
            val pct = progress?.percent ?: -1
            if (pct >= 0) "Downloading… $pct%" else "Downloading…"
        }
    }
    val sizeText = if (progress != null && progress.totalBytes > 0) {
        " · ${formatBytes(progress.downloadedBytes)} of ${formatBytes(progress.totalBytes)}"
    } else if (progress != null && progress.downloadedBytes > 0) {
        " · ${formatBytes(progress.downloadedBytes)}"
    } else {
        ""
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusText + sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadState == DownloadState.COMPLETED) {
                    IconButton(onClick = onOpen) { Icon(Icons.Filled.OpenInNew, "Open") }
                    IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
                }
                if (downloadState == DownloadState.DOWNLOADING) {
                    IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, "Pause") }
                }
                if (downloadState == DownloadState.PAUSED) {
                    IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, "Resume") }
                }
                if (isActive) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                if (downloadState == DownloadState.COMPLETED) {
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
            if (downloadState == DownloadState.DOWNLOADING && progress != null && progress.percent >= 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Open a finished custom download. Normal mode opens the published
 *  MediaStore Uri; private mode opens the private file through the
 *  FileProvider (cache-path covers cacheDir/Downloads). */
private fun openCustomJob(context: Context, job: CustomDownloadJob): Boolean {
    return try {
        val uri: Uri = job.publishedUri ?: run {
            val file = job.download.file
            if (!file.exists()) return false
            FileProvider.getUriForFile(context, "com.nova.browser.provider", file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            // CustomDownload keeps its mimeType private; "*/*" lets the
            // system pick a viewer by extension/content.
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

/** Share a finished custom download (same Uri rules as [openCustomJob]). */
private fun shareCustomJob(context: Context, job: CustomDownloadJob): Boolean {
    return try {
        val uri: Uri = job.publishedUri ?: run {
            val file = job.download.file
            if (!file.exists()) return false
            FileProvider.getUriForFile(context, "com.nova.browser.provider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share ${job.fileName}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}

/** Delete a finished custom download: the published MediaStore row
 *  (normal mode) and the private copy. A failed publish never leaves
 *  a job in the list, so there is no half-published state to clean. */
private fun deleteCustomJob(context: Context, job: CustomDownloadJob) {
    try {
        job.publishedUri?.let { context.contentResolver.delete(it, null, null) }
    } catch (_: Exception) { }
    try {
        val file = job.download.file
        if (file.exists()) file.delete()
    } catch (_: Exception) { }
}

