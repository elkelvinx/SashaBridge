package io.github.elkelvinx.sashabridge.history

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The migration of the history database from version 1 (one table of plays) to version 2,
 * on a real SQLite for the JVM, with plays like the ones on the user's phone.
 */
class HistoryMigrationTest {

    private val zone = ZoneId.of("America/Mexico_City")
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(":memory:")

    @After
    fun close() = connection.close()

    /** Creates the version 1 table, as `SQLiteOpenHelper` did, with [rows]. */
    private fun version1(vararg rows: V1Play) {
        connection.execSQL(
            "CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, played_at INTEGER NOT NULL, source TEXT NOT NULL, " +
                "title TEXT, artist TEXT, album TEXT, media_id TEXT, cover_key TEXT)",
        )
        connection.execSQL("CREATE INDEX plays_played_at ON plays (played_at)")
        connection.prepare("INSERT INTO plays (played_at, source, title, artist, album, media_id, cover_key) VALUES (?, ?, ?, ?, ?, ?, ?)")
            .use { insert ->
                for (row in rows) {
                    insert.bindLong(1, row.at.atZone(zone).toInstant().toEpochMilli())
                    insert.bindText(2, row.source)
                    listOf(row.title, row.artist, row.album, row.mediaId, row.coverKey).forEachIndexed { i, value ->
                        if (value == null) insert.bindNull(i + 3) else insert.bindText(i + 3, value)
                    }
                    insert.step()
                    insert.reset()
                }
            }
    }

    private data class V1Play(
        val at: LocalDateTime,
        val source: String,
        val title: String?,
        val artist: String? = null,
        val album: String? = null,
        val mediaId: String? = null,
        val coverKey: String? = null,
    )

    /** The rows of [sql], each as a list of its columns as text. */
    private fun query(sql: String): List<List<String?>> = connection.prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }

    private val ytMusic = "com.google.android.apps.youtube.music"
    private val spotify = "com.spotify.music"

    /**
     * Every play keeps its time; the same song played on several days becomes one song; the
     * monthly sums count every play; plays without a title (Chrome) are left out.
     */
    @Test
    fun playsBecomeSongsAndMonths() {
        version1(
            V1Play(LocalDateTime.of(2026, 10, 30, 16, 45), spotify, "Radio", "Bershy", "Radio", "spotify:track:4o23", "art:a"),
            V1Play(LocalDateTime.of(2026, 10, 30, 17, 0), ytMusic, "Castle Vein", "Heaven Pierce Her", "ULTRAKILL"),
            V1Play(LocalDateTime.of(2026, 10, 31, 1, 0), ytMusic, "castle  vein", "Heaven Pierce Her", "ULTRAKILL", coverKey = "art:b"),
            V1Play(LocalDateTime.of(2026, 10, 31, 2, 0), "com.android.chrome", null),
            V1Play(LocalDateTime.of(2026, 10, 31, 3, 0), spotify, "Radio (Remastered)", "Bershy", "Radio", "spotify:track:4o23"),
            V1Play(LocalDateTime.of(2026, 11, 1, 9, 0), ytMusic, "ループザルーム (con Hatsune Miku)", "rusino", "ループザルーム"),
        )

        HistoryMigrations.migrateTo2(connection, zone)

        assertEquals(listOf(listOf("5")), query("SELECT COUNT(*) FROM plays"))
        assertEquals(
            listOf(
                listOf(spotify, "spotify:track:4o23", "Radio (Remastered)", "art:a"),
                listOf(ytMusic, "castle vein|heaven pierce her", "castle  vein", "art:b"),
                listOf(ytMusic, "ループザルーム (con hatsune miku)|rusino", "ループザルーム (con Hatsune Miku)", null),
            ),
            query("SELECT source, song_key, title, cover_key FROM songs ORDER BY id"),
        )
        assertEquals(
            listOf(listOf("1", "202610", "2"), listOf("2", "202610", "2"), listOf("3", "202611", "1")),
            query("SELECT song_id, month, plays FROM song_months ORDER BY song_id, month"),
        )
        assertEquals(listOf(listOf("2")), query("SELECT COUNT(*) FROM plays WHERE song_id = 2"))
        assertFalse(query("SELECT name FROM sqlite_master WHERE type = 'table'").flatten().contains("plays_v1"))
    }

    /** A YouTube Music upload without album is a video; a track with album is a song. */
    @Test
    fun youTubeMusicUploadsBecomeVideos() {
        version1(
            V1Play(LocalDateTime.of(2026, 10, 31, 4, 0), ytMusic, "Goodbye Joe", "JH", album = ""),
            V1Play(LocalDateTime.of(2026, 10, 31, 5, 0), ytMusic, "KICK BACK", "Kenshi Yonezu", "KICK BACK"),
        )

        HistoryMigrations.migrateTo2(connection, zone)

        assertEquals(listOf(listOf("Goodbye Joe", "VIDEO"), listOf("KICK BACK", "SONG")), query("SELECT title, kind FROM songs ORDER BY id"))
    }

    /** An empty history migrates to empty tables. */
    @Test
    fun emptyHistoryMigrates() {
        version1()

        HistoryMigrations.migrateTo2(connection, zone)

        assertEquals(listOf(listOf("0", "0", "0")), query("SELECT (SELECT COUNT(*) FROM songs), (SELECT COUNT(*) FROM plays), (SELECT COUNT(*) FROM song_months)"))
    }

    /**
     * The tables the migration creates are exactly the ones Room expects (its exported schema),
     * or Room would refuse the migrated file.
     */
    @Test
    fun migrationTablesMatchRoomSchema() {
        val schema = File("schemas/io.github.elkelvinx.sashabridge.history.HistoryDatabase/2.json").readText()
        val expected = Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"").findAll(schema)
            .map { it.groupValues[1].replace("\\\"", "\"") }
            .toList()
        val tables = Regex("\"tableName\": \"([a-z_]+)\"").findAll(schema).map { it.groupValues[1] }.toList()
        // Each statement names its table as ${TABLE_NAME}; the indices follow their table.
        var table = ""
        val resolved = expected.map { sql ->
            if (sql.startsWith("CREATE TABLE")) table = tables[expected.filter { it.startsWith("CREATE TABLE") }.indexOf(sql)]
            sql.replace("\${TABLE_NAME}", table)
        }

        assertEquals(resolved.toSet(), HistoryMigrations.VERSION_2_TABLES.toSet())
    }
}
