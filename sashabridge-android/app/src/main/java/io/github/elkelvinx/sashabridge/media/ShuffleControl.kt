package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.protocol.ShuffleMode

/**
 * Reads and changes the shuffle mode of players that expose it as a custom action.
 *
 * The framework media session has no shuffle state. Spotify instead offers one custom action
 * whose identifier names what pressing it will do, so the identifier reveals the current mode,
 * and sending it moves to the next mode, exactly like the button in the app.
 *
 * Observed on Spotify for Android (October 2026): `TURN_SMART_SHUFFLE_OFF` while smart shuffle
 * is on. The other identifiers follow the same pattern and are confirmed on the device in
 * phase 5: `TURN_SHUFFLE_ON` while shuffle is off, `TURN_SMART_SHUFFLE_ON` while plain shuffle
 * is on, and `TURN_SHUFFLE_OFF` while shuffle is on and smart shuffle is not available.
 *
 * Pure and thread-safe.
 */
object ShuffleControl {

    /** Part of the identifier shared by every shuffle action. */
    private const val SHUFFLE_MARKER = "SHUFFLE"

    /**
     * Finds the custom action that toggles shuffle.
     *
     * @param actionIds Identifiers of the player's custom actions, from `PlaybackState.customActions`.
     * @return The identifier to send with `TransportControls.sendCustomAction`, or `null` if
     *   the player has no shuffle action.
     */
    fun findToggleAction(actionIds: List<String>): String? =
        actionIds.firstOrNull { SHUFFLE_MARKER in it }

    /**
     * Derives the current shuffle mode from the player's custom actions.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @return The mode, or `null` if there is no shuffle action or its identifier is unknown,
     *   in which case the PC hides its shuffle button.
     */
    fun modeOf(actionIds: List<String>): ShuffleMode? = when (findToggleAction(actionIds)) {
        "TURN_SHUFFLE_ON" -> ShuffleMode.OFF
        "TURN_SMART_SHUFFLE_ON", "TURN_SHUFFLE_OFF" -> ShuffleMode.ON
        "TURN_SMART_SHUFFLE_OFF" -> ShuffleMode.SMART
        else -> null
    }
}
