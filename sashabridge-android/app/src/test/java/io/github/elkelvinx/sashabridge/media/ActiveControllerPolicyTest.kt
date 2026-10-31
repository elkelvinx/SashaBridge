package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Choice of the player that the bridge follows. */
class ActiveControllerPolicyTest {

    /** Builds a candidate whose player and package are both [name]. */
    private fun candidate(name: String, active: Boolean, changedAt: Long, order: Int) =
        ControllerCandidate(name, name, active, changedAt, order)

    /** E4: a playing player wins over a paused one, even if the paused one is first in the system list. */
    @Test
    fun playingPlayerWinsOverPausedPlayer() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(
                candidate("youtube", active = false, changedAt = 0, order = 0),
                candidate("spotify", active = true, changedAt = 0, order = 1),
            ),
        )
        assertEquals("spotify", chosen)
    }

    /** A playing player wins even when a paused one changed state more recently. */
    @Test
    fun playingWinsOverMoreRecentPause() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(
                candidate("spotify", active = true, changedAt = 1_000, order = 1),
                candidate("youtube", active = false, changedAt = 5_000, order = 0),
            ),
        )
        assertEquals("spotify", chosen)
    }

    /** With two players playing, the one that started most recently wins. */
    @Test
    fun mostRecentAmongPlayingPlayers() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(
                candidate("spotify", active = true, changedAt = 1_000, order = 0),
                candidate("ytmusic", active = true, changedAt = 2_000, order = 1),
            ),
        )
        assertEquals("ytmusic", chosen)
    }

    /** After pausing, the bridge keeps following the player that was just paused. */
    @Test
    fun followsTheLastPausedPlayer() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(
                candidate("youtube", active = false, changedAt = 1_000, order = 0),
                candidate("spotify", active = false, changedAt = 9_000, order = 1),
            ),
        )
        assertEquals("spotify", chosen)
    }

    /** Without any observed change, the system order decides. */
    @Test
    fun systemOrderBreaksTies() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(
                candidate("second", active = false, changedAt = 0, order = 1),
                candidate("first", active = false, changedAt = 0, order = 0),
            ),
        )
        assertEquals("first", chosen)
    }

    /** No candidates, no player. */
    @Test
    fun noCandidatesGivesNull() {
        assertNull(ActiveControllerPolicy.choose(emptyList<ControllerCandidate<String>>()))
    }

    /** While a video is hidden, the paused music player wins over anything that plays. */
    @Test
    fun aPinnedPlayerWinsOverAPlayingOne() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(
                candidate("youtube", active = true, changedAt = 9_000, order = 0),
                candidate("spotify", active = false, changedAt = 1_000, order = 1),
            ),
            pinnedSource = "spotify",
        )
        assertEquals("spotify", chosen)
    }

    /** Without a session of the pinned player there is no player, never the video. */
    @Test
    fun aMissingPinnedPlayerGivesNull() {
        val chosen = ActiveControllerPolicy.choose(
            listOf(candidate("youtube", active = true, changedAt = 9_000, order = 0)),
            pinnedSource = "spotify",
        )
        assertNull(chosen)
    }
}
