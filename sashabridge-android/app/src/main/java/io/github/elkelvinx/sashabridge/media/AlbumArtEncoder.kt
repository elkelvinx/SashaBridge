package io.github.elkelvinx.sashabridge.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns the album art bitmap of a player into an [AlbumArt]: a small JPEG for the PC and the
 * player's own pixels for the phone's screen ([AlbumArt.screen]).
 *
 * Fixes three defects of the previous code:
 * - The art is scaled keeping its proportions (YouTube thumbnails are 16:9 and used to be
 *   squashed into a square).
 * - Scaling and JPEG compression run on [Dispatchers.Default] instead of the main thread (A9).
 * - The art is read from the metadata of every update, so art that arrives after the title
 *   is no longer lost (R3).
 *
 * The screen keeps the bitmap as the player gave it: the PC's JPEG, shown on the card's cover,
 * which is about 760 px wide in landscape, looked blurred and blocky.
 *
 * The last result is cached by `Bitmap.generationId`, so updates that only change the
 * playback state do not encode the same bitmap again.
 *
 * Players that name their art with a `content://` address get it read from there instead
 * ([resolver]). Spotify announces a new track with that address but no bitmap, and sends the
 * bitmap 0.5 to 1.4 s later; read from the address, the art arrives with the title, in about
 * 40 ms. The art read is cached by its address, so the bitmap that follows changes nothing.
 *
 * Not thread-safe: [encode] must not run concurrently. [MediaSessionTracker] calls it from the
 * main thread only.
 */
class AlbumArtEncoder(private val resolver: ContentResolver? = null) {

    /** `content://` address behind [cachedUriArt]. */
    private var cachedUri: String? = null

    /** Art read from [cachedUri]. */
    private var cachedUriArt: AlbumArt? = null

    /** Generation id of the bitmap behind [cachedArt]. */
    private var cachedGenerationId: Int? = null

    /** The bitmap behind [cachedArt], to recognize the same image sent again as a new bitmap. */
    private var cachedBitmap: Bitmap? = null

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
        artUriOf(metadata)?.let { uri -> fromUri(uri)?.let { return it } }
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (bitmap.generationId == cachedGenerationId) return cachedArt
        // YouTube Music sends a new copy of the same image with every update; comparing the
        // pixels costs far less than encoding them again. Hardware bitmaps keep their pixels
        // out of reach, so they are encoded again.
        val previous = cachedBitmap?.takeIf { it.config != Bitmap.Config.HARDWARE && bitmap.config != Bitmap.Config.HARDWARE }
        if (previous != null && bitmap.sameAs(previous)) {
            cachedGenerationId = bitmap.generationId
            return cachedArt
        }

        val art = withContext(Dispatchers.Default) { artOf(bitmap) }
        Log.d(LOG_TAG, "Art ${bitmap.width}x${bitmap.height} from the metadata")
        cachedGenerationId = bitmap.generationId
        cachedBitmap = bitmap
        cachedArt = art
        return art
    }

    /**
     * The art at [uri], read once and then cached; `null` if it cannot be read in time, and
     * the bitmap of the metadata is used instead.
     */
    private suspend fun fromUri(uri: String): AlbumArt? {
        if (uri == cachedUri) return cachedUriArt
        val resolver = resolver ?: return null
        val art = withTimeoutOrNull(URI_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) { readUri(resolver, uri) }
        } ?: return null
        cachedUri = uri
        cachedUriArt = art
        return art
    }

    /** Reads the image at [uri] and makes its art. IO thread. */
    private fun readUri(resolver: ContentResolver, uri: String): AlbumArt? = try {
        resolver.openInputStream(uri.toUri())?.use { it.readBytes() }
            ?.let(::decode)
            ?.let { bitmap ->
                Log.d(LOG_TAG, "Art ${bitmap.width}x${bitmap.height} from $uri")
                artOf(bitmap)
            }
    } catch (e: IOException) {
        Log.d(LOG_TAG, "Cannot read the art at $uri: $e")
        null
    } catch (e: SecurityException) {
        Log.d(LOG_TAG, "No access to the art at $uri: $e")
        null
    }

    companion object {

        /** Largest side of the JPEG the PC receives, in pixels. */
        const val MAX_SIDE_PX: Int = 300

        /**
         * Largest side of the image for the phone's screen, in pixels: the short side of the
         * screen, more than the cover ever takes. Players give 544 to 900 px.
         */
        const val SCREEN_MAX_PX: Int = 1080

        /** JPEG quality, from 0 to 100. */
        const val JPEG_QUALITY: Int = 80

        /** Longest wait for art read from its address before using the bitmap instead. */
        private const val URI_TIMEOUT_MS = 400L

        /** Metadata keys that may hold the address of the art, in order of preference. */
        private val ART_URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )

        /** The `content://` address of the art in [metadata], or `null`. */
        private fun artUriOf(metadata: MediaMetadata): String? = ART_URI_KEYS.firstNotNullOfOrNull { key ->
            metadata.getString(key)?.takeIf { it.startsWith(ContentResolver.SCHEME_CONTENT + "://") }
        }

        /**
         * The art of an image file (JPEG, PNG or WebP), such as the history's copy of the
         * newest track's cover; `null` if [bytes] are no image. Works on [Dispatchers.Default].
         */
        suspend fun fromImage(bytes: ByteArray): AlbumArt? = withContext(Dispatchers.Default) {
            decode(bytes)?.let(::artOf)
        }

        /**
         * The art of [bitmap]: the image for the screen, and the PC's JPEG made from it. Any
         * thread but the main one; [bitmap] is not modified.
         */
        fun artOf(bitmap: Bitmap): AlbumArt {
            val screen = forScreen(bitmap)
            val jpeg = compress(screen, MAX_SIDE_PX, JPEG_QUALITY)
            return AlbumArt(artIdOf(jpeg), jpeg, screen)
        }

        /**
         * [bitmap] as the screen keeps it: no larger than [SCREEN_MAX_PX], and in memory the
         * app can read (a hardware bitmap cannot be blurred for the card's backdrop).
         */
        internal fun forScreen(bitmap: Bitmap): Bitmap {
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            val (width, height) = fitWithin(readable.width, readable.height, SCREEN_MAX_PX)
            return if (width == readable.width && height == readable.height) readable else readable.scale(width, height)
        }

        /**
         * Decodes [bytes], skipping whole halvings that would still leave more than
         * [SCREEN_MAX_PX]; `null` if they are no image.
         */
        private fun decode(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SCREEN_MAX_PX) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        /**
         * Scales [bitmap] to fit within [maxSide] and compresses it as JPEG at [quality].
         *
         * @param bitmap Source bitmap; it is not modified.
         * @return The JPEG bytes.
         */
        fun compress(bitmap: Bitmap, maxSide: Int, quality: Int): ByteArray {
            val (width, height) = fitWithin(bitmap.width, bitmap.height, maxSide)
            val scaled = if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                bitmap.scale(width, height)
            }
            try {
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                return stream.toByteArray()
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }

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
