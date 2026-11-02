package io.github.elkelvinx.sashabridge.history

import android.content.pm.ApplicationInfo
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure rules of the history: song keys, kinds of apps, time per app and dates. */
class HistoryRulesTest {

    private val spotify = "com.spotify.music"
    private val ytMusic = "com.google.android.apps.youtube.music"

    /** A song keeps an image of its cover only while it has no web address for it. */
    @Test
    fun keptCovers() {
        val url = "https://i.scdn.co/image/ab67616d00001e02"
        assertNull(HistoryRepository.keptCover(url, newKey = null, oldKey = "art:old"))
        assertNull(HistoryRepository.keptCover(url, newKey = "art:new", oldKey = "art:old"))
        assertEquals("art:new", HistoryRepository.keptCover(null, newKey = "art:new", oldKey = "art:old"))
        assertEquals("art:old", HistoryRepository.keptCover(null, newKey = null, oldKey = "art:old"))
        assertNull(HistoryRepository.keptCover(null, newKey = null, oldKey = null))
    }

    /** Spotify's id is the key, whatever the title; without it, the title and the artist. */
    @Test
    fun songKeys() {
        assertEquals("spotify:track:4o23", SongKeys.of(spotify, "spotify:track:4o23", "Radio", "Bershy"))
        assertEquals("spotify:track:4o23", SongKeys.of(spotify, "spotify:track:4o23", "Radio (Remastered)", "Bershy"))
        assertEquals("radio|bershy", SongKeys.of(spotify, null, "Radio", "Bershy"))
        assertEquals("radio|bershy", SongKeys.of(ytMusic, "anything", "Radio", "Bershy"))
        assertEquals("radio|", SongKeys.of(ytMusic, null, "Radio", null))
        assertNull(SongKeys.of(ytMusic, null, "  ", "Bershy"))
        assertNull(SongKeys.of("com.android.chrome", null, null, null))
    }

    /** Case, spaces and width variants do not make another song; other scripts stay as they are. */
    @Test
    fun songKeysNormalize() {
        assertEquals(SongKeys.of(ytMusic, null, "Castle Vein", "Heaven Pierce Her"), SongKeys.of(ytMusic, null, "  castle\tVEIN ", "heaven  pierce her"))
        assertEquals(SongKeys.of(ytMusic, null, "ÉXITO", "Ñu"), SongKeys.of(ytMusic, null, "éxito", "ñu"))
        // Full-width letters, as some Japanese uploads write them, are the ordinary ones.
        assertEquals(SongKeys.of(ytMusic, null, "KICK BACK", "米津玄師"), SongKeys.of(ytMusic, null, "ＫＩＣＫ ＢＡＣＫ", "米津玄師"))
        assertEquals("ループザルーム|rusino", SongKeys.of(ytMusic, null, "ループザルーム", "rusino"))
    }

    /** Known apps by name; the rest by the category they declare, or music if none. */
    @Test
    fun appKinds() {
        assertEquals(AppKind.MUSIC, AppKinds.of(spotify))
        assertEquals(AppKind.MUSIC, AppKinds.of(ytMusic))
        assertEquals(AppKind.VIDEO, AppKinds.of("com.google.android.youtube"))
        assertEquals(AppKind.OTHER, AppKinds.of("com.android.chrome"))
        assertEquals(AppKind.VIDEO, AppKinds.of("com.example.tv", ApplicationInfo.CATEGORY_VIDEO))
        assertEquals(AppKind.MUSIC, AppKinds.of("com.example.radio", ApplicationInfo.CATEGORY_AUDIO))
        assertEquals(AppKind.MUSIC, AppKinds.of("com.example.player", null))
        // A known app keeps its kind whatever it declares.
        assertEquals(AppKind.VIDEO, AppKinds.of("com.google.android.youtube", ApplicationInfo.CATEGORY_AUDIO))
    }

    /** Only YouTube Music's tracks without album are videos. */
    @Test
    fun songKinds() {
        assertEquals(SongKind.VIDEO, AppKinds.songKind(ytMusic, ""))
        assertEquals(SongKind.VIDEO, AppKinds.songKind(ytMusic, null))
        assertEquals(SongKind.SONG, AppKinds.songKind(ytMusic, "KICK BACK"))
        assertEquals(SongKind.SONG, AppKinds.songKind(spotify, null))
    }

    private fun snapshot(source: String, playing: Boolean) = MediaSnapshot(
        trackId = "t",
        source = source,
        title = "T",
        artist = null,
        playbackContext = null,
        album = null,
        durationMs = null,
        isPlaying = playing,
        reportedPositionMs = 0,
        positionUpdateTimeMs = 0,
        playbackSpeed = 1f,
        shuffle = null,
        art = null,
    )

    /** Playing time goes to its app; paused time and nothing playing count nothing. */
    @Test
    fun usageMeter() {
        val meter = UsageMeter()
        assertNull(meter.update(snapshot(spotify, playing = true), nowMs = 0))
        assertEquals(UsageMeter.Usage(spotify, 10_000), meter.tick(nowMs = 10_000))
        assertEquals(UsageMeter.Usage(spotify, 5_000), meter.update(snapshot("com.google.android.youtube", playing = true), nowMs = 15_000))
        assertEquals(UsageMeter.Usage("com.google.android.youtube", 20_000), meter.update(snapshot(spotify, playing = false), nowMs = 35_000))
        assertNull(meter.tick(nowMs = 90_000))
        assertNull(meter.update(null, nowMs = 100_000))
    }

    /** Days and months as numbers, both ways. */
    @Test
    fun dates() {
        val date = LocalDate.of(2026, 10, 31)
        assertEquals(20261031, HistoryDates.day(date))
        assertEquals(date, HistoryDates.date(20261031))
        assertEquals(202610, HistoryDates.month(java.time.YearMonth.of(2026, 10)))
    }

    /** First day and month of each period, and the 30 days of the chart. */
    @Test
    fun statsPeriods() {
        val today = LocalDate.of(2026, 10, 31)
        assertEquals(20261025, StatsDates.firstDay(StatsPeriod.WEEK, today))
        assertEquals(20261002, StatsDates.firstDay(StatsPeriod.DAYS_30, today))
        assertEquals(20261001, StatsDates.firstDay(StatsPeriod.THIS_MONTH, today))
        assertEquals(202610, StatsDates.firstMonth(StatsPeriod.THIS_MONTH, today))
        assertEquals(0, StatsDates.firstMonth(StatsPeriod.ALL, today))

        val days = StatsDates.chartDays(mapOf(20261031 to 60_000L, 20261002 to 5_000L), today)
        assertEquals(30, days.size)
        assertEquals(DayUsage(LocalDate.of(2026, 10, 2), 5_000), days.first())
        assertEquals(DayUsage(today, 60_000), days.last())
        assertEquals(0L, days[1].playedMs)
    }
}
