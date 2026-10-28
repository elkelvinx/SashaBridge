package io.github.elkelvinx.sashabridge.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns the album art bitmap of a player into an [AlbumArt].
 *
 * Fixes three defects of the previous code:
 * - The art is scaled keeping its proportions (YouTube thumbnails are 16:9 and used to be
 *   squashed into a square).
 * - Scaling and JPEG compression run on [Dispatchers.Default] instead of the main thread (A9).
 * - The art is read from the metadata of every update, so art that arrives after the title
 *   is no longer lost (R3).
 *
 * The last result is cached by `Bitmap.generationId`, so updates that only change the
 * playback state do not encode the same bitmap again.
 *
 * Not thread-safe: [encode] must not run concurrently. [MediaSessionTracker] calls it from the
 * main thread only.
 */
class AlbumArtEncoder {

    /** Generation id of the bitmap behind [cachedArt]. */
    private var cachedGenerationId: Int? = null

    /** Result for the bitmap with [cachedGenerationId]. */
    private var cachedArt: AlbumArt? = null

    /**
     * Returns the album art of [metadata].
     *
     * The bitmap is looked up in `METADATA_KEY_ALBUM_ART`, then `METADATA_KEY_ART`, then
     * `METADATA_KEY_DISPLAY_ICON`.
     *
     * Suspends while the bitmap is encoded on [Dispatchers.Default]; resumes on the caller's
     * dispatcher.
     *
     * @param metadata Metadata of the active player.
     * @return The encoded art, or `null` if the metadata has no usable bitmap.
     */
    suspend fun encode(metadata: MediaMetadata): AlbumArt? {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (bitmap.generationId == cachedGenerationId) return cachedArt

        val jpeg = withContext(Dispatchers.Default) { compress(bitmap) }
        val art = AlbumArt(artIdOf(jpeg), jpeg)
        cachedGenerationId = bitmap.generationId
        cachedArt = art
        return art
    }

    /**
     * Scales [bitmap] to fit within [MAX_SIDE_PX] and compresses it as JPEG.
     *
     * Runs on [Dispatchers.Default].
     *
     * @param bitmap Source bitmap; it is not modified.
     * @return The JPEG bytes.
     */
    private fun compress(bitmap: Bitmap): ByteArray {
        val (width, height) = fitWithin(bitmap.width, bitmap.height, MAX_SIDE_PX)
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            bitmap.scale(width, height)
        }
        try {
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            return stream.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    companion object {

        /** Largest side of the encoded image, in pixels. */
        const val MAX_SIDE_PX: Int = 300

        /** JPEG quality, from 0 to 100. */
        const val JPEG_QUALITY: Int = 80

        /**
         * Computes the size that fits within a square of [maxSide] keeping the proportions.
         * Images that already fit are not enlarged.
         *
         * @param width Source width in pixels, greater than 0.
         * @param height Source height in pixels, greater than 0.
         * @param maxSide Largest allowed side in pixels.
         * @return The target width and height, each at least 1.
         */
        fun fitWithin(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
            val largest = maxOf(width, height)
            if (largest <= maxSide) return width to height
            val scale = maxSide.toDouble() / largest
            return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
        }

        /**
         * Derives the art identifier from the encoded bytes, so that the same image always
         * gets the same identifier.
         *
         * @param jpeg Encoded image.
         * @return 16 lowercase hexadecimal digits.
         */
        fun artIdOf(jpeg: ByteArray): String = shortHash(jpeg)
    }
}
