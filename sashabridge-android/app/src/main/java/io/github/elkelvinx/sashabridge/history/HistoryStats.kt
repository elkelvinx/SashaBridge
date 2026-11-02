package io.github.elkelvinx.sashabridge.history

import java.time.LocalDate
import java.time.YearMonth

/**
 * Periods of the statistics. The short ones read the plays of the history; the long ones,
 * longer than the 30 days the plays are kept, read the monthly sums.
 *
 * @property days Days back from today, today included, for the periods read from the plays;
 *   `null` for the ones read from the monthly sums.
 */
enum class StatsPeriod(val days: Int?) {

    /** The last 7 days. */
    WEEK(7),

    /** The last 30 days. */
    DAYS_30(30),

    /** The current calendar month. */
    THIS_MONTH(null),

    /** Everything since the statistics began. */
    ALL(null),
}

/**
 * The statistics of a period.
 *
 * @property listenedMs Time the music apps played.
 * @property plays Plays of songs.
 * @property songs Different songs.
 * @property artists Different artists.
 * @property apps Time of every app that played, music and video, longest first.
 * @property topSongs Most played songs, most first.
 * @property topArtists Most played artists, most first.
 * @property days Time the music apps played on each of the last 30 days, oldest first; a day
 *   without music has 0. The same whatever the period.
 */
data class HistoryStats(
    val period: StatsPeriod,
    val listenedMs: Long,
    val plays: Int,
    val songs: Int,
    val artists: Int,
    val apps: List<AppUsage>,
    val topSongs: List<SongStat>,
    val topArtists: List<ArtistStat>,
    val days: List<DayUsage>,
)

/** How long an app played. */
data class AppUsage(val source: String, val kind: AppKind, val playedMs: Long)

/** A song and its plays; [entry] carries its texts and cover, as in the history. */
data class SongStat(val entry: HistoryEntry, val plays: Int)

/** An artist and the plays of their songs. */
data class ArtistStat(val artist: String, val plays: Int)

/** How long the music apps played on [date]. */
data class DayUsage(val date: LocalDate, val playedMs: Long)

/** The date arithmetic of the statistics. Pure and thread-safe. */
object StatsDates {

    /** Days the chart of days covers. */
    const val CHART_DAYS = 30

    /** First day of [period] that ends [today], as a day number ([HistoryDates.day]). */
    fun firstDay(period: StatsPeriod, today: LocalDate): Int = HistoryDates.day(
        when (period) {
            StatsPeriod.WEEK, StatsPeriod.DAYS_30 -> today.minusDays((period.days ?: 1) - 1L)
            StatsPeriod.THIS_MONTH -> today.withDayOfMonth(1)
            StatsPeriod.ALL -> LocalDate.of(1, 1, 1)
        },
    )

    /** First month of a period read from the monthly sums, as a month number ([HistoryDates.month]). */
    fun firstMonth(period: StatsPeriod, today: LocalDate): Int =
        if (period == StatsPeriod.THIS_MONTH) HistoryDates.month(YearMonth.from(today)) else 0

    /**
     * The [CHART_DAYS] days that end [today], each with its time in [times] (by day number),
     * or 0 if it has none.
     */
    fun chartDays(times: Map<Int, Long>, today: LocalDate): List<DayUsage> =
        (CHART_DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayUsage(date, times[HistoryDates.day(date)] ?: 0L)
        }
}
