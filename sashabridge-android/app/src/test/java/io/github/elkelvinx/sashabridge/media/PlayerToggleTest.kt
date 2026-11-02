package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.protocol.ShuffleMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling whether a player ignored a press of one of its buttons. */
class PlayerToggleTest {

    private fun snapshot(
        trackId: String = "t1",
        source: String = "com.spotify.music",
        liked: Boolean? = true,
        shuffle: ShuffleMode? = ShuffleMode.OFF,
        repeat: RepeatMode? = null,
    ) = MediaSnapshot(
        trackId = trackId,
        source = source,
        title = "Black Rover",
        artist = "VK Blanka",
        playbackContext = null,
        album = null,
        durationMs = null,
        isPlaying = true,
        reportedPositionMs = 0L,
        positionUpdateTimeMs = 0L,
        playbackSpeed = 1f,
        shuffle = shuffle,
        art = null,
        repeat = repeat,
        liked = liked,
    )

    /** Spotify keeps the song liked after a press to take it out: ignored. */
    @Test
    fun sameStateOnTheSameTrackIsIgnored() {
        assertTrue(PlayerToggle.LIKE.ignored(snapshot(), snapshot()))
        assertTrue(PlayerToggle.SHUFFLE.ignored(snapshot(), snapshot()))
    }

    /** The state changed: the player obeyed. */
    @Test
    fun aChangedStateIsObeyed() {
        assertFalse(PlayerToggle.LIKE.ignored(snapshot(liked = true), snapshot(liked = false)))
        assertFalse(PlayerToggle.SHUFFLE.ignored(snapshot(), snapshot(shuffle = ShuffleMode.ON)))
    }

    /** Another track, another player or no player: nothing to tell. */
    @Test
    fun anotherTrackTellsNothing() {
        assertFalse(PlayerToggle.LIKE.ignored(snapshot(), snapshot(trackId = "t2")))
        assertFalse(PlayerToggle.LIKE.ignored(snapshot(), snapshot(source = "com.google.android.apps.youtube.music")))
        assertFalse(PlayerToggle.LIKE.ignored(snapshot(), null))
    }

    /** A player that hides the state, such as Echo Music's repeat, never counts as ignoring. */
    @Test
    fun anUnknownStateTellsNothing() {
        assertFalse(PlayerToggle.REPEAT.ignored(snapshot(repeat = null), snapshot(repeat = null)))
        assertFalse(PlayerToggle.LIKE.ignored(snapshot(liked = null), snapshot(liked = null)))
    }
}
