package com.nova.browser.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import java.io.File

/** Live download state, merged from the DB row and DownloadManager. */
data class DownloadItem(
    val row: DownloadRow,
    val status: Int, // DownloadManager.STATUS_*
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val reason: Int
) {
    val isActive: Boolean
        get() = status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_PAUSED
    val isDone: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val isFailed: Boolean
        get() = status == DownloadManager.STATUS_FAILED
}

/**
 * Downloads via the system DownloadManager: reliable, survives the app
 * being killed, and needs no foreground service. Files land in
 * Downloads/NOVA/Browser/ so NOVA Files shows them.
 *
 * Honest limit (v1.0): DownloadManager has no public pause/resume API,
 * so the list shows the system pause state but offers cancel + re-download
 * instead of a pause button. A custom download engine with Range resume
 * is v1.1 work.
 */
object Downloads {

    private const val SUBDIR = "NOVA/Browser"

    /** Enqueue a download. Returns the DownloadManager id, or -1 on failure.
     *  [cookie] is the page's cookies for this URL (the system
     *  DownloadManager can't read the WebView's cookies itself — and in
     *  private mode it can't read the incognito process's at all), so
     *  downloads behind a login still work.
     *  [recordToDb] is false in private mode: the shared downloads table
     *  is never written from a private session, so a private download
     *  never appears in the normal Downloads list. The row is handed to
     *  [onPrivateRow] instead and lives only in memory. */
    fun enqueue(
        context: Context, db: BrowserDb,
        url: String, userAgent: String?, contentDisposition: String?,
        mimeType: String?, contentLength: Long, cookie: String? = null,
        recordToDb: Boolean = true,
        onPrivateRow: ((DownloadRow) -> Unit)? = null
    ): Long {
        return try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setMimeType(mimeType)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            if (recordToDb) {
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "$SUBDIR/$fileName"
                )
            } else {
                // P-1 (W6 baseline): a private download must NEVER land in
                // public Downloads — the file would survive the session
                // wipe (recordToDb=false only skipped the DB row). The
                // app-private external files dir has the same
                // DownloadManager reliability and is deleted with the
                // session (PrivateActivity.onDestroy).
                request.setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, "$SUBDIR/$fileName"
                )
            }
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent)
            if (cookie != null) request.addRequestHeader("Cookie", cookie)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            if (recordToDb) {
                db.addDownload(id, url, fileName, mimeType)
            } else {
                onPrivateRow?.invoke(
                    DownloadRow(0, id, url, fileName, mimeType, System.currentTimeMillis())
                )
            }
            id
        } catch (e: Exception) {
            ErrorLog.log(context, "Downloads", "Failed to start download of $url", e)
            -1
        }
    }

    /** Current live state for every download the DB knows about. Rows whose
     *  DownloadManager entry is gone (system cleared it) are dropped.
     *  [extraRows] are in-memory rows (private-mode downloads) merged
     *  in; with [includeDbRows] false only the extra rows are shown. */
    fun list(
        context: Context, db: BrowserDb,
        extraRows: List<DownloadRow> = emptyList(),
        includeDbRows: Boolean = true
    ): List<DownloadItem> {
        val rows = (if (includeDbRows) db.listDownloads() else emptyList()) + extraRows
        if (rows.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val out = ArrayList<DownloadItem>()
        try {
            val query = DownloadManager.Query()
            dm.query(query).use { cursor ->
                val iId = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iStatus = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iDone = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iReason = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val byDmId = rows.associateBy { it.dmId }
                val live = HashMap<Long, DownloadItem>()
                while (cursor.moveToNext()) {
                    val dmId = cursor.getLong(iId)
                    val row = byDmId[dmId] ?: continue
                    live[dmId] = DownloadItem(
                        row = row,
                        status = cursor.getInt(iStatus),
                        bytesDownloaded = cursor.getLong(iDone),
                        bytesTotal = cursor.getLong(iTotal),
                        reason = cursor.getInt(iReason)
                    )
                }
                // Keep DB order (newest first); rows the system no longer
                // knows are shown as failed so the user can remove them.
                for (row in rows) {
                    out.add(
                        live[row.dmId] ?: DownloadItem(
                            row, DownloadManager.STATUS_FAILED, 0, 0, 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(context, "Downloads", "Failed to list downloads", e)
            return rows.map { DownloadItem(it, DownloadManager.STATUS_FAILED, 0, 0, 0) }
        }
        return out
    }

    /** Cancel the system download and drop its record, so a cancelled
     *  download does not sit in the list as a permanent "Failed" row. */
    fun cancel(context: Context, db: BrowserDb, dmId: Long): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(dmId)
            db.removeDownload(dmId)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Cancel the system download, delete the file and drop the DB row.
     *  The manual file delete is a backstop (DownloadManager.remove
     *  deletes the file itself) and covers BOTH destinations — the
     *  public path (normal) and the app-private external-files path
     *  (private, P-1). */
    fun remove(context: Context, db: BrowserDb, item: DownloadItem): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(item.row.dmId)
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$SUBDIR/${item.row.fileName}"
            )
            if (file.exists()) file.delete()
            try {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
                    val privateFile = File(dir, "$SUBDIR/${item.row.fileName}")
                    if (privateFile.exists()) privateFile.delete()
                }
            } catch (_: Exception) { }
            db.removeDownload(item.row.dmId)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Downloads", "Failed to remove ${item.row.fileName}", e)
            false
        }
    }

    /** Open a finished download with a viewer app. Returns false when the
     *  file is gone or no app can open it. */
    fun open(context: Context, item: DownloadItem): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(item.row.dmId) ?: return false
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.row.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Downloads", "Failed to open ${item.row.fileName}", e)
            false
        }
    }

    /** Share a finished download. The Uri comes from DownloadManager
     *  itself (P-1) — getUriForDownloadedFile works for BOTH
     *  destinations (public Downloads and the app-private external
     *  files dir used for private downloads), where the old public-path
     *  File would 404 for a private file. Falls back to the public-path
     *  FileProvider Uri when the DM entry is gone but the file remains. */
    fun share(context: Context, item: DownloadItem): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = try {
                dm.getUriForDownloadedFile(item.row.dmId)
            } catch (_: Exception) {
                null
            } ?: run {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$SUBDIR/${item.row.fileName}"
                )
                if (!file.exists()) return false
                FileProvider.getUriForFile(context, "com.nova.browser.provider", file)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.row.mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Share ${item.row.fileName}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            ErrorLog.log(context, "Downloads", "Failed to share ${item.row.fileName}", e)
            false
        }
    }
}
