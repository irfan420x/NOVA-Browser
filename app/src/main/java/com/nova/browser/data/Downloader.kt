package com.nova.browser.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Custom resumable downloader — pure Kotlin/Java, no Android imports
 * (the download directory is injected as a [File], so this stays
 * JVM-testable). Uses HttpURLConnection with Range requests:
 * pause persists the partial file + a `.meta` sidecar (URL + bytes
 * downloaded), resume continues with `Range: bytes=<n>-`, so a resume
 * survives an app kill.
 *
 * This is an ADDITIONAL path, not a replacement: the system
 * DownloadManager flow in Downloads.kt is untouched and stays the
 * fallback. ANY failure here reports `onResult(null)` and the coordinator
 * must fall back to Downloads.enqueue — the downloader itself never
 * calls DownloadManager. A user cancel is NOT a failure: it calls
 * [onCancelled] and never triggers the fallback.
 *
 * STORAGE MODEL (W4 fix D-2): partials live ONLY in an app-private
 * directory (injected [CustomDownload] downloadDir — the coordinator
 * passes `filesDir/Downloads`). Nothing is ever written directly to
 * public Downloads: on finish the `.part` file is renamed to its final
 * name and the coordinator publishes it to public Downloads via
 * MediaStore (see [DownloadPublish.publishToDownloads], Android-only —
 * it lives in DownloadPublish.kt because this file must stay
 * JVM-testable for the unit suite; the coordinator passes it in as
 * [CustomDownload.publisher]). The finished file is then visible in
 * NOVA Files under Downloads/NOVA/Browser/. Private-mode downloads
 * ([CustomDownload.privateMode]) are never published — they stay in
 * the app-private cache dir and the coordinator deletes them when the
 * private session closes.
 *
 * Honest limits: servers that ignore Range (reply 200 instead of 206)
 * force a restart from byte 0; servers that need cookies/auth only work
 * when the coordinator passes them in [headers] (e.g. the WebView cookie);
 * non-http(s) URLs (blob:, data:) are not handled here at all — the
 * coordinator must route those straight to DownloadManager.
 */
enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long, // -1 when the server did not send a size
    val state: DownloadState
) {
    /** 0..100, or -1 when the total size is unknown. */
    val percent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
}

/** Persisted resume state, stored next to the partial file in the
 *  app-private meta dir — never in public Downloads (M-W4-1: the URL
 *  is often signed/token-bearing, so the sidecar must not sit in a
 *  world-readable folder). [etag]/[lastModified] are the server's
 *  validators: on resume a mismatch restarts from byte 0 (M-W4-3). */
data class DownloadMeta(
    val url: String,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val mimeType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null
)

object Downloader {

    const val META_SUFFIX = ".meta"
    const val PART_SUFFIX = ".part"
    const val MAX_REDIRECTS = 5
    private const val BUFFER_SIZE = 8192
    private const val META_FLUSH_BYTES = 1024L * 1024L // rewrite .meta every 1 MB

    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER, 307, 308
    )
    private val AUTH_HEADER_NAMES = setOf("authorization", "cookie", "proxy-authorization")

    /** `Range: bytes=<n>-`, or null when starting from scratch. */
    fun buildRangeHeader(downloadedBytes: Long): String? {
        return if (downloadedBytes > 0) "bytes=$downloadedBytes-" else null
    }

    /** Total size from a `Content-Range: bytes 100-999/1000` header,
     *  or null when the header is absent/malformed. Never throws. */
    fun parseContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return try {
            val slash = header.lastIndexOf('/')
            if (slash < 0) return null
            val total = header.substring(slash + 1).trim()
            if (total == "*") null else total.toLongOrNull()?.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** Strip path separators, control chars and trailing dots/spaces so a
     *  server-supplied name can never escape the download directory. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c ->
            if (c.code < 32 || c == '/' || c == '\\' || c == ':' || c == '*' ||
                c == '?' || c == '"' || c == '<' || c == '>' || c == '|'
            ) '_' else c
        }.joinToString("").trim().trim('.', ' ')
        return cleaned.take(200).ifBlank { "download" }
    }

    private val MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/vnd.android.package-archive" to "apk",
        "video/mp4" to "mp4",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "audio/mpeg" to "mp3",
        "text/plain" to "txt"
    )

    /** Decode %XX escapes WITHOUT turning `+` into a space (B-10).
     *  `URLDecoder.decode` treats `+` as a space, which is only correct
     *  for form bodies — never for URL paths or RFC 5987 filenames, where
     *  a literal `+` is a plus. A real space arrives as `%20`. */
    fun decodePathComponent(raw: String): String {
        return try {
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    /** File name for a download: Content-Disposition filename first
     *  (incl. RFC 5987 filename*=UTF-8''...), then the URL's last path
     *  segment, then "download". A missing extension is added from a
     *  small known-MIME map only. Never throws. */
    fun resolveFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        var name: String? = null
        if (!contentDisposition.isNullOrBlank()) {
            // RFC 5987: filename*=UTF-8''%E2%82%AC.txt
            val star = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.getOrNull(1)
            if (star != null) {
                name = decodePathComponent(star)
            }
            if (name == null) {
                val plain = Regex("filename\\s*=\\s*\"([^\"]+)\"|filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
                    .find(contentDisposition)
                name = plain?.groupValues?.firstOrNull { it.isNotBlank() && !it.startsWith("filename") }
                    ?: plain?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: plain?.groupValues?.getOrNull(2)
            }
        }
        if (name.isNullOrBlank()) {
            name = try {
                val path = URL(url).path.substringAfterLast('/')
                decodePathComponent(path)
            } catch (_: Exception) {
                null
            }
        }
        var clean = sanitizeFileName(name ?: "")
        if (clean == "download" || !clean.contains('.')) {
            val ext = MIME_EXTENSIONS[mimeType?.lowercase()]
            if (ext != null && !clean.endsWith(".$ext")) clean = "$clean.$ext"
        }
        return clean
    }

    /** A non-colliding destination for [name] in [dir] (M-W4-4): when a
     *  finished file with that name already exists, `name (1).ext`,
     *  `name (2).ext`, … — an existing finished file belonging to another
     *  URL is NEVER deleted or overwritten. Never throws. */
    /** A non-colliding file name in [dir]: `name.ext`, `name (1).ext`,
     *  … — never overwrites an existing finished file (M-W4-4).
     *
     *  H1: a name is taken when the final file OR its `.part` exists.
     *  Two concurrent jobs for the same name used to share one `.part`
     *  (the final file does not exist yet while downloading), which
     *  interleaved their writes and let the second job delete the
     *  first job's partial as "untrusted". */
    fun uniqueFileName(dir: File, name: String): File {
        val clean = sanitizeFileName(name)
        var candidate = File(dir, clean)
        if (!candidate.exists() && !File(dir, clean + PART_SUFFIX).exists()) return candidate
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var i = 1
        while (true) {
            candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists() && !File(dir, "$base ($i)$ext" + PART_SUFFIX).exists()) return candidate
            i++
        }
    }

    // ---- .meta sidecar (app-private dir only, M-W4-1) ----

    /** Meta file for [file]: `<name>.meta` in [metaDir] (default: the
     *  file's own directory). */
    fun metaFileFor(file: File, metaDir: File? = null): File =
        File(metaDir ?: file.parentFile, file.name + META_SUFFIX)

    fun metaToText(meta: DownloadMeta): String {
        fun enc(v: String) = try {
            URLEncoder.encode(v, "UTF-8")
        } catch (_: Exception) {
            v
        }
        val sb = StringBuilder()
        sb.append("url=").append(enc(meta.url)).append('\n')
        sb.append("file=").append(enc(meta.fileName)).append('\n')
        sb.append("downloaded=").append(meta.downloadedBytes).append('\n')
        sb.append("total=").append(meta.totalBytes).append('\n')
        sb.append("mime=").append(enc(meta.mimeType ?: "")).append('\n')
        if (meta.etag != null) sb.append("etag=").append(enc(meta.etag)).append('\n')
        if (meta.lastModified != null) sb.append("lastmod=").append(enc(meta.lastModified)).append('\n')
        return sb.toString()
    }

    fun parseMetaText(text: String): DownloadMeta? {
        return try {
            val map = HashMap<String, String>()
            for (line in text.lines()) {
                val eq = line.indexOf('=')
                if (eq > 0) map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            val url = map["url"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
            val file = map["file"]?.let { URLDecoder.decode(it, "UTF-8") } ?: return null
            val downloaded = map["downloaded"]?.toLongOrNull() ?: return null
            val total = map["total"]?.toLongOrNull() ?: -1L
            val mime = map["mime"]?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }
            val etag = map["etag"]?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }
            val lastMod = map["lastmod"]?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }
            DownloadMeta(url, file, downloaded, total, mime, etag, lastMod)
        } catch (_: Exception) {
            null
        }
    }

    fun writeMeta(file: File, meta: DownloadMeta, metaDir: File? = null) {
        try {
            metaFileFor(file, metaDir).writeText(metaToText(meta), Charsets.UTF_8)
        } catch (_: Exception) {
            // A meta write failure must not kill the download itself.
        }
    }

    fun readMeta(file: File, metaDir: File? = null): DownloadMeta? {
        return try {
            val metaFile = metaFileFor(file, metaDir)
            if (!metaFile.exists()) null else parseMetaText(metaFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** True when a partial file + matching .meta exist and the download
     *  can be resumed instead of restarted. */
    fun resumeAvailable(file: File, url: String): Boolean {
        val meta = readMeta(file) ?: return false
        return meta.url == url && file.exists() && file.length() > 0 &&
            file.length() == meta.downloadedBytes
    }

    /** Delete orphaned `.meta` files in [dir] — metas whose base file
     *  (partial or finished) no longer exists, e.g. after a crash or a
     *  manual delete. Returns the number removed. The coordinator calls
     *  this once at startup. Never throws. */
    fun cleanupOrphanedMeta(dir: File): Int {
        var removed = 0
        val files = try {
            dir.listFiles() ?: return 0
        } catch (_: Exception) {
            return 0
        }
        for (f in files) {
            if (!f.name.endsWith(META_SUFFIX)) continue
            val base = File(dir, f.name.removeSuffix(META_SUFFIX))
            if (!base.exists()) {
                try {
                    if (f.delete()) removed++
                } catch (_: Exception) {
                }
            }
        }
        return removed
    }

    // ---- Redirect safety (D-5 / H-W4-1) ----

    /** Effective port: explicit, else the scheme default (80/443). */
    fun effectivePort(url: URL): Int {
        if (url.port != -1) return url.port
        return when (url.protocol.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    /** True when [a] and [b] are the same origin: same scheme, host
     *  (case-insensitive) and effective port. Unparseable URLs count as
     *  different (fail closed). Same rule as HttpClientEngine. */
    fun sameOrigin(a: String, b: String): Boolean {
        return try {
            val ua = URL(a)
            val ub = URL(b)
            ua.protocol.equals(ub.protocol, ignoreCase = true) &&
                ua.host.equals(ub.host, ignoreCase = true) &&
                effectivePort(ua) == effectivePort(ub)
        } catch (_: Exception) {
            false
        }
    }

    /** True when the redirect downgrades https → http. Such a redirect
     *  must never carry credentials (see [resolveRedirect]). */
    fun isDowngradeRedirect(fromUrl: String, toUrl: String): Boolean {
        return try {
            val from = URL(fromUrl)
            val to = URL(toUrl)
            from.protocol.equals("https", ignoreCase = true) &&
                to.protocol.equals("http", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /** Resolve a redirect `Location` (absolute or relative) against the
     *  current URL. Returns null when it cannot be resolved. */
    fun resolveRedirectUrl(currentUrl: String, location: String): String? {
        return try {
            URL(URL(currentUrl), location).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** True when [headers] carry any credential header (Cookie /
     *  Authorization / Proxy-Authorization, case-insensitive). */
    fun hasCredentials(headers: Map<String, String>): Boolean =
        headers.keys.any { it.trim().lowercase() in AUTH_HEADER_NAMES }

    /** [headers] with credential headers removed (cross-origin redirect).
     *  Never throws. */
    fun stripAuthHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { it.trim().lowercase() !in AUTH_HEADER_NAMES }

    /** True when the redirect response status is one we follow manually. */
    fun isRedirectStatus(code: Int): Boolean = code in REDIRECT_CODES

    // ---- Resume validation + completion (M-W4-3, D-1, B-9, P-4) ----

    /** True when a resume must restart from byte 0: the stored validator
     *  (ETag or Last-Modified) is present AND the server now reports a
     *  different value for the same validator. A validator the server
     *  does not send this time cannot prove a change, so it does not
     *  force a restart (fail open — the length check in [validateCompletion]
     *  still guards the result). */
    fun shouldRestartOnValidation(
        metaEtag: String?, responseEtag: String?,
        metaLastModified: String?, responseLastModified: String?
    ): Boolean {
        if (metaEtag != null && responseEtag != null && metaEtag != responseEtag) return true
        if (metaLastModified != null && responseLastModified != null &&
            metaLastModified != responseLastModified
        ) return true
        return false
    }

    /**
     * D-1: is this download really complete? A truncated body (EOF before
     * the declared total, or a file shorter than the total) is NOT
     * complete — the caller must fail (keeping .meta for resume) instead
     * of reporting COMPLETED.
     * - total > 0: downloaded AND on-disk length must equal the total.
     * - total == 0: an empty body was declared — fail (P-4), never a
     *   0-byte "success".
     * - total < 0 (unknown): EOF is the only signal, so completion is
     *   accepted as long as *something* was received; a 0-byte body with
     *   an unknown total is also accepted (a legitimately empty file
     *   cannot be told apart from a truncated one without a total).
     */
    fun validateCompletion(downloadedBytes: Long, totalBytes: Long, fileLength: Long): Boolean {
        return when {
            totalBytes > 0 -> downloadedBytes == totalBytes && fileLength == totalBytes
            totalBytes == 0L -> false
            else -> downloadedBytes >= 0L // unknown total: EOF is the signal
        }
    }

    /** Outcome of a 416 (Range Not Satisfiable) — B-9.
     *  FINISH: the partial already covers the known total.
     *  RESTART: the server now reports a total the partial does not cover.
     *  ALREADY_DOWNLOADED: the partial is non-empty but no total can be
     *  established (HEAD/Range could not tell us) — a 416 means our range
     *  start is past EOF, i.e. the server believes the file is complete;
     *  surface "already downloaded" instead of restarting from 0 and
     *  failing.
     *  FAIL: nothing downloaded at all — a 416 with no partial is a
     *  server error, not a completed file. */
    enum class Range416Decision { FINISH, RESTART, ALREADY_DOWNLOADED, FAIL }

    fun decide416(downloaded: Long, total: Long, headTotal: Long?): Range416Decision {
        if (downloaded <= 0) return Range416Decision.FAIL
        if (total > 0 && downloaded >= total) return Range416Decision.FINISH
        if (headTotal != null) {
            return if (downloaded >= headTotal) Range416Decision.FINISH
            else Range416Decision.RESTART
        }
        return Range416Decision.ALREADY_DOWNLOADED
    }

    internal const val INTERNAL_BUFFER_SIZE = BUFFER_SIZE
    internal const val INTERNAL_META_FLUSH_BYTES = META_FLUSH_BYTES
}

/**
 * One resumable download. Create per download; [downloadDir] is an
 * APP-PRIVATE directory (injected by the coordinator — `filesDir/Downloads`
 * for normal downloads, the app-private cache dir for private ones).
 * This class never touches Android APIs itself.
 *
 * Lifecycle: [start] → DOWNLOADING; [pause] → PAUSED (partial `.part`
 * file + .meta kept in the private dir); [resume] → DOWNLOADING from the
 * persisted offset; [cancel] → CANCELLED (partial file + .meta deleted,
 * no fallback). Completion calls [onResult] with the finished file; any
 * error calls [onResult] with null — the coordinator then falls back to
 * Downloads.enqueue (DownloadManager). [onProgress] is called from the
 * worker thread (throttled to ~64 KB / completion); the coordinator must
 * post it to the main thread before touching UI.
 *
 * Files: the body is written to `<name>.part` and renamed to the final
 * name only in finish() (L-W4-3) — a half-written file never wears the
 * final name. A same-name finished file from another URL is never
 * touched: the download gets `name (1).ext` instead (M-W4-4). The
 * `.meta` sidecar lives in [metaDir] (default: [downloadDir]), never in
 * public Downloads.
 *
 * Private mode ([privateMode] = true): the download stays in the
 * app-private dir, [publisher] is ignored, and the file is NEVER
 * published to public Downloads. The coordinator deletes it when the
 * private session closes; when in doubt it should refuse private
 * downloads rather than leak them.
 *
 * [publisher], when supplied by the coordinator, publishes the finished
 * private file to public Downloads via MediaStore
 * (DownloadPublish.publishToDownloads) and returns true on success.
 * A failed publish fails the download ([onResult] null → DownloadManager
 * fallback) rather than leaving an invisible file in the private dir.
 *
 * Pause/cancel unblock a stalled read immediately by disconnecting the
 * live connection (B-8) and set the state at once, so the UI stops
 * showing DOWNLOADING while the worker winds down.
 */
class CustomDownload(
    private val downloadDir: File,
    private val url: String,
    fileName: String? = null,
    private val mimeType: String? = null,
    private val headers: Map<String, String> = emptyMap(),
    private val userAgent: String? = null,
    private val onProgress: (DownloadProgress) -> Unit = {},
    private val onResult: (File?) -> Unit = {},
    private val onCancelled: () -> Unit = {},
    private val metaDir: File? = null,
    private val privateMode: Boolean = false,
    private val publisher: ((File) -> Boolean)? = null
) {
    /** Final destination (renamed from [partialFile] in finish()). May
     *  change from the requested name on a same-name collision. */
    var file: File
        private set

    /** The file actually being written (`<name>.part`, or the final file
     *  itself for a legacy pre-.part partial restored from .meta). */
    val partialFile: File

    /** Effective meta directory (never public Downloads). */
    val effectiveMetaDir: File get() = metaDir ?: downloadDir

    val metaFile: File get() = Downloader.metaFileFor(partialFile, effectiveMetaDir)

    @Volatile
    var state: DownloadState = DownloadState.QUEUED
        private set

    @Volatile
    var downloadedBytes: Long = 0L
        private set

    @Volatile
    var totalBytes: Long = -1L
        private set

    /** Human-readable failure reason for the last failure, or null. */
    @Volatile
    var lastError: String? = null
        private set

    /** True when finish() ran via the B-9 "already downloaded" path: the
     *  server 416'd and no total could be established, so the existing
     *  partial was accepted as complete. */
    @Volatile
    var finishedAsAlreadyDownloaded: Boolean = false
        private set

    @Volatile
    private var paused = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var currentEtag: String? = null

    @Volatile
    private var currentLastModified: String? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private var worker: Thread? = null
    private val cancelLock = Any()
    private var cancelNotified = false
    /** Serialises the terminal pause/cancel actions (write .meta vs
     *  delete partial + .meta) so a pause-then-cancel can never
     *  resurrect the .meta after cancel deleted it. */
    private val terminalLock = Any()

    init {
        val requested = fileName?.takeIf { it.isNotBlank() }
            ?: Downloader.resolveFileName(url, headers["Content-Disposition"], mimeType)
        val clean = Downloader.sanitizeFileName(requested)
        val finalCandidate = File(downloadDir, clean)
        val finalMeta = Downloader.readMeta(finalCandidate, effectiveMetaDir)
        val legacyPartial = finalCandidate.exists() && finalMeta != null &&
            finalMeta.url == url &&
            (finalMeta.totalBytes <= 0 || finalCandidate.length() < finalMeta.totalBytes)
        if (legacyPartial) {
            // Pre-.part layout: a partial stored under the final name with
            // a matching .meta. Resume it in place (never delete it — it
            // is our own partial, not another download's finished file).
            file = finalCandidate
            partialFile = finalCandidate
            downloadedBytes = finalMeta!!.downloadedBytes.coerceIn(0L, finalCandidate.length())
            totalBytes = finalMeta.totalBytes
            currentEtag = finalMeta.etag
            currentLastModified = finalMeta.lastModified
        } else {
            // A .part with a meta for THIS url is our own partial (e.g.
            // the app was killed mid-download) — resume it in place.
            // Any other .part belongs to a different in-flight job (H1)
            // — sharing one .part corrupts both downloads, so uniquify
            // when the final name OR any .part for it already exists.
            val ownPart = File(downloadDir, clean + Downloader.PART_SUFFIX)
            val ownPartMeta = Downloader.readMeta(ownPart, effectiveMetaDir)
            val resumeOwnPart = ownPart.exists() && ownPartMeta != null && ownPartMeta.url == url
            file = if (!resumeOwnPart && (finalCandidate.exists() || ownPart.exists())) {
                Downloader.uniqueFileName(downloadDir, clean)
            } else {
                finalCandidate
            }
            val partCandidate = if (resumeOwnPart) ownPart
            else File(downloadDir, file.name + Downloader.PART_SUFFIX)
            val partMeta = Downloader.readMeta(partCandidate, effectiveMetaDir)
            partialFile = partCandidate
            if (partCandidate.exists() && partMeta != null && partMeta.url == url) {
                downloadedBytes = partMeta.downloadedBytes.coerceIn(0L, partCandidate.length())
                totalBytes = partMeta.totalBytes
                currentEtag = partMeta.etag
                currentLastModified = partMeta.lastModified
            }
        }
    }

    fun start() {
        if (state == DownloadState.DOWNLOADING) return
        cancelled = false
        paused = false
        synchronized(cancelLock) { cancelNotified = false }
        lastError = null
        finishedAsAlreadyDownloaded = false
        state = DownloadState.DOWNLOADING
        worker = Thread({ runDownload() }, "nova-download").apply {
            isDaemon = true
            start()
        }
    }

    /** Pause: the worker stops at the next buffer boundary (or at once —
     *  the live connection is disconnected, unblocking a stalled read),
     *  the partial file + .meta are flushed, state becomes PAUSED.
     *  [resume] restarts. The state is set immediately so the UI stops
     *  showing DOWNLOADING. */
    fun pause() {
        if (state != DownloadState.DOWNLOADING) return
        paused = true
        disconnectActive()
        state = DownloadState.PAUSED
    }

    fun resume() {
        if (state == DownloadState.PAUSED) start()
    }

    /** Cancel: deletes the partial file + .meta, calls [onCancelled]
     *  (NOT [onResult]) so the coordinator does NOT fall back to
     *  DownloadManager for a download the user deliberately stopped.
     *  For PAUSED/QUEUED the worker has already exited (or never
     *  started), so the cleanup happens synchronously here (D-3) —
     *  previously it only flagged the worker and the partial + .meta
     *  lingered forever. For DOWNLOADING the state is set to CANCELLED
     *  immediately and the live connection is disconnected (B-8); the
     *  worker's cancelled branch does the file cleanup. */
    fun cancel() {
        // FAILED is terminal too: onResult(null) already fired and the
        // coordinator already fell back — there is nothing left to cancel.
        if (state == DownloadState.COMPLETED || state == DownloadState.CANCELLED ||
            state == DownloadState.FAILED
        ) return
        cancelled = true
        paused = false
        disconnectActive()
        try {
            worker?.interrupt()
        } catch (_: Exception) {
        }
        if (state == DownloadState.QUEUED || state == DownloadState.PAUSED) {
            terminalCancel()
        } else {
            // DOWNLOADING: worker cleans up; set the state now so the UI
            // stops showing DOWNLOADING.
            state = DownloadState.CANCELLED
        }
    }

    private fun disconnectActive() {
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
        }
        activeConnection = null
    }

    private fun notifyCancelledOnce() {
        val fire = synchronized(cancelLock) {
            if (cancelNotified) false else {
                cancelNotified = true
                true
            }
        }
        if (fire) {
            try {
                onCancelled()
            } catch (_: Exception) {
            }
        }
    }

    private fun deletePartialAndMeta() {
        try {
            partialFile.delete()
        } catch (_: Exception) {
        }
        try {
            Downloader.metaFileFor(partialFile, effectiveMetaDir).delete()
        } catch (_: Exception) {
        }
    }

    /** Terminal pause: flush .meta and become PAUSED — unless a cancel
     *  landed first, in which case this becomes a cancel instead. The
     *  check and the file action are atomic under [terminalLock], so a
     *  pause-then-cancel can never rewrite the .meta after cancel
     *  deleted it. Callers close the streams/connection first. */
    private fun terminalPause() {
        val wasCancelled = synchronized(terminalLock) {
            if (cancelled) {
                deletePartialAndMeta()
                state = DownloadState.CANCELLED
                true
            } else {
                Downloader.writeMeta(
                    partialFile,
                    DownloadMeta(url, file.name, downloadedBytes, totalBytes, mimeType, currentEtag, currentLastModified),
                    effectiveMetaDir
                )
                state = DownloadState.PAUSED
                false
            }
        }
        if (wasCancelled) notifyCancelledOnce() else reportProgress()
    }

    /** Terminal cancel: delete partial + .meta, become CANCELLED, notify
     *  exactly once. Idempotent; safe to call from the worker, from
     *  [cancel]'s synchronous path, and from the runDownload safety net. */
    private fun terminalCancel() {
        synchronized(terminalLock) {
            deletePartialAndMeta()
            state = DownloadState.CANCELLED
        }
        notifyCancelledOnce()
    }

    private fun reportProgress() {
        try {
            onProgress(DownloadProgress(downloadedBytes, totalBytes, state))
        } catch (_: Exception) {
        }
    }

    /** Manual-redirect connection (D-5): instanceFollowRedirects is OFF
     *  — the caller follows redirects itself so credential headers are
     *  dropped on a cross-origin hop and https→http downgrades carrying
     *  credentials are refused. */
    private fun openConnection(
        urlString: String,
        activeHeaders: Map<String, String>,
        fromByte: Long
    ): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
        for ((k, v) in activeHeaders) {
            if (!k.equals("Content-Disposition", ignoreCase = true)) {
                conn.setRequestProperty(k, v)
            }
        }
        Downloader.buildRangeHeader(fromByte)?.let { conn.setRequestProperty("Range", it) }
        return conn
    }

    private fun writeMeta() {
        if (cancelled) return
        Downloader.writeMeta(
            partialFile,
            DownloadMeta(url, file.name, downloadedBytes, totalBytes, mimeType, currentEtag, currentLastModified),
            effectiveMetaDir
        )
    }

    private fun fail(reason: String? = null) {
        // Pause/cancel disconnects the socket, which surfaces as an
        // exception in the worker — that is not a failure: the worker's
        // paused/cancelled branch owns the outcome.
        if (cancelled || paused) return
        lastError = reason
        state = DownloadState.FAILED
        writeMeta() // keep resume state so the user can retry/resume
        reportProgress()
        try {
            onResult(null) // coordinator falls back to DownloadManager
        } catch (_: Exception) {
        }
    }

    /** Best-effort HEAD to learn the total after a 416. Returns null
     *  when the server does not report a usable total (or the HEAD
     *  itself fails). Never throws. */
    private fun probeTotalHead(urlString: String, activeHeaders: Map<String, String>): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in activeHeaders) {
                if (!k.equals("Content-Disposition", ignoreCase = true)) {
                    conn.setRequestProperty(k, v)
                }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val len = conn.contentLengthLong
            if (len >= 0) len else Downloader.parseContentRange(conn.getHeaderField("Content-Range"))
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun runDownload() {
        try {
            downloadDir.mkdirs()
            if (effectiveMetaDir != downloadDir) effectiveMetaDir.mkdirs()
            // A partial file without a matching .meta is untrusted (crashed
            // mid-write or from another URL) — restart from byte 0.
            var downloaded = downloadedBytes
            val meta = Downloader.readMeta(partialFile, effectiveMetaDir)
            if (partialFile.exists() && (meta == null || meta.url != url)) {
                deletePartialAndMeta()
                downloaded = 0L
                downloadedBytes = 0L
                totalBytes = -1L
                currentEtag = null
                currentLastModified = null
            } else if (partialFile.exists()) {
                downloaded = partialFile.length()
                downloadedBytes = downloaded
            } else {
                downloaded = 0L
                downloadedBytes = 0L
            }

            // --- Connection phase: manual redirects + resume validation.
            // Retried at most once (validation restart / 200-restart).
            var conn: HttpURLConnection? = null
            var code = -1
            var currentUrl = url
            var activeHeaders = headers.toMutableMap()
            var redirects = 0
            var restarts = 0
            var alreadyDownloaded = false
            connectionPhase@
            while (true) {
                // Redirect loop.
                while (true) {
                    val c = openConnection(currentUrl, activeHeaders, downloaded)
                    activeConnection = c
                    code = try {
                        c.responseCode
                    } catch (_: Exception) {
                        c.disconnect()
                        activeConnection = null
                        fail("Connection failed")
                        return
                    }
                    if (!Downloader.isRedirectStatus(code)) {
                        conn = c
                        break
                    }
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    activeConnection = null
                    if (location.isNullOrBlank()) {
                        fail("Redirect without a Location header")
                        return
                    }
                    if (redirects >= Downloader.MAX_REDIRECTS) {
                        fail("Too many redirects (more than ${Downloader.MAX_REDIRECTS})")
                        return
                    }
                    val next = Downloader.resolveRedirectUrl(currentUrl, location)
                    if (next == null) {
                        fail("Redirect target could not be resolved")
                        return
                    }
                    if (Downloader.isDowngradeRedirect(currentUrl, next) &&
                        Downloader.hasCredentials(activeHeaders)
                    ) {
                        fail("Refused https-to-http redirect carrying credentials")
                        return
                    }
                    if (!Downloader.sameOrigin(currentUrl, next)) {
                        activeHeaders = Downloader.stripAuthHeaders(activeHeaders).toMutableMap()
                    }
                    currentUrl = next
                    redirects++
                }
                val live = conn!!
                // 416 = the range is already satisfied (file is complete).
                if (code == 416) {
                    live.disconnect()
                    activeConnection = null
                    val headTotal = probeTotalHead(currentUrl, activeHeaders)
                    when (Downloader.decide416(downloaded, totalBytes, headTotal)) {
                        Downloader.Range416Decision.FINISH -> {
                            // Accept the partial as complete.
                            totalBytes = downloaded
                            downloadedBytes = downloaded
                            alreadyDownloaded = false
                            break@connectionPhase
                        }
                        Downloader.Range416Decision.ALREADY_DOWNLOADED -> {
                            // No total can be established, but a 416 with a
                            // non-empty partial means the server believes
                            // the file is complete — accept it (B-9).
                            totalBytes = downloaded
                            downloadedBytes = downloaded
                            alreadyDownloaded = true
                            break@connectionPhase
                        }
                        Downloader.Range416Decision.RESTART -> {
                            if (restarts++ >= 1) {
                                fail("Server range no longer satisfiable")
                                return
                            }
                            deletePartialAndMeta()
                            downloaded = 0L
                            downloadedBytes = 0L
                            totalBytes = -1L
                            currentEtag = null
                            currentLastModified = null
                            continue@connectionPhase
                        }
                        Downloader.Range416Decision.FAIL -> {
                            fail("Server rejected the download range")
                            return
                        }
                    }
                }
                // Server ignored Range (200 instead of 206) — restart
                // cleanly rather than appending a full body onto a
                // partial file.
                if (code == HttpURLConnection.HTTP_OK && downloaded > 0) {
                    live.disconnect()
                    activeConnection = null
                    if (restarts++ >= 1) {
                        fail("Server ignored the resume range")
                        return
                    }
                    deletePartialAndMeta()
                    downloaded = 0L
                    downloadedBytes = 0L
                    totalBytes = -1L
                    currentEtag = null
                    currentLastModified = null
                    continue@connectionPhase
                }
                if (code != HttpURLConnection.HTTP_OK &&
                    code != HttpURLConnection.HTTP_PARTIAL
                ) {
                    live.disconnect()
                    activeConnection = null
                    fail("Server returned HTTP $code")
                    return
                }
                totalBytes = if (code == HttpURLConnection.HTTP_PARTIAL) {
                    Downloader.parseContentRange(live.getHeaderField("Content-Range"))
                        ?: live.contentLengthLong.takeIf { it > 0 }?.let { it + downloaded }
                        ?: -1L
                } else {
                    // Content-Length 0 is a declared empty body: keep it
                    // as total 0 so validateCompletion() fails it (P-4);
                    // a missing length stays -1 (unknown).
                    if (live.contentLengthLong >= 0) live.contentLengthLong else -1L
                }
                // Resume validation (M-W4-3): a changed ETag /
                // Last-Modified means the file on the server is not the
                // one we partially downloaded — restart from byte 0.
                val respEtag = live.getHeaderField("ETag")
                val respLastMod = live.getHeaderField("Last-Modified")
                if (downloaded > 0 && restarts < 1 &&
                    Downloader.shouldRestartOnValidation(
                        currentEtag, respEtag, currentLastModified, respLastMod
                    )
                ) {
                    live.disconnect()
                    activeConnection = null
                    restarts++
                    deletePartialAndMeta()
                    downloaded = 0L
                    downloadedBytes = 0L
                    totalBytes = -1L
                    currentEtag = null
                    currentLastModified = null
                    continue@connectionPhase
                }
                currentEtag = respEtag ?: currentEtag
                currentLastModified = respLastMod ?: currentLastModified
                // Free-space check (P-4): fail early with a clear message
                // instead of dying mid-write.
                if (totalBytes > 0) {
                    val free = try {
                        downloadDir.usableSpace
                    } catch (_: Exception) {
                        -1L
                    }
                    if (free > 0 && free < totalBytes) {
                        live.disconnect()
                        activeConnection = null
                        fail("Not enough free space to download this file")
                        return
                    }
                }
                break@connectionPhase
            }
            finishedAsAlreadyDownloaded = alreadyDownloaded

            // 416 finish: there is no body to copy — the partial file
            // itself is the download. Go straight to finish().
            if (code == 416) {
                finish()
                return
            }

            val liveConn = conn!!
            val input = try {
                liveConn.inputStream
            } catch (_: Exception) {
                liveConn.disconnect()
                activeConnection = null
                fail("Could not read the download")
                return
            }
            // Append mode when resuming (downloaded > 0), truncate when fresh.
            // NOTE: never open the file non-append first — that would
            // truncate the partial file and destroy the resume data.
            val out = try {
                java.io.FileOutputStream(partialFile, downloaded > 0)
            } catch (_: Exception) {
                try {
                    input.close()
                } catch (_: Exception) {
                }
                liveConn.disconnect()
                activeConnection = null
                fail("Could not write the download file")
                return
            }
            val buf = ByteArray(Downloader.INTERNAL_BUFFER_SIZE)
            var sinceMeta = 0L
            var sinceReport = 0L
            try {
                while (true) {
                    if (cancelled) {
                        try {
                            out.close()
                        } catch (_: Exception) {
                        }
                        try {
                            input.close()
                        } catch (_: Exception) {
                        }
                        liveConn.disconnect()
                        activeConnection = null
                        terminalCancel()
                        return
                    }
                    if (paused) {
                        try {
                            out.close()
                        } catch (_: Exception) {
                        }
                        try {
                            input.close()
                        } catch (_: Exception) {
                        }
                        liveConn.disconnect()
                        activeConnection = null
                        terminalPause()
                        return
                    }
                    val read = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        // A pause/cancel disconnects the socket, so the
                        // read throws — break out to the paused/cancelled
                        // handling below instead of failing.
                        if (cancelled || paused) break
                        fail("Download interrupted")
                        return
                    }
                    if (read < 0) break
                    try {
                        out.write(buf, 0, read)
                    } catch (_: Exception) {
                        fail("Could not write the download file")
                        return
                    }
                    downloaded += read
                    downloadedBytes = downloaded
                    sinceMeta += read
                    sinceReport += read
                    if (sinceMeta >= Downloader.INTERNAL_META_FLUSH_BYTES) {
                        writeMeta()
                        sinceMeta = 0L
                    }
                    if (sinceReport >= 65536L) {
                        reportProgress()
                        sinceReport = 0L
                    }
                }
            } finally {
                try {
                    out.close()
                } catch (_: Exception) {
                }
                try {
                    input.close()
                } catch (_: Exception) {
                }
                liveConn.disconnect()
                activeConnection = null
            }
            if (cancelled) {
                // Cancel raced the final read — treat as cancel, not success.
                terminalCancel()
                return
            }
            if (paused) {
                terminalPause()
                return
            }
            finish()
        } catch (e: Throwable) {
            // L9: catch Throwable, not Exception — a thread-killing Error
            // (OutOfMemoryError, StackOverflowError) must still fail the
            // download instead of leaving the job stuck !done forever
            // (which would also block tab suspension, H3). fail() only
            // writes state + the .meta, it cannot throw.
            if (!cancelled && !paused) fail("Download failed: ${e.javaClass.simpleName}")
        } finally {
            // Safety net: a cancel that raced any early return (e.g. the
            // connection phase) must still clean up and notify exactly
            // once. Idempotent — the worker's own cancelled branch does
            // the same, and notifyCancelledOnce() guards double delivery.
            // A download that already COMPLETED is never un-completed.
            if (cancelled && state != DownloadState.COMPLETED) {
                terminalCancel()
            }
        }
    }

    private fun finish() {
        // D-1: a truncated body must NEVER be reported COMPLETED.
        if (!Downloader.validateCompletion(downloadedBytes, totalBytes, partialFile.length())) {
            fail(
                "Download incomplete: received $downloadedBytes of " +
                    if (totalBytes > 0) "$totalBytes bytes" else "the expected file"
            )
            return
        }
        // Rename the .part file to its final name (L-W4-3). If another
        // finished file took the name while we downloaded, pick a fresh
        // unique name instead of overwriting it (M-W4-4).
        if (partialFile != file) {
            if (file.exists()) {
                file = Downloader.uniqueFileName(downloadDir, file.name)
            }
            val renamed = try {
                partialFile.renameTo(file)
            } catch (_: Exception) {
                false
            }
            if (!renamed) {
                // renameTo fails across filesystems — fall back to copy.
                val copied = try {
                    partialFile.copyTo(file, overwrite = false)
                    true
                } catch (_: Exception) {
                    false
                }
                if (copied) {
                    try {
                        partialFile.delete()
                    } catch (_: Exception) {
                    }
                } else {
                    fail("Could not finalise the download")
                    return
                }
            }
        }
        try {
            Downloader.metaFileFor(partialFile, effectiveMetaDir).delete()
        } catch (_: Exception) {
        }
        state = DownloadState.COMPLETED
        reportProgress()
        if (!privateMode && publisher != null) {
            val published = try {
                publisher.invoke(file)
            } catch (_: Exception) {
                false
            }
            if (!published) {
                // The file is complete but invisible in public Downloads —
                // fail so the coordinator falls back to DownloadManager
                // instead of showing a download the user cannot find.
                state = DownloadState.FAILED
                lastError = "Could not save the download to Downloads"
                reportProgress()
                try {
                    onResult(null)
                } catch (_: Exception) {
                }
                return
            }
        }
        try {
            onResult(file)
        } catch (_: Exception) {
        }
    }
}
