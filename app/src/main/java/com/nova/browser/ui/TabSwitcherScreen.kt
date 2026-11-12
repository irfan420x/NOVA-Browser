package com.nova.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.data.Tab

/**
 * Tab switcher — a grid of tab cards. In normal mode a private section
 * shows whether a private session is open and lets the user switch to it
 * or close it. In private mode the switcher offers the way back to the
 * normal tabs instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherScreen(
    state: BrowserState,
    onOpenPrivateTab: () -> Unit,
    onClosePrivateTabs: () -> Unit,
    onSwitchToNormal: () -> Unit
) {
    val tabs = state.tabs
    val currentId = state.currentTabId
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.privateMode) "Private tabs" else "Tabs (${tabs.size})") },
                navigationIcon = {
                    IconButton(onClick = { state.screen = BrowserScreen.BROWSER }) {
                        Icon(Icons.Filled.Close, "Close tab switcher")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { state.newTab("") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("New tab")
                }
                OutlinedButton(
                    onClick = {
                        if (state.privateMode) state.newTab("", incognito = true)
                        else onOpenPrivateTab()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Lock, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Private tab")
                }
            }

            Spacer(Modifier.height(16.dp))

            if (tabs.isEmpty()) {
                NebulaEmptyState(Icons.Filled.Public, "No tabs open.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        TabCard(
                            tab = tab,
                            selected = tab.id == currentId,
                            onClick = { state.selectTab(tab) },
                            onClose = { state.closeTab(tab) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (state.privateMode) {
                OutlinedButton(
                    onClick = onSwitchToNormal,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Switch to normal tabs") }
            } else if (state.privateSessionActive) {
                Card(
                    Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Private tabs are open", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Private tabs run in a separate session — their history, cookies and site data are wiped when they close.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenPrivateTab, modifier = Modifier.weight(1f)) {
                                Text("Switch")
                            }
                            OutlinedButton(onClick = onClosePrivateTabs, modifier = Modifier.weight(1f)) {
                                Text("Close all")
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Private tabs open in a separate session and leave nothing behind when they close.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TabCard(tab: Tab, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(128.dp)
            .clip(NovaCardShape)
            .clickable(onClick = onClick),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tab.incognito) {
                    Icon(
                        Icons.Filled.Lock, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Private",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "Close tab", Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                tab.title.ifBlank { if (tab.url.isBlank()) "New tab" else tab.url },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (tab.url.isNotBlank()) {
                Text(
                    tab.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
