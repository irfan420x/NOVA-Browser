package com.nova.browser.data

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nova.browser.devtools.HttpHeaderCodec
import com.nova.browser.devtools.HttpRequestData
import com.nova.browser.devtools.UrlRedactor

/** One row of the HTTP Client history (last 50 sends). */
data class HttpHistoryEntry(
    val id: Long,
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val createdAt: Long
) {
    fun toRequest(): HttpRequestData = HttpRequestData(method, url, headers, body)
}

/** One named saved request (a mini-collection entry). */
data class SavedHttpRequest(
    val id: Long,
    val name: String,
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val createdAt: Long
) {
    fun toRequest(): HttpRequestData = HttpRequestData(method, url, headers, body)
}

/**
 * HTTP Client storage — its OWN SQLiteOpenHelper on its OWN file
 * ("http_history.db"). BrowserDb.kt is NOT touched: the HTTP Client must
 * never be able to corrupt history/bookmarks, and deleting the HTTP
 * history must never touch browsing data.
 *
 * Two tables: `http_history` (last 50 sends, auto-pruned) and
 * `http_saved` (named requests the user explicitly saved). Every method
 * catches its own errors and returns an empty/false result instead of
 * crashing the browser.
 *
 * SECURITY (W6 H-2): auth header values (Authorization, Cookie,
 * Proxy-Authorization, token-like headers) are redacted to `<redacted>`
 * by [HttpHeaderCodec.encodeForStorage] before ANY write — a bearer
 * token never sits in this plaintext SQLite file. Request BODIES are
 * stored as-is (the user is warned in the UI); a body can itself carry
 * a secret, so the history screen says so plainly.
 */
class HttpHistory(context: Context) :
    SQLiteOpenHelper(context, "http_history.db", null, 1) {

    private val appContext: Context = context.applicationContext

    companion object {
        const val MAX_HISTORY = 50
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE http_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                method TEXT NOT NULL,
                url TEXT NOT NULL,
                headers TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_http_history_time ON http_history(created_at)")
        db.execSQL(
            """CREATE TABLE http_saved (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                method TEXT NOT NULL,
                url TEXT NOT NULL,
                headers TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: no upgrades yet.
    }

    // ---- History (last 50) ----

    /** Record a sent request; prunes to the newest [MAX_HISTORY] rows. */
    /** True when this process is :incognito — history must never be
     *  written from a private session (W6 N-2). */
    private fun isIncognitoProcess(): Boolean =
        Application.getProcessName()?.endsWith(":incognito") == true

    fun addHistory(request: HttpRequestData): Boolean {
        // N-2: never write history from the :incognito process.
        if (isIncognitoProcess()) return false
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("method", request.cleanMethod)
                    // N-1: the URL is redacted before it touches the disk —
                    // query-string tokens and userinfo must never sit in
                    // plaintext SQLite. See UrlRedactor.
                    put("url", UrlRedactor.redact(request.url.trim()))
                    // H-2: auth header values are redacted before they touch
                    // the disk — a bearer token must never sit in plaintext
                    // SQLite. See HttpHeaderCodec.redactedForStorage.
                    put("headers", HttpHeaderCodec.encodeForStorage(request.headers))
                    put("body", request.body)
                    put("created_at", System.currentTimeMillis())
                }
                db.insert("http_history", null, values)
                // Prune: keep the newest MAX_HISTORY rows.
                db.execSQL(
                    """DELETE FROM http_history WHERE id NOT IN
                       (SELECT id FROM http_history ORDER BY created_at DESC, id DESC LIMIT ?)""",
                    arrayOf(MAX_HISTORY)
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        } catch (e: Exception) {
            ErrorLog.log(appContext, "HttpHistory", "addHistory failed", e)
            false
        }
    }

    fun listHistory(): List<HttpHistoryEntry> {
        return try {
            val out = ArrayList<HttpHistoryEntry>()
            readableDatabase.query(
                "http_history", null, null, null, null, null,
                "created_at DESC, id DESC", MAX_HISTORY.toString()
            ).use { c ->
                val iId = c.getColumnIndexOrThrow("id")
                val iMethod = c.getColumnIndexOrThrow("method")
                val iUrl = c.getColumnIndexOrThrow("url")
                val iHeaders = c.getColumnIndexOrThrow("headers")
                val iBody = c.getColumnIndexOrThrow("body")
                val iTime = c.getColumnIndexOrThrow("created_at")
                while (c.moveToNext()) {
                    out.add(
                        HttpHistoryEntry(
                            c.getLong(iId), c.getString(iMethod), c.getString(iUrl),
                            HttpHeaderCodec.decode(c.getString(iHeaders) ?: ""),
                            c.getString(iBody) ?: "",
                            c.getLong(iTime)
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deleteHistory(id: Long): Boolean = try {
        writableDatabase.delete("http_history", "id = ?", arrayOf(id.toString())) > 0
    } catch (_: Exception) {
        false
    }

    fun clearHistory() {
        try { writableDatabase.delete("http_history", null, null) } catch (_: Exception) { }
    }

    // ---- Saved requests (named) ----

    /** Save a request under [name]. A repeat save with the same name
     *  replaces the old one instead of duplicating it. */
    fun saveRequest(name: String, request: HttpRequestData): Boolean {
        // N-2: never write history from the :incognito process.
        if (isIncognitoProcess()) return false
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return false
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("http_saved", "name = ?", arrayOf(cleanName))
                val values = ContentValues().apply {
                    put("name", cleanName)
                    put("method", request.cleanMethod)
                    // N-1: redacted before storage, same as history.
                    put("url", UrlRedactor.redact(request.url.trim()))
                    // H-2: same redaction as history — saved requests are
                    // persisted too, so no credential may be stored.
                    put("headers", HttpHeaderCodec.encodeForStorage(request.headers))
                    put("body", request.body)
                    put("created_at", System.currentTimeMillis())
                }
                db.insert("http_saved", null, values)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        } catch (e: Exception) {
            ErrorLog.log(appContext, "HttpHistory", "saveRequest failed", e)
            false
        }
    }

    fun listSaved(): List<SavedHttpRequest> {
        return try {
            val out = ArrayList<SavedHttpRequest>()
            readableDatabase.query(
                "http_saved", null, null, null, null, null, "name ASC"
            ).use { c ->
                val iId = c.getColumnIndexOrThrow("id")
                val iName = c.getColumnIndexOrThrow("name")
                val iMethod = c.getColumnIndexOrThrow("method")
                val iUrl = c.getColumnIndexOrThrow("url")
                val iHeaders = c.getColumnIndexOrThrow("headers")
                val iBody = c.getColumnIndexOrThrow("body")
                val iTime = c.getColumnIndexOrThrow("created_at")
                while (c.moveToNext()) {
                    out.add(
                        SavedHttpRequest(
                            c.getLong(iId), c.getString(iName), c.getString(iMethod),
                            c.getString(iUrl),
                            HttpHeaderCodec.decode(c.getString(iHeaders) ?: ""),
                            c.getString(iBody) ?: "",
                            c.getLong(iTime)
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun deleteSaved(id: Long): Boolean = try {
        writableDatabase.delete("http_saved", "id = ?", arrayOf(id.toString())) > 0
    } catch (_: Exception) {
        false
    }
}
