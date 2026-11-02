package io.github.elkelvinx.sashabridge.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Every query of the history database. Room checks each one against the tables when the app
 * is built, and runs them off the main thread.
 *
 * The statistics read two kinds of tables: the plays of the last 30 days for the periods
 * inside them, and the monthly sums for longer ones, which outlive the plays.
 */
@Dao
internal interface HistoryDao {

    // Songs and plays.

    @Query("SELECT * FROM songs WHERE source = :source AND song_key = :key")
    suspend fun song(source: String, key: String): SongEntity?

    @Insert
    suspend fun insert(song: SongEntity): Long

    @Update
    suspend fun update(song: SongEntity)

    @Insert
    suspend fun insert(play: PlayEntity): Long

    /** Adds one play to the month's sum of a song; returns 0 if the month has no row yet. */
    @Query("UPDATE song_months SET plays = plays + 1 WHERE song_id = :songId AND month = :month")
    suspend fun addToMonth(songId: Long, month: Int): Int

    @Insert
    suspend fun insert(month: SongMonthEntity)

    /** Adds [ms] to the day's time of an app; returns 0 if the day has no row yet. */
    @Query("UPDATE app_days SET played_ms = played_ms + :ms WHERE day = :day AND source = :source")
    suspend fun addToDay(day: Int, source: String, ms: Long): Int

    @Insert
    suspend fun insert(day: AppDayEntity)

    // The history screen.

    @Query(
        """
        SELECT plays.id AS play_id, plays.played_at, songs.* FROM plays
        JOIN songs ON songs.id = plays.song_id
        WHERE plays.played_at >= :sinceMs
        ORDER BY plays.played_at DESC
        """,
    )
    suspend fun playsSince(sinceMs: Long): List<PlayRow>

    /** As [playsSince], only the songs whose title or artist matches [pattern] (LIKE, `\` escapes). */
    @Query(
        """
        SELECT plays.id AS play_id, plays.played_at, songs.* FROM plays
        JOIN songs ON songs.id = plays.song_id
        WHERE plays.played_at >= :sinceMs
          AND (songs.title LIKE :pattern ESCAPE '\' OR songs.artist LIKE :pattern ESCAPE '\')
        ORDER BY plays.played_at DESC
        """,
    )
    suspend fun playsSince(sinceMs: Long, pattern: String): List<PlayRow>

    @Query(
        """
        SELECT plays.id AS play_id, plays.played_at, songs.* FROM plays
        JOIN songs ON songs.id = plays.song_id
        ORDER BY plays.played_at DESC LIMIT 1
        """,
    )
    suspend fun latestPlay(): PlayRow?

    // Cleaning.

    @Query("DELETE FROM plays WHERE played_at < :cutoffMs")
    suspend fun deletePlaysBefore(cutoffMs: Long): Int

    /** Covers kept on the phone for songs that still have plays in the history. */
    @Query("SELECT DISTINCT cover_key FROM songs WHERE cover_key IS NOT NULL AND id IN (SELECT song_id FROM plays)")
    suspend fun coverKeysInUse(): List<String>

    /** Forgets the kept covers of songs with no plays left, whose files are deleted. */
    @Query("UPDATE songs SET cover_key = NULL WHERE cover_key IS NOT NULL AND id NOT IN (SELECT song_id FROM plays)")
    suspend fun forgetUnusedCovers(): Int

    @Query("DELETE FROM plays")
    suspend fun deleteAllPlays()

    @Query("DELETE FROM song_months")
    suspend fun deleteAllMonths()

    @Query("DELETE FROM app_days")
    suspend fun deleteAllDays()

    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()

    // Statistics of the last days, from the plays.

    @Query(
        """
        SELECT COUNT(*) AS play_count, COUNT(DISTINCT plays.song_id) AS song_count,
               COUNT(DISTINCT songs.artist) AS artist_count
        FROM plays JOIN songs ON songs.id = plays.song_id
        WHERE plays.played_at >= :sinceMs
        """,
    )
    suspend fun totalsSince(sinceMs: Long): PlayTotals

    @Query(
        """
        SELECT songs.*, COUNT(*) AS play_count FROM plays
        JOIN songs ON songs.id = plays.song_id
        WHERE plays.played_at >= :sinceMs
        GROUP BY songs.id ORDER BY play_count DESC, MAX(plays.played_at) DESC LIMIT :limit
        """,
    )
    suspend fun topSongsSince(sinceMs: Long, limit: Int): List<SongCount>

    @Query(
        """
        SELECT songs.artist AS artist, COUNT(*) AS play_count FROM plays
        JOIN songs ON songs.id = plays.song_id
        WHERE plays.played_at >= :sinceMs AND songs.artist IS NOT NULL AND songs.artist != ''
        GROUP BY songs.artist ORDER BY play_count DESC LIMIT :limit
        """,
    )
    suspend fun topArtistsSince(sinceMs: Long, limit: Int): List<ArtistCount>

    // Statistics of whole months, from the monthly sums.

    @Query(
        """
        SELECT COALESCE(SUM(song_months.plays), 0) AS play_count, COUNT(DISTINCT song_months.song_id) AS song_count,
               COUNT(DISTINCT songs.artist) AS artist_count
        FROM song_months JOIN songs ON songs.id = song_months.song_id
        WHERE song_months.month >= :fromMonth
        """,
    )
    suspend fun totalsFromMonth(fromMonth: Int): PlayTotals

    @Query(
        """
        SELECT songs.*, SUM(song_months.plays) AS play_count FROM song_months
        JOIN songs ON songs.id = song_months.song_id
        WHERE song_months.month >= :fromMonth
        GROUP BY songs.id ORDER BY play_count DESC, songs.last_played_at DESC LIMIT :limit
        """,
    )
    suspend fun topSongsFromMonth(fromMonth: Int, limit: Int): List<SongCount>

    @Query(
        """
        SELECT songs.artist AS artist, SUM(song_months.plays) AS play_count FROM song_months
        JOIN songs ON songs.id = song_months.song_id
        WHERE song_months.month >= :fromMonth AND songs.artist IS NOT NULL AND songs.artist != ''
        GROUP BY songs.artist ORDER BY play_count DESC LIMIT :limit
        """,
    )
    suspend fun topArtistsFromMonth(fromMonth: Int, limit: Int): List<ArtistCount>

    // Time per app and per day.

    @Query(
        """
        SELECT source, kind, SUM(played_ms) AS played_ms FROM app_days
        WHERE day >= :fromDay GROUP BY source ORDER BY played_ms DESC
        """,
    )
    suspend fun appTimesFromDay(fromDay: Int): List<AppTime>

    @Query(
        """
        SELECT day, SUM(played_ms) AS played_ms FROM app_days
        WHERE day >= :fromDay AND kind = 'MUSIC' GROUP BY day ORDER BY day
        """,
    )
    suspend fun musicTimePerDay(fromDay: Int): List<DayTime>
}
