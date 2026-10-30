package io.github.elkelvinx.sashabridge.media

/**
 * Finds and reads the "like" button of players that expose it as a custom action: Spotify's
 * heart, which saves the track to "Tus me gusta", and YouTube Music's thumb up.
 *
 * Observed on the phone (October 2026):
 * - Spotify offers `ADD_TO` ("Agregar a la colección") while the track is not saved and
 *   `CHECK_FILL` ("Eliminar de la colección") once it is; either one toggles it, so the
 *   identifier reveals the state, like its shuffle and repeat actions.
 * - YouTube Music offers `thumbs_up_action` ("Me gusta", "Deshacer \"me gusta\"" once liked)
 *   and `thumbs_down_action`. The identifier stays the same, so the state comes from the
 *   track's user rating in the metadata.
 * - Echo Music, a YouTube Music client, offers `TOGGLE_LIKE`.
 *
 * Pure and thread-safe.
 */
object LikeControl {

    /** Spotify's action while the track is not saved. */
    private const val SPOTIFY_ADD = "ADD_TO"

    /** Spotify's action once the track is saved. */
    private const val SPOTIFY_SAVED = "CHECK_FILL"

    /** Part of YouTube Music's thumb-up identifier. */
    private const val THUMBS_UP_MARKER = "thumbs_up"

    /** Echo Music's action. */
    private const val ECHO_LIKE = "TOGGLE_LIKE"

    /**
     * Finds the custom action that likes the track, or takes the like back.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @return The identifier to send, or `null` if the player has no like action.
     */
    fun findToggleAction(actionIds: List<String>): String? = actionIds.firstOrNull {
        it == SPOTIFY_ADD || it == SPOTIFY_SAVED || it == ECHO_LIKE || it.contains(THUMBS_UP_MARKER, ignoreCase = true)
    }

    /**
     * Whether the user likes the track.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @param ratedUp Whether the track's user rating is a thumb up or a heart, `false` if it is
     *   not rated, or `null` if the player gives no rating.
     * @return Whether it is liked, or `null` if the player has no like action or does not
     *   reveal the state.
     */
    fun likedOf(actionIds: List<String>, ratedUp: Boolean?): Boolean? = when (findToggleAction(actionIds)) {
        null -> null
        SPOTIFY_SAVED -> true
        SPOTIFY_ADD -> false
        else -> ratedUp
    }
}
