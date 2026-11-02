package io.github.elkelvinx.sashabridge.history

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The tables of the history database (version 2). The schema Room exports from them is in
 * app/schemas/, one JSON file per version.
 *
 * - songs: each song once, with its texts and where its cover is.
 * - plays: one light row per play, kept 30 days; the history screen lists them.
 * - song_months: plays of each song per month, added up at each play and kept for good, so
 *   the statistics outlive the plays.
 * - app_days: time each app played per day, videos included, kept for good.
 */

/**
 * A song of the history.
 *
 * @property songKey Its identity, from [SongKeys].
 * @property artUrl Web address of its cover, so the image need not be kept: Spotify's or
 *   YouTube Music's own. `null` if the player gives none.
 * @property coverKey Key of a cover kept on the phone, only for players without [artUrl].
 * @property firstPlayedAt First play, in milliseconds since the epoch.
 * @property lastPlayedAt Newest play, in milliseconds since the epoch.
 */
@Entity(tableName = "songs", indices = [Index(value = ["source", "song_key"], unique = true)])
internal data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    @ColumnInfo(name = "song_key") val songKey: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val kind: SongKind,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "art_url") val artUrl: String?,
    @ColumnInfo(name = "cover_key") val coverKey: String?,
    @ColumnInfo(name = "media_id") val mediaId: String?,
    @ColumnInfo(name = "first_played_at") val firstPlayedAt: Long,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
)

/** One play of a song, at [playedAt] (milliseconds since the epoch). */
@Entity(
    tableName = "plays",
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("played_at"), Index("song_id")],
)
internal data class PlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "played_at") val playedAt: Long,
)

/** How many times a song played in a [month] such as `202610` ([HistoryDates.month]). */
@Entity(
    tableName = "song_months",
    primaryKeys = ["song_id", "month"],
    foreignKeys = [ForeignKey(SongEntity::class, ["id"], ["song_id"], onDelete = ForeignKey.CASCADE)],
)
internal data class SongMonthEntity(
    @ColumnInfo(name = "song_id") val songId: Long,
    val month: Int,
    val plays: Int,
)

/** How long an app played on a [day] such as `20261031` ([HistoryDates.day]). */
@Entity(tableName = "app_days", primaryKeys = ["day", "source"])
internal data class AppDayEntity(
    val day: Int,
    val source: String,
    val kind: AppKind,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
)

/** A play with its song, as the history screen lists it. */
internal data class PlayRow(
    @ColumnInfo(name = "play_id") val playId: Long,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    @Embedded val song: SongEntity,
)

/** A song and how many times it played in a period. */
internal data class SongCount(
    @Embedded val song: SongEntity,
    @ColumnInfo(name = "play_count") val plays: Int,
)

/** An artist and how many times their songs played in a period. */
internal data class ArtistCount(
    val artist: String,
    @ColumnInfo(name = "play_count") val plays: Int,
)

/** How long an app played in a period. */
internal data class AppTime(
    val source: String,
    val kind: AppKind,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
)

/** How long the music apps played on a [day]. */
internal data class DayTime(
    val day: Int,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
)

/** Totals of a period: plays, different songs and different artists. */
internal data class PlayTotals(
    @ColumnInfo(name = "play_count") val plays: Int,
    @ColumnInfo(name = "song_count") val songs: Int,
    @ColumnInfo(name = "artist_count") val artists: Int,
)
