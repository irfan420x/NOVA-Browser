package com.nova.browser.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a finished custom download from the app-private directory
 * into public Downloads via MediaStore (D-2).
 *
 * WHY THIS FILE EXISTS: [CustomDownload] writes only to an app-private
 * dir (direct File writes to public Downloads fail on targetSdk 35 —
 * scoped storage, no WRITE_EXTERNAL_STORAGE / legacy flag in the
 * manifest). This object is the Android-only half: it is NOT compiled
 * by tests/run-tests.sh (which compiles Downloader.kt on the JVM), only
 * by tools/build-apk.sh.
 *
 * FLOW (coordinator wiring — see the W4 FIX report hook spec):
 * 1. CustomDownload finishes a file in `filesDir/Downloads`
 *    (or the private cache dir for private mode — those are NEVER
 *    published, see below).
 * 2. The coordinator passes this function as CustomDownload's
 *    `publisher` lambda:
 *    `publisher = { f -> DownloadPublish.publishToDownloads(context, f, mimeType, f.name) != null }`
 * 3. On success the file appears in NOVA Files under
 *    Downloads/NOVA/Browser/ (MediaStore.Downloads, RELATIVE_PATH
 *    `Download/NOVA/Browser`). The coordinator may then delete the
 *    private copy; this function deliberately does NOT delete it, so a
 *    failed publish never destroys the only copy.
 * 4. On failure (null return) CustomDownload reports FAILED and the
 *    coordinator falls back to Downloads.enqueue (DownloadManager).
 *
 * Private mode: the coordinator must NOT call this for private
 * downloads — CustomDownload already refuses (its `publisher` is
 * ignored when privateMode is true). A private file published here
 * would leak into shared storage.
 *
 * Returns the MediaStore Uri of the published file, or null on any
 * failure. Never throws.
 */
object DownloadPublish {

    const val PUBLIC_SUBDIR = "NOVA/Browser"

    fun publishToDownloads(
        context: Context,
        privateFile: File,
        mimeType: String?,
        fileName: String
    ): Uri? {
        if (!privateFile.exists() || !privateFile.isFile) return null
        val name = Downloader.sanitizeFileName(fileName)
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
                // API 29+: RELATIVE_PATH is the scoped-storage way to land
                // in Downloads/NOVA/Browser/ without any storage permission.
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    privateFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                } ?: run {
                    // Could not open the destination — remove the empty row.
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                    return null
                }
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                return null
            }
            uri
        } catch (_: Exception) {
            null
        }
    }
}
