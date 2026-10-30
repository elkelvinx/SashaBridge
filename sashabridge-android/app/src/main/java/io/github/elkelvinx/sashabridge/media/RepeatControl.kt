package io.github.elkelvinx.sashabridge.media

import java.util.Locale

/** Repeat modes a player can be in. */
enum class RepeatMode {
    /** Playback stops at the end of the queue. */
    OFF,

    /** The whole queue or context starts over. */
    ALL,

    /** The current track starts over. */
    ONE,
}

/**
 * Reads and changes the repeat mode of players that expose it as a custom action.
 *
 * Like shuffle, the framework media session has no repeat state. Spotify offers one custom
 * action whose identifier names what pressing it will do, so the identifier reveals the
 * current mode, and sending it moves to the next mode, exactly like the button in the app.
 * YouTube Music offers `loop_mode_action` and Echo Music `TOGGLE_REPEAT_MODE`; both keep the
 * identifier and say the mode in the name ("Repetición desactivada"), read by [ActionNames].
 *
 * Observed on Spotify for Android (October 2026), cycling through every mode:
 * `TURN_REPEAT_ALL_ON` ("Empezar a repetir todas las canciones") while repeat is off,
 * `TURN_REPEAT_ONE_ON` ("Empezar a repetir una canción") while the whole context repeats, and
 * `TURN_REPEAT_OFF` while one track repeats. Pressing moves OFF, then ALL, then ONE, then OFF.
 *
 * Pure and thread-safe.
 */
object RepeatControl {

    /** Parts of the identifiers of repeat actions: Spotify's and Echo Music's, YouTube Music's. */
    private val REPEAT_MARKERS = listOf("REPEAT", "LOOP")

    /**
     * Finds the custom action that changes the repeat mode.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @return The identifier to send, or `null` if the player has no repeat action.
     */
    fun findToggleAction(actionIds: List<String>): String? =
        actionIds.firstOrNull { id -> REPEAT_MARKERS.any { it in id.uppercase(Locale.ROOT) } }

    /**
     * Derives the current repeat mode from the player's custom actions.
     *
     * In the ONE mode Spotify offers to stop repeating (`TURN_REPEAT_OFF`), so any
     * `TURN_REPEAT_..._OFF` action means that one track repeats.
     *
     * @param actionIds Identifiers of the player's custom actions.
     * @param names Names of those actions, by identifier, for players whose identifier does
     *   not change with the mode.
     * @return The mode, or `null` if there is no repeat action or it does not reveal the mode.
     */
    fun modeOf(actionIds: List<String>, names: Map<String, String> = emptyMap()): RepeatMode? {
        val action = findToggleAction(actionIds) ?: return null
        return when {
            action == "TURN_REPEAT_ALL_ON" -> RepeatMode.OFF
            action == "TURN_REPEAT_ONE_ON" -> RepeatMode.ALL
            action.startsWith("TURN_REPEAT") && action.endsWith("_OFF") -> RepeatMode.ONE
            else -> ActionNames.repeatMode(names[action])
        }
    }
}
