package com.nova.browser.web

/**
 * JVM unit tests for F03 — PermissionQueue (web/PermissionQueue.kt).
 * Plain main() + check(), no framework. Run with tests/run-tests.sh
 * (coordinator adds this file + PermissionQueue.kt to the compile
 * list). Exits non-zero on any failure.
 *
 * Covers: allowlisted resource granted after the Android permission,
 * non-allowlisted resource denied, unknown resource denied, two
 * concurrent requests decided independently, a denied request never
 * grants, and FIFO drain order.
 */

private var passed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) passed++ else failures.add(name)
}

fun main() {
    val camera = PermissionResources.VIDEO_CAPTURE
    val mic = PermissionResources.AUDIO_CAPTURE
    val protectedMedia = PermissionResources.PROTECTED_MEDIA_ID
    val camPerm = "android.permission.CAMERA"
    val micPerm = "android.permission.RECORD_AUDIO"

    // --- Allowlist / classification ---
    check("camera is allowlisted", PermissionResources.isAllowlisted(camera))
    check("mic is allowlisted", PermissionResources.isAllowlisted(mic))
    check("protected media id is NOT allowlisted", !PermissionResources.isAllowlisted(protectedMedia))
    check("unknown resource is NOT allowlisted", !PermissionResources.isAllowlisted("android.webkit.resource.FUTURE_THING"))
    check("empty string is NOT allowlisted", !PermissionResources.isAllowlisted(""))
    check("camera needs CAMERA", PermissionResources.androidPermission(camera) == camPerm)
    check("mic needs RECORD_AUDIO", PermissionResources.androidPermission(mic) == micPerm)
    check("protected media id has no Android permission (denied, not free)",
        PermissionResources.androidPermission(protectedMedia) == null)
    check("unknown resource has no Android permission",
        PermissionResources.androidPermission("android.webkit.resource.FUTURE_THING") == null)
    check("describe camera+mic", PermissionResources.describe(listOf(camera, mic)) == "Camera and Microphone")
    check("describe dedupes", PermissionResources.describe(listOf(camera, camera)) == "Camera")

    // --- 1. Allowlisted resource granted after Android permission ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://meet.example.com", listOf(camera))
        check("camera request waits on Android", e.decision == PermissionDecision.PENDING_ANDROID)
        check("camera request needs CAMERA", e.androidPermissionsNeeded == listOf(camPerm))
        check("camera request has no denied resources", e.deniedResources.isEmpty())
        check("no dialog before Android result", e.confirmationMessage() == null)
        check("head needing Android is the camera request", q.nextNeedingAndroid()?.id == e.id)
        val afterAndroid = q.applyAndroidResult(e.id, setOf(camPerm))
        check("Android grant moves to AWAITING_USER", afterAndroid?.decision == PermissionDecision.AWAITING_USER)
        check("awaiting-user resources are exactly the camera",
            afterAndroid?.awaitingUserResources == listOf(camera))
        val msg = afterAndroid?.confirmationMessage()
        check("dialog names the origin", msg != null && msg.contains("https://meet.example.com"))
        check("dialog names the resource", msg != null && msg.contains("Camera"))
        val granted = q.resolveUserDecision(e.id, allow = true)
        check("user confirm grants exactly the camera", granted == listOf(camera))
        check("entry is GRANTED", q.find(e.id)?.decision == PermissionDecision.GRANTED)
        check("granted request leaves the pending list", q.pending().isEmpty())
    }

    // --- 2. Non-allowlisted resource denied (never auto-granted) ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://video.example.com", listOf(protectedMedia))
        check("protected media id is DENIED at enqueue", e.decision == PermissionDecision.DENIED)
        check("protected media id is in deniedResources", e.deniedResources == listOf(protectedMedia))
        check("protected media id has no grantable resources", e.grantableResources.isEmpty())
        check("protected media id needs no Android flow", e.androidPermissionsNeeded.isEmpty())
        check("denied request is not pending", q.pending().isEmpty())
        check("denied request never reaches Android flow", q.nextNeedingAndroid() == null)
        check("user decision on denied request grants nothing",
            q.resolveUserDecision(e.id, allow = true).isEmpty())
        check("denied request stays DENIED after a bogus allow",
            q.find(e.id)?.decision == PermissionDecision.DENIED)
    }

    // --- 3. Unknown / future resource denied ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://future.example.com", listOf("android.webkit.resource.SOMETHING_NEW"))
        check("unknown resource is DENIED at enqueue", e.decision == PermissionDecision.DENIED)
        check("unknown resource grants nothing on user decision",
            q.resolveUserDecision(e.id, allow = true).isEmpty())
    }

    // --- 4. Mixed request: allowlisted part grantable, rest denied ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://mixed.example.com", listOf(camera, protectedMedia, "android.webkit.resource.X"))
        check("mixed request waits on Android (camera present)", e.decision == PermissionDecision.PENDING_ANDROID)
        check("mixed grantable is only the camera", e.grantableResources == listOf(camera))
        check("mixed denied holds the rest",
            e.deniedResources == listOf(protectedMedia, "android.webkit.resource.X"))
        q.applyAndroidResult(e.id, setOf(camPerm))
        val granted = q.resolveUserDecision(e.id, allow = true)
        check("mixed grant returns ONLY the camera (never the denied parts)",
            granted == listOf(camera))
    }

    // --- 5. Android permission denied -> request denied ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://call.example.com", listOf(camera, mic))
        check("camera+mic needs both Android perms",
            e.androidPermissionsNeeded == listOf(camPerm, micPerm))
        val after = q.applyAndroidResult(e.id, emptySet())
        check("Android denial denies the request", after?.decision == PermissionDecision.DENIED)
        check("Android denial grants nothing on user decision",
            q.resolveUserDecision(e.id, allow = true).isEmpty())
        check("Android-denied request stays DENIED", q.find(e.id)?.decision == PermissionDecision.DENIED)
    }

    // --- 6. Partial Android grant: only the approved resource survives ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://call.example.com", listOf(camera, mic))
        val after = q.applyAndroidResult(e.id, setOf(micPerm))
        check("partial Android grant still awaits the user", after?.decision == PermissionDecision.AWAITING_USER)
        check("partial grant keeps only the mic", after?.awaitingUserResources == listOf(mic))
        check("partial grant grants only the mic",
            q.resolveUserDecision(e.id, allow = true) == listOf(mic))
    }

    // --- 7. User declines after Android grant -> denied, never grants ---
    run {
        val q = PermissionQueue()
        val e = q.enqueue("https://call.example.com", listOf(mic))
        q.applyAndroidResult(e.id, setOf(micPerm))
        val granted = q.resolveUserDecision(e.id, allow = false)
        check("user decline grants nothing", granted.isEmpty())
        check("user-declined request is DENIED", q.find(e.id)?.decision == PermissionDecision.DENIED)
        check("user-declined request cannot be re-granted",
            q.resolveUserDecision(e.id, allow = true).isEmpty())
        check("user-declined request stays DENIED", q.find(e.id)?.decision == PermissionDecision.DENIED)
    }

    // --- 8. Two concurrent requests decided independently ---
    run {
        val q = PermissionQueue()
        val a = q.enqueue("https://a.example.com", listOf(camera))
        val b = q.enqueue("https://b.example.com", listOf(mic))
        check("two requests get distinct ids", a.id != b.id)
        check("both are pending", q.pending().size == 2)
        check("FIFO head is the first request", q.nextNeedingAndroid()?.id == a.id)
        // Decide the SECOND request first: the first must survive.
        val bAfter = q.applyAndroidResult(b.id, setOf(micPerm))
        check("second request reaches AWAITING_USER independently",
            bAfter?.decision == PermissionDecision.AWAITING_USER)
        check("first request still PENDING_ANDROID after second is decided",
            q.find(a.id)?.decision == PermissionDecision.PENDING_ANDROID)
        check("FIFO head is now the first request", q.nextNeedingAndroid()?.id == a.id)
        check("denying the second does not touch the first",
            q.resolveUserDecision(b.id, allow = false).isEmpty() &&
                q.find(a.id)?.decision == PermissionDecision.PENDING_ANDROID)
        check("first request still grants after second was denied",
            q.applyAndroidResult(a.id, setOf(camPerm))?.decision == PermissionDecision.AWAITING_USER &&
                q.resolveUserDecision(a.id, allow = true) == listOf(camera))
        check("both decided, none pending", q.pending().isEmpty())
    }

    // --- 9. Queue drains in FIFO order ---
    run {
        val q = PermissionQueue()
        val ids = (1..3).map { q.enqueue("https://site$it.example.com", listOf(camera)).id }
        val drained = mutableListOf<Long>()
        while (true) {
            val head = q.nextNeedingAndroid() ?: break
            drained.add(head.id)
            q.applyAndroidResult(head.id, setOf(camPerm))
            q.resolveUserDecision(head.id, allow = true)
        }
        check("queue drains oldest-first", drained == ids)
        check("queue empty after drain", q.pending().isEmpty())
        check("all three GRANTED", q.all().all { it.decision == PermissionDecision.GRANTED })
    }

    // --- 10. Edge cases: empty resources, unknown ids, deny/cancel, prune ---
    run {
        val q = PermissionQueue()
        val empty = q.enqueue("https://empty.example.com", emptyList())
        check("empty resource list is DENIED", empty.decision == PermissionDecision.DENIED)
        check("applyAndroidResult on unknown id returns null",
            q.applyAndroidResult(999L, setOf(camPerm)) == null)
        check("resolveUserDecision on unknown id grants nothing",
            q.resolveUserDecision(999L, allow = true).isEmpty())
        check("deny on unknown id returns false", !q.deny(999L))
        val e = q.enqueue("https://gone.example.com", listOf(camera))
        check("deny() denies a pending request", q.deny(e.id))
        check("denied-by-deny() is DENIED", q.find(e.id)?.decision == PermissionDecision.DENIED)
        check("deny() twice returns false (already decided)", !q.deny(e.id))
        check("denied request never grants after deny()",
            q.resolveUserDecision(e.id, allow = true).isEmpty())
        val pruned = q.pruneDecided()
        check("prune removes decided entries", pruned == 2 && q.all().isEmpty())
        val live = q.enqueue("https://live.example.com", listOf(mic))
        check("prune keeps undecided entries", q.pruneDecided() == 0 && q.all().size == 1)
        check("cancel path: deny() on live entry then prune clears it",
            q.deny(live.id) && q.pruneDecided() == 1)
    }

    println("PermissionQueueTest: $passed passed, ${failures.size} failed")
    if (failures.isNotEmpty()) {
        failures.forEach { println("  FAIL: $it") }
        kotlin.system.exitProcess(1)
    }
}
