package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.protocol.ShuffleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shuffle mode read from, and toggled through, the custom actions of a player. */
class ShuffleControlTest {

    /** The other custom actions that Spotify exposes next to the shuffle one. */
    private val otherSpotifyActions = listOf("CHECK_FILL", "START_RADIO", "TURN_REPEAT_ONE_ON")

    /** The custom actions Spotify exposed on the device while smart shuffle was on. */
    @Test
    fun readsSmartShuffleFromTheObservedSpotifyActions() {
        val actions = listOf("TURN_SMART_SHUFFLE_OFF") + otherSpotifyActions
        assertEquals(ShuffleMode.SMART, ShuffleControl.modeOf(actions))
        assertEquals("TURN_SMART_SHUFFLE_OFF", ShuffleControl.findToggleAction(actions))
    }

    /** The identifier names what pressing the action does, which reveals the current mode. */
    @Test
    fun mapsEveryShuffleIdentifier() {
        assertEquals(ShuffleMode.OFF, ShuffleControl.modeOf(listOf("TURN_SHUFFLE_ON")))
        assertEquals(ShuffleMode.ON, ShuffleControl.modeOf(listOf("TURN_SMART_SHUFFLE_ON")))
        assertEquals(ShuffleMode.ON, ShuffleControl.modeOf(listOf("TURN_SHUFFLE_OFF")))
    }

    /** A player without a shuffle action, such as YouTube, has no controllable mode. */
    @Test
    fun playersWithoutShuffleActionHaveNoMode() {
        assertNull(ShuffleControl.modeOf(emptyList()))
        assertNull(ShuffleControl.findToggleAction(otherSpotifyActions))
    }

    /** An unknown shuffle identifier can still be pressed, but its mode cannot be told. */
    @Test
    fun unknownShuffleIdentifierHasNoMode() {
        assertNull(ShuffleControl.modeOf(listOf("SHUFFLE_SOMETHING_NEW")))
        assertEquals("SHUFFLE_SOMETHING_NEW", ShuffleControl.findToggleAction(listOf("SHUFFLE_SOMETHING_NEW")))
    }
}
