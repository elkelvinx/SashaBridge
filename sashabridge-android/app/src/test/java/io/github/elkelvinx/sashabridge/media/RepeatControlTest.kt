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
}
