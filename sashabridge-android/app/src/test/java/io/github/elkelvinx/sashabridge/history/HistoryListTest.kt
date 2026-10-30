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

    /** No tracks, no rows. */
    @Test
    fun emptyHistory() {
        assertEquals(emptyList<HistoryRow>(), HistoryList.rows(emptyList(), zone))
    }
}
