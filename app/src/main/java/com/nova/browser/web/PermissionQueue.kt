package com.nova.browser.web

/**
 * F03 — WebView permission queue (pure Kotlin, NO Android imports).
 *
 * Replaces the old single-`pendingPermissionRequest` variable in
 * BrowserHost, which had two defects:
 *  1. it auto-granted any resource that needs no Android runtime
 *     permission (`request.grant(request.resources)` when the Android
 *     list was empty, and `else -> null` in handlePermissionResult
 *     also granted) — so PROTECTED_MEDIA_ID and any future/unknown
 *     resource were silently granted;
 *  2. one variable held one request, so a second concurrent request
 *     overwrote (silently dropped) the first.
 *
 * Model:
 * - Every incoming WebView permission request becomes one
 *   [QueuedPermissionRequest] in a FIFO queue, tracked independently
 *   by [QueuedPermissionRequest.id]. Nothing is ever overwritten or
 *   silently dropped: a request stays queued until it is decided
 *   (granted / denied) or explicitly cancelled.
 * - ALLOWLIST: only [PermissionResources.VIDEO_CAPTURE] (camera) and
 *   [PermissionResources.AUDIO_CAPTURE] (microphone) may ever be
 *   granted, and only after BOTH (a) the matching Android runtime
 *   permission is granted AND (b) the user confirms in the dialog
 *   that shows the origin. EVERYTHING else — PROTECTED_MEDIA_ID,
 *   geolocation-style resources, and any future/unknown resource
 *   string — is DENIED BY DEFAULT. There is no code path that grants
 *   a non-allowlisted resource.
 * - The Android runtime-permission flow can only carry one request
 *   at a time (one requestCode), so the host processes the queue
 *   head-first: [nextNeedingAndroid] returns the oldest undecided
 *   entry that still needs an Android permission; the rest wait in
 *   the queue. [applyAndroidResult] matches the result back to the
 *   right entry by id, never by position.
 *
 * Android interop: the WebView's `PermissionRequest` is NOT used
 * here — the host adapts it (see the hook spec in the F03/F06
 * report): `request.origin.toString()` becomes [QueuedPermissionRequest.origin],
 * `request.resources` (an Array<String>) becomes the resource list,
 * and the host calls `request.grant(...)` / `request.deny()` only
 * with the exact resource arrays this queue returns. The string
 * constants below are the literal values of
 * `android.webkit.PermissionRequest.RESOURCE_*` (verified against
 * the Android SDK: "android.webkit.resource.VIDEO_CAPTURE" etc.),
 * so the adapter compares by value, not by importing WebKit.
 */
object PermissionResources {
    /** android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE */
    const val VIDEO_CAPTURE = "android.webkit.resource.VIDEO_CAPTURE"

    /** android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE */
    const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    /** android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.
     *  NEVER granted: it needs no Android permission, which is exactly
     *  why the old code auto-granted it. */
    const val PROTECTED_MEDIA_ID = "android.webkit.resource.PROTECTED_MEDIA_ID"

    /** The only resources that may ever be granted. */
    val ALLOWLIST: Set<String> = setOf(VIDEO_CAPTURE, AUDIO_CAPTURE)

    /** True only for allowlisted resources. Unknown / future resource
     *  strings return false — deny by default. */
    fun isAllowlisted(resource: String): Boolean = resource in ALLOWLIST

    /** The Android runtime permission a resource needs, or null if the
     *  resource is not allowlisted (it has no Android permission that
     *  could ever authorise it — it is denied, not "free"). */
    fun androidPermission(resource: String): String? = when (resource) {
        VIDEO_CAPTURE -> "android.permission.CAMERA"
        AUDIO_CAPTURE -> "android.permission.RECORD_AUDIO"
        else -> null
    }

    /** Human-readable label for the confirmation dialog. Unknown
     *  resources get a generic label — they are denied anyway, so the
     *  label is only ever shown in a denial context. */
    fun label(resource: String): String = when (resource) {
        VIDEO_CAPTURE -> "Camera"
        AUDIO_CAPTURE -> "Microphone"
        PROTECTED_MEDIA_ID -> "Protected media ID"
        else -> "Unknown permission ($resource)"
    }

    /** "Camera and Microphone" / "Camera" / "" for a resource list. */
    fun describe(resources: List<String>): String =
        resources.map { label(it) }.distinct().joinToString(" and ")
}

/** Lifecycle of one queued permission request. */
enum class PermissionDecision {
    /** Allowlisted resource(s) present; waiting for the Android
     *  runtime-permission result (FIFO head is processed first). */
    PENDING_ANDROID,

    /** Android permission granted; waiting for the user to confirm in
     *  the dialog that names the origin. */
    AWAITING_USER,

    /** Granted (only allowlisted resources, only after Android grant
     *  AND user confirmation). */
    GRANTED,

    /** Denied — non-allowlisted resources, Android permission denied,
     *  user declined, or explicitly cancelled. Terminal. */
    DENIED
}

/**
 * One pending permission request. Immutable snapshot: the queue
 * replaces the entry on every state change, so a stale snapshot can
 * never be mistaken for the live state.
 *
 * [grantableResources] — the allowlisted subset of [resources]; the
 *   only resources that can ever be granted from this request.
 * [deniedResources] — everything else in [resources]; denied by
 *   default, never granted under any path.
 * [androidPermissionsNeeded] — distinct Android runtime permissions
 *   for [grantableResources] (CAMERA and/or RECORD_AUDIO).
 * [awaitingUserResources] — the grantable resources that survived the
 *   Android-permission result and are now waiting on the user dialog;
 *   empty unless [decision] == AWAITING_USER.
 */
data class QueuedPermissionRequest(
    val id: Long,
    val origin: String,
    val resources: List<String>,
    val decision: PermissionDecision,
    val grantableResources: List<String>,
    val deniedResources: List<String>,
    val androidPermissionsNeeded: List<String>,
    val awaitingUserResources: List<String> = emptyList()
) {
    /** True once the request is decided (granted or denied). */
    val isDecided: Boolean get() = decision == PermissionDecision.GRANTED ||
        decision == PermissionDecision.DENIED

    /** Exact text for the confirmation dialog. The origin is always
     *  shown — the user must know WHICH site is asking. Returns null
     *  when no dialog is needed (request is not awaiting the user). */
    fun confirmationMessage(): String? {
        if (decision != PermissionDecision.AWAITING_USER) return null
        val what = PermissionResources.describe(awaitingUserResources)
        return "“$origin” wants to use your $what.\n\n" +
            "Allow $origin to access your $what? Only allow sites you trust — " +
            "a site with camera or microphone access can record you while the page is open."
    }
}

/**
 * FIFO queue of WebView permission requests. Single-threaded by
 * design: every entry point is the main thread (WebChromeClient
 * callbacks and onRequestPermissionsResult both run there), so no
 * synchronisation is needed — and none is faked here.
 */
class PermissionQueue {
    private val entries = mutableListOf<QueuedPermissionRequest>()
    private var nextId = 1L

    /** Enqueue a new request. Never drops or overwrites an existing
     *  entry. Returns the created entry:
     *  - no allowlisted resource (incl. empty resource list, only
     *    PROTECTED_MEDIA_ID, or only unknown resources) → DENIED
     *    immediately; the host must call `request.deny()` for it.
     *  - allowlisted resource(s) present → PENDING_ANDROID; the host
     *    takes it through the Android runtime-permission flow
     *    ([nextNeedingAndroid]) and then the user dialog. */
    fun enqueue(origin: String, resources: List<String>): QueuedPermissionRequest {
        val grantable = resources.filter { PermissionResources.isAllowlisted(it) }.distinct()
        val denied = resources.filter { !PermissionResources.isAllowlisted(it) }.distinct()
        val androidPerms = grantable.mapNotNull { PermissionResources.androidPermission(it) }.distinct()
        val entry = QueuedPermissionRequest(
            id = nextId++,
            origin = origin,
            resources = resources.toList(),
            decision = if (grantable.isEmpty()) PermissionDecision.DENIED
                else PermissionDecision.PENDING_ANDROID,
            grantableResources = grantable,
            deniedResources = denied,
            androidPermissionsNeeded = androidPerms
        )
        entries.add(entry)
        return entry
    }

    /** Snapshot of all entries, FIFO order (oldest first). */
    fun all(): List<QueuedPermissionRequest> = entries.toList()

    /** Entries not yet decided, FIFO order. */
    fun pending(): List<QueuedPermissionRequest> =
        entries.filter { !it.isDecided }

    /** The oldest entry still waiting on the Android runtime-permission
     *  flow, or null if none. The host processes one at a time; the
     *  rest wait — they are NOT dropped. */
    fun nextNeedingAndroid(): QueuedPermissionRequest? =
        entries.firstOrNull { it.decision == PermissionDecision.PENDING_ANDROID }

    /** Look up one entry by id, or null. */
    fun find(id: Long): QueuedPermissionRequest? = entries.firstOrNull { it.id == id }

    /**
     * Apply the Android runtime-permission result to one entry
     * (matched by id — never by position). [grantedAndroidPermissions]
     * is the set of Android permissions the user granted in
     * onRequestPermissionsResult.
     * - Entry unknown or already decided → returns null, no change.
     * - Entry not waiting on Android (e.g. already awaiting the user)
     *   → returns the entry unchanged.
     * - At least one grantable resource's Android permission granted →
     *   AWAITING_USER, with [QueuedPermissionRequest.awaitingUserResources]
     *   set to exactly the surviving resources (the rest join the
     *   denied set for this request — partial grants grant only what
     *   was approved).
     * - None survived → DENIED (the host must call `request.deny()`).
     */
    fun applyAndroidResult(
        id: Long,
        grantedAndroidPermissions: Set<String>
    ): QueuedPermissionRequest? {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val entry = entries[index]
        if (entry.isDecided || entry.decision != PermissionDecision.PENDING_ANDROID) return entry
        val surviving = entry.grantableResources.filter { resource ->
            val perm = PermissionResources.androidPermission(resource)
            perm != null && perm in grantedAndroidPermissions
        }
        val updated = if (surviving.isEmpty()) {
            entry.copy(decision = PermissionDecision.DENIED)
        } else {
            entry.copy(
                decision = PermissionDecision.AWAITING_USER,
                awaitingUserResources = surviving
            )
        }
        entries[index] = updated
        return updated
    }

    /**
     * Apply the user's dialog choice to one entry. Only valid while
     * the entry is AWAITING_USER; any other state returns an empty
     * list and changes nothing (a denied request can never be granted
     * by a late or duplicate callback).
     * - allow == true → GRANTED; returns the exact resources to pass
     *   to `request.grant(...)` (the surviving allowlisted subset —
     *   never the full original list, so denied resources are never
     *   smuggled into a grant).
     * - allow == false → DENIED; returns an empty list (host calls
     *   `request.deny()`).
     */
    fun resolveUserDecision(id: Long, allow: Boolean): List<String> {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return emptyList()
        val entry = entries[index]
        if (entry.decision != PermissionDecision.AWAITING_USER) return emptyList()
        return if (allow) {
            entries[index] = entry.copy(decision = PermissionDecision.GRANTED)
            entry.awaitingUserResources.toList()
        } else {
            entries[index] = entry.copy(decision = PermissionDecision.DENIED)
            emptyList()
        }
    }

    /** Force-deny one entry (e.g. the page navigated away, or the
     *  host is tearing down). Returns false if unknown or already
     *  decided. A denied entry can never later be granted. */
    fun deny(id: Long): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        val entry = entries[index]
        if (entry.isDecided) return false
        entries[index] = entry.copy(decision = PermissionDecision.DENIED)
        return true
    }

    /** Drop decided entries from the queue (housekeeping; undecided
     *  entries are never removed). Returns how many were removed. */
    fun pruneDecided(): Int {
        val before = entries.size
        entries.removeAll { it.isDecided }
        return before - entries.size
    }
}
