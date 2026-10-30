package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.protocol.ShuffleMode
import java.util.Locale

/**
 * Reads and changes the shuffle mode of players that expose it as a custom action.
 *
 * The framework media session has no shuffle state. Spotify instead offers one custom action
 * whose identifier names what pressing it will do, so the identifier reveals the current mode,
 * and sending it moves to the next mode, exactly like the button in the app.
 *
 * Observed on Spotify for Android (October 2026), cycling through every mode: `TURN_SHUFFLE_ON`
 * while shuffle is off, `TURN_SHUFFLE_OFF` while plain shuffle is on, and
 * `TURN_SMART_SHUFFLE_OFF` while smart shuffle is on. Pressing the action moves OFF, then ON,
 * then SMART, then OFF again; the identifier of plain shuffle does not announce that smart
 * shuffle comes next.
 *
 * YouTube Music keeps the identifier `shuffle_action` and says the mode in its name
 * ("Reproducción aleatoria desactivada"), read by [ActionNames]; Echo Music does the same with
 * `TOGGLE_SHUFFLE`.
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
        actionIds.firstOrNull { SHUFFLE_MARKER in it.uppercase(Locale.ROOT) }

    /**
     * Derives the current shuffle mode from the player's custom actions.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @param names Names of those actions, by identifier, for players whose identifier does
     *   not change with the mode.
     * @return The mode, or `null` if there is no shuffle action or neither its identifier nor
     *   its name tells the mode, in which case the PC hides its shuffle button.
     */
    fun modeOf(actionIds: List<String>, names: Map<String, String> = emptyMap()): ShuffleMode? {
        val action = findToggleAction(actionIds) ?: return null
        return when (action) {
            "TURN_SHUFFLE_ON" -> ShuffleMode.OFF
            "TURN_SHUFFLE_OFF" -> ShuffleMode.ON
            "TURN_SMART_SHUFFLE_OFF" -> ShuffleMode.SMART
            else -> when (ActionNames.isOn(names[action])) {
                true -> ShuffleMode.ON
                false -> ShuffleMode.OFF
                null -> null
            }
        }
    }
}
