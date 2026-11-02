package io.github.elkelvinx.sashabridge.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One play of a song, as the history shows it.
 *
 * Immutable; can be shared between threads.
 *
 * @property id Id of the play.
 * @property playedAtMs When it counted as listened to, in milliseconds since the epoch.
 * @property source Android package of the player, such as `com.spotify.music`.
 * @property title Track title, or `null`.
 * @property artist Track artist, or `null`.
 * @property album Album name, or `null`.
 * @property mediaId The player's own id of the track (Spotify's `spotify:track:...`), or `null`.
 * @property coverKey Key of its cover kept on the phone, or `null`.
 * @property songId Id of the song, the same for every play of it.
 * @property kind Whether it is a song or a video played in a music app.
 * @property artUrl Web address of its cover, or `null`.
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
    val songId: Long = 0,
    val kind: SongKind = SongKind.SONG,
    val artUrl: String? = null,
) {

    /** Where its cover comes from: the web address, or else the cover kept on the phone. */
    val coverRef: String?
        get() = artUrl ?: coverKey

    /** Whether this is the same song as [other]: the same song row, or else the same player and names. */
    fun isSameSong(other: HistoryEntry): Boolean =
        if (songId != 0L && other.songId != 0L) songId == other.songId else isSameSong(other.source, other.title, other.artist)

    /** Whether this is the same song as [source], [title] and [artist]: same player and names. */
    fun isSameSong(source: String, title: String?, artist: String?): Boolean =
        this.source == source && this.title == title && this.artist == artist
}

/** A row of the history screen: a day heading or a play. */
sealed interface HistoryRow {

    /** Heading of the plays of [date]. */
    data class Day(val date: LocalDate) : HistoryRow

    /**
     * A play, or several of the same song in a row ("×3"), shown as its newest.
     *
     * @property count How many plays in a row it stands for.
     */
    data class Play(val entry: HistoryEntry, val count: Int = 1) : HistoryRow
}

/** Builds the rows of the history screen. Pure and thread-safe. */
object HistoryList {

    /**
     * Puts a heading before the plays of each day, and folds the same song played several
     * times in a row (repeat, or back to it) into one row with its count. The database keeps
     * every play; folding them is only how the screen shows them.
     *
     * @param entries Plays, newest first.
     * @param zone Time zone that decides where a day starts.
     */
    fun rows(entries: List<HistoryEntry>, zone: ZoneId): List<HistoryRow> {
        val rows = ArrayList<HistoryRow>(entries.size + entries.size / 8 + 1)
        var day: LocalDate? = null
        for (entry in entries) {
            val date = Instant.ofEpochMilli(entry.playedAtMs).atZone(zone).toLocalDate()
            val last = rows.lastOrNull()
            if (date == day && last is HistoryRow.Play && last.entry.isSameSong(entry)) {
                rows[rows.lastIndex] = last.copy(count = last.count + 1)
                continue
            }
            if (date != day) {
                rows += HistoryRow.Day(date)
                day = date
            }
            rows += HistoryRow.Play(entry)
        }
        return rows
    }
}
