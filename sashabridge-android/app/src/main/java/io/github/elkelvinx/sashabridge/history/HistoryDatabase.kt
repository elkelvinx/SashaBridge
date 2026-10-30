package io.github.elkelvinx.sashabridge.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The history's SQLite database: one row per track listened to, indexed by time, so that
 * the newest tracks, a search and the deletion of old ones stay quick even with the
 * thousands of rows of 50 days.
 *
 * SQLite serializes the calls; any thread except the main one.
 */
internal class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $PLAYED_AT INTEGER NOT NULL,
                $SOURCE TEXT NOT NULL,
                $TITLE TEXT,
                $ARTIST TEXT,
                $ALBUM TEXT,
                $MEDIA_ID TEXT,
                $COVER_KEY TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ${TABLE}_$PLAYED_AT ON $TABLE ($PLAYED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first one; later versions migrate here.
    }

    /**
     * Adds a track.
     *
     * @return The new row id.
     */
    fun insert(
        playedAtMs: Long,
        source: String,
        title: String?,
        artist: String?,
        album: String?,
        mediaId: String?,
        coverKey: String?,
    ): Long {
        val values = ContentValues().apply {
            put(PLAYED_AT, playedAtMs)
            put(SOURCE, source)
            put(TITLE, title)
            put(ARTIST, artist)
            put(ALBUM, album)
            put(MEDIA_ID, mediaId)
            put(COVER_KEY, coverKey)
        }
        return writableDatabase.insert(TABLE, null, values)
    }

    /**
     * The tracks listened to since [sinceMs], newest first.
     *
     * @param search Words to find in the title or the artist, or `null` for every track.
     */
    fun since(sinceMs: Long, search: String?): List<HistoryEntry> {
        val text = search?.trim()?.takeIf { it.isNotEmpty() }
        val where = StringBuilder("$PLAYED_AT >= ?")
        val args = mutableListOf(sinceMs.toString())
        if (text != null) {
            val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(" AND ($TITLE LIKE ? ESCAPE '\\' OR $ARTIST LIKE ? ESCAPE '\\')")
            args += pattern
            args += pattern
        }
        return readableDatabase.query(TABLE, null, where.toString(), args.toTypedArray(), null, null, "$PLAYED_AT DESC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(entryOf(cursor)) } }
    }

    /** Deletes the tracks listened to before [cutoffMs]; returns how many. */
    fun deleteBefore(cutoffMs: Long): Int = writableDatabase.delete(TABLE, "$PLAYED_AT < ?", arrayOf(cutoffMs.toString()))

    /** Keys of the covers the history still names. */
    fun coverKeys(): Set<String> =
        readableDatabase.query(true, TABLE, arrayOf(COVER_KEY), "$COVER_KEY IS NOT NULL", null, null, null, null, null)
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** The track at the cursor's row. */
    private fun entryOf(cursor: Cursor): HistoryEntry {
        fun text(column: String): String? = cursor.getColumnIndexOrThrow(column).let { if (cursor.isNull(it)) null else cursor.getString(it) }
        return HistoryEntry(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(ID)),
            playedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow(PLAYED_AT)),
            source = cursor.getString(cursor.getColumnIndexOrThrow(SOURCE)),
            title = text(TITLE),
            artist = text(ARTIST),
            album = text(ALBUM),
            mediaId = text(MEDIA_ID),
            coverKey = text(COVER_KEY),
        )
    }

    private companion object {
        const val NAME = "history.db"
        const val VERSION = 1
        const val TABLE = "plays"
        const val ID = "id"
        const val PLAYED_AT = "played_at"
        const val SOURCE = "source"
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val MEDIA_ID = "media_id"
        const val COVER_KEY = "cover_key"
    }
}
