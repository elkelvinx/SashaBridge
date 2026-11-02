package io.github.elkelvinx.sashabridge.media

import java.net.URLDecoder

/**
 * Where to fetch the small cover of a queue item from, given the art address its player
 * publishes (`MediaDescription.iconUri`).
 *
 * Observed on the phone (October 2026):
 * - Spotify publishes `content://com.spotify.mobile.android.mediaapi/spotify%3Aimage%3A<id>`,
 *   which this app may read (640 px, served locally, no network). The same id, with the
 *   300 px prefix, is on Spotify's public image server, the fallback.
 * - YouTube Music publishes `https://yt3.googleusercontent.com/...=w60-h60-l90-rj`; Google's
 *   image server takes the size in the address, so it is asked for [SIZE_PX].
 *
 * Pure and thread-safe.
 */
object ThumbnailSources {

    /** Side, in pixels, of the covers in lists. */
    const val SIZE_PX = 192

    /** Side, in pixels, asked of Google's server for a sharp cover: the short side of the screen. */
    private const val SHARP_PX = AlbumArtEncoder.SCREEN_MAX_PX

    /**
     * The size part of a Spotify album cover id after `/image/`, such as `ab67616d0000b273`
     * (640 px); `ab67616d000082c1` is the 2000 px one.
     */
    private val SPOTIFY_ALBUM_IMAGE = Regex("/image/ab67616d[0-9a-f]{8}")
    private const val SPOTIFY_SHARP_IMAGE = "/image/ab67616d000082c1"

    /** Spotify's public image server. */
    private const val SPOTIFY_IMAGE_SERVER = "https://i.scdn.co/image/"

    /** Start of the content URIs of Spotify's covers. */
    private const val SPOTIFY_CONTENT = "content://com.spotify."

    /** Length of the size prefix of a Spotify image id, such as `ab67616d0000b273` (640 px). */
    private const val SPOTIFY_PREFIX_LENGTH = 16

    /** Prefix of the 300 px version of a Spotify cover. */
    private const val SPOTIFY_300_PREFIX = "ab67616d00001e02"

    /** Width and height in a Google image address, such as `=w60-h60`. */
    private val GOOGLE_SIZE = Regex("=w\\d+-h\\d+")

    /** Square size in a Google image address, such as `=s60`. */
    private val GOOGLE_SQUARE = Regex("=s\\d+")

    /**
     * The addresses to try, in order, for the cover at [artUri].
     *
     * @return `content://` or `https://` addresses; none for a key that is not an address,
     *   such as the key of a cover the history keeps.
     */
    fun candidates(artUri: String): List<String> = when {
        artUri.startsWith(SPOTIFY_CONTENT) -> listOfNotNull(artUri, spotifyServer(artUri))
        "googleusercontent.com" in artUri -> listOf(googleSized(artUri, SIZE_PX))
        artUri.startsWith("content://") || artUri.startsWith("https://") || artUri.startsWith("http://") -> listOf(artUri)
        else -> emptyList()
    }

    /**
     * Where a sharper cover than the player's is, given its web address ([MediaSnapshot.artUrl]):
     * Spotify's 2000 px album cover, or Google's asked for [SHARP_PX]; `null` for any other.
     */
    fun sharpUrl(artUrl: String): String? = when {
        SPOTIFY_ALBUM_IMAGE.containsMatchIn(artUrl) -> SPOTIFY_ALBUM_IMAGE.replaceFirst(artUrl, SPOTIFY_SHARP_IMAGE)
        "googleusercontent.com" in artUrl -> googleSized(artUrl, SHARP_PX).takeIf { it != artUrl }
        else -> null
    }

    /** The 300 px cover on Spotify's public server, or `null` if [contentUri] has no image id. */
    private fun spotifyServer(contentUri: String): String? {
        val id = URLDecoder.decode(contentUri.substringBefore('?'), "UTF-8").substringAfterLast(':')
        if (id.length <= SPOTIFY_PREFIX_LENGTH || '/' in id) return null
        return SPOTIFY_IMAGE_SERVER + SPOTIFY_300_PREFIX + id.substring(SPOTIFY_PREFIX_LENGTH)
    }

    /** [url] asking Google's image server for a cover of [size] pixels. */
    private fun googleSized(url: String, size: Int): String = when {
        GOOGLE_SIZE.containsMatchIn(url) -> GOOGLE_SIZE.replaceFirst(url, "=w$size-h$size")
        GOOGLE_SQUARE.containsMatchIn(url) -> GOOGLE_SQUARE.replaceFirst(url, "=s$size")
        else -> url
    }
}
