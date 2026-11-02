package io.github.elkelvinx.sashabridge.history

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import java.time.ZoneId

/**
 * Migrations of the history database between its versions.
 *
 * Room runs them inside a transaction when it opens an older file: if one fails, the file
 * stays as it was.
 */
internal object HistoryMigrations {

    /**
     * Version 1, one table `plays` with the texts in every row, becomes the tables of version 2.
     *
     * Done in Kotlin and not in SQL alone: the song keys must be the very ones [SongKeys]
     * gives new plays, and SQLite's `lower()` only knows the 26 English letters. Plays without
     * a title (a browser, for example) are left out; every other play keeps its time, and
     * each song its newest texts and cover.
     */
    val FROM_1_TO_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) = migrateTo2(connection, ZoneId.systemDefault())
    }

    /** [FROM_1_TO_2], with the time zone that decides the months. */
    fun migrateTo2(connection: SQLiteConnection, zone: ZoneId) {
        connection.execSQL("ALTER TABLE plays RENAME TO plays_v1")
        VERSION_2_TABLES.forEach(connection::execSQL)
        val songs = LinkedHashMap<Pair<String, String>, MigratedSong>()
        val plays = ArrayList<Pair<MigratedSong, Long>>()
        connection.prepare("SELECT played_at, source, title, artist, album, media_id, cover_key FROM plays_v1 ORDER BY played_at")
            .use { rows ->
                while (rows.step()) {
                    val playedAt = rows.getLong(0)
                    val source = rows.getText(1)
                    val title = rows.text(2)
                    val key = SongKeys.of(source, rows.text(5), title, rows.text(3)) ?: continue
                    val song = songs.getOrPut(source to key) { MigratedSong(source, key, firstPlayedAt = playedAt) }
                    song.update(rows, title.orEmpty(), playedAt)
                    plays += song to playedAt
                }
            }
        insertSongs(connection, songs.values)
        insertPlays(connection, plays, zone)
        connection.execSQL("DROP TABLE plays_v1")
    }

    /** Writes [songs], giving each its new id. */
    private fun insertSongs(connection: SQLiteConnection, songs: Collection<MigratedSong>) {
        connection.prepare(
            """
            INSERT INTO songs (source, song_key, title, artist, album, kind, duration_ms, art_url, cover_key, media_id,
                               first_played_at, last_played_at)
            VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { insert ->
            for (song in songs) {
                insert.bindText(1, song.source)
                insert.bindText(2, song.key)
                insert.bindText(3, song.title)
                insert.bindTextOrNull(4, song.artist)
                insert.bindTextOrNull(5, song.album)
                insert.bindText(6, AppKinds.songKind(song.source, song.album).name)
                insert.bindTextOrNull(7, song.coverKey)
                insert.bindTextOrNull(8, song.mediaId)
                insert.bindLong(9, song.firstPlayedAt)
                insert.bindLong(10, song.lastPlayedAt)
                insert.step()
                insert.reset()
                song.id = connection.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
            }
        }
    }

    /** Writes the [plays] and adds each to its song's monthly sum. */
    private fun insertPlays(connection: SQLiteConnection, plays: List<Pair<MigratedSong, Long>>, zone: ZoneId) {
        val months = HashMap<Pair<Long, Int>, Int>()
        connection.prepare("INSERT INTO plays (song_id, played_at) VALUES (?, ?)").use { insert ->
            for ((song, playedAt) in plays) {
                insert.bindLong(1, song.id)
                insert.bindLong(2, playedAt)
                insert.step()
                insert.reset()
                months.merge(song.id to HistoryDates.month(playedAt, zone), 1, Int::plus)
            }
        }
        connection.prepare("INSERT INTO song_months (song_id, month, plays) VALUES (?, ?, ?)").use { insert ->
            for ((key, count) in months) {
                insert.bindLong(1, key.first)
                insert.bindLong(2, key.second.toLong())
                insert.bindLong(3, count.toLong())
                insert.step()
                insert.reset()
            }
        }
    }

    /** A song gathered from the version 1 rows, oldest first. */
    private class MigratedSong(val source: String, val key: String, val firstPlayedAt: Long) {
        var id = 0L
        var title = ""
        var artist: String? = null
        var album: String? = null
        var mediaId: String? = null
        var coverKey: String? = null
        var lastPlayedAt = firstPlayedAt

        /** Takes the texts of a newer play, and its cover when it has one. */
        fun update(row: SQLiteStatement, title: String, playedAt: Long) {
            this.title = title
            artist = row.text(3)
            album = row.text(4)
            mediaId = row.text(5)
            coverKey = row.text(6) ?: coverKey
            lastPlayedAt = playedAt
        }
    }

    /** The text of column [index], or `null`. */
    private fun SQLiteStatement.text(index: Int): String? = if (isNull(index)) null else getText(index)

    /** Binds [value], or NULL. */
    private fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    /**
     * The tables of version 2, exactly as Room creates them (its exported schema,
     * `app/schemas/.../2.json`), so its check after the migration passes.
     */
    internal val VERSION_2_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `songs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, " +
            "`song_key` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT, `album` TEXT, `kind` TEXT NOT NULL, " +
            "`duration_ms` INTEGER, `art_url` TEXT, `cover_key` TEXT, `media_id` TEXT, `first_played_at` INTEGER NOT NULL, " +
            "`last_played_at` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_source_song_key` ON `songs` (`source`, `song_key`)",
        "CREATE TABLE IF NOT EXISTS `plays` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `song_id` INTEGER NOT NULL, " +
            "`played_at` INTEGER NOT NULL, FOREIGN KEY(`song_id`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_plays_played_at` ON `plays` (`played_at`)",
        "CREATE INDEX IF NOT EXISTS `index_plays_song_id` ON `plays` (`song_id`)",
        "CREATE TABLE IF NOT EXISTS `song_months` (`song_id` INTEGER NOT NULL, `month` INTEGER NOT NULL, `plays` INTEGER NOT NULL, " +
            "PRIMARY KEY(`song_id`, `month`), FOREIGN KEY(`song_id`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `app_days` (`day` INTEGER NOT NULL, `source` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
            "`played_ms` INTEGER NOT NULL, PRIMARY KEY(`day`, `source`))",
    )
}
