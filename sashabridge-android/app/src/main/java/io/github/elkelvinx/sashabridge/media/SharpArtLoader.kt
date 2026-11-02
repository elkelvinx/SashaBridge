package io.github.elkelvinx.sashabridge.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Fetches a sharper cover for the card than the player gives, from the player's own image
 * server ([ThumbnailSources.sharpUrl]).
 *
 * Observed on the phone (November 2026): through the session, Spotify gives its covers at
 * 640 px and YouTube Music at 506 px, while the card's cover is about 760 px wide in landscape.
 * Spotify's server has the same cover at 2000 px, and Google's serves YouTube Music's up to its
 * original size.
 *
 * Only on an unmetered network such as Wi-Fi: Spotify's large cover is about half a megabyte
 * per song.
 *
 * Thread-safe.
 *
 * @param connectivity Tells whether the network is metered.
 */
class SharpArtLoader(private val connectivity: ConnectivityManager) {

    /** Whether a sharper cover may be downloaded now. */
    fun allowed(): Boolean = !connectivity.isActiveNetworkMetered

    /**
     * Downloads the cover at [url] and decodes it for the screen: halved while it stays above
     * [MIN_SIDE_PX], so Spotify's 2000 px cover arrives at 1000 px.
     *
     * @return The image, or `null` if it cannot be fetched or is no image.
     */
    suspend fun load(url: String): Bitmap? = runInterruptible(Dispatchers.IO) {
        try {
            download(url)?.let(::decode)?.let(AlbumArtEncoder::forScreen)
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Cannot fetch the sharper cover $url: $e")
            null
        }
    }

    /** The bytes at [url], or `null` if the server answers with anything but the image. */
    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    if (bytes.size() > MAX_BYTES) return null
                }
                return bytes.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Decodes [bytes], halving while the image stays at least [MIN_SIDE_PX]. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MIN_SIDE_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {

        /**
         * A cover this size or larger is sharp enough for the card, and none is fetched for it.
         * Halving stops above it.
         */
        const val MIN_SIDE_PX = 900

        private const val TIMEOUT_MS = 8_000
        private const val BUFFER_BYTES = 16 * 1024

        /** Larger answers are not a cover; Spotify's 2000 px one is about 0.6 MB. */
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
