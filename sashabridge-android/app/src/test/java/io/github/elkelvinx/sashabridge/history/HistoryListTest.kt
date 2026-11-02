package io.github.elkelvinx.sashabridge.history

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** The history screen groups the tracks by day. */
class HistoryListTest {

    private val zone = ZoneId.of("America/Mexico_City")

    private fun entry(id: Long, at: LocalDateTime) = HistoryEntry(
        id = id,
        playedAtMs = at.atZone(zone).toInstant().toEpochMilli(),
        source = "com.spotify.music",
        title = "T$id",
        artist = "A",
        album = null,
        mediaId = null,
        coverKey = null,
    )

    /** One heading per day, before its tracks, newest day first. */
    @Test
    fun oneHeadingPerDay() {
        val late = entry(3, LocalDateTime.of(2026, 10, 30, 23, 50))
        val morning = entry(2, LocalDateTime.of(2026, 10, 30, 8, 0))
        val yesterday = entry(1, LocalDateTime.of(2026, 10, 29, 23, 59))

        val rows = HistoryList.rows(listOf(late, morning, yesterday), zone)

        assertEquals(
            listOf(
                HistoryRow.Day(LocalDate.of(2026, 10, 30)),
                HistoryRow.Play(late),
                HistoryRow.Play(morning),
                HistoryRow.Day(LocalDate.of(2026, 10, 29)),
                HistoryRow.Play(yesterday),
            ),
            rows,
        )
    }

    /** The same song several times in a row is one row with its count; later on, another row. */
    @Test
    fun repeatsInARowFoldIntoOneRow() {
        fun song(id: Long, title: String) = entry(id, LocalDateTime.of(2026, 10, 30, 12, id.toInt())).copy(title = title)
        val entries = listOf(
            song(6, "Run Rabbit"),
            song(5, "Run Rabbit"),
            song(4, "Run Rabbit"),
            song(3, "Snake"),
            song(2, "Run Rabbit"),
        )

        val plays = HistoryList.rows(entries, zone).filterIsInstance<HistoryRow.Play>()

        assertEquals(listOf(6L to 3, 3L to 1, 2L to 1), plays.map { it.entry.id to it.count })
    }

    /** Plays of the same song on two days are two rows, one under each day. */
    @Test
    fun repeatsDoNotFoldAcrossDays() {
        val today = entry(2, LocalDateTime.of(2026, 10, 30, 0, 5))
        val yesterday = entry(1, LocalDateTime.of(2026, 10, 29, 23, 55)).copy(title = today.title)

        val rows = HistoryList.rows(listOf(today, yesterday), zone)

        assertEquals(4, rows.size)
    }

    /** The same song row folds even if its texts changed; another title by another player does not. */
    @Test
    fun foldingFollowsTheSong() {
        val newer = entry(2, LocalDateTime.of(2026, 10, 30, 12, 2)).copy(songId = 9, title = "Radio (Remastered)")
        val older = entry(1, LocalDateTime.of(2026, 10, 30, 12, 1)).copy(songId = 9, title = "Radio")
        val elsewhere = entry(3, LocalDateTime.of(2026, 10, 30, 12, 3)).copy(source = "com.google.android.apps.youtube.music", title = "Radio (Remastered)")

        assertEquals(listOf(2), HistoryList.rows(listOf(newer, older), zone).filterIsInstance<HistoryRow.Play>().map { it.count })
        assertEquals(2, HistoryList.rows(listOf(elsewhere, newer), zone).filterIsInstance<HistoryRow.Play>().size)
    }

    /** No tracks, no rows. */
    @Test
    fun emptyHistory() {
        assertEquals(emptyList<HistoryRow>(), HistoryList.rows(emptyList(), zone))
    }
}
