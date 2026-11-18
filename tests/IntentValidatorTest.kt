package com.nova.browser.web

/**
 * JVM unit tests for IntentValidator (F02) — plain main() + check(),
 * no framework. Run with tests/run-tests.sh. Exits non-zero on any
 * failure.
 *
 * Every check names the rule it pins: a malicious intent:// URL must
 * be rejected, a package-without-fallback or gesture-less navigation
 * must ask first, and legit intent:// links (package + https fallback,
 * plain https data) must still launch.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

private fun isAllow(d: IntentValidator.Decision) = d is IntentValidator.Decision.Allow
private fun isReject(d: IntentValidator.Decision) = d is IntentValidator.Decision.Reject
private fun isConfirm(d: IntentValidator.Decision) = d is IntentValidator.Decision.NeedsConfirmation

private fun allowOf(d: IntentValidator.Decision): IntentValidator.ParsedIntent? =
    (d as? IntentValidator.Decision.Allow)?.intent

private fun confirmOf(d: IntentValidator.Decision): IntentValidator.Decision.NeedsConfirmation? =
    d as? IntentValidator.Decision.NeedsConfirmation

private const val VIEW = "android.intent.action.VIEW"

fun main() {
    // --- 1. valid intent with https fallback (allow, gesture) ---
    val validFallback = IntentValidator.validate(
        "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
            "package=com.example.app;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        hasGesture = true
    )
    check("valid intent with https fallback allows", isAllow(validFallback))
    check("valid intent keeps the package", allowOf(validFallback)?.packageName == "com.example.app")
    check("valid intent decodes the https fallback", allowOf(validFallback)?.fallbackUrl == "https://example.com/fallback")
    check("valid intent keeps the https data URI", allowOf(validFallback)?.dataUri == "https://example.com/page")
    check("valid intent action is VIEW", allowOf(validFallback)?.action == VIEW)

    // --- 1b. valid intent, http fallback (allow) ---
    check(
        "valid intent with http fallback allows",
        isAllow(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=http%3A%2F%2Fexample.com%2Ffallback;end",
                hasGesture = true
            )
        )
    )

    // --- 1c. valid intent, plain https data, no package (allow) ---
    val plainHttps = IntentValidator.validate(
        "intent://example.com/page#Intent;action=$VIEW;scheme=https;end",
        hasGesture = true
    )
    check("plain https intent allows", isAllow(plainHttps))
    check("plain https intent has no package", allowOf(plainHttps)?.packageName == null)
    check("plain https intent has no fallback", allowOf(plainHttps)?.fallbackUrl == null)

    // --- 1d. valid intent with BROWSABLE category (allow) ---
    check(
        "intent with BROWSABLE category allows",
        isAllow(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;" +
                    "category=android.intent.category.BROWSABLE;scheme=https;end",
                hasGesture = true
            )
        )
    )

    // --- 1e. fallback-only intent, no data, no package (allow — loads fallback) ---
    val fallbackOnly = IntentValidator.validate(
        "intent:#Intent;action=$VIEW;scheme=https;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        hasGesture = true
    )
    check("fallback-only intent allows", isAllow(fallbackOnly))
    check("fallback-only intent has null data", allowOf(fallbackOnly)?.dataUri == null)
    check("fallback-only intent keeps the fallback", allowOf(fallbackOnly)?.fallbackUrl == "https://example.com/fallback")

    // --- 2. explicit component (reject) ---
    check(
        "intent with explicit component rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "component=com.evil/.MainActivity;end",
                hasGesture = true
            )
        )
    )
    check(
        "component rejects even with a gesture and a valid fallback",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;component=com.example/.Main;" +
                    "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
                hasGesture = true
            )
        )
    )

    // --- 3. selector (reject) ---
    check(
        "intent with SEL selector rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;package=com.evil.app;" +
                    "SEL;action=$VIEW;scheme=https;end",
                hasGesture = true
            )
        )
    )

    // --- 4. javascript: fallback (reject) ---
    check(
        "intent with javascript: fallback rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=javascript%3Aalert%281%29;end",
                hasGesture = true
            )
        )
    )

    // --- 5. file: fallback (reject) ---
    check(
        "intent with file: fallback rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=file%3A%2F%2F%2Fetc%2Fpasswd;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with content: fallback rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=content%3A%2F%2Fcom.example%2Fsecret;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with nested intent: fallback rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=intent%3A%2F%2Fevil.com%23Intent%3Baction%3Dandroid.intent.action.VIEW%3Bend;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with fallback that has no host rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=https%3A%2F%2F;end",
                hasGesture = true
            )
        )
    )

    // --- 6. package + no fallback (needs confirmation, even with gesture) ---
    val pkgNoFallback = IntentValidator.validate(
        "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
            "package=com.example.app;end",
        hasGesture = true
    )
    check("package without fallback needs confirmation", isConfirm(pkgNoFallback))
    check("confirmation names the package", confirmOf(pkgNoFallback)?.packageName == "com.example.app")
    check("confirmation has no fallback", confirmOf(pkgNoFallback)?.fallbackUrl == null)

    // --- 7. non-VIEW action (reject) ---
    check(
        "intent with SEND action rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=android.intent.action.SEND;scheme=https;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with MAIN action rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=android.intent.action.MAIN;scheme=https;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with no action rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;scheme=https;end",
                hasGesture = true
            )
        )
    )

    // --- 8. extra payloads (reject) ---
    check(
        "intent with an unexpected String extra rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "S.evil_extra=payload;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with an unexpected int extra rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "i.some_int=123;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with a boolean extra rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "B.flag=true;end",
                hasGesture = true
            )
        )
    )
    check(
        "extra rejects even when everything else is valid",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;" +
                    "S.referrer=evil;end",
                hasGesture = true
            )
        )
    )

    // --- 9. malformed intent URL (reject, no crash) ---
    check("empty string rejects", isReject(IntentValidator.validate("", hasGesture = true)))
    check("non-intent URL rejects", isReject(IntentValidator.validate("https://example.com", hasGesture = true)))
    check("intent without #Intent marker rejects", isReject(IntentValidator.validate("intent://example.com/page", hasGesture = true)))
    check("intent without ;end rejects", isReject(IntentValidator.validate("intent://example.com/page#Intent;action=$VIEW;scheme=https", hasGesture = true)))
    check("bare intent:// rejects", isReject(IntentValidator.validate("intent://", hasGesture = true)))
    check("intent with nothing to launch rejects", isReject(IntentValidator.validate("intent:#Intent;action=$VIEW;end", hasGesture = true)))
    check("intent with duplicate action rejects", isReject(IntentValidator.validate("intent://example.com#Intent;action=$VIEW;action=$VIEW;scheme=https;end", hasGesture = true)))
    check("intent with unknown field rejects", isReject(IntentValidator.validate("intent://example.com#Intent;action=$VIEW;scheme=https;foo=bar;end", hasGesture = true)))
    check("intent with bad percent-encoding in fallback rejects", isReject(IntentValidator.validate("intent://example.com#Intent;action=$VIEW;scheme=https;S.browser_fallback_url=https%3A%2F%2Fexa%ZZample.com;end", hasGesture = true)))

    // --- 10. intent without gesture (needs confirmation) ---
    val noGesture = IntentValidator.validate(
        "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
            "package=com.example.app;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        hasGesture = false
    )
    check("valid intent without gesture needs confirmation", isConfirm(noGesture))
    check("no-gesture confirmation names the package", confirmOf(noGesture)?.packageName == "com.example.app")
    check("no-gesture confirmation keeps the fallback", confirmOf(noGesture)?.fallbackUrl == "https://example.com/fallback")
    check(
        "plain https intent without gesture needs confirmation",
        isConfirm(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;end",
                hasGesture = false
            )
        )
    )
    check(
        "no-gesture confirmation with no package names null package",
        confirmOf(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;end",
                hasGesture = false
            )
        )?.packageName == null
    )

    // --- extra: non-http(s) data schemes (reject) ---
    check(
        "intent with tel: data scheme rejects",
        isReject(
            IntentValidator.validate(
                "intent://123456#Intent;action=$VIEW;scheme=tel;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with javascript: data scheme rejects",
        isReject(
            IntentValidator.validate(
                "intent://alert(1)#Intent;action=$VIEW;scheme=javascript;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with custom data scheme rejects",
        isReject(
            IntentValidator.validate(
                "intent://scan/#Intent;action=$VIEW;scheme=zxing;package=com.google.zxing.client.android;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with data but no scheme rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;end",
                hasGesture = true
            )
        )
    )
    check(
        "intent with malformed package name rejects",
        isReject(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;package=not-a-package;end",
                hasGesture = true
            )
        )
    )

    // --- extra: package + valid fallback + gesture allows; same without gesture asks ---
    check(
        "package with valid fallback and gesture allows",
        isAllow(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
                hasGesture = true
            )
        )
    )
    check(
        "package with valid fallback but no gesture needs confirmation",
        isConfirm(
            IntentValidator.validate(
                "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
                    "package=com.example.app;" +
                    "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
                hasGesture = false
            )
        )
    )
    // --- extra: fallback '+' is preserved (URLDecoder would turn it into a space) ---
    val plusFallback = IntentValidator.validate(
        "intent://example.com/page#Intent;action=$VIEW;scheme=https;" +
            "package=com.example.app;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fsearch%3Fq%3Da%2Bb;end",
        hasGesture = true
    )
    check("fallback '+' is preserved, not decoded to a space", allowOf(plusFallback)?.fallbackUrl == "https://example.com/search?q=a+b")

    // --- AUDIT 2 (F02-A): launchAction — what the hook does after Allow ---
    // A package-only intent (package + fallback, NO data) must TRY THE
    // APP first: the old hook loaded the fallback outright and the app
    // never launched, even after the user tapped Allow.
    val pkgOnly = IntentValidator.validate(
        "intent:#Intent;action=$VIEW;scheme=https;" +
            "package=com.example.app;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        hasGesture = true
    )
    check("package-only intent (no data) allows", isAllow(pkgOnly))
    check(
        "package-only intent launches the app, it does NOT load the fallback directly",
        IntentValidator.launchAction(
            allowOf(pkgOnly)?.dataUri, allowOf(pkgOnly)?.fallbackUrl, allowOf(pkgOnly)?.packageName
        ) == IntentValidator.LaunchAction.LaunchApp
    )
    // The same shape after a no-gesture confirmation: onAllow must also
    // launch the app, not load the fallback.
    val pkgOnlyConfirm = IntentValidator.validate(
        "intent:#Intent;action=$VIEW;scheme=https;" +
            "package=com.example.app;" +
            "S.browser_fallback_url=https%3A%2F%2Fexample.com%2Ffallback;end",
        hasGesture = false
    )
    val pkgOnlyDecision = pkgOnlyConfirm as? IntentValidator.Decision.NeedsConfirmation
    check("package-only intent without gesture needs confirmation", pkgOnlyDecision != null)
    check(
        "confirmed package-only intent launches the app",
        pkgOnlyDecision != null && IntentValidator.launchAction(
            pkgOnlyDecision.dataUri, pkgOnlyDecision.fallbackUrl, pkgOnlyDecision.packageName
        ) == IntentValidator.LaunchAction.LaunchApp
    )
    // Fallback-only (no data, no package) still loads the fallback.
    check(
        "fallback-only intent loads the fallback directly",
        IntentValidator.launchAction(null, "https://example.com/fallback", null) ==
            IntentValidator.LaunchAction.LoadFallback("https://example.com/fallback")
    )
    // Data + fallback: try the app, fall back only if it is missing.
    check(
        "data + fallback intent launches the app",
        IntentValidator.launchAction("https://example.com/page", "https://example.com/fallback", "com.example.app") ==
            IntentValidator.LaunchAction.LaunchApp
    )
    // Package without fallback: try the app (no fallback to load).
    check(
        "package without fallback launches the app",
        IntentValidator.launchAction("https://example.com/page", null, "com.example.app") ==
            IntentValidator.LaunchAction.LaunchApp
    )
    // Data only: try the app.
    check(
        "data-only intent launches the app",
        IntentValidator.launchAction("https://example.com/page", null, null) ==
            IntentValidator.LaunchAction.LaunchApp
    )

    println("IntentValidatorTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
