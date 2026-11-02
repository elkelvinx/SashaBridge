package io.github.elkelvinx.sashabridge.media

import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG

/**
 * Reads a player's session into a [MediaSnapshot]: its metadata, its playback state, its own
 * buttons (shuffle, repeat, like) and its cover, with the sharper image when there is one.
 *
 * Each player says things its own way; the rules of each are in [MetadataNormalizer],
 * [ShuffleControl], [RepeatControl] and [LikeControl], and this class only gathers them.
 *
 * Main thread; [read] suspends while the cover is encoded.
 *
 * @param artEncoder Encodes the cover, with its cache.
 * @param sharpArt Sharper covers for the screen.
 */
internal class SnapshotReader(private val artEncoder: AlbumArtEncoder, private val sharpArt: SharpArtCache) {

    /** The state of [controller], or `null` if it has no metadata. */
    suspend fun read(controller: MediaController): MediaSnapshot? {
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState
        val source = controller.packageName

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val (artist, playbackContext) = MetadataNormalizer.splitArtist(
            source,
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        )
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotEmpty() }
        val actionIds = state?.customActions?.map { it.action }.orEmpty()
        val actionNames = state?.customActions?.associate { it.action to it.name.toString() }.orEmpty()
        val artUrl = artUrlOf(controller, metadata, title)
        val art = artEncoder.encode(metadata)?.let(sharpArt::sharpest)
        art?.let { sharpArt.ask(it, artUrl) }

        return MediaSnapshot(
            trackId = MediaSnapshot.trackIdOf(source, mediaId, title, artist, album, durationMs),
            source = source,
            title = title,
            artist = artist,
            playbackContext = playbackContext,
            album = album,
            durationMs = durationMs,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            reportedPositionMs = state?.position ?: PlaybackPositionCalculator.UNKNOWN_POSITION,
            positionUpdateTimeMs = state?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = state?.playbackSpeed ?: 0f,
            shuffle = ShuffleControl.modeOf(actionIds, actionNames),
            art = art,
            canRepeat = RepeatControl.findToggleAction(actionIds) != null,
            repeat = RepeatControl.modeOf(actionIds, actionNames),
            canLike = LikeControl.findToggleAction(actionIds) != null,
            liked = LikeControl.likedOf(actionIds, ratedUp(metadata)),
            mediaId = mediaId,
            artUrl = artUrl,
        )
    }

    /**
     * The web address of the cover, if the player gives one: Spotify's own extra, a standard
     * art address that is on the web, or else the icon of the queue item playing, which is how
     * YouTube Music gives it. The queue item must have the track's [title], since the queue
     * may still point at the previous track.
     */
    private fun artUrlOf(controller: MediaController, metadata: MediaMetadata, title: String?): String? {
        val fromMetadata = sequenceOf(SPOTIFY_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .mapNotNull { metadata.getString(it) }
            .firstOrNull { it.startsWith(HTTPS) }
        if (fromMetadata != null) return fromMetadata
        val activeId = controller.playbackState?.activeQueueItemId ?: return null
        return controller.queue
            ?.firstOrNull { it.queueId == activeId && it.description.title?.toString() == title }
            ?.description?.iconUri?.toString()
            ?.takeIf { it.startsWith(HTTPS) }
    }

    /**
     * Whether the track's user rating is a thumb up or a heart: `false` if it is not rated,
     * `null` if the player gives no rating or rates in another style (stars, percentage).
     */
    private fun ratedUp(metadata: MediaMetadata): Boolean? {
        val rating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return null
        if (!rating.isRated) return false
        return when (rating.ratingStyle) {
            Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
            Rating.RATING_HEART -> rating.hasHeart()
            else -> null
        }
    }

    companion object {

        /** Spotify's extra with the web address of the cover (i.scdn.co). */
        private const val SPOTIFY_ART_URI = "com.spotify.music.extra.ART_HTTPS_URI"

        private const val HTTPS = "https://"

        /**
         * Logs which metadata keys and art sources a player provides. Diagnostic for R3: it
         * shows whether a player sends the art as a bitmap, only as a URI, or later than the
         * title.
         */
        fun logMetadataKeys(source: String, metadata: MediaMetadata?) {
            if (metadata == null) {
                Log.d(LOG_TAG, "Metadata from $source: none")
                return
            }
            val bitmaps = listOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
            ).mapNotNull { key -> metadata.getBitmap(key)?.let { "$key=${it.width}x${it.height}" } }
            val uris = listOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            ).mapNotNull { key -> metadata.getString(key)?.let { "$key=$it" } }
            Log.d(LOG_TAG, "Metadata from $source: keys=${metadata.keySet().sorted()} bitmaps=$bitmaps uris=$uris")
        }
    }
}
