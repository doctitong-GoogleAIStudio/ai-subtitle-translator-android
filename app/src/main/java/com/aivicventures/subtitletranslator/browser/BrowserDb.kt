package com.aivicventures.subtitletranslator.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Bookmarks and browsing history of the built-in browser, in their own database file
 * (`browser.db`), so the movie cache's schema is not touched. Stays on the phone; the
 * history can be cleared from the browser menu.
 */
class BrowserDb(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 1) {
    data class Entry(val url: String, val title: String, val at: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks (url TEXT PRIMARY KEY, title TEXT NOT NULL, added INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL, visited INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_visited ON history(visited)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun bookmarks(): List<Entry> = list("SELECT url, title, added FROM bookmarks ORDER BY added DESC")

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title.ifBlank { url }); put("added", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url=?", arrayOf(url))
    }

    fun history(limit: Int = 200): List<Entry> = list("SELECT url, title, visited FROM history ORDER BY visited DESC LIMIT $limit")

    /** One row per address, moved to the top on each visit; at most [MAX_HISTORY] rows kept. */
    fun visit(url: String, title: String) {
        val db = writableDatabase
        db.insertWithOnConflict("history", null, ContentValues().apply {
            put("url", url); put("title", title.ifBlank { url }); put("visited", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM history WHERE url NOT IN (SELECT url FROM history ORDER BY visited DESC LIMIT $MAX_HISTORY)")
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    private fun list(sql: String): List<Entry> = readableDatabase.rawQuery(sql, null).use { c ->
        val out = ArrayList<Entry>()
        while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1), c.getLong(2)))
        out
    }

    companion object {
        const val MAX_HISTORY = 1000

        /** What the address bar typed means: an address, or words to search for. */
        fun toUrl(input: String, searchTemplate: String): String {
            val t = input.trim()
            if (t.isEmpty()) return ""
            val lower = t.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) return t
            if (lower.startsWith("about:")) return t
            val looksLikeHost = ' ' !in t && '.' in t && !t.startsWith(".") && !t.endsWith(".")
            if (looksLikeHost) return "https://$t"
            return searchTemplate.replace("%s", java.net.URLEncoder.encode(t, "UTF-8"))
        }

        /** Search engines offered in the browser menu: name to query address. */
        val SEARCH_ENGINES = linkedMapOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
            "Google" to "https://www.google.com/search?q=%s",
            "Bing" to "https://www.bing.com/search?q=%s",
        )
    }
}
