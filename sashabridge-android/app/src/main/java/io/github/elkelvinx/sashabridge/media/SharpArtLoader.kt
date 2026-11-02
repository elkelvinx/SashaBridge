package io.github.elkelvinx.sashabridge.media

import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import java.io.IOException
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
            Downloads.bytes(url)?.let { ImageDecoding.decode(it, MIN_SIDE_PX) }?.let(AlbumArtEncoder::forScreen)
        } catch (e: IOException) {
            Log.d(LOG_TAG, "Cannot fetch the sharper cover $url: $e")
            null
        }
    }

    companion object {

        /**
         * A cover this size or larger is sharp enough for the card, and none is fetched for it.
         * Halving stops above it.
         */
        const val MIN_SIDE_PX = 900
    }
}
