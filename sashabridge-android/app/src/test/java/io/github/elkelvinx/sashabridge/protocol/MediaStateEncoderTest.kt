package io.github.elkelvinx.sashabridge.protocol

import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2 messages of one session: when the album art travels (R3, R6, ordering rules 3 and 4)
 * and the message format, checked against the fixtures that the Rust client decodes.
 */
class MediaStateEncoderTest {

    /** JPEG header bytes used by the fixtures. */
    private val fixtureJpeg: ByteArray = Base64.getDecoder().decode("/9j/4AAQSkZJRg==")

    /** The state described by the `valid` fixtures, with the position reported at time 1000. */
    private fun snapshot(art: AlbumArt?, trackId: String = "8f14e45f") = MediaSnapshot(
        trackId = trackId,
        source = "com.spotify.music",
        title = "Sample Track - サンプル",
        artist = "Sample Artist",
        playbackContext = "Del Modo aleatorio inteligente",
        album = "Sample Album",
        durationMs = 215_000,
        isPlaying = true,
        reportedPositionMs = 12_345,
        positionUpdateTimeMs = 1_000,
        playbackSpeed = 1.0f,
        shuffle = ShuffleMode.SMART,
        art = art,
    )

    /** Types of the encoded messages, in order. */
    private fun types(messages: List<PhoneMessage>) = messages.map { it::class.simpleName }

    /** The messages are exactly the v2 fixtures that the Rust client decodes, art first. */
    @Test
    fun matchesTheV2Fixtures() {
        val messages = MediaStateEncoder().encode(snapshot(AlbumArt("a1b2c3d4", fixtureJpeg)), 1_000)

        assertEquals(
            listOf(
                Json.parseToJsonElement(Fixtures.read("phone-to-pc/valid/media-art.json")),
                Json.parseToJsonElement(Fixtures.read("phone-to-pc/valid/media-state.json")),
            ),
            messages.map { Json.parseToJsonElement(ProtocolCodec.encode(it)) },
        )
    }

    /** Later states with the same art only name it. */
    @Test
    fun artIsSentOnlyWhenItChanges() {
        val encoder = MediaStateEncoder()
        val state = snapshot(AlbumArt("a", fixtureJpeg))
        encoder.encode(state, 1_000)

        assertEquals(listOf("MediaState"), types(encoder.encode(state.copy(isPlaying = false), 1_000)))
    }

    /**
     * Going back to a track whose art was sent earlier sends it again: the PC keeps only the
     * latest art.
     */
    @Test
    fun earlierArtIsSentAgain() {
        val encoder = MediaStateEncoder()
        encoder.encode(snapshot(AlbumArt("a", fixtureJpeg), trackId = "1"), 1_000)
        encoder.encode(snapshot(AlbumArt("b", byteArrayOf(1)), trackId = "2"), 1_000)

        val messages = encoder.encode(snapshot(AlbumArt("a", fixtureJpeg), trackId = "1"), 1_000)

        assertEquals(listOf("MediaArt", "MediaState"), types(messages))
    }

    /** Art that arrives after the title is sent as soon as it appears (R3). */
    @Test
    fun artArrivingLateIsSent() {
        val encoder = MediaStateEncoder()
        val first = encoder.encode(snapshot(art = null), 1_000)

        val second = encoder.encode(snapshot(AlbumArt("a", fixtureJpeg)), 1_000)

        assertEquals(listOf("MediaState"), types(first))
        assertEquals(null, (first.single() as PhoneMessage.MediaState).artId)
        assertEquals(listOf("MediaArt", "MediaState"), types(second))
    }

    /** No player becomes media.cleared. */
    @Test
    fun noPlayerIsCleared() {
        assertEquals(listOf(PhoneMessage.MediaCleared), MediaStateEncoder().encode(null, 1_000))
    }

    /** The position is extrapolated to the moment the message is encoded. */
    @Test
    fun positionIsExtrapolatedWhenEncoding() {
        val state = MediaStateEncoder().encode(snapshot(art = null), nowElapsedMs = 3_000).single()

        assertEquals(14_345L, (state as PhoneMessage.MediaState).positionMs)
    }
}
