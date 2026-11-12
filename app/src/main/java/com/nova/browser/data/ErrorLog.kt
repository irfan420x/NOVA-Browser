package com.nova.browser.data

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app error log (same pattern as the other NOVA apps): every crash and
 * failed operation is recorded here with its time, so problems can be
 * reported with the exact error. Entries are separated by a "---" line and
 * capped at 100; the log itself must never crash the app.
 *
 * The :incognito process writes to its OWN file (error-log-private.txt).
 * The two processes share the app data directory, so a single shared
 * file would let private-mode error entries — which can contain the URL
 * that failed — show up in the normal error log. The private file is
 * deleted when the private session ends (PrivateActivity.onDestroy).
 * Writes are synchronized: log() is called from the main thread and
 * from the IO scope (recordVisit failures), and the read-modify-write
 * would otherwise race and tear the file.
 */
object ErrorLog {
    private const val FILE_NAME = "error-log.txt"
    private const val PRIVATE_FILE_NAME = "error-log-private.txt"
    private const val MAX_ENTRIES = 100
    private const val SEPARATOR = "\n---\n"

    private fun file(context: Context): File {
        val name =
            if (Application.getProcessName()?.endsWith(":incognito") == true) PRIVATE_FILE_NAME
            else FILE_NAME
        return File(context.filesDir, name)
    }

    @Synchronized
    fun log(context: Context, tag: String, message: String, e: Throwable? = null) {
        try {
            val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
            val detail = e?.let {
                "\n${it.javaClass.simpleName}: ${it.message}\n" +
                    it.stackTraceToString().lines().take(12).joinToString("\n")
            } ?: ""
            val entry = "[$time] $tag: $message$detail"
            val f = file(context)
            val existing = if (f.exists()) f.readText() else ""
            val all = (if (existing.isBlank()) emptyList() else existing.split(SEPARATOR)) + entry
            f.writeText(all.takeLast(MAX_ENTRIES).joinToString(SEPARATOR))
        } catch (_: Exception) { /* the log itself must never crash the app */ }
    }

    /** Newest first. */
    @Synchronized
    fun read(context: Context): List<String> = try {
        val f = file(context)
        if (!f.exists()) emptyList()
        else f.readText().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.asReversed()
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun count(context: Context): Int = try {
        val f = file(context)
        if (!f.exists()) 0 else f.readText().split(SEPARATOR).count { it.isNotBlank() }
    } catch (_: Exception) { 0 }

    @Synchronized
    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) { }
    }
}
