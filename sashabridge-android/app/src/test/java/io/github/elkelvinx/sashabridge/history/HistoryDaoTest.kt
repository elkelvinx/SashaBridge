package io.github.elkelvinx.sashabridge.history

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The queries of the history database, on a Room database in memory over a SQLite for the
 * JVM: the statistics count every play, and outlive the plays the history deletes.
 */
class HistoryDaoTest {

    private val database = Room.inMemoryDatabaseBuilder(ContextWrapper(null), HistoryDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val dao = database.dao()

    @After
    fun close() = database.close()

    private fun song(key: String, artist: String, source: String = "com.spotify.music", cover: String? = null) = SongEntity(
        source = source,
        songKey = key,
        title = key,
        artist = artist,
        album = "A",
        kind = SongKind.SONG,
        durationMs = 200_000,
        artUrl = null,
        coverKey = cover,
        mediaId = null,
        firstPlayedAt = 0,
        lastPlayedAt = 0,
    )

    /** Adds plays of [songId] at [times], each also to its month, as the repository does. */
    private suspend fun play(songId: Long, vararg times: Long) {
        for (at in times) {
            dao.insert(PlayEntity(songId = songId, playedAt = at))
            if (dao.addToMonth(songId, MONTH) == 0) dao.insert(SongMonthEntity(songId, MONTH, 1))
        }
    }

    /** The most played songs and artists first, and the totals of the period. */
    @Test
    fun rankingsAndTotals() = runBlocking {
        val radio = dao.insert(song("radio", "Bershy"))
        val castle = dao.insert(song("castle", "Heaven Pierce Her"))
        val divine = dao.insert(song("divine", "Heaven Pierce Her"))
        play(radio, 100, 200)
        play(castle, 300, 400, 500)
        play(divine, 600)

        assertEquals(listOf("castle" to 3, "radio" to 2, "divine" to 1), dao.topSongsSince(0, 10).map { it.song.songKey to it.plays })
        assertEquals(listOf("Heaven Pierce Her" to 4, "Bershy" to 2), dao.topArtistsSince(0, 5).map { it.artist to it.plays })
        assertEquals(PlayTotals(plays = 6, songs = 3, artists = 2), dao.totalsSince(0))
        assertEquals(PlayTotals(plays = 2, songs = 2, artists = 1), dao.totalsSince(450))
    }

    /** Deleting old plays keeps the monthly sums, so "all time" still counts them. */
    @Test
    fun monthlySumsOutliveThePlays() = runBlocking {
        val radio = dao.insert(song("radio", "Bershy"))
        play(radio, 100, 200, 300)

        dao.deletePlaysBefore(250)

        assertEquals(1, dao.totalsSince(0).plays)
        assertEquals(listOf("radio" to 3), dao.topSongsFromMonth(0, 10).map { it.song.songKey to it.plays })
        assertEquals(PlayTotals(plays = 3, songs = 1, artists = 1), dao.totalsFromMonth(MONTH))
        assertEquals(PlayTotals(plays = 0, songs = 0, artists = 0), dao.totalsFromMonth(MONTH + 1))
    }

    /** Time adds up per app and day; the chart of days only counts music. */
    @Test
    fun timePerAppAndDay() = runBlocking {
        suspend fun add(day: Int, source: String, kind: AppKind, ms: Long) {
            if (dao.addToDay(day, source, ms) == 0) dao.insert(AppDayEntity(day, source, kind, ms))
        }
        add(20261030, "com.spotify.music", AppKind.MUSIC, 60_000)
        add(20261031, "com.spotify.music", AppKind.MUSIC, 30_000)
        add(20261031, "com.spotify.music", AppKind.MUSIC, 30_000)
        add(20261031, "com.google.android.youtube", AppKind.VIDEO, 500_000)

        assertEquals(
            listOf(AppTime("com.google.android.youtube", AppKind.VIDEO, 500_000), AppTime("com.spotify.music", AppKind.MUSIC, 120_000)),
            dao.appTimesFromDay(20261030),
        )
        assertEquals(listOf(DayTime(20261030, 60_000), DayTime(20261031, 60_000)), dao.musicTimePerDay(0))
    }

    /** The plays of the history screen, newest first, and the search by title or artist. */
    @Test
    fun historyAndSearch() = runBlocking {
        val radio = dao.insert(song("radio", "Bershy"))
        val castle = dao.insert(song("castle", "Heaven 100% Pierce"))
        play(radio, 100)
        play(castle, 200)

        assertEquals(listOf(castle, radio), dao.playsSince(0).map { it.song.id })
        assertEquals(listOf(castle), dao.playsSince(0, HistoryRepository.likePattern("100%")).map { it.song.id })
        assertEquals(listOf(radio), dao.playsSince(0, HistoryRepository.likePattern("bers")).map { it.song.id })
        assertEquals(castle, dao.latestPlay()?.song?.id)
    }

    /** Kept covers count while their song has plays; once it has none, they are forgotten. */
    @Test
    fun coversFollowThePlays() = runBlocking {
        val radio = dao.insert(song("radio", "Bershy", cover = "art:a"))
        val castle = dao.insert(song("castle", "HPH", cover = "art:b"))
        play(radio, 100)
        play(castle, 300)

        dao.deletePlaysBefore(200)

        assertEquals(listOf("art:b"), dao.coverKeysInUse())
        assertEquals(1, dao.forgetUnusedCovers())
        assertEquals(null, dao.song("com.spotify.music", "radio")?.coverKey)
    }

    private companion object {
        const val MONTH = 202610
    }
}
