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

    /** Runs of the same song keep only the newest; the same song later on is kept. */
    @Test
    fun repeatsInARowAreOneListening() {
        fun song(id: Long, title: String) = entry(id, LocalDateTime.of(2026, 10, 30, 12, id.toInt())).copy(title = title)
        val entries = listOf(
            song(6, "Run Rabbit"),
            song(5, "Run Rabbit"),
            song(4, "Run Rabbit"),
            song(3, "Snake"),
            song(2, "Run Rabbit"),
            song(1, "Snake"),
        )

        assertEquals(listOf(5L, 4L), HistoryList.repeats(entries))
    }

    /** The same title by another artist or in another player is another song. */
    @Test
    fun sameTitleElsewhereIsAnotherSong() {
        val spotify = entry(2, LocalDateTime.of(2026, 10, 30, 12, 2))
        val youTubeMusic = entry(1, LocalDateTime.of(2026, 10, 30, 12, 1)).copy(source = "com.google.android.apps.youtube.music")

        assertEquals(emptyList<Long>(), HistoryList.repeats(listOf(spotify, youTubeMusic)))
    }

    /** No tracks, no rows. */
    @Test
    fun emptyHistory() {
        assertEquals(emptyList<HistoryRow>(), HistoryList.rows(emptyList(), zone))
    }
}
