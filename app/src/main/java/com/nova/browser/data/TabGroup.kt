package com.nova.browser.data

import java.util.Locale

/**
 * W1 — Tab groups, recently-closed tabs, tab search and the background-tab
 * suspension policy. PURE KOTLIN — no Android imports, so this file is
 * compiled by the JVM unit tests (tests/run-tests.sh) as well as by the
 * app build. Anything that touches Context / WebView lives in TabStore.kt.
 *
 * Persistence: TabStore encodes these models with [TabStoreCodec] (a
 * line-based format with backslash escaping) into SharedPreferences.
 * Group membership is stored as member URLs, not tab ids — tab ids are
 * session-local (BrowserState restarts nextTabId at 1), while a restored
 * tab keeps its saved URL, so URL matching re-links groups after an app
 * kill. Within a session, membership is by tab id ([Tab.groupId]).
 */

/** The 6 group colours, all NOVA Nebula tokens (ARGB longs). */
val NEBULA_GROUP_COLORS: List<Long> = listOf(
    0xFF7C3AEDL, // gradient start violet
    0xFF8B5CF6L, // primary violet
    0xFF3B82F6L, // gradient end blue
    0xFF22D3EEL, // secondary cyan
    0xFFEC4899L, // tertiary pink (sparing)
    0xFF34D399L  // success green
)

/** One tab group. [tabIds] is the in-session membership (tab ids);
 *  [memberUrls] is only filled by [TabStoreCodec] round-trips so TabStore
 *  can re-link tabs after an app kill. [collapsed] is UI state. */
data class TabGroup(
    val id: Long,
    val name: String,
    val colorArgb: Long = NEBULA_GROUP_COLORS[0],
    val tabIds: List<Long> = emptyList(),
    val collapsed: Boolean = false,
    val memberUrls: List<String> = emptyList()
)

/** One recently-closed tab. Incognito tabs are never stored (see
 *  [RecentlyClosedStack.push] and [TabStore.pushClosed]). */
data class ClosedTab(
    val url: String,
    val title: String,
    val groupId: Long? = null,
    val closedAtMs: Long = 0L,
    val incognito: Boolean = false
)

/** Pure tab-group operations. Every function returns a new list and never
 *  mutates its input. A tab belongs to at most one group: adding it to a
 *  group removes it from any other group first. */
object TabGroups {

    fun nextId(groups: List<TabGroup>): Long =
        (groups.maxOfOrNull { it.id } ?: 0L) + 1L

    fun colorForIndex(index: Int): Long =
        NEBULA_GROUP_COLORS[((index % NEBULA_GROUP_COLORS.size) + NEBULA_GROUP_COLORS.size) % NEBULA_GROUP_COLORS.size]

    /** First colour no existing group uses (P-2: colourForIndex(groups.size)
     *  collides with a surviving group after deletions — e.g. groups #0/#1
     *  exist, #0 is deleted, the next group would reuse #1's violet).
     *  Falls back to colourForIndex when all six are in use. */
    fun nextColor(groups: List<TabGroup>): Long {
        val used = groups.map { it.colorArgb }.toSet()
        return NEBULA_GROUP_COLORS.firstOrNull { it !in used }
            ?: colorForIndex(groups.size)
    }

    /** Create a group. Blank names fall back to "Group N". Returns the new
     *  list plus the created group. */
    fun create(
        groups: List<TabGroup>,
        name: String,
        colorArgb: Long = nextColor(groups)
    ): Pair<List<TabGroup>, TabGroup> {
        val id = nextId(groups)
        val clean = name.trim().ifBlank { "Group ${groups.size + 1}" }
        val group = TabGroup(id = id, name = clean, colorArgb = colorArgb)
        return groups + group to group
    }

    /** Rename; a blank new name leaves the group unchanged. */
    fun rename(groups: List<TabGroup>, groupId: Long, newName: String): List<TabGroup> {
        val clean = newName.trim()
        if (clean.isBlank()) return groups
        return groups.map { if (it.id == groupId) it.copy(name = clean) else it }
    }

    /** Delete a group. Tabs are NOT deleted — the caller must clear their
     *  [Tab.groupId] (see [ungroupedTabIds]). */
    fun delete(groups: List<TabGroup>, groupId: Long): List<TabGroup> =
        groups.filter { it.id != groupId }

    /** Tab ids that belonged to [groupId] (so the caller can ungroup them). */
    fun ungroupedTabIds(groups: List<TabGroup>, groupId: Long): List<Long> =
        groups.firstOrNull { it.id == groupId }?.tabIds ?: emptyList()

    fun setCollapsed(groups: List<TabGroup>, groupId: Long, collapsed: Boolean): List<TabGroup> =
        groups.map { if (it.id == groupId) it.copy(collapsed = collapsed) else it }

    /** Add [tabId] to [groupId] (removing it from any other group first).
     *  Unknown group ids leave the list unchanged. */
    fun addTab(groups: List<TabGroup>, groupId: Long, tabId: Long): List<TabGroup> {
        if (groups.none { it.id == groupId }) return groups
        return groups.map { group ->
            when {
                group.id == groupId ->
                    if (group.tabIds.contains(tabId)) group
                    else group.copy(tabIds = group.tabIds + tabId)
                group.tabIds.contains(tabId) ->
                    group.copy(tabIds = group.tabIds - tabId)
                else -> group
            }
        }
    }

    /** Remove [tabId] from whichever group holds it (ungroup). */
    fun removeTab(groups: List<TabGroup>, tabId: Long): List<TabGroup> =
        groups.map { group ->
            if (group.tabIds.contains(tabId)) group.copy(tabIds = group.tabIds - tabId)
            else group
        }

    /** Move [tabId] to [targetGroupId], or ungroup it when null. */
    fun moveTab(groups: List<TabGroup>, tabId: Long, targetGroupId: Long?): List<TabGroup> =
        if (targetGroupId == null) removeTab(groups, tabId)
        else addTab(groups, targetGroupId, tabId)

    fun groupForTab(groups: List<TabGroup>, tabId: Long): TabGroup? =
        groups.firstOrNull { it.tabIds.contains(tabId) }

    fun tabsInGroup(group: TabGroup, allTabIds: Collection<Long>): List<Long> =
        group.tabIds.filter { allTabIds.contains(it) }

    /** Groups with no live tabs left (stale after tabs were closed). The
     *  caller decides whether to prune them; empty groups are kept by
     *  default so a group survives its last tab being closed. */
    fun emptyGroups(groups: List<TabGroup>, allTabIds: Collection<Long>): List<TabGroup> =
        groups.filter { tabsInGroup(it, allTabIds).isEmpty() }
}

/** Case-insensitive substring search over a tab's title and URL. */
object TabSearch {
    fun matchesTab(query: String, title: String, url: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return true
        return title.lowercase(Locale.ROOT).contains(q) || url.lowercase(Locale.ROOT).contains(q)
    }
}

/** Background-tab suspension policy (pure decision, no WebView touch).
 *  The actual destroy/recreate is [TabSuspension] in TabStore.kt, which
 *  must run on the main thread. [trimLevel] uses the same integer values
 *  as android.content.ComponentCallbacks2 so the MainActivity hook can
 *  pass its level straight through; -1 means "no trim event" (a periodic
 *  idle check). */
object TabSuspensionPolicy {

    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val TRIM_MEMORY_RUNNING_MODERATE = 5
    const val TRIM_MEMORY_RUNNING_LOW = 10
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    const val TRIM_MEMORY_BACKGROUND = 40
    const val TRIM_MEMORY_MODERATE = 60
    const val TRIM_MEMORY_COMPLETE = 80

    /** A background tab idle longer than this may be suspended even
     *  without any memory pressure. */
    const val IDLE_SUSPEND_MS: Long = 10L * 60L * 1000L

    /** Minimal tab facts the policy needs — no Android types, so the JVM
     *  tests can drive it. */
    data class SuspendableTab(
        val id: Long,
        val incognito: Boolean,
        val lastUsedMs: Long,
        val discarded: Boolean,
        val hasWebView: Boolean,
        /** W1-1: page reached by POST — restore is a plain GET, so the
         *  POST data would be lost. Never suspend. */
        val wasPost: Boolean = false,
        /** W1-1: a download/upload is running for this tab. Never
         *  suspend mid-transfer. */
        val hasActiveTransfer: Boolean = false
    )

    fun shouldSuspend(
        tab: SuspendableTab,
        currentTabId: Long?,
        nowMs: Long,
        trimLevel: Int,
        suspendEnabled: Boolean
    ): Boolean {
        if (!suspendEnabled) return false
        if (tab.id == currentTabId) return false
        if (tab.incognito) return false
        if (tab.discarded) return false
        if (!tab.hasWebView) return false
        // W1-1: a POST-result page restores as a plain GET — the form /
        // checkout data would be lost (or double-submitted). A tab
        // mid-transfer must not have its WebView destroyed under it.
        if (tab.wasPost) return false
        if (tab.hasActiveTransfer) return false
        val idleMs = nowMs - tab.lastUsedMs
        if (idleMs >= IDLE_SUSPEND_MS) return true
        // Memory pressure suspends even a recently-used background tab.
        // NOTE: trim levels are NOT one ordered scale — UI_HIDDEN (20)
        // is a different category from the RUNNING_* levels (5/10/15),
        // so a plain `>= RUNNING_LOW` would wrongly treat UI_HIDDEN as
        // pressure. Pressure means RUNNING_LOW / RUNNING_CRITICAL, or
        // any background level (BACKGROUND 40 / MODERATE 60 / COMPLETE 80).
        if (trimLevel == TRIM_MEMORY_RUNNING_LOW ||
            trimLevel == TRIM_MEMORY_RUNNING_CRITICAL ||
            trimLevel >= TRIM_MEMORY_BACKGROUND
        ) return true
        return false
    }

    fun tabsToSuspend(
        tabs: List<SuspendableTab>,
        currentTabId: Long?,
        nowMs: Long,
        trimLevel: Int,
        suspendEnabled: Boolean
    ): List<SuspendableTab> =
        tabs.filter { shouldSuspend(it, currentTabId, nowMs, trimLevel, suspendEnabled) }
}

/** The recently-closed stack: newest first, capped at [MAX_CLOSED].
 *  Incognito and blank-URL entries are refused — a closed private tab
 *  must leave nothing behind, and a blank tab has nothing to restore. */
object RecentlyClosedStack {
    const val MAX_CLOSED = 10

    fun push(stack: List<ClosedTab>, entry: ClosedTab): List<ClosedTab> {
        if (entry.incognito) return stack
        if (entry.url.isBlank()) return stack
        // URL-only dedupe (deliberate): re-closing the same tab after a
        // restore — same URL, possibly a new title — replaces the stale
        // entry instead of listing the URL twice. Known limit (W5 P-5):
        // two DIFFERENT tabs on the same URL also collapse to one entry;
        // telling them apart would need a tab identity that does not
        // survive an app kill, so the newest close wins.
        val withoutDup = stack.filter { it.url != entry.url }
        return (listOf(entry) + withoutDup).take(MAX_CLOSED)
    }

    /** Remove one entry (restoring it). Out-of-range index: no change. */
    fun popAt(stack: List<ClosedTab>, index: Int): List<ClosedTab> {
        if (index < 0 || index >= stack.size) return stack
        return stack.filterIndexed { i, _ -> i != index }
    }

    /** Restore-all order: oldest first, so the tab order after restoring
     *  matches the order the tabs were closed in (reversed stack). */
    fun restoreAllOrder(stack: List<ClosedTab>): List<ClosedTab> = stack.reversed()

    fun clear(): List<ClosedTab> = emptyList()
}

/** Line-based encode/decode for [TabGroup] lists and [ClosedTab] lists.
 *  Field separator is U+0001, member-URL sub-separator U+0002, one record
 *  per line. Backslash escapes: \\ -> backslash, \1 -> U+0001,
 *  \2 -> U+0002, \n -> newline, \r -> carriage return. Malformed lines
 *  are skipped on decode (never crash on a corrupt prefs value). */
object TabStoreCodec {

    fun escapeField(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\u0001' -> sb.append("\\1")
                '\u0002' -> sb.append("\\2")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun unescapeField(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '1' -> sb.append('\u0001')
                    '2' -> sb.append('\u0002')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Split on [sep], honouring backslash escapes (an escaped separator
     *  stays inside the field). */
    fun splitEscaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
            } else if (c == sep) {
                out.add(cur.toString())
                cur.clear()
                i++
            } else {
                cur.append(c)
                i++
            }
        }
        out.add(cur.toString())
        return out
    }

    fun encodeGroups(groups: List<TabGroup>): String {
        val sb = StringBuilder()
        for (g in groups) {
            val members = g.memberUrls.joinToString("\u0002") { escapeField(it) }
            sb.append(g.id).append('\u0001')
                .append(escapeField(g.name)).append('\u0001')
                .append(g.colorArgb).append('\u0001')
                .append(if (g.collapsed) "1" else "0").append('\u0001')
                .append(members).append('\n')
        }
        return sb.toString()
    }

    fun decodeGroups(encoded: String): List<TabGroup> {
        if (encoded.isBlank()) return emptyList()
        val out = ArrayList<TabGroup>()
        for (line in encoded.split('\n')) {
            if (line.isEmpty()) continue
            val f = splitEscaped(line, '\u0001')
            if (f.size < 4) continue
            val id = f[0].toLongOrNull() ?: continue
            val color = f[2].toLongOrNull() ?: continue
            val members = if (f.size > 4 && f[4].isNotEmpty()) {
                splitEscaped(f[4], '\u0002').map { unescapeField(it) }.filter { it.isNotBlank() }
            } else emptyList()
            out.add(
                TabGroup(
                    id = id,
                    name = unescapeField(f[1]),
                    colorArgb = color,
                    collapsed = f[3] == "1",
                    memberUrls = members
                )
            )
        }
        return out
    }

    fun encodeClosed(stack: List<ClosedTab>): String {
        val sb = StringBuilder()
        for (c in stack) {
            sb.append(escapeField(c.url)).append('\u0001')
                .append(escapeField(c.title)).append('\u0001')
                .append(c.groupId?.toString() ?: "").append('\u0001')
                .append(c.closedAtMs).append('\u0001')
                .append(if (c.incognito) "1" else "0").append('\n')
        }
        return sb.toString()
    }

    fun decodeClosed(encoded: String): List<ClosedTab> {
        if (encoded.isBlank()) return emptyList()
        val out = ArrayList<ClosedTab>()
        for (line in encoded.split('\n')) {
            if (line.isEmpty()) continue
            val f = splitEscaped(line, '\u0001')
            if (f.size < 5) continue
            val closedAt = f[3].toLongOrNull() ?: continue
            val url = unescapeField(f[0])
            if (url.isBlank()) continue
            out.add(
                ClosedTab(
                    url = url,
                    title = unescapeField(f[1]),
                    groupId = f[2].toLongOrNull(),
                    closedAtMs = closedAt,
                    incognito = f[4] == "1"
                )
            )
        }
        return out
    }
}
