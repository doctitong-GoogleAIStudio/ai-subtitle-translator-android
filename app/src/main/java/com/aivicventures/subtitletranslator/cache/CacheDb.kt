package com.aivicventures.subtitletranslator.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aivicventures.subtitletranslator.subtitles.Subtitle

data class MovieRecord(val fingerprint: String, val uri: String, val name: String, val duration: Double, val lastPosition: Double, val updated: Long)

/**
 * SQLite cache, same idea as the Windows app's cache.db: a movie (by content fingerprint)
 * is never processed twice for the same profile (model + task + source language).
 * Subtitles that were shown are flagged and never rewritten.
 */
class CacheDb(context: Context) : SQLiteOpenHelper(context, "cache.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE movies (fingerprint TEXT PRIMARY KEY, uri TEXT, name TEXT, duration REAL, last_position REAL DEFAULT 0, updated INTEGER)")
        db.execSQL("CREATE TABLE profiles (id INTEGER PRIMARY KEY AUTOINCREMENT, movie_fp TEXT, model TEXT, task TEXT, language TEXT, UNIQUE(movie_fp, model, task, language))")
        db.execSQL("CREATE TABLE segments (id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id INTEGER, start REAL, end_ REAL, english TEXT, source TEXT, language TEXT, shown INTEGER DEFAULT 0, avg_logprob REAL)")
        db.execSQL("CREATE INDEX segments_profile ON segments(profile_id, start)")
        db.execSQL("CREATE TABLE coverage (profile_id INTEGER, start REAL, end_ REAL)")
        db.execSQL("CREATE INDEX coverage_profile ON coverage(profile_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertMovie(fp: String, uri: String, name: String, duration: Double) {
        val v = ContentValues().apply {
            put("fingerprint", fp); put("uri", uri); put("name", name); put("duration", duration)
            put("updated", System.currentTimeMillis())
        }
        val db = writableDatabase
        if (db.update("movies", v, "fingerprint=?", arrayOf(fp)) == 0) db.insert("movies", null, v)
    }

    fun savePosition(fp: String, position: Double) {
        writableDatabase.update("movies", ContentValues().apply {
            put("last_position", position); put("updated", System.currentTimeMillis())
        }, "fingerprint=?", arrayOf(fp))
    }

    fun movie(fp: String): MovieRecord? = recentMovies(1000).firstOrNull { it.fingerprint == fp }

    fun recentMovies(limit: Int = 20): List<MovieRecord> =
        readableDatabase.rawQuery("SELECT fingerprint, uri, name, duration, last_position, updated FROM movies ORDER BY updated DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(MovieRecord(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(4), c.getLong(5))) }
        }

    fun profileId(fp: String, model: String, task: String, language: String): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM profiles WHERE movie_fp=? AND model=? AND task=? AND language=?", arrayOf(fp, model, task, language)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return db.insert("profiles", null, ContentValues().apply {
            put("movie_fp", fp); put("model", model); put("task", task); put("language", language)
        })
    }

    fun segments(profileId: Long): List<Subtitle> =
        readableDatabase.rawQuery("SELECT id, start, end_, english, source, language, shown FROM segments WHERE profile_id=? ORDER BY start", arrayOf(profileId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Subtitle(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3), c.getString(4) ?: "", c.getString(5) ?: "", c.getInt(6) != 0)) }
        }

    fun coverage(profileId: Long): List<Pair<Double, Double>> =
        readableDatabase.rawQuery("SELECT start, end_ FROM coverage WHERE profile_id=? ORDER BY start", arrayOf(profileId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getDouble(1)) }
        }

    /** Stores one finished chunk atomically: its new lines and the updated coverage. */
    fun saveChunk(profileId: Long, newSubs: List<Subtitle>, avgLogprobs: List<Float>, coverage: List<Pair<Double, Double>>): List<Subtitle> {
        val db = writableDatabase
        val out = mutableListOf<Subtitle>()
        db.beginTransaction()
        try {
            newSubs.forEachIndexed { i, s ->
                val id = db.insert("segments", null, ContentValues().apply {
                    put("profile_id", profileId); put("start", s.start); put("end_", s.end)
                    put("english", s.english); put("source", s.source); put("language", s.language)
                    put("avg_logprob", avgLogprobs.getOrNull(i)?.toDouble())
                })
                out.add(s.copy(id = id))
            }
            db.delete("coverage", "profile_id=?", arrayOf(profileId.toString()))
            coverage.forEach { (a, b) ->
                db.insert("coverage", null, ContentValues().apply { put("profile_id", profileId); put("start", a); put("end_", b) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return out
    }

    fun markShown(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("UPDATE segments SET shown=1 WHERE id IN (${ids.joinToString(",")})")
    }

    fun clearMovie(fp: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM segments WHERE profile_id IN (SELECT id FROM profiles WHERE movie_fp=?)", arrayOf(fp))
        db.execSQL("DELETE FROM coverage WHERE profile_id IN (SELECT id FROM profiles WHERE movie_fp=?)", arrayOf(fp))
    }

    /** Takes a movie off the recent list; with [deleteSubtitles] its cached subtitles go too
     *  (otherwise they are reused if the movie is opened again). */
    fun removeMovie(fp: String, deleteSubtitles: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (deleteSubtitles) {
                clearMovie(fp)
                db.delete("profiles", "movie_fp=?", arrayOf(fp))
            }
            db.delete("movies", "fingerprint=?", arrayOf(fp))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Seconds of the movie with subtitles, for its most complete pipeline / audio track. */
    fun coveredSeconds(fp: String): Double =
        readableDatabase.rawQuery(
            "SELECT MAX(s) FROM (SELECT SUM(c.end_ - c.start) AS s FROM coverage c JOIN profiles p ON p.id = c.profile_id WHERE p.movie_fp=? GROUP BY p.id)",
            arrayOf(fp),
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else 0.0 }
}
