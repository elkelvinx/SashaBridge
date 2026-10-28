package io.github.elkelvinx.sashabridge.protocol

import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 output of the new pipeline: when the album art travels (R3, R6), how the playback
 * context is shown, and the message format, checked against the v1 fixtures that the Rust
 * client accepts.
 */
class LegacyV1EncoderTest {

    /** JPEG header bytes used by the fixtures. */
    private val fixtureJpeg: ByteArray = Base64.getDecoder().decode("/9j/4AAQSkZJRg==")

    /** The state described by the `legacy-v1` fixtures, with the position reported at time 1000. */
    private fun snapshot(art: AlbumArt?, title: String = "Sample Track", context: String? = null) = MediaSnapshot(
        trackId = "8f14e45f",
        source = "com.spotify.music",
        title = title,
        artist = "Sample Artist",
        playbackContext = context,
        album = null,
        durationMs = 215_000,
        isPlaying = true,
        reportedPositionMs = 12_345,
        positionUpdateTimeMs = 1_000,
        playbackSpeed = 1.0f,
        shuffle = null,
        art = art,
    )

    /** Parses an encoded message. */
    private fun fields(message: String) = Json.parseToJsonElement(message).jsonObject

    /** Whether an encoded message carries album art. */
    private fun hasArt(message: String) = "albumArt" in fields(message)

    /** The first message of a session carries the art and later ones of the same track do not repeat it. */
    @Test
    fun artIsSentOncePerTrack() {
        val session = LegacyV1Session()
        val state = snapshot(AlbumArt("a", fixtureJpeg))

        assertTrue(hasArt(session.encode(state, 1_000)))
        assertFalse(hasArt(session.encode(state.copy(isPlaying = false), 1_000)))
    }

    /**
     * A new title carries the art again even when it is the same image, so that the PC can
     * tell it apart from a track without art. This also covers Spotify filling in the title
     * in two steps for the same track.
     */
    @Test
    fun newTitleCarriesTheArtAgain() {
        val session = LegacyV1Session()
        val art = AlbumArt("album", fixtureJpeg)

        session.encode(snapshot(art, title = "The Night and the Lie"), 1_000)
        assertTrue(hasArt(session.encode(snapshot(art, title = "The Night and the Lie - 夜と嘘"), 1_000)))
    }

    /** A change of playback context changes the shown artist, so the art travels again. */
    @Test
    fun newContextCarriesTheArtAgain() {
        val session = LegacyV1Session()
        val art = AlbumArt("album", fixtureJpeg)

        session.encode(snapshot(art), 1_000)
        assertTrue(hasArt(session.encode(snapshot(art, context = "Del Modo aleatorio inteligente"), 1_000)))
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

    /** v1 has no context field, so the context is shown after the artist, as Spotify does. */
    @Test
    fun contextIsAppendedToTheArtist() {
        val message = LegacyV1Session().encode(snapshot(art = null, context = "Del Modo aleatorio inteligente"), 1_000)
        assertEquals(
            "Sample Artist • Del Modo aleatorio inteligente",
            fields(message).getValue("artist").jsonPrimitive.content,
        )
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
        assertEquals("14345", fields(message).getValue("position").toString())
    }
}
