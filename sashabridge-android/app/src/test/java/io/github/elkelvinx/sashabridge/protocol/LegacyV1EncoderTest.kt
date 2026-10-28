package io.github.elkelvinx.sashabridge.protocol

import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 output of the new pipeline: the album art resend rule (R3) and the message format,
 * checked against the v1 fixtures that the Rust client accepts.
 */
class LegacyV1EncoderTest {

    /** JPEG header bytes used by the fixtures. */
    private val fixtureJpeg: ByteArray = Base64.getDecoder().decode("/9j/4AAQSkZJRg==")

    /** The state described by the `legacy-v1` fixtures, with the position reported at time 1000. */
    private fun snapshot(art: AlbumArt?) = MediaSnapshot(
        trackId = "8f14e45f",
        source = "com.spotify.music",
        title = "Sample Track",
        artist = "Sample Artist",
        album = null,
        durationMs = 215_000,
        isPlaying = true,
        reportedPositionMs = 12_345,
        positionUpdateTimeMs = 1_000,
        playbackSpeed = 1.0f,
        art = art,
    )

    /** Whether an encoded message carries album art. */
    private fun hasArt(message: String) = "albumArt" in Json.parseToJsonElement(message).jsonObject

    /** The first message of a session carries the art and later ones do not repeat it. */
    @Test
    fun artIsSentOncePerSession() {
        val session = LegacyV1Session()
        val state = snapshot(AlbumArt("a", fixtureJpeg))

        assertTrue(hasArt(session.encode(state, 1_000)))
        assertFalse(hasArt(session.encode(state.copy(isPlaying = false), 1_000)))
    }

    /** New art is sent as soon as it appears, even for the same track (art that arrives late, R3). */
    @Test
    fun artArrivingLateIsSent() {
        val session = LegacyV1Session()

        assertFalse(hasArt(session.encode(snapshot(art = null), 1_000)))
        assertTrue(hasArt(session.encode(snapshot(AlbumArt("a", fixtureJpeg)), 1_000)))
        assertTrue(hasArt(session.encode(snapshot(AlbumArt("b", byteArrayOf(1))), 1_000)))
    }

    /** A PC that reconnects gets the art again, because each connection is a new session. */
    @Test
    fun newSessionReceivesTheArtAgain() {
        val state = snapshot(AlbumArt("a", fixtureJpeg))
        LegacyV1Session().encode(state, 1_000)

        assertTrue(hasArt(LegacyV1Session().encode(state, 1_000)))
    }

    /** The messages are exactly the v1 fixtures that the Rust client decodes. */
    @Test
    fun matchesTheV1Fixtures() {
        val session = LegacyV1Session()
        val state = snapshot(AlbumArt("a", fixtureJpeg))

        assertEquals(
            Json.parseToJsonElement(Fixtures.read("phone-to-pc/legacy-v1/media-with-art.json")),
            Json.parseToJsonElement(session.encode(state, 1_000)),
        )
        assertEquals(
            Json.parseToJsonElement(Fixtures.read("phone-to-pc/legacy-v1/media.json")),
            Json.parseToJsonElement(session.encode(state, 1_000)),
        )
    }

    /** The position is extrapolated to the moment the message is encoded. */
    @Test
    fun positionIsExtrapolatedWhenEncoding() {
        val message = LegacyV1Session().encode(snapshot(art = null), nowElapsedMs = 3_000)
        assertEquals("14345", Json.parseToJsonElement(message).jsonObject["position"].toString())
    }
}
