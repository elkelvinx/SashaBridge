package io.github.elkelvinx.sashabridge.media

import java.util.Locale

/**
 * Reads a mode from the name of a player's custom action, for players whose identifiers
 * stay the same in every mode. YouTube Music, for example, keeps `shuffle_action` and
 * `loop_mode_action` and describes the mode in the name: "Reproducción aleatoria
 * desactivada", "Repetición desactivada". The names follow the phone's language, so the
 * words are matched in Spanish and English.
 *
 * Pure and thread-safe.
 */
internal object ActionNames {

    /** Words of a mode that is off. Checked first: "desactivada" contains "activada". */
    private val OFF = listOf("desactiv", "inhabilit", "apagad", "ningun", "off", "disabled", "none")

    /** Words of a mode that is on. */
    private val ON = listOf("activ", "encendid", " on", "enabled")

    /** Words of repeating one track. */
    private val ONE = listOf("una", "uno", "canción", "cancion", "one", "once", "song", "track")

    /** Words of repeating the whole list. */
    private val ALL = listOf("todo", "todas", "lista", "all", "playlist", "queue")

    /** Whether [name] says the mode is off, on, or neither (`null`). */
    fun isOn(name: String?): Boolean? {
        val text = name?.lowercase(Locale.ROOT) ?: return null
        return when {
            OFF.any { it in text } -> false
            ON.any { it in text } -> true
            else -> null
        }
    }

    /**
     * The repeat mode [name] describes, or `null` if it says none. The whole list is checked
     * before one track ("todas las canciones" contains "cancion"), and a plain "on"
     * ("Repetición activada", as YouTube Music says) means the whole list.
     */
    fun repeatMode(name: String?): RepeatMode? {
        val text = name?.lowercase(Locale.ROOT) ?: return null
        return when {
            OFF.any { it in text } -> RepeatMode.OFF
            ALL.any { it in text } -> RepeatMode.ALL
            ONE.any { it in text } -> RepeatMode.ONE
            ON.any { it in text } -> RepeatMode.ALL
            else -> null
        }
    }
}
