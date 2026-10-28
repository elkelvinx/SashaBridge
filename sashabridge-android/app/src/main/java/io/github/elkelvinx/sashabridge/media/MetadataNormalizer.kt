package io.github.elkelvinx.sashabridge.media

/**
 * Removes player-specific decorations from the metadata before it reaches the PC.
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
    private const val SPOTIFY_CONTEXT_SEPARATOR = " • "

    /**
     * Returns the artist without player decorations.
     *
     * @param source Android package of the player.
     * @param artist Artist as reported by the player.
     * @return The cleaned artist, or [artist] unchanged when there is nothing to remove.
     */
    fun artist(source: String, artist: String?): String? {
        if (artist == null || source != SPOTIFY_PACKAGE) return artist
        return artist.substringBefore(SPOTIFY_CONTEXT_SEPARATOR).trimEnd().ifEmpty { artist }
    }
}
