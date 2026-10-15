package com.kasidwala.kasidtv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One watch-history entry: a movie or a single episode. */
data class HistoryEntry(
    val key: String,          // "m:<stalkerId>" or "e:<seriesId>:<seasonId>:<episodeId>"
    val title: String,
    val poster: String?,
    val kind: String,         // "movie" or "episode"
    val seriesTitle: String?,
    val seriesId: String?,
    val seasonId: String?,
    val seasonName: String?,
    val episodeId: String?,
    val episodeNum: String?,
    val cmd: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** SQLite: watch history + OMDb metadata cache. */
class DbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "kasidtv.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history(
            key TEXT PRIMARY KEY, title TEXT, poster TEXT, kind TEXT,
            series_title TEXT, series_id TEXT, season_id TEXT, season_name TEXT,
            episode_id TEXT, episode_num TEXT, cmd TEXT,
            position_ms INTEGER, duration_ms INTEGER, updated_at INTEGER)"""
        )
        db.execSQL("CREATE TABLE omdb_cache(key TEXT PRIMARY KEY, json TEXT, updated_at INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    // ------------------------------------------------------------ history

    fun upsertHistory(e: HistoryEntry) {
        val v = ContentValues().apply {
            put("key", e.key); put("title", e.title); put("poster", e.poster)
            put("kind", e.kind); put("series_title", e.seriesTitle)
            put("series_id", e.seriesId); put("season_id", e.seasonId)
            put("season_name", e.seasonName); put("episode_id", e.episodeId)
            put("episode_num", e.episodeNum); put("cmd", e.cmd)
            put("position_ms", e.positionMs); put("duration_ms", e.durationMs)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateProgress(key: String, positionMs: Long, durationMs: Long) {
        val v = ContentValues().apply {
            put("position_ms", positionMs); put("duration_ms", durationMs)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("history", v, "key=?", arrayOf(key))
    }

    fun getHistory(key: String): HistoryEntry? {
        readableDatabase.query("history", null, "key=?", arrayOf(key), null, null, null)
            .use { c -> return if (c.moveToFirst()) row(c) else null }
    }

    /** Most recent first; only items actually started but not finished. */
    fun recentHistory(limit: Int = 30): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        readableDatabase.query(
            "history", null, null, null, null, null, "updated_at DESC", limit.toString()
        ).use { c -> while (c.moveToNext()) out.add(row(c)) }
        return out
    }

    fun clearHistory() = writableDatabase.delete("history", null, null)

    private fun row(c: android.database.Cursor) = HistoryEntry(
        key = c.getString(0), title = c.getString(1), poster = c.getString(2),
        kind = c.getString(3), seriesTitle = c.getString(4), seriesId = c.getString(5),
        seasonId = c.getString(6), seasonName = c.getString(7), episodeId = c.getString(8),
        episodeNum = c.getString(9), cmd = c.getString(10),
        positionMs = c.getLong(11), durationMs = c.getLong(12), updatedAt = c.getLong(13),
    )

    // ------------------------------------------------------------ omdb cache

    fun getOmdb(key: String): String? {
        readableDatabase.query(
            "omdb_cache", arrayOf("json"), "key=?", arrayOf(key), null, null, null
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun putOmdb(key: String, json: String) {
        val v = ContentValues().apply {
            put("key", key); put("json", json); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "omdb_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun clearOmdbCache() = writableDatabase.delete("omdb_cache", null, null)

    companion object {
        @Volatile private var inst: DbHelper? = null
        fun get(c: Context): DbHelper =
            inst ?: synchronized(this) { inst ?: DbHelper(c).also { inst = it } }
    }
}
