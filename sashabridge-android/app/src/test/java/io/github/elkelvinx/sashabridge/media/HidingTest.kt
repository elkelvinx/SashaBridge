package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of hiding a video with the PC's `Ctrl+Alt+O`. */
class HidingTest {

    private val youTube = "com.google.android.youtube"
    private val ytMusic = "com.google.android.apps.youtube.music"
    private val spotify = "com.spotify.music"

    private fun start(earlier: Hiding? = null, shown: String?, paused: List<String>, nowMs: Long = 10_000L) =
        Hiding.start(earlier, musicSource = ytMusic, fallback = null, nowMs = nowMs, shownSource = shown, pausedNow = paused)

    /** The video shown is the one to go back to, playing or paused by the user (H18). */
    @Test
    fun theShownVideoIsRemembered() {
        assertEquals(youTube, start(shown = youTube, paused = listOf(youTube)).video)
        val pausedByTheUser = start(shown = youTube, paused = emptyList())
        assertEquals(youTube, pausedByTheUser.video)
        assertEquals(emptySet<String>(), pausedByTheUser.paused)
    }

    /** A music app shown when hiding is no video to go back to. */
    @Test
    fun musicIsNoVideo() {
        assertNull(start(shown = spotify, paused = listOf(spotify)).video)
    }

    /** Hiding again keeps the first video and everything paused so far. */
    @Test
    fun hidingAgainKeepsTheFirstOne() {
        val first = start(shown = youTube, paused = listOf(youTube))
        val again = start(earlier = first, shown = ytMusic, paused = listOf(ytMusic), nowMs = 20_000L)
        assertEquals(youTube, again.video)
        assertEquals(setOf(youTube, ytMusic), again.paused)
        assertEquals(20_000L, again.sinceMs)
    }

    /** A player starting during the first second does not end it; later, it does. */
    @Test
    fun aGraceSecondAfterHiding() {
        val hiding = start(shown = youTube, paused = listOf(youTube), nowMs = 10_000L)
        assertFalse(hiding.endsWhenPlayingAt(10_000L + Hiding.GRACE_MS))
        assertTrue(hiding.endsWhenPlayingAt(10_001L + Hiding.GRACE_MS))
    }
}
