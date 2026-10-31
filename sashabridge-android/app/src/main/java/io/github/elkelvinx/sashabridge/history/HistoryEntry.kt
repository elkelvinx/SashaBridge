package io.github.elkelvinx.sashabridge.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One track listened to, as the history keeps it.
 *
 * Immutable; can be shared between threads.
 *
 * @property id Row id in the history database.
 * @property playedAtMs When it counted as listened to, in milliseconds since the epoch.
 * @property source Android package of the player, such as `com.spotify.music`.
 * @property title Track title, or `null`.
 * @property artist Track artist, or `null`.
 * @property album Album name, or `null`.
 * @property mediaId The player's own id of the track (Spotify's `spotify:track:...`), or `null`.
 * @property coverKey Key of its small cover in the thumbnail store, or `null` if it had none.
 */
data class HistoryEntry(
    val id: Long,
    val playedAtMs: Long,
    val source: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val mediaId: String?,
    val coverKey: String?,
) {

    /** Whether this is the same song as [source], [title] and [artist]: same player and names. */
    fun isSameSong(source: String, title: String?, artist: String?): Boolean =
        this.source == source && this.title == title && this.artist == artist
}

/** A row of the history screen: a day heading or a track. */
sealed interface HistoryRow {

    /** Heading of the tracks listened to on [date]. */
    data class Day(val date: LocalDate) : HistoryRow

    /** A track. */
    data class Play(val entry: HistoryEntry) : HistoryRow
}

/** Builds the rows of the history screen. Pure and thread-safe. */
object HistoryList {

    /**
     * The same song listened to several times in a row (forward, then back to it) is one
     * listening: the ids of all but the newest of each such run.
     *
     * @param entries Tracks, newest first.
     */
    fun repeats(entries: List<HistoryEntry>): List<Long> =
        entries.zipWithNext().filter { (newer, older) -> older.isSameSong(newer.source, newer.title, newer.artist) }
            .map { (_, older) -> older.id }

    /**
     * Puts a heading before the tracks of each day.
     *
     * @param entries Tracks, newest first.
     * @param zone Time zone that decides where a day starts.
     */
    fun rows(entries: List<HistoryEntry>, zone: ZoneId): List<HistoryRow> {
        val rows = ArrayList<HistoryRow>(entries.size + entries.size / 8 + 1)
        var day: LocalDate? = null
        for (entry in entries) {
            val date = Instant.ofEpochMilli(entry.playedAtMs).atZone(zone).toLocalDate()
            if (date != day) {
                rows += HistoryRow.Day(date)
                day = date
            }
            rows += HistoryRow.Play(entry)
        }
        return rows
    }
}
