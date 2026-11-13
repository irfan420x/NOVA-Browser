package com.nova.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nova.browser.web.UrlUtils

data class HistoryEntry(
    val id: Long, val url: String, val title: String,
    val visitedAt: Long, val visitCount: Int
)

data class Bookmark(
    val id: Long, val url: String, val title: String,
    val createdAt: Long, val pinned: Boolean
)

data class SavedTab(val url: String, val title: String, val position: Int, val groupId: Long? = null)

data class DownloadRow(
    val id: Long, val dmId: Long, val url: String, val fileName: String,
    val mimeType: String?, val createdAt: Long
)

/**
 * SQLite storage for history, bookmarks, saved tab sessions and the
 * download list. No Room in v1.0 (no kapt/KSP in the manual pipeline) —
 * plain SQLiteOpenHelper + raw SQL. Every method catches its own errors
 * and returns an empty/false result instead of crashing the browser.
 */
class BrowserDb(context: Context) :
    SQLiteOpenHelper(context, "nova_browser.db", null, 2) {

    private val appContext: Context = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL DEFAULT '',
                visited_at INTEGER NOT NULL,
                visit_count INTEGER NOT NULL DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX idx_history_time ON history(visited_at)")
        db.execSQL(
            """CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE tabs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL,
                group_id INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dm_id INTEGER NOT NULL UNIQUE,
                url TEXT NOT NULL,
                file_name TEXT NOT NULL,
                mime_type TEXT,
                created_at INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: tab groups survive a kill — group_id on the tabs table.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tabs ADD COLUMN group_id INTEGER NOT NULL DEFAULT 0")
        }
    }

    // ---- History ----

    /** Record a visit: insert, or bump the count and time on re-visit.
     *  The check-then-insert runs inside one transaction — two visits to
     *  the same new URL at once used to race: both saw "no row", both
     *  inserted, and the second died on the UNIQUE(url) constraint. */
    fun recordVisit(url: String, title: String) {
        if (url.isBlank()) return
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("url", url)
                    put("title", title)
                    put("visited_at", now)
                }
                val updated = db.update(
                    "history", values, "url = ?",
                    arrayOf(url)
                )
                if (updated == 0) {
                    values.put("visit_count", 1)
                    db.insertOrThrow("history", null, values)
                } else {
                    db.execSQL(
                        "UPDATE history SET visit_count = visit_count + 1 WHERE url = ?",
                        arrayOf(url)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            ErrorLog.log(appContext, "BrowserDb", "recordVisit failed for $url", e)
        }
    }

    fun searchHistory(query: String, limit: Int = 100): List<HistoryEntry> {
        return try {
            val db = readableDatabase
            // Escape LIKE wildcards so a query containing % or _ matches
            // literally instead of matching far more rows than asked for.
            val (selection, args) = if (query.isBlank()) {
                null to null
            } else {
                val escaped = "%${UrlUtils.escapeLike(query)}%"
                "url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\'" to arrayOf(escaped, escaped)
            }
            val out = ArrayList<HistoryEntry>()
            db.query(
                "history", null, selection, args, null, null,
                "visited_at DESC", limit.toString()
            ).use { c ->
                val iId = c.getColumnIndexOrThrow("id")
                val iUrl = c.getColumnIndexOrThrow("url")
                val iTitle = c.getColumnIndexOrThrow("title")
                val iTime = c.getColumnIndexOrThrow("visited_at")
                val iCount = c.getColumnIndexOrThrow("visit_count")
                while (c.moveToNext()) {
                    out.add(
                        HistoryEntry(
                            c.getLong(iId), c.getString(iUrl), c.getString(iTitle),
                            c.getLong(iTime), c.getInt(iCount)
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun topSites(limit: Int = 8): List<HistoryEntry> {
        return try {
            val db = readableDatabase
            val out = ArrayList<HistoryEntry>()
            db.query(
                "history", null, null, null, null, null,
                "visit_count DESC, visited_at DESC", limit.toString()
            ).use { c ->
                val iId = c.getColumnIndexOrThrow("id")
                val iUrl = c.getColumnIndexOrThrow("url")
                val iTitle = c.getColumnIndexOrThrow("title")
                val iTime = c.getColumnIndexOrThrow("visited_at")
                val iCount = c.getColumnIndexOrThrow("visit_count")
                while (c.moveToNext()) {
                    out.add(
                        HistoryEntry(
                            c.getLong(iId), c.getString(iUrl), c.getString(iTitle),
                            c.getLong(iTime), c.getInt(iCount)
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
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) > 0
    } catch (_: Exception) {
        false
    }

    fun clearHistory() {
        try { writableDatabase.delete("history", null, null) } catch (_: Exception) { }
    }

    // ---- Bookmarks ----

    fun addBookmark(url: String, title: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put("url", url)
                put("title", title.ifBlank { url })
                put("created_at", System.currentTimeMillis())
                put("pinned", 0)
            }
            writableDatabase.insertWithOnConflict(
                "bookmarks", null, values, SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
        } catch (_: Exception) {
            false
        }
    }

    fun isBookmarked(url: String): Boolean {
        return try {
            readableDatabase.query(
                "bookmarks", arrayOf("id"), "url = ?", arrayOf(url),
                null, null, null, "1"
            ).use { it.count > 0 }
        } catch (_: Exception) {
            false
        }
    }

    fun removeBookmark(url: String): Boolean = try {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url)) > 0
    } catch (_: Exception) {
        false
    }

    fun listBookmarks(): List<Bookmark> {
        return try {
            val out = ArrayList<Bookmark>()
            readableDatabase.query(
                "bookmarks", null, null, null, null, null,
                "pinned DESC, created_at DESC"
            ).use { c ->
                val iId = c.getColumnIndexOrThrow("id")
                val iUrl = c.getColumnIndexOrThrow("url")
                val iTitle = c.getColumnIndexOrThrow("title")
                val iTime = c.getColumnIndexOrThrow("created_at")
                val iPinned = c.getColumnIndexOrThrow("pinned")
                while (c.moveToNext()) {
                    out.add(
                        Bookmark(
                            c.getLong(iId), c.getString(iUrl), c.getString(iTitle),
                            c.getLong(iTime), c.getInt(iPinned) == 1
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setPinned(url: String, pinned: Boolean): Boolean = try {
        val values = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
        writableDatabase.update("bookmarks", values, "url = ?", arrayOf(url)) > 0
    } catch (_: Exception) {
        false
    }

    // ---- Saved tab sessions (normal tabs only — incognito is never saved) ----

    fun saveTabs(tabs: List<SavedTab>) {
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("tabs", null, null)
                tabs.forEachIndexed { index, tab ->
                    val values = ContentValues().apply {
                        put("url", tab.url)
                        put("title", tab.title)
                        put("position", index)
                        put("group_id", tab.groupId ?: 0L)
                    }
                    db.insert("tabs", null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) { }
    }

    fun loadTabs(): List<SavedTab> {
        return try {
            val out = ArrayList<SavedTab>()
            readableDatabase.query(
                "tabs", null, null, null, null, null, "position ASC"
            ).use { c ->
                val iUrl = c.getColumnIndexOrThrow("url")
                val iTitle = c.getColumnIndexOrThrow("title")
                val iPos = c.getColumnIndexOrThrow("position")
                // group_id exists from v2; -1 on a pre-upgrade DB is
                // treated as ungrouped (onUpgrade normally adds it first).
                val iGroup = c.getColumnIndex("group_id")
                while (c.moveToNext()) {
                    val groupId = if (iGroup >= 0) {
                        c.getLong(iGroup).takeIf { it != 0L }
                    } else null
                    out.add(SavedTab(c.getString(iUrl), c.getString(iTitle), c.getInt(iPos), groupId))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- Downloads ----

    fun addDownload(dmId: Long, url: String, fileName: String, mimeType: String?): Boolean {
        return try {
            val values = ContentValues().apply {
                put("dm_id", dmId)
                put("url", url)
                put("file_name", fileName)
                put("mime_type", mimeType)
                put("created_at", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict(
                "downloads", null, values, SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
        } catch (_: Exception) {
            false
        }
    }

    fun listDownloads(): List<DownloadRow> {
        return try {
            val out = ArrayList<DownloadRow>()
            readableDatabase.query(
                "downloads", null, null, null, null, null, "created_at DESC"
            ).use { c ->
                val iId = c.getColumnIndexOrThrow("id")
                val iDm = c.getColumnIndexOrThrow("dm_id")
                val iUrl = c.getColumnIndexOrThrow("url")
                val iName = c.getColumnIndexOrThrow("file_name")
                val iMime = c.getColumnIndexOrThrow("mime_type")
                val iTime = c.getColumnIndexOrThrow("created_at")
                while (c.moveToNext()) {
                    out.add(
                        DownloadRow(
                            c.getLong(iId), c.getLong(iDm), c.getString(iUrl),
                            c.getString(iName),
                            if (c.isNull(iMime)) null else c.getString(iMime),
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

    fun removeDownload(dmId: Long): Boolean = try {
        writableDatabase.delete("downloads", "dm_id = ?", arrayOf(dmId.toString())) > 0
    } catch (_: Exception) {
        false
    }
}
