package com.nova.browser.data

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
 */
object ErrorLog {
    private const val FILE_NAME = "error-log.txt"
    private const val MAX_ENTRIES = 100
    private const val SEPARATOR = "\n---\n"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

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
    fun read(context: Context): List<String> = try {
        val f = file(context)
        if (!f.exists()) emptyList()
        else f.readText().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.asReversed()
    } catch (_: Exception) { emptyList() }

    fun count(context: Context): Int = try {
        val f = file(context)
        if (!f.exists()) 0 else f.readText().split(SEPARATOR).count { it.isNotBlank() }
    } catch (_: Exception) { 0 }

    fun clear(context: Context) {
        try { file(context).delete() } catch (_: Exception) { }
    }
}
