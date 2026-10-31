package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Repeat mode read from, and changed through, the custom actions of a player. */
class RepeatControlTest {

    /** The other custom actions that Spotify exposes next to the repeat one. */
    private val otherSpotifyActions = listOf("TURN_SHUFFLE_ON", "CHECK_FILL", "START_RADIO")

    /** The identifiers Spotify exposed on the device while cycling every mode. */
    @Test
    fun readsTheObservedSpotifyModes() {
        assertEquals(RepeatMode.OFF, RepeatControl.modeOf(otherSpotifyActions + "TURN_REPEAT_ALL_ON"))
        assertEquals(RepeatMode.ALL, RepeatControl.modeOf(otherSpotifyActions + "TURN_REPEAT_ONE_ON"))
        assertEquals(RepeatMode.ONE, RepeatControl.modeOf(otherSpotifyActions + "TURN_REPEAT_OFF"))
        assertEquals("TURN_REPEAT_ALL_ON", RepeatControl.findToggleAction(otherSpotifyActions + "TURN_REPEAT_ALL_ON"))
    }

    /** Offering to stop repeating means that one track repeats. */
    @Test
    fun anOffActionMeansRepeatOne() {
        assertEquals(RepeatMode.ONE, RepeatControl.modeOf(listOf("TURN_REPEAT_ONE_OFF")))
        assertEquals(RepeatMode.ONE, RepeatControl.modeOf(listOf("TURN_REPEAT_OFF")))
    }

    /** Echo Music's action can be pressed, but its identifier does not tell the mode. */
    @Test
    fun aToggleWithoutModeCanStillBePressed() {
        val echo = listOf("TOGGLE_LIKE", "TOGGLE_REPEAT_MODE", "TOGGLE_SHUFFLE")
        assertEquals("TOGGLE_REPEAT_MODE", RepeatControl.findToggleAction(echo))
        assertNull(RepeatControl.modeOf(echo))
    }

    /** A player without a repeat action, such as YouTube Music, has neither. */
    @Test
    fun playersWithoutRepeatActionHaveNothing() {
        assertNull(RepeatControl.findToggleAction(otherSpotifyActions))
        assertNull(RepeatControl.modeOf(emptyList()))
    }

    /** YouTube Music's loop action says the mode in its name. */
    @Test
    fun readsYouTubeMusicFromTheName() {
        val ids = listOf("thumbs_up_action", "shuffle_action", "loop_mode_action", "thumbs_down_action")

        assertEquals("loop_mode_action", RepeatControl.findToggleAction(ids))
        assertEquals(RepeatMode.OFF, RepeatControl.modeOf(ids, mapOf("loop_mode_action" to "Repetición desactivada")))
        assertEquals(RepeatMode.ALL, RepeatControl.modeOf(ids, mapOf("loop_mode_action" to "Repetir todo")))
        assertEquals(RepeatMode.ONE, RepeatControl.modeOf(ids, mapOf("loop_mode_action" to "Repetir una canción")))
    }

    /** The names in YouTube Music's own Spanish texts, and their English counterparts. */
    @Test
    fun readsEveryYouTubeMusicName() {
        val spanish = mapOf(
            "Repetición desactivada" to RepeatMode.OFF,
            "Repetición inhabilitada" to RepeatMode.OFF,
            "Repetición activada" to RepeatMode.ALL,
            "Repetir todas las canciones" to RepeatMode.ALL,
            "Repetir uno" to RepeatMode.ONE,
            "Repetir una vez" to RepeatMode.ONE,
        )
        val english = mapOf(
            "Repeat off" to RepeatMode.OFF,
            "Repeat disabled" to RepeatMode.OFF,
            "Repeat on" to RepeatMode.ALL,
            "Repeat all" to RepeatMode.ALL,
            "Repeat one" to RepeatMode.ONE,
            "Repeat once" to RepeatMode.ONE,
        )

        for ((name, mode) in spanish + english) {
            assertEquals(name, mode, ActionNames.repeatMode(name))
        }
        assertNull(ActionNames.repeatMode("Reproducir"))
    }
}
