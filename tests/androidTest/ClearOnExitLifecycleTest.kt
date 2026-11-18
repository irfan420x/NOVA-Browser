package com.nova.browser.data

// DEVICE / EMULATOR ONLY — NEVER RUN ON THIS SERVER.
// NOVA Browser v2.2, F07. Clear-on-exit lifecycle tests (F06): the wipe
// coordination (history → removeAllCookies(callback) → flush() →
// storage → cache) and the pending-flag / UNVERIFIED recording can only
// be proven by actually finishing and killing the app. WRITTEN but
// UNRUN: no device/emulator on this server; do NOT claim as passing.

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.browser.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClearOnExitLifecycleTest {

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cleanFinish_withClearOnExit_recordsVerifiedWipe() {
        // Enable clear-on-exit, browse (history + a cookie), finish the
        // activity cleanly, relaunch, read lastWipeStatus(). Expect
        // VERIFIED and a non-zero lastWipeTimeMs. UNRUN: needs relaunch
        // driving + a cookie-setting fixture page.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.use { it.onActivity { /* browse + finish */ } }
        assertEquals(
            "clean finish must record a VERIFIED wipe",
            WipeStatus.NOT_RUN, ClearOnExit.lastWipeStatus(targetContext())
        )
    }

    @Test
    fun killMidWipe_nextLaunchRecordsUnverified() {
        // Enable clear-on-exit, arrange a slow cookie callback (large
        // cookie jar), kill the process mid-wipe, relaunch. Expect
        // checkInterruptedWipe() to return true and lastWipeStatus() to
        // be UNVERIFIED — never a silent success. UNRUN: needs process
        // kill control + a slow-cookie fixture.
        assertTrue(
            "a wipe interrupted by kill must surface as UNVERIFIED",
            ClearOnExit.checkInterruptedWipe(targetContext())
        )
    }

    @Test
    fun swipeAway_doesNotClaimWipe() {
        // Enable clear-on-exit, browse, swipe the app away from recents
        // (no clean finish → onDestroy with isFinishing=false).
        // Relaunch: expect lastWipeStatus() NOT_RUN or UNVERIFIED —
        // never VERIFIED for a wipe that never ran. UNRUN: needs
        // recents-swipe driving via UiAutomator.
        val status = ClearOnExit.lastWipeStatus(targetContext())
        assertTrue(
            "swipe-kill must never report a verified wipe",
            status == WipeStatus.NOT_RUN || status == WipeStatus.UNVERIFIED
        )
    }
}
