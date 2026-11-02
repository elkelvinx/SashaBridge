package io.github.elkelvinx.sashabridge.media

import android.graphics.Bitmap
import io.github.elkelvinx.sashabridge.protocol.ShuffleMode
import java.security.MessageDigest

/**
 * Album art of a track, in two sizes: small for the wire, and as sharp as the player gave it
 * for this phone's screen.
 *
 * Two instances are equal when their [artId] and the size of their [screen] image are equal.
 * This keeps [MediaSnapshot] comparisons cheap and makes snapshots with the same image compare
 * equal, while a sharper image of the same art, fetched later, is a change for the screen. The
 * PC only looks at the [artId], so for it nothing changes.
 *
 * Immutable by contract; can be shared between threads.
 *
 * @property artId Identifier derived from the bytes; see [AlbumArtEncoder.artIdOf].
 * @property jpeg JPEG bytes of at most [AlbumArtEncoder.MAX_SIDE_PX], what the PC receives.
 *   The array is not copied, so it must not be modified.
 * @property screen The image for the app's own screen, never sent: the player's own pixels,
 *   up to [AlbumArtEncoder.SCREEN_MAX_PX], not compressed again. The card's cover is several
 *   times larger than [jpeg]. `null` when only the JPEG is known; never drawn on, and never a
 *   hardware bitmap, so its pixels can be read.
 */
class AlbumArt(val artId: String, val jpeg: ByteArray, val screen: Bitmap? = null) {

    override fun equals(other: Any?): Boolean =
        other is AlbumArt && other.artId == artId && other.screen?.width == screen?.width && other.screen?.height == screen?.height

    override fun hashCode(): Int = artId.hashCode()

    override fun toString(): String = "AlbumArt(artId=$artId, bytes=${jpeg.size}, screen=${screen?.let { "${it.width}x${it.height}" }})"
}

/**
 * State of the active player at one moment.
 *
 * The position is stored as the player reported it, together with the time of that report,
 * so that it can be extrapolated to the moment it is sent ([positionAt]).
 *
 * Immutable; can be shared between threads.
 *
 * @property trackId Deterministic identifier of the track; see [trackIdOf].
 * @property source Android package of the player, such as `com.spotify.music`.
 * @property title Track title, or `null` if the player does not report it.
 * @property artist Track artist without the playback context, or `null`.
 * @property playbackContext Context the player attaches to the artist, such as Spotify's
 *   smart shuffle, or `null`; see [MetadataNormalizer.splitArtist].
 * @property album Album name, or `null`.
 * @property durationMs Track length in milliseconds, or `null` if unknown.
 * @property isPlaying Whether the player is in `STATE_PLAYING`.
 * @property reportedPositionMs Position reported by the player, or
 *   [PlaybackPositionCalculator.UNKNOWN_POSITION].
 * @property positionUpdateTimeMs `SystemClock.elapsedRealtime()` of that report, or 0 if unknown.
 * @property playbackSpeed Playback speed reported by the player.
 * @property shuffle Shuffle mode, or `null` if it cannot be controlled; see [ShuffleControl].
 * @property art Album art of the track, or `null` if it has none.
 * @property canRepeat Whether the player has a repeat button the bridge can press; see [RepeatControl].
 * @property repeat Repeat mode, or `null` if the player does not reveal it.
 * @property canLike Whether the player has a like button the bridge can press; see [LikeControl].
 * @property liked Whether the user likes the track, or `null` if the player does not reveal it.
 * @property mediaId The player's own id of the track, such as Spotify's `spotify:track:...`,
 *   or `null` (YouTube Music sets none); the history uses it to open that track again.
 * @property artUrl Web address of the cover, if the player gives one; the history keeps it
 *   instead of the image. Not sent to the PC.
 */
data class MediaSnapshot(
    val trackId: String,
    val source: String,
    val title: String?,
    val artist: String?,
    val playbackContext: String?,
    val album: String?,
    val durationMs: Long?,
    val isPlaying: Boolean,
    val reportedPositionMs: Long,
    val positionUpdateTimeMs: Long,
    val playbackSpeed: Float,
    val shuffle: ShuffleMode?,
    val art: AlbumArt?,
    val canRepeat: Boolean = false,
    val repeat: RepeatMode? = null,
    val canLike: Boolean = false,
    val liked: Boolean? = null,
    val mediaId: String? = null,
    val artUrl: String? = null,
) {

    /**
     * Returns the playback position at [nowElapsedMs].
     *
     * @param nowElapsedMs Current `SystemClock.elapsedRealtime()`.
     * @return Position in milliseconds, between 0 and [durationMs].
     */
    fun positionAt(nowElapsedMs: Long): Long = PlaybackPositionCalculator.positionAt(
        reportedPositionMs = reportedPositionMs,
        updateTimeMs = positionUpdateTimeMs,
        speed = playbackSpeed,
        isPlaying = isPlaying,
        durationMs = durationMs,
        nowMs = nowElapsedMs,
    )

    companion object {

        /**
         * Builds the deterministic identifier of a track.
         *
         * The player's media id is preferred because it stays the same while the player is
         * still filling in the other fields (Spotify, for example, sends the title in two
         * steps). Without a media id, the title and the artist are used, but not the album or
         * the length: YouTube Music, which sets no media id, sends those a moment after the
         * title, and the card would take each step for a new track and animate it again.
         *
         * @param source Android package of the player.
         * @param mediaId `METADATA_KEY_MEDIA_ID`, if the player sets it.
         * @param title Track title.
         * @param artist Track artist.
         * @param album Album name; not part of the identifier.
         * @param durationMs Track length; not part of the identifier.
         * @return 16 lowercase hexadecimal digits.
         */
        fun trackIdOf(
            source: String,
            mediaId: String?,
            title: String?,
            artist: String?,
            album: String?,
            durationMs: Long?,
        ): String {
            val key = if (!mediaId.isNullOrEmpty()) {
                "$source|id|$mediaId"
            } else {
                "$source|meta|$title|$artist"
            }
            return shortHash(key.toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * Returns the first 8 bytes of the SHA-256 digest of [bytes] as 16 lowercase hexadecimal digits.
 *
 * @param bytes Data to hash.
 * @return The hexadecimal digest prefix.
 */
internal fun shortHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .take(8)
        .joinToString("") { "%02x".format(it) }
