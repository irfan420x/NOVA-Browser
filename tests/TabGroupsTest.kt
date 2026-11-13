package com.nova.browser.data

/**
 * JVM unit tests for W1 — tab groups, recently-closed stack, tab search,
 * suspension policy and the TabStore codec. Plain main() + check(), no
 * framework. Run with tests/run-tests.sh. Exits non-zero on any failure.
 *
 * NOTE: TabStore.kt itself is NOT compiled here (it needs android.content
 * / android.webkit); only the pure models in TabGroup.kt are tested.
 * The coordinator adds TabGroup.kt to the run-tests.sh compile list —
 * see the W1 report, hook HOOK-G.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    // --- Group colours: exactly 6 Nebula tokens, all distinct ---
    check("6 group colours", NEBULA_GROUP_COLORS.size == 6)
    check("group colours distinct", NEBULA_GROUP_COLORS.toSet().size == 6)
    check("colour index wraps", TabGroups.colorForIndex(6) == NEBULA_GROUP_COLORS[0])
    check("colour index 0 is violet", TabGroups.colorForIndex(0) == 0xFF7C3AEDL)

    // --- Group create / rename / delete ---
    val (g1, work) = TabGroups.create(emptyList(), "Work", 0xFF8B5CF6L)
    check("first group id is 1", work.id == 1L)
    check("group keeps name", work.name == "Work")
    check("group keeps colour", work.colorArgb == 0xFF8B5CF6L)
    check("group starts empty", work.tabIds.isEmpty())
    check("create returns list with group", g1.size == 1)
    val (g2, _) = TabGroups.create(g1, "  ", 0xFF22D3EEL)
    check("blank name falls back to Group N", g2[1].name == "Group 2")
    check("ids keep incrementing", g2[1].id == 2L)
    val renamed = TabGroups.rename(g2, 1L, "  Job  ")
    check("rename trims", renamed[0].name == "Job")
    check("rename of unknown id is a no-op", TabGroups.rename(g2, 99L, "X") == g2)
    check("blank rename is a no-op", TabGroups.rename(g2, 1L, "   ") == g2)
    check("delete removes group", TabGroups.delete(g2, 1L).size == 1)
    check("delete of unknown id is a no-op", TabGroups.delete(g2, 99L).size == 2)
    check("ungroupedTabIds lists members", TabGroups.ungroupedTabIds(g2, 1L).isEmpty())

    // --- Group membership: one group per tab, moves, ungroup ---
    var groups = TabGroups.create(emptyList(), "A").first
    groups = TabGroups.create(groups, "B").first
    groups = TabGroups.addTab(groups, 1L, 10L)
    groups = TabGroups.addTab(groups, 1L, 11L)
    check("addTab adds members", groups[0].tabIds == listOf(10L, 11L))
    groups = TabGroups.addTab(groups, 1L, 10L)
    check("addTab is idempotent", groups[0].tabIds == listOf(10L, 11L))
    groups = TabGroups.addTab(groups, 2L, 10L)
    check("moving a tab removes it from the old group", groups[0].tabIds == listOf(11L))
    check("moving a tab adds it to the new group", groups[1].tabIds == listOf(10L))
    check("groupForTab finds the group", TabGroups.groupForTab(groups, 10L)?.id == 2L)
    check("groupForTab is null for ungrouped", TabGroups.groupForTab(groups, 99L) == null)
    groups = TabGroups.moveTab(groups, 10L, null)
    check("moveTab to null ungroups", TabGroups.groupForTab(groups, 10L) == null)
    groups = TabGroups.moveTab(groups, 11L, 2L)
    check("moveTab moves between groups", TabGroups.groupForTab(groups, 11L)?.id == 2L)
    groups = TabGroups.removeTab(groups, 11L)
    check("removeTab ungroups", groups.all { !it.tabIds.contains(11L) })
    check("addTab to unknown group is a no-op", TabGroups.addTab(groups, 99L, 5L) == groups)
    check("tabsInGroup intersects live tabs", TabGroups.tabsInGroup(groups[1], listOf(10L)) .isEmpty())
    val withMember = TabGroups.addTab(groups, 2L, 7L)
    check("tabsInGroup keeps live members", TabGroups.tabsInGroup(withMember[1], listOf(7L, 8L)) == listOf(7L))
    check("emptyGroups finds groups with no live tabs", TabGroups.emptyGroups(withMember, listOf(8L)).size == 2)
    check("collapsed toggles", TabGroups.setCollapsed(groups, 1L, true)[0].collapsed)
    check("collapsed toggles back", !TabGroups.setCollapsed(groups, 1L, false)[0].collapsed)

    // --- Recently-closed stack ---
    var stack = emptyList<ClosedTab>()
    val now = 1_700_000_000_000L
    stack = RecentlyClosedStack.push(stack, ClosedTab("https://a.com", "A", null, now))
    check("push stores entry", stack.size == 1 && stack[0].url == "https://a.com")
    check("incognito push refused", RecentlyClosedStack.push(stack, ClosedTab("https://p.com", "P", null, now, incognito = true)) == stack)
    check("blank URL push refused", RecentlyClosedStack.push(stack, ClosedTab("", "Blank", null, now)) == stack)
    stack = RecentlyClosedStack.push(stack, ClosedTab("https://a.com", "A2", null, now + 1))
    check("re-closing same URL dedupes, newest first", stack.size == 1 && stack[0].title == "A2")
    for (i in 1..12) {
        stack = RecentlyClosedStack.push(stack, ClosedTab("https://s$i.com", "S$i", null, now + i))
    }
    check("stack caps at 10", stack.size == RecentlyClosedStack.MAX_CLOSED)
    check("cap drops the oldest", stack.none { it.url == "https://s1.com" } && stack.none { it.url == "https://s2.com" })
    check("newest is first", stack[0].url == "https://s12.com")
    check("restore-all order is oldest first", RecentlyClosedStack.restoreAllOrder(stack)[0].url == "https://s3.com")
    val popped = RecentlyClosedStack.popAt(stack, 0)
    check("popAt removes one", popped.size == 9 && popped[0].url == "https://s11.com")
    check("popAt out of range is a no-op", RecentlyClosedStack.popAt(stack, 99) == stack)
    check("popAt negative is a no-op", RecentlyClosedStack.popAt(stack, -1) == stack)
    check("clear empties", RecentlyClosedStack.clear().isEmpty())

    // --- Tab search ---
    check("blank query matches everything", TabSearch.matchesTab("", "Google", "https://google.com"))
    check("spaces-only query matches everything", TabSearch.matchesTab("   ", "Google", "https://google.com"))
    check("title substring matches", TabSearch.matchesTab("goo", "Google Search", "https://example.com"))
    check("URL substring matches", TabSearch.matchesTab("github", "Untitled", "https://github.com/nova"))
    check("match is case-insensitive (title)", TabSearch.matchesTab("WIKI", "wikipedia", "https://x.com"))
    check("match is case-insensitive (URL)", TabSearch.matchesTab("YOUTUBE", "", "https://YouTube.com/watch"))
    check("no match on unrelated text", !TabSearch.matchesTab("banana", "Google", "https://google.com"))
    check("partial word matches", TabSearch.matchesTab("pedia", "Wikipedia", ""))
    check("query with spaces matches title", TabSearch.matchesTab("stack over", "Stack Overflow", ""))

    // --- Suspension policy ---
    val policy = TabSuspensionPolicy
    fun tab(id: Long, incognito: Boolean = false, lastUsed: Long = now, discarded: Boolean = false, hasWebView: Boolean = true) =
        TabSuspensionPolicy.SuspendableTab(id, incognito, lastUsed, discarded, hasWebView)
    check("current tab never suspends", !policy.shouldSuspend(tab(1), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    check("incognito never suspends", !policy.shouldSuspend(tab(2, incognito = true), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    check("already-discarded never suspends", !policy.shouldSuspend(tab(2, discarded = true), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    check("no-WebView never suspends", !policy.shouldSuspend(tab(2, hasWebView = false), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    check("disabled toggle never suspends", !policy.shouldSuspend(tab(2, lastUsed = now - 60 * 60 * 1000), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, false))
    check("idle >10min suspends with no trim event", policy.shouldSuspend(tab(2, lastUsed = now - 10 * 60 * 1000 - 1), 1L, now, -1, true))
    check("idle exactly 10min suspends", policy.shouldSuspend(tab(2, lastUsed = now - TabSuspensionPolicy.IDLE_SUSPEND_MS), 1L, now, -1, true))
    check("idle just under 10min does not suspend alone", !policy.shouldSuspend(tab(2, lastUsed = now - TabSuspensionPolicy.IDLE_SUSPEND_MS + 1000), 1L, now, -1, true))
    check("fresh tab suspends under RUNNING_LOW", policy.shouldSuspend(tab(2), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_RUNNING_LOW, true))
    check("fresh tab suspends under BACKGROUND", policy.shouldSuspend(tab(2), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_BACKGROUND, true))
    check("fresh tab suspends under COMPLETE", policy.shouldSuspend(tab(2), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    check("fresh tab does NOT suspend on UI_HIDDEN alone", !policy.shouldSuspend(tab(2), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_UI_HIDDEN, true))
    check("fresh tab does NOT suspend on RUNNING_MODERATE", !policy.shouldSuspend(tab(2), 1L, now, TabSuspensionPolicy.TRIM_MEMORY_RUNNING_MODERATE, true))
    val mixed = listOf(tab(1), tab(2), tab(3, incognito = true), tab(4, lastUsed = now - 20 * 60 * 1000))
    val picked = policy.tabsToSuspend(mixed, 1L, now, TabSuspensionPolicy.TRIM_MEMORY_RUNNING_LOW, true)
    check("tabsToSuspend picks background + idle, skips current + incognito", picked.map { it.id } == listOf(2L, 4L))
    check("tabsToSuspend empty when disabled", policy.tabsToSuspend(mixed, 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, false).isEmpty())

    // --- W1-1: POST-result and in-transfer tabs are never suspended ---
    val postTab = TabSuspensionPolicy.SuspendableTab(5, false, now - 60 * 60 * 1000, false, true, wasPost = true)
    check("POST tab never suspends, even idle + memory pressure", !policy.shouldSuspend(postTab, 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    check("POST tab never suspends on idle alone", !policy.shouldSuspend(postTab, 1L, now, -1, true))
    val transferTab = TabSuspensionPolicy.SuspendableTab(6, false, now - 60 * 60 * 1000, false, true, hasActiveTransfer = true)
    check("in-transfer tab never suspends, even idle + memory pressure", !policy.shouldSuspend(transferTab, 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true))
    val getTab = TabSuspensionPolicy.SuspendableTab(7, false, now - 60 * 60 * 1000, false, true, wasPost = false, hasActiveTransfer = false)
    check("plain GET tab still suspends when idle", policy.shouldSuspend(getTab, 1L, now, -1, true))
    val mixedPost = listOf(tab(1), postTab, transferTab, getTab)
    val pickedPost = policy.tabsToSuspend(mixedPost, 1L, now, TabSuspensionPolicy.TRIM_MEMORY_COMPLETE, true)
    check("tabsToSuspend skips POST + transfer, keeps idle GET", pickedPost.map { it.id } == listOf(7L))

    // --- Codec round-trips ---
    val codecGroups = listOf(
        TabGroup(1, "Work | Stuff", 0xFF8B5CF6L, collapsed = true, memberUrls = listOf("https://a.com", "https://b.com/x?y=1&z=2")),
        TabGroup(2, "Name\nwith newline \\ backslash", 0xFF22D3EEL, memberUrls = listOf("https://ünicode.example/ü"))
    )
    val encoded = TabStoreCodec.encodeGroups(codecGroups)
    val decoded = TabStoreCodec.decodeGroups(encoded)
    check("groups round-trip count", decoded.size == 2)
    check("groups round-trip name with separators", decoded[0].name == "Work | Stuff")
    check("groups round-trip collapsed", decoded[0].collapsed)
    check("groups round-trip colour", decoded[0].colorArgb == 0xFF8B5CF6L)
    check("groups round-trip member URLs", decoded[0].memberUrls == listOf("https://a.com", "https://b.com/x?y=1&z=2"))
    check("groups round-trip newline/backslash name", decoded[1].name == "Name\nwith newline \\ backslash")
    check("groups round-trip unicode URL", decoded[1].memberUrls == listOf("https://ünicode.example/ü"))
    check("groups decode skips malformed lines", TabStoreCodec.decodeGroups("junk\n1\u0001Ok\u0001badcolor\u00010\u0001\n2\u0001Good\u000165580671\u00010\u0001").size == 1)
    check("groups decode of blank is empty", TabStoreCodec.decodeGroups("").isEmpty())
    check("groups decode of blank lines is empty", TabStoreCodec.decodeGroups("\n\n").isEmpty())

    val closed = listOf(
        ClosedTab("https://a.com", "A | Title", 3L, now),
        ClosedTab("https://b.com", "", null, now + 5)
    )
    val closedDecoded = TabStoreCodec.decodeClosed(TabStoreCodec.encodeClosed(closed))
    check("closed round-trip count", closedDecoded.size == 2)
    check("closed round-trip keeps group id", closedDecoded[0].groupId == 3L)
    check("closed round-trip keeps title", closedDecoded[0].title == "A | Title")
    check("closed round-trip keeps null group", closedDecoded[1].groupId == null)
    check("closed round-trip keeps timestamp", closedDecoded[1].closedAtMs == now + 5)
    check("closed decode skips blank-URL lines", TabStoreCodec.decodeClosed("\u0001T\u0001\u00010\u00010\nhttps://ok.com\u0001Ok\u0001\u00010\u00010\n").size == 1)
    check("closed decode of blank is empty", TabStoreCodec.decodeClosed("").isEmpty())
    check("escape round-trips control chars", TabStoreCodec.unescapeField(TabStoreCodec.escapeField("a\u0001b\u0002c\nd\\e")) == "a\u0001b\u0002c\nd\\e")

    println("TabGroupsTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
