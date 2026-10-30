package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The like button of Spotify and YouTube Music, with the identifiers seen on the phone. */
class LikeControlTest {

    private val spotifyNotSaved = listOf("TURN_SHUFFLE_ON", "ADD_TO", "START_RADIO", "TURN_REPEAT_ONE_ON")
    private val spotifySaved = listOf("TURN_SHUFFLE_ON", "CHECK_FILL", "START_RADIO", "TURN_REPEAT_ONE_ON")
    private val youTubeMusic = listOf("thumbs_up_action", "shuffle_action", "loop_mode_action", "thumbs_down_action")

    /** Spotify's identifier tells whether the track is saved. */
    @Test
    fun spotifyIdentifierRevealsTheState() {
        assertEquals("ADD_TO", LikeControl.findToggleAction(spotifyNotSaved))
        assertEquals(false, LikeControl.likedOf(spotifyNotSaved, ratedUp = null))
        assertEquals("CHECK_FILL", LikeControl.findToggleAction(spotifySaved))
        assertEquals(true, LikeControl.likedOf(spotifySaved, ratedUp = null))
    }

    /** YouTube Music presses the thumb up, never the thumb down, and reads the rating. */
    @Test
    fun youTubeMusicReadsTheRating() {
        assertEquals("thumbs_up_action", LikeControl.findToggleAction(youTubeMusic))
        assertEquals(true, LikeControl.likedOf(youTubeMusic, ratedUp = true))
        assertEquals(false, LikeControl.likedOf(youTubeMusic, ratedUp = false))
        assertNull(LikeControl.likedOf(youTubeMusic, ratedUp = null))
    }

    /** Echo Music's like is found; its state comes from the rating, as with YouTube Music. */
    @Test
    fun echoMusicLike() {
        val echo = listOf("TOGGLE_LIKE", "TOGGLE_REPEAT_MODE", "TOGGLE_SHUFFLE")
        assertEquals("TOGGLE_LIKE", LikeControl.findToggleAction(echo))
        assertEquals(true, LikeControl.likedOf(echo, ratedUp = true))
    }

    /** A player without the button cannot like, whatever its rating says. */
    @Test
    fun playersWithoutTheButton() {
        assertNull(LikeControl.findToggleAction(listOf("TOGGLE_REPEAT_MODE", "TOGGLE_SHUFFLE")))
        assertNull(LikeControl.likedOf(emptyList(), ratedUp = true))
    }
}
