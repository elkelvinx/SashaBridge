package io.github.elkelvinx.sashabridge.media

/**
 * Artist of a track and the playback context that some players attach to it.
 *
 * @property artist Artist without the context, or `null` if the player reports none.
 * @property context Playback context, such as "Del Modo aleatorio inteligente", or `null`.
 */
data class ArtistParts(val artist: String?, val context: String?)

/**
 * Separates player-specific decorations from the metadata.
 *
 * The decorations are kept as data instead of being discarded, so that each client decides
 * how to show them.
 *
 * Pure and thread-safe.
 */
object MetadataNormalizer {

    /** Android package of Spotify. */
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    /**
     * Separator that Spotify places before the playback context it appends to the artist,
     * for example "Artist • Del Modo aleatorio inteligente" in smart shuffle (A13). The text
     * after it depends on the phone's language, so only the separator is matched.
     */
    const val SPOTIFY_CONTEXT_SEPARATOR: String = " • "

    /**
     * Splits the artist reported by a player into the artist and its playback context.
     *
     * @param source Android package of the player.
     * @param artist Artist as reported by the player.
     * @return The parts; the context is `null` for players other than Spotify and when
     *   Spotify does not add one.
     */
    fun splitArtist(source: String, artist: String?): ArtistParts {
        if (artist == null || source != SPOTIFY_PACKAGE) return ArtistParts(artist, null)
        val name = artist.substringBefore(SPOTIFY_CONTEXT_SEPARATOR).trimEnd()
        val context = artist.substringAfter(SPOTIFY_CONTEXT_SEPARATOR, missingDelimiterValue = "").trim()
        if (name.isEmpty() || context.isEmpty()) return ArtistParts(artist, null)
        return ArtistParts(name, context)
    }
}
