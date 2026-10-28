package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.media.PlaybackPositionCalculator.UNKNOWN_POSITION
import io.github.elkelvinx.sashabridge.media.PlaybackPositionCalculator.positionAt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Extrapolation of the reported playback position (R5). */
class PlaybackPositionCalculatorTest {

    /** While playing, the position advances with the time elapsed since the report. */
    @Test
    fun advancesWhilePlaying() {
        assertEquals(12_500L, positionAt(10_000, 1_000, 1.0f, true, 200_000, nowMs = 3_500))
    }

    /** The advance is scaled by the playback speed. */
    @Test
    fun appliesPlaybackSpeed() {
        assertEquals(15_000L, positionAt(10_000, 1_000, 2.0f, true, 200_000, nowMs = 3_500))
    }

    /** A paused player stays where it was reported, even if it reports a speed of 1.0 (YouTube does). */
    @Test
    fun doesNotAdvanceWhilePaused() {
        assertEquals(10_000L, positionAt(10_000, 1_000, 1.0f, false, 200_000, nowMs = 60_000))
    }

    /** The position never goes past the end of the track. */
    @Test
    fun clampsToTheDuration() {
        assertEquals(200_000L, positionAt(199_000, 1_000, 1.0f, true, 200_000, nowMs = 10_000))
    }

    /** Without a duration there is no upper bound. */
    @Test
    fun unknownDurationHasNoUpperBound() {
        assertEquals(19_000L, positionAt(10_000, 1_000, 1.0f, true, null, nowMs = 10_000))
    }

    /** An unknown position is reported as the start of the track. */
    @Test
    fun unknownPositionIsZero() {
        assertEquals(0L, positionAt(UNKNOWN_POSITION, 1_000, 1.0f, true, 200_000, nowMs = 5_000))
    }

    /** Without the time of the report there is nothing to extrapolate from. */
    @Test
    fun unknownUpdateTimeIsNotExtrapolated() {
        assertEquals(10_000L, positionAt(10_000, 0, 1.0f, true, 200_000, nowMs = 5_000))
    }

    /** Rewinding never produces a negative position. */
    @Test
    fun rewindingStopsAtZero() {
        assertEquals(0L, positionAt(1_000, 1_000, -4.0f, true, 200_000, nowMs = 5_000))
    }
}
