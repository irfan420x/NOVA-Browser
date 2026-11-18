package com.nova.browser.data

/**
 * JVM unit tests for F06 — WipeCoordinator (data/ClearOnExit.kt).
 * Plain main() + check(), no framework. Run with tests/run-tests.sh
 * (coordinator adds this file + ClearOnExit.kt + BrowserDb.kt +
 * ErrorLog.kt to the compile list, with the Android SDK android.jar
 * on the kotlinc classpath so the Android imports resolve — the
 * tests below only ever call the pure WipeCoordinator with fake
 * steps, never any Android API). Exits non-zero on any failure.
 *
 * Covers: all steps succeed -> VERIFIED; cookie callback false ->
 * PARTIAL with the other steps still run; cookie callback never
 * fires -> no result is ever produced (no invented success);
 * a throwing step counts as "not cleared" and the rest still run;
 * steps run in order and flush runs only after the cookie callback.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

/** Fake steps: each records its call order; the cookie step can be
 *  told to report success, report failure, throw, or never call back. */
private class FakeSteps(
    var historyOk: Boolean = true,
    var historyThrows: Boolean = false,
    var cookieResult: Boolean? = true, // null = never calls back
    var cookieThrows: Boolean = false,
    var storageOk: Boolean = true,
    var storageThrows: Boolean = false,
    var cacheOk: Boolean = true
) {
    val order = mutableListOf<String>()
    var flushCalls = 0

    fun coordinator() = WipeCoordinator(
        clearHistory = {
            order.add("history")
            if (historyThrows) throw RuntimeException("db boom")
            historyOk
        },
        removeCookies = { callback ->
            order.add("removeCookies")
            if (cookieThrows) throw RuntimeException("cookie boom")
            cookieResult?.let { callback(it) }
        },
        flushCookies = {
            order.add("flush")
            flushCalls++
        },
        deleteStorage = {
            order.add("storage")
            if (storageThrows) throw RuntimeException("storage boom")
            storageOk
        },
        clearCache = {
            order.add("cache")
            cacheOk
        }
    )
}

fun main() {
    // --- 1. All steps succeed -> VERIFIED, everything true ---
    run {
        val steps = FakeSteps()
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("all-ok result delivered", result != null)
        check("all-ok cookiesCleared", result?.cookiesCleared == true)
        check("all-ok historyCleared", result?.historyCleared == true)
        check("all-ok storageCleared", result?.storageCleared == true)
        check("all-ok cacheCleared", result?.cacheCleared == true)
        check("all-ok verified", result?.verified == true)
        check("all-ok status VERIFIED", result?.status == WipeStatus.VERIFIED)
        check("steps run history -> cookies -> flush -> storage -> cache",
            steps.order == listOf("history", "removeCookies", "flush", "storage", "cache"))
        check("flush called once", steps.flushCalls == 1)
    }

    // --- 2. Cookie callback false -> PARTIAL, other steps still run ---
    run {
        val steps = FakeSteps(cookieResult = false)
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("cookie-false result delivered", result != null)
        check("cookie-false cookiesCleared false", result?.cookiesCleared == false)
        check("cookie-false history still cleared", result?.historyCleared == true)
        check("cookie-false storage still cleared", result?.storageCleared == true)
        check("cookie-false cache still cleared", result?.cacheCleared == true)
        check("cookie-false not verified", result?.verified == false)
        check("cookie-false status PARTIAL", result?.status == WipeStatus.PARTIAL)
        check("cookie-false flush still runs (after the callback)",
            steps.order.contains("flush") && steps.order.indexOf("flush") > steps.order.indexOf("removeCookies"))
    }

    // --- 3. Cookie callback NEVER fires -> NO result is produced ---
    run {
        val steps = FakeSteps(cookieResult = null)
        var delivered = false
        steps.coordinator().run { delivered = true }
        check("no-callback produces no result (no invented success)", !delivered)
        check("no-callback never reaches flush/storage/cache",
            steps.order == listOf("history", "removeCookies"))
        check("no-callback never flushes", steps.flushCalls == 0)
    }

    // --- 4. History throws -> history false, rest still run, PARTIAL ---
    run {
        val steps = FakeSteps(historyThrows = true)
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("history-throw historyCleared false", result?.historyCleared == false)
        check("history-throw cookies still cleared", result?.cookiesCleared == true)
        check("history-throw storage still cleared", result?.storageCleared == true)
        check("history-throw cache still cleared", result?.cacheCleared == true)
        check("history-throw status PARTIAL", result?.status == WipeStatus.PARTIAL)
    }

    // --- 5. removeCookies throws synchronously -> cookies false, rest run ---
    run {
        val steps = FakeSteps(cookieThrows = true)
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("cookie-throw result delivered", result != null)
        check("cookie-throw cookiesCleared false", result?.cookiesCleared == false)
        check("cookie-throw storage still cleared", result?.storageCleared == true)
        check("cookie-throw cache still cleared", result?.cacheCleared == true)
        check("cookie-throw status PARTIAL", result?.status == WipeStatus.PARTIAL)
        check("cookie-throw never flushes (no callback to flush after)",
            steps.flushCalls == 0)
    }

    // --- 6. Storage throws -> storage false, cache still runs ---
    run {
        val steps = FakeSteps(storageThrows = true)
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("storage-throw storageCleared false", result?.storageCleared == false)
        check("storage-throw cache still cleared", result?.cacheCleared == true)
        check("storage-throw status PARTIAL", result?.status == WipeStatus.PARTIAL)
    }

    // --- 7. Everything fails -> FAILED, verified false ---
    run {
        val steps = FakeSteps(
            historyOk = false, cookieResult = false,
            storageOk = false, cacheOk = false
        )
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("all-fail status FAILED", result?.status == WipeStatus.FAILED)
        check("all-fail verified false", result?.verified == false)
        check("all-fail every part false",
            result?.cookiesCleared == false && result?.historyCleared == false &&
                result?.storageCleared == false && result?.cacheCleared == false)
    }

    // --- 8. buildResult truth table (no coordinator needed) ---
    run {
        check("buildResult all true -> VERIFIED",
            WipeCoordinator.buildResult(true, true, true, true).let { it.verified && it.status == WipeStatus.VERIFIED })
        check("buildResult one false -> PARTIAL",
            WipeCoordinator.buildResult(true, true, true, false).let { !it.verified && it.status == WipeStatus.PARTIAL })
        check("buildResult only cookies -> PARTIAL",
            WipeCoordinator.buildResult(true, false, false, false).let { !it.verified && it.status == WipeStatus.PARTIAL })
        check("buildResult none -> FAILED",
            WipeCoordinator.buildResult(false, false, false, false).let { !it.verified && it.status == WipeStatus.FAILED })
    }

    // --- 9. WipeStatus semantics: UNVERIFIED is distinct from FAILED ---
    run {
        check("UNVERIFIED exists for the interrupted-wipe case",
            WipeStatus.valueOf("UNVERIFIED") == WipeStatus.UNVERIFIED)
        check("NOT_RUN exists for never-wiped",
            WipeStatus.valueOf("NOT_RUN") == WipeStatus.NOT_RUN)
        check("VERIFIED / PARTIAL / FAILED are distinct",
            WipeStatus.VERIFIED != WipeStatus.PARTIAL && WipeStatus.PARTIAL != WipeStatus.FAILED)
    }

    // --- 10. AUDIT 2 (F06-A): a failed cache clear is REPORTED, not assumed ---
    // The old wipe() forced cacheCleared = true whatever the WebView
    // did, so a wipe could claim VERIFIED with the cache untouched.
    // The coordinator must honour a false cache step: PARTIAL, and
    // cacheCleared false in the result.
    run {
        val steps = FakeSteps(cacheOk = false)
        var result: WipeResult? = null
        steps.coordinator().run { result = it }
        check("cache-false result delivered", result != null)
        check("cache-false cacheCleared false", result?.cacheCleared == false)
        check("cache-false other parts still cleared",
            result?.cookiesCleared == true && result?.historyCleared == true &&
                result?.storageCleared == true)
        check("cache-false not verified", result?.verified == false)
        check("cache-false status PARTIAL", result?.status == WipeStatus.PARTIAL)
    }

    println("ClearOnExitTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
