package io.github.elkelvinx.sashabridge.media

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads small files, such as covers, over HTTPS: the only place the app opens a web
 * connection of its own, for the list covers ([ThumbnailStore]) and the sharper ones
 * ([SharpArtLoader]).
 *
 * Blocking; call on the IO dispatcher. Thread-safe.
 */
internal object Downloads {

    /** Time to connect, and then between two reads. */
    const val TIMEOUT_MS = 8_000

    /** Larger answers are not a cover; Spotify's 2000 px one is about 0.6 MB. */
    const val MAX_BYTES = 4 * 1024 * 1024

    private const val BUFFER_BYTES = 16 * 1024

    /**
     * The bytes at [url].
     *
     * @return The body, or `null` if the server answers with anything but success or sends
     *   more than [maxBytes].
     * @throws IOException if the connection fails.
     */
    fun bytes(url: String, maxBytes: Int = MAX_BYTES): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.use { input ->
                val body = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                var read = input.read(buffer)
                while (read >= 0) {
                    body.write(buffer, 0, read)
                    if (body.size() > maxBytes) return null
                    read = input.read(buffer)
                }
                body.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
