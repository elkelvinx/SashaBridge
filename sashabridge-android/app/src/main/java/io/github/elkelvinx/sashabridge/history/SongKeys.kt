package io.github.elkelvinx.sashabridge.history

import java.text.Normalizer
import java.util.Locale

/**
 * The stable identity of a song in the history, so every play of it lands on the same row of
 * `songs` however many days apart.
 *
 * - Spotify names each track with an id that never changes (`spotify:track:...`), so that is
 *   the key. A song released twice (single and album, a remaster) has two ids and counts as
 *   two songs.
 * - YouTube Music and the other players give no lasting id, so the key is the title and the
 *   artist, written the same way however the player spells them: Unicode normalized (NFKC),
 *   lower case, and single spaces. A title the player renames counts as another song.
 *
 * The same function makes the keys of new plays and of the plays migrated from version 1, so
 * both always agree. Pure and thread-safe.
 */
internal object SongKeys {

    private const val SPOTIFY = "com.spotify.music"
    private const val SPOTIFY_TRACK = "spotify:track:"

    private val SPACES = Regex("\\s+")

    /**
     * The key of a song, or `null` if it has no title, which is no song at all (a browser
     * playing a page, for example).
     *
     * @param source Package of the player.
     * @param mediaId The player's own id of the track, if it gives one.
     */
    fun of(source: String, mediaId: String?, title: String?, artist: String?): String? {
        val name = normalize(title) ?: return null
        if (source == SPOTIFY && mediaId != null && mediaId.startsWith(SPOTIFY_TRACK)) return mediaId
        return "$name|${normalize(artist).orEmpty()}"
    }

    /** [text] in the form keys compare: NFKC, lower case, single spaces; `null` if blank. */
    fun normalize(text: String?): String? = text
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
        ?.lowercase(Locale.ROOT)
        ?.replace(SPACES, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
