package io.github.elkelvinx.sashabridge.media

/**
 * Finds the repeat button of players that expose it as a custom action.
 *
 * Like shuffle, the framework media session has no repeat state. Spotify offers a custom
 * action such as `TURN_REPEAT_ONE_ON`, and YouTube Music clients such as Echo Music offer
 * `TOGGLE_REPEAT_MODE`; sending the action moves to the player's next repeat mode, exactly
 * like its own button.
 *
 * Pure and thread-safe.
 */
object RepeatControl {

    /** Part of the identifier shared by every repeat action. */
    private const val REPEAT_MARKER = "REPEAT"

    /**
     * Finds the custom action that changes the repeat mode.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @return The identifier to send, or `null` if the player has no repeat action.
     */
    fun findToggleAction(actionIds: List<String>): String? =
        actionIds.firstOrNull { REPEAT_MARKER in it }
}
