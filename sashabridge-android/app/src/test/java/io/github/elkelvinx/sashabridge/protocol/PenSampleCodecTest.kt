package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Binary pen-sample frames, checked against the fixture that the Rust client decodes too: the
 * phone must write exactly those bytes.
 */
class PenSampleCodecTest {

    /** The samples that `samples.json` describes. */
    private val expected: List<PenSample> =
        Json.parseToJsonElement(Fixtures.read("pen-samples/samples.json")).jsonObject
            .getValue("samples").jsonArray.map { element ->
                val s = element.jsonObject
                PenSample(
                    timeMs = s.getValue("timeMs").jsonPrimitive.long,
                    x = s.getValue("x").jsonPrimitive.int,
                    y = s.getValue("y").jsonPrimitive.int,
                    pressure = s.getValue("pressure").jsonPrimitive.int,
                    tiltX = s.getValue("tiltX").jsonPrimitive.int,
                    tiltY = s.getValue("tiltY").jsonPrimitive.int,
                    inContact = s.getValue("inContact").jsonPrimitive.boolean,
                    inRange = s.getValue("inRange").jsonPrimitive.boolean,
                    barrel = s.getValue("barrel").jsonPrimitive.boolean,
                    eraser = s.getValue("eraser").jsonPrimitive.boolean,
                )
            }

    /** Encoding the fixture's samples gives exactly the fixture's bytes. */
    @Test
    fun encodesTheSharedFixture() {
        assertArrayEquals(Fixtures.bytes("pen-samples/samples.bin"), PenSampleCodec.encode(expected))
    }

    /** Decoding the fixture's bytes gives the fixture's samples. */
    @Test
    fun decodesTheSharedFixture() {
        assertEquals(expected, PenSampleCodec.decode(Fixtures.bytes("pen-samples/samples.bin")))
    }

    /** Values outside their ranges are clamped instead of wrapping around. */
    @Test
    fun clampsOutOfRangeValues() {
        val wild = PenSample(0, -5, 70_000, 99_999, -120, 120, true, true, false, false)

        val decoded = PenSampleCodec.decode(PenSampleCodec.encode(listOf(wild))).single()

        assertEquals(listOf(0, 0xFFFF, 0xFFFF, -90, 90), listOf(decoded.x, decoded.y, decoded.pressure, decoded.tiltX, decoded.tiltY))
    }

    /** Frames of another kind, another version or the wrong length are rejected. */
    @Test
    fun rejectsMalformedFrames() {
        val frame = Fixtures.bytes("pen-samples/samples.bin")
        assertThrows(ProtocolException::class.java) { PenSampleCodec.decode(frame.copyOf(3)) }
        assertThrows(ProtocolException::class.java) { PenSampleCodec.decode(frame.copyOf().also { it[0] = 0x51 }) }
        assertThrows(ProtocolException::class.java) { PenSampleCodec.decode(frame.copyOf().also { it[1] = 2 }) }
        assertThrows(ProtocolException::class.java) { PenSampleCodec.decode(frame.copyOf(frame.size - 1)) }
        assertEquals(emptyList<PenSample>(), PenSampleCodec.decode(byteArrayOf(0x50, 1, 0, 0)))
    }
}
