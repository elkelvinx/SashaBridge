package io.github.elkelvinx.sashabridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The way of the track changes that follow the user's presses. */
class SkipIntentTest {

    /** Next three times and back three times: the latest press decides (Spotify only shows the end). */
    @Test
    fun theLatestPressDecides() {
        val skips = SkipIntent()
        skips.press(1, 0L)
        skips.press(1, 400L)
        skips.press(-1, 800L)
        assertEquals(-1, skips.recent(2_000L))
    }

    /** A change long after the last press is someone else's: the queue decides it. */
    @Test
    fun aPressOnlyDecidesForAWhile() {
        val skips = SkipIntent()
        assertNull(skips.recent(0L))
        skips.press(-1, 1_000L)
        assertEquals(-1, skips.recent(1_000L + SkipIntent.WINDOW_MS))
        assertNull(skips.recent(1_001L + SkipIntent.WINDOW_MS))
    }

    /** "Previous" far into a track starts it over; right after another press it goes back. */
    @Test
    fun previousRestartsOnlyFarIntoAQuietTrack() {
        val skips = SkipIntent()
        assertTrue(skips.restarts(positionMs = 72_000L, nowMs = 0L))
        assertFalse(skips.restarts(positionMs = 1_000L, nowMs = 0L))
        skips.press(-1, 10_000L)
        assertFalse(skips.restarts(positionMs = 72_000L, nowMs = 10_500L))
        assertTrue(skips.restarts(positionMs = 72_000L, nowMs = 20_000L))
    }
}
