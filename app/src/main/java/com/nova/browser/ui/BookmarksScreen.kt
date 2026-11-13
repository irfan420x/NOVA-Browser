package com.nova.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.data.Bookmark
import com.nova.browser.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color

/**
 * Bookmarks and history in one screen (two tabs). Bookmarks can be pinned
 * to show as home shortcuts. History can be searched and cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(state: BrowserState) {
    var tab by remember { mutableStateOf(state.bookmarksTab) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() { refreshKey++ }

    LaunchedEffect(refreshKey, query) {
        val b = withContext(Dispatchers.IO) { state.db.listBookmarks() }
        val h = withContext(Dispatchers.IO) { state.db.searchHistory(query) }
        bookmarks = b
        history = h
    }
    // Keep the state's tab in sync so the menu can open History directly.
    LaunchedEffect(tab) { state.bookmarksTab = tab }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassTopAppBar(
                title = { Text(if (tab == 0) "Bookmarks" else "History") },
                navigationIcon = {
                    IconButton(onClick = { state.screen = BrowserScreen.BROWSER }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (tab == 1 && history.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Bookmarks") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
            }
            if (tab == 0) {
                if (bookmarks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NebulaEmptyState(Icons.Filled.Bookmark, "No bookmarks yet.\nTap the star in the address bar to save a page.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bookmarks, key = { it.id }) { bookmark ->
                            GlassCard(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            state.loadUrl(state.currentTab ?: state.newTab(), bookmark.url)
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            bookmark.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                state.db.setPinned(bookmark.url, !bookmark.pinned)
                                            }
                                            refresh()
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.PushPin, "Pin to home",
                                            tint = if (bookmark.pinned) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { state.db.removeBookmark(bookmark.url) }
                                            refresh()
                                        }
                                    }) {
                                        Icon(Icons.Filled.Delete, "Delete bookmark")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search history") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    if (history.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            NebulaEmptyState(Icons.Filled.History, "No history yet.")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(history, key = { it.id }) { entry ->
                                GlassCard(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                state.loadUrl(state.currentTab ?: state.newTab(), entry.url)
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                entry.title.ifBlank { entry.url },
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${entry.url} · ${formatTime(entry.visitedAt)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { state.db.deleteHistory(entry.id) }
                                                refresh()
                                            }
                                        }) {
                                            Icon(Icons.Filled.Delete, "Delete history entry")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("Every visited page will be removed from history. Bookmarks are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { state.db.clearHistory() }
                        refresh()
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatTime(time: Long): String {
    return try {
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(time))
    } catch (_: Exception) {
        ""
    }
}
