package com.nova.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.data.NEBULA_GROUP_COLORS
import com.nova.browser.data.RecentlyClosedStack
import com.nova.browser.data.Tab
import com.nova.browser.data.TabGroup
import com.nova.browser.data.TabGroups
import com.nova.browser.data.TabSearch
import com.nova.browser.data.TabStore
import com.nova.browser.data.TabSuspension

/**
 * Tab switcher — a grid of tab cards, with tab search, tab groups and a
 * recently-closed section (W1).
 *
 * Groups and recently-closed tabs are owned HERE (TabStore, a dedicated
 * SharedPreferences file) because BrowserState is shared code owned by
 * the coordinator — the switcher records a close before calling
 * state.closeTab, and restores/duplicates through state.newTab, so no
 * BrowserState change is needed for the switcher flows. The hooks the
 * coordinator still needs (onTrimMemory, Settings toggle, recording
 * closes made outside the switcher) are in the W1 report.
 *
 * In normal mode a private section shows whether a private session is
 * open and lets the user switch to it or close it. In private mode the
 * switcher offers the way back to the normal tabs instead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TabSwitcherScreen(
    state: BrowserState,
    onOpenPrivateTab: () -> Unit,
    onClosePrivateTabs: () -> Unit,
    onSwitchToNormal: () -> Unit,
    onSwitchToPrivate: () -> Unit = { }
) {
    val tabs = state.tabs
    val currentId = state.currentTabId
    val context = LocalContext.current
    // H-3: the store is private-aware — in the :incognito process every
    // persistence call is a no-op and loads return empty, so a private
    // tab's URL can never land in the shared prefs file.
    val tabStore = remember { TabStore(context, state.privateMode) }
    // Groups re-link to the live tabs by URL on load (tab ids are
    // session-local); see TabStore.loadGroups. Deliberately loaded
    // synchronously here, not in a LaunchedEffect: the load is one tiny
    // SharedPreferences read, and every group mutation goes through
    // persistGroups — an async load would race a fast "New group" /
    // close-tab tap and could overwrite the stored groups with a list
    // derived from the still-empty state (W5 L-2 weighed and rejected
    // for this reason; tabs restore synchronously in BrowserState.init,
    // before any composition, so the stale-tabs half of L-2 does not
    // apply).
    var groups by remember { mutableStateOf(tabStore.loadGroups(tabs)) }
    var closedTabs by remember { mutableStateOf(tabStore.loadClosed()) }
    // P-6: search text + dialog inputs survive rotation. (The dialog
    // targets themselves — menuTab/moveTab/group objects — are not
    // saveable, so those dialogs close on rotation; no crash, no stale tab.)
    var query by rememberSaveable { mutableStateOf("") }
    var menuTab by remember { mutableStateOf<Tab?>(null) }
    var moveTab by remember { mutableStateOf<Tab?>(null) }
    var showGroupDialog by rememberSaveable { mutableStateOf(false) }
    var groupDialogTab by remember { mutableStateOf<Tab?>(null) }
    var groupDialogName by rememberSaveable { mutableStateOf("") }
    var groupDialogColor by rememberSaveable { mutableStateOf(NEBULA_GROUP_COLORS[0]) }
    var renameGroup by remember { mutableStateOf<TabGroup?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteGroup by remember { mutableStateOf<TabGroup?>(null) }

    fun persistGroups(next: List<TabGroup>) {
        groups = next
        tabStore.saveGroups(next, tabs)
    }

    fun selectTab(tab: Tab) {
        val group = groups.firstOrNull { it.id == tab.groupId }
        if (group != null && group.collapsed) {
            persistGroups(TabGroups.setCollapsed(groups, group.id, false))
        }
        TabSuspension.markTabUsed(tab)
        state.selectTab(tab)
    }

    fun closeTab(tab: Tab) {
        if (!tab.incognito && tab.url.isNotBlank()) {
            tabStore.pushClosed(tab.toClosedTab())
            closedTabs = tabStore.loadClosed()
        }
        if (tab.groupId != null) {
            persistGroups(TabGroups.removeTab(groups, tab.id))
        }
        state.closeTab(tab)
    }

    /** BrowserState caps tabs at 12; newTab silently returns the current
     *  tab when full, so guard before duplicating/restoring. */
    fun canOpenAnotherTab(): Boolean {
        if (tabs.size >= 12) {
            state.status("Too many tabs open — close one first")
            return false
        }
        return true
    }

    fun duplicateTab(tab: Tab) {
        if (!canOpenAnotherTab()) return
        val copy = state.newTab(tab.url, title = tab.title)
        // B-6: the copy inherits the source's desktop mode and form-result
        // marker (a POST-result copy restores as a GET, but until its first
        // navigation completes it must not be suspended); group membership
        // only when the group still exists (M-2 — an orphan groupId made
        // the copy invisible in the switcher).
        copy.desktopMode = tab.desktopMode
        copy.wasPost = tab.wasPost
        if (tab.groupId != null && groups.any { it.id == tab.groupId }) {
            copy.groupId = tab.groupId
            persistGroups(TabGroups.addTab(groups, tab.groupId!!, copy.id))
        }
        copy.touch()
        state.status("Tab duplicated")
    }

    fun restoreClosed(index: Int) {
        if (!canOpenAnotherTab()) return
        val entry = closedTabs.getOrNull(index) ?: return
        val tab = state.newTab(entry.url, title = entry.title)
        val groupId = entry.groupId
        if (groupId != null && groups.any { it.id == groupId }) {
            tab.groupId = groupId
            persistGroups(TabGroups.addTab(groups, groupId, tab.id))
        }
        tabStore.removeClosedAt(index)
        closedTabs = tabStore.loadClosed()
    }

    fun restoreAllClosed() {
        if (closedTabs.isEmpty()) return
        if (tabs.size + closedTabs.size > 12) {
            state.status("Not enough room — close some tabs first")
            return
        }
        // Oldest first, so the restored order matches the close order.
        // B-2: add each tab to its group via TabGroups.addTab (the old
        // code set tab.groupId directly, leaving group.tabIds stale).
        var updatedGroups = groups
        RecentlyClosedStack.restoreAllOrder(closedTabs).forEach { entry ->
            val tab = state.newTab(entry.url, title = entry.title, select = false)
            val groupId = entry.groupId
            if (groupId != null && updatedGroups.any { it.id == groupId }) {
                tab.groupId = groupId
                updatedGroups = TabGroups.addTab(updatedGroups, groupId, tab.id)
            }
        }
        // Re-derive group membership + member URLs from the restored tabs.
        persistGroups(updatedGroups)
        tabStore.clearClosed()
        closedTabs = emptyList()
        state.status("Tabs restored")
    }

    fun createGroup(name: String, colorArgb: Long, forTab: Tab?) {
        val (next, group) = TabGroups.create(groups, name, colorArgb)
        var updated = next
        if (forTab != null) {
            forTab.groupId = group.id
            updated = TabGroups.addTab(next, group.id, forTab.id)
        }
        persistGroups(updated)
    }

    fun groupColor(groupId: Long?): Long? =
        groups.firstOrNull { it.id == groupId }?.colorArgb

    val searching = query.isNotBlank()
    val visibleTabs = if (searching) {
        tabs.filter { TabSearch.matchesTab(query, it.title, it.url) }
    } else {
        tabs
    }
    val ungroupedTabs = visibleTabs.filter { it.groupId == null }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassTopAppBar(
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

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search tabs by title or address") },
                leadingIcon = { Icon(Icons.Filled.Search, "Search tabs") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(12.dp))

            if (!searching && !state.privateMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tab groups",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        groupDialogTab = null
                        groupDialogName = ""
                        groupDialogColor = TabGroups.nextColor(groups)
                        showGroupDialog = true
                    }) { Text("New group") }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (tabs.isEmpty()) {
                NebulaEmptyState(Icons.Filled.Public, "No tabs open.")
            } else if (searching && visibleTabs.isEmpty()) {
                NebulaEmptyState(Icons.Filled.Search, "No tabs match \"$query\".")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (searching) {
                        items(visibleTabs, key = { it.id }) { tab ->
                            TabCard(
                                tab = tab,
                                selected = tab.id == currentId,
                                groupColor = groupColor(tab.groupId),
                                onClick = { selectTab(tab) },
                                onClose = { closeTab(tab) },
                                onDuplicate = { duplicateTab(tab) },
                                onLongClick = { menuTab = tab }
                            )
                        }
                    } else {
                        for (group in groups) {
                            val groupTabs = tabs.filter { it.groupId == group.id }
                            item(span = { GridItemSpan(2) }) {
                                GroupHeader(
                                    group = group,
                                    count = groupTabs.size,
                                    onToggleCollapse = {
                                        persistGroups(
                                            TabGroups.setCollapsed(groups, group.id, !group.collapsed)
                                        )
                                    },
                                    onRename = {
                                        renameGroup = group
                                        renameText = group.name
                                    },
                                    onDelete = { deleteGroup = group }
                                )
                            }
                            if (!group.collapsed) {
                                items(groupTabs, key = { it.id }) { tab ->
                                    TabCard(
                                        tab = tab,
                                        selected = tab.id == currentId,
                                        groupColor = group.colorArgb,
                                        onClick = { selectTab(tab) },
                                        onClose = { closeTab(tab) },
                                        onDuplicate = { duplicateTab(tab) },
                                        onLongClick = { menuTab = tab }
                                    )
                                }
                            }
                        }
                        if (groups.isNotEmpty() && ungroupedTabs.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Text(
                                    "Other tabs (${ungroupedTabs.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        items(ungroupedTabs, key = { it.id }) { tab ->
                            TabCard(
                                tab = tab,
                                selected = tab.id == currentId,
                                groupColor = null,
                                onClick = { selectTab(tab) },
                                onClose = { closeTab(tab) },
                                onDuplicate = { duplicateTab(tab) },
                                onLongClick = { menuTab = tab }
                            )
                        }
                    }
                }
            }

            // W1-1 + M-4 — the GET-only restore limit, stated in the UI:
            // a suspended tab reloads as a fresh visit, and a restored
            // closed form-result tab reloads as a fresh page too (its POST
            // data cannot be restored). Tabs showing a form/checkout
            // result, or with a download running, are never suspended —
            // but uploads started inside a page cannot be detected, so a
            // tab mid-upload may still be suspended.
            Text(
                "Suspended tabs reload as a fresh visit — form results and tabs with a download running are never suspended. Uploads started inside a page can't be detected, and restoring a closed tab always reloads it as a fresh page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // ---- Recently closed (normal mode only — a private tab must
            // leave nothing behind, and TabStore refuses incognito
            // entries, so this list can never hold one) ----
            if (!state.privateMode && closedTabs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.History, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Recently closed",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { restoreAllClosed() }) { Text("Restore all") }
                            TextButton(onClick = {
                                tabStore.clearClosed()
                                closedTabs = emptyList()
                            }) { Text("Clear") }
                        }
                        Column(
                            Modifier.heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            closedTabs.forEachIndexed { index, entry ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            entry.title.ifBlank { entry.url },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            entry.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { restoreClosed(index) }) {
                                        Text("Restore")
                                    }
                                }
                            }
                        }
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
                GlassCard(
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
                            // "Switch" goes to the tabs that are already
                            // open — it must NOT create a new blank tab.
                            Button(onClick = onSwitchToPrivate, modifier = Modifier.weight(1f)) {
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

        // ---- Long-press tab menu ----
        val tabForMenu = menuTab
        if (tabForMenu != null) {
            AlertDialog(
                onDismissRequest = { menuTab = null },
                title = {
                    Text(
                        tabForMenu.title.ifBlank { tabForMenu.url.ifBlank { "New tab" } },
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column {
                        TextButton(
                            onClick = { menuTab = null; duplicateTab(tabForMenu) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Duplicate tab", modifier = Modifier.fillMaxWidth()) }
                        TextButton(
                            onClick = { menuTab = null; moveTab = tabForMenu },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.privateMode
                        ) { Text("Move to group…", modifier = Modifier.fillMaxWidth()) }
                        TextButton(
                            onClick = {
                                menuTab = null
                                groupDialogTab = tabForMenu
                                groupDialogName = ""
                                groupDialogColor = TabGroups.nextColor(groups)
                                showGroupDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.privateMode
                        ) { Text("New group…", modifier = Modifier.fillMaxWidth()) }
                        TextButton(
                            onClick = { menuTab = null; closeTab(tabForMenu) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Close tab", modifier = Modifier.fillMaxWidth()) }
                    }
                },
                confirmButton = { },
                dismissButton = {
                    TextButton(onClick = { menuTab = null }) { Text("Cancel") }
                }
            )
        }

        // ---- Move to group ----
        val tabToMove = moveTab
        if (tabToMove != null) {
            AlertDialog(
                onDismissRequest = { moveTab = null },
                title = { Text("Move to group") },
                text = {
                    Column {
                        if (groups.isEmpty()) {
                            Text(
                                "No groups yet — create one first.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        groups.forEach { group ->
                            TextButton(
                                onClick = {
                                    moveTab = null
                                    tabToMove.groupId = group.id
                                    persistGroups(TabGroups.addTab(groups, group.id, tabToMove.id))
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(12.dp)
                                            .background(Color(group.colorArgb), CircleShape)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(group.name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        if (tabToMove.groupId != null) {
                            TextButton(
                                onClick = {
                                    moveTab = null
                                    val oldGroup = tabToMove.groupId
                                    tabToMove.groupId = null
                                    if (oldGroup != null) {
                                        persistGroups(TabGroups.removeTab(groups, tabToMove.id))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Remove from group", modifier = Modifier.fillMaxWidth()) }
                        }
                        TextButton(
                            onClick = {
                                moveTab = null
                                groupDialogTab = tabToMove
                                groupDialogName = ""
                                groupDialogColor = TabGroups.nextColor(groups)
                                showGroupDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("New group…", modifier = Modifier.fillMaxWidth()) }
                    }
                },
                confirmButton = { },
                dismissButton = {
                    TextButton(onClick = { moveTab = null }) { Text("Cancel") }
                }
            )
        }

        // ---- Create group (optionally for one tab) ----
        if (showGroupDialog) {
            AlertDialog(
                onDismissRequest = { showGroupDialog = false },
                title = { Text(if (groupDialogTab == null) "New tab group" else "New group for this tab") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = groupDialogName,
                            onValueChange = { groupDialogName = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Group name") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Colour", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NEBULA_GROUP_COLORS.forEach { colorArgb ->
                                val selected = groupDialogColor == colorArgb
                                Box(
                                    Modifier.size(32.dp)
                                        .background(Color(colorArgb), CircleShape)
                                        .then(
                                            if (selected) Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.onSurface,
                                                CircleShape
                                            ) else Modifier
                                        )
                                        .clickable { groupDialogColor = colorArgb }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        createGroup(groupDialogName, groupDialogColor, groupDialogTab)
                        showGroupDialog = false
                        groupDialogTab = null
                    }) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showGroupDialog = false
                        groupDialogTab = null
                    }) { Text("Cancel") }
                }
            )
        }

        // ---- Rename group ----
        val groupToRename = renameGroup
        if (groupToRename != null) {
            AlertDialog(
                onDismissRequest = { renameGroup = null },
                title = { Text("Rename group") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (renameText.isNotBlank()) {
                            persistGroups(
                                TabGroups.rename(groups, groupToRename.id, renameText)
                            )
                        }
                        renameGroup = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { renameGroup = null }) { Text("Cancel") }
                }
            )
        }

        // ---- Delete group (tabs stay open, ungrouped) ----
        val groupToDelete = deleteGroup
        if (groupToDelete != null) {
            AlertDialog(
                onDismissRequest = { deleteGroup = null },
                title = { Text("Delete group?") },
                text = {
                    Text("“${groupToDelete.name}” will be removed. Its tabs stay open, ungrouped.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        tabs.filter { it.groupId == groupToDelete.id }
                            .forEach { it.groupId = null }
                        // D-3: closed entries holding this groupId must
                        // not restore into the next group that reuses it.
                        tabStore.clearClosedGroup(groupToDelete.id)
                        closedTabs = tabStore.loadClosed()
                        persistGroups(TabGroups.delete(groups, groupToDelete.id))
                        deleteGroup = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteGroup = null }) { Text("Cancel") }
                }
            )
        }
    }
}

/** Group section header: colour dot, name + count, rename/delete, collapse. */
@Composable
private fun GroupHeader(
    group: TabGroup,
    count: Int,
    onToggleCollapse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Box(
            Modifier.size(12.dp).background(Color(group.colorArgb), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Folder, null, Modifier.size(18.dp), tint = Color(group.colorArgb))
        Spacer(Modifier.width(8.dp))
        Text(
            "${group.name} ($count)",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, "Rename group", Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, "Delete group", Modifier.size(18.dp))
        }
        IconButton(onClick = onToggleCollapse, modifier = Modifier.size(32.dp)) {
            Icon(
                if (group.collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                if (group.collapsed) "Expand group" else "Collapse group",
                Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabCard(
    tab: Tab,
    selected: Boolean,
    groupColor: Long?,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onDuplicate: () -> Unit,
    onLongClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().height(128.dp)
            .clip(NovaCardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (groupColor != null) {
                    Box(
                        Modifier.size(10.dp)
                            .background(Color(groupColor), CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                }
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
                if (tab.discarded) {
                    Text(
                        "Suspended",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
                // W1-1: a form/checkout result page is never suspended
                // (its restore would be a plain GET and the POST data
                // would be lost) — show why this tab stays live.
                if (tab.wasPost) {
                    Text(
                        "Form result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDuplicate, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, "Duplicate tab", Modifier.size(16.dp))
                }
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
