package com.nova.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nova.browser.data.Bookmark
import com.nova.browser.data.HistoryEntry
import com.nova.browser.web.UrlUtils

/**
 * Home content — shown inside the browser screen when the current tab has
 * no page loaded. Big search, pinned shortcuts, recent sites, quick actions.
 * In private mode a banner explains that nothing is saved.
 */
@Composable
fun HomeScreen(state: BrowserState) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var shortcuts by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var recent by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }

    // DB reads off the main thread — the bookmark/history queries ran on
    // the UI thread on every home refresh (and on every tab switch).
    LaunchedEffect(refreshKey) {
        shortcuts = withContext(Dispatchers.IO) {
            state.db.listBookmarks().filter { it.pinned }
        }
        recent = if (state.privateMode) emptyList() else withContext(Dispatchers.IO) {
            state.db.topSites(8)
        }
    }
    // Refresh when returning to home (a bookmark may have been added).
    LaunchedEffect(state.currentTab?.id) { refreshKey++ }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column {
                Text(
                    "NOVA Browser",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.privateMode) "Private browsing — nothing is saved"
                    else "Fast, private, ad-free browsing",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.privateMode) {
            item {
                GlassCard(
                    Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Shield, null, Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Private tabs run in a separate session. History, cookies and site data are wiped when you close them.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search with ${state.settings.searchEngine.label} or enter an address") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (query.isNotBlank()) {
                        state.loadInput(query)
                        query = ""
                    }
                })
            )
        }

        if (shortcuts.isNotEmpty()) {
            item {
                Text("Shortcuts", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.chunked(4).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { bookmark ->
                                ShortcutChip(
                                    bookmark = bookmark,
                                    modifier = Modifier.weight(1f),
                                    onClick = { state.loadUrl(state.currentTab ?: state.newTab(), bookmark.url) }
                                )
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent sites", fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { state.screen = BrowserScreen.BOOKMARKS }) {
                        Text("History")
                    }
                }
            }
            items(recent, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { state.loadUrl(state.currentTab ?: state.newTab(), entry.url) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).background(NebulaGradientTintBrush, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Language, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title.ifBlank { entry.url },
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            entry.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HomeAction(Icons.Filled.Bookmark, "Bookmarks") { state.screen = BrowserScreen.BOOKMARKS }
                HomeAction(Icons.Filled.History, "History") {
                    state.bookmarksTab = 1
                    state.screen = BrowserScreen.BOOKMARKS
                }
                HomeAction(Icons.Filled.Download, "Downloads") { state.screen = BrowserScreen.DOWNLOADS }
                HomeAction(Icons.Filled.Settings, "Settings") { state.screen = BrowserScreen.SETTINGS }
            }
        }
    }
}

@Composable
private fun ShortcutChip(bookmark: Bookmark, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(52.dp).background(NebulaGradientBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                // First code POINT, not first Char: a leading emoji is a
                // surrogate pair, and firstOrNull() returned its lone
                // high surrogate (rendered as "?").
                bookmark.title.takeIf { it.isNotEmpty() }?.let {
                    String(Character.toChars(it.codePointAt(0))).uppercase()
                } ?: "?",
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun HomeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
