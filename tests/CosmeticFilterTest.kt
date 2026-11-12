package com.nova.browser.web

/**
 * JVM unit tests for CosmeticFilter — plain main() + check(), no framework.
 * Run with tests/run-tests.sh. Exits non-zero on any failure.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    // --- Empty input ---
    check("empty list returns null", CosmeticFilter.buildHideScript(emptyList()) == null)

    // --- One selector ---
    val one = CosmeticFilter.buildHideScript(listOf(".ad-banner"))
    check("single selector is hidden", one != null && one.contains(".ad-banner{display:none!important}"))
    check("script creates a style element", one != null && one.contains("createElement('style')"))
    check("script is guarded against double injection", one != null && one.contains("if(document.getElementById('nova-cosmetic'))return;"))
    check("script tags the style element", one != null && one.contains("nova-cosmetic"))

    // --- Several selectors are joined with commas ---
    val many = CosmeticFilter.buildHideScript(listOf(".a", "#b", "div.ad"))
    check(
        "selectors are comma-joined",
        many != null && many.contains(".a{display:none!important},#b{display:none!important},div.ad{display:none!important}")
    )

    // --- Escaping: a quote inside a selector must not break the JS string ---
    val quoted = CosmeticFilter.buildHideScript(listOf("div[data-x='a']"))
    check(
        "single quotes are escaped",
        quoted != null && quoted.contains("div[data-x=\\'a\\']{display:none!important}")
    )

    // --- Escaping: a backslash inside a selector must survive ---
    val backslashed = CosmeticFilter.buildHideScript(listOf(".a\\b"))
    check(
        "backslashes are escaped",
        backslashed != null && backslashed.contains(".a\\\\b{display:none!important}")
    )

    println("CosmeticFilterTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
