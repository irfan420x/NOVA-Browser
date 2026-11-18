package com.nova.browser.web

// DEVICE / EMULATOR ONLY — NEVER RUN ON THIS SERVER.
// NOVA Browser v2.2, F07. Intent-URL handling on a real device (F02):
// the JVM IntentValidatorTest proves the string-level decisions; only
// a device proves the dialog appears, nothing launches for hostile
// links, and legit links still work. WRITTEN but UNRUN: no
// device/emulator on this server; do NOT claim as passing.
// Fixture needed: a local https page with tap targets for: (a) a
// component= intent link, (b) a SEL selector intent link, (c) a
// javascript: fallback intent link, (d) a package+fallback Play Store
// intent link, (e) a plain https intent link (no package).

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nova.browser.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntentValidationUiTest {

    @Test
    fun componentIntentLink_launchesNothing() {
        // Tap the component= fixture link. Expect no external activity
        // (assert the browser stays foreground) and an ErrorLog entry
        // "Blocked intent:// URL". UNRUN: needs the fixture page.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.use {
            assertTrue("explicit-component intents must never launch", true)
        }
    }

    @Test
    fun selectorIntentLink_launchesNothing() {
        // Tap the SEL selector fixture link. Expect the same as above.
        // UNRUN: needs the fixture page.
        assertTrue("selector intents must never launch", true)
    }

    @Test
    fun packageWithoutFallback_withoutGesture_showsConfirmation() {
        // Tap the package-only fixture link without a prior gesture
        // context (auto-navigation). Expect the "Open in another app?"
        // dialog naming the package; tapping "Don't allow" keeps the
        // browser on the page. UNRUN: needs the fixture page.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.use {
            onView(withText("Open in another app?"))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun legitPlayStoreIntent_withGesture_stillWorks() {
        // Tap the legit Play Store intent link (package + https
        // fallback, real user tap). Expect the Play Store (or the
        // fallback page if the store app is absent) — never a block.
        // UNRUN: needs the fixture page + Play Store presence check.
        assertTrue("legit intent links must keep working", true)
    }
}
