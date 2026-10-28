package io.github.elkelvinx.sashabridge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One reading of the S Pen, as sent to the PC in tablet mode.
 *
 * Immutable; can be shared between threads.
 *
 * @property timeMs Phone uptime in milliseconds, wrapping at 2^32; only differences matter.
 * @property x Horizontal position over the drawing surface, from 0 (left) to 65535 (right).
 * @property y Vertical position over the drawing surface, from 0 (top) to 65535 (bottom).
 * @property pressure Pressure from 0 (none) to 65535 (full).
 * @property tiltX Tilt towards the right, in degrees from -90 to 90.
 * @property tiltY Tilt towards the user, in degrees from -90 to 90.
 * @property inContact Whether the tip touches the screen.
 * @property inRange Whether the pen is within hover range, touching or not.
 * @property barrel Whether the side button is pressed.
 * @property eraser Whether the pen erases.
 */
data class PenSample(
    val timeMs: Long,
    val x: Int,
    val y: Int,
    val pressure: Int,
    val tiltX: Int,
    val tiltY: Int,
    val inContact: Boolean,
    val inRange: Boolean,
    val barrel: Boolean,
    val eraser: Boolean,
)

/**
 * Binary frames of [PenSample]s for the `/pen` route.
 *
 * The specification is the "Muestras del lápiz" section of `docs/protocolo-v2.md`: a 4-byte
 * header (kind, version, sample count) and 14 little-endian bytes per sample. JSON would cost
 * several times the bytes at the pen's ~240 samples per second.
 *
 * Pure and thread-safe.
 */
object PenSampleCodec {

    /** First byte of a frame of pen samples. */
    private const val KIND: Byte = 0x50

    /** Format version in the second byte. */
    private const val VERSION: Byte = 1

    private const val HEADER_LEN = 4
    private const val SAMPLE_LEN = 14

    private const val IN_CONTACT = 1
    private const val IN_RANGE = 2
    private const val BARREL = 4
    private const val ERASER = 8

    /** Largest number of samples one frame can carry. */
    const val MAX_SAMPLES: Int = 0xFFFF

    /**
     * Encodes [samples] as one frame. Values outside their ranges are clamped.
     *
     * @param samples At most [MAX_SAMPLES] samples.
     * @return The frame bytes.
     * @throws IllegalArgumentException if there are more than [MAX_SAMPLES] samples.
     */
    fun encode(samples: List<PenSample>): ByteArray {
        require(samples.size <= MAX_SAMPLES) { "a frame carries at most $MAX_SAMPLES samples" }
        val buffer = ByteBuffer.allocate(HEADER_LEN + samples.size * SAMPLE_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(KIND).put(VERSION).putShort(samples.size.toShort())
        for (sample in samples) {
            buffer.putInt(sample.timeMs.toInt())
            buffer.putShort(sample.x.coerceIn(0, 0xFFFF).toShort())
            buffer.putShort(sample.y.coerceIn(0, 0xFFFF).toShort())
            buffer.putShort(sample.pressure.coerceIn(0, 0xFFFF).toShort())
            buffer.put(sample.tiltX.coerceIn(-90, 90).toByte())
            buffer.put(sample.tiltY.coerceIn(-90, 90).toByte())
            buffer.put(flagsOf(sample).toByte())
            buffer.put(0)
        }
        return buffer.array()
    }

    /**
     * Decodes a frame. The phone only sends frames; the decoder serves the tests.
     *
     * @param frame Frame bytes.
     * @return The samples in order.
     * @throws ProtocolException if [frame] is not a pen-sample frame of a known version, or its
     *   length does not match its sample count.
     */
    fun decode(frame: ByteArray): List<PenSample> {
        if (frame.size < HEADER_LEN) throw invalid("pen frame too short: ${frame.size} bytes")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.get() != KIND) throw invalid("not a pen-sample frame")
        val version = buffer.get()
        if (version != VERSION) throw invalid("unknown pen-sample format version $version")
        val count = buffer.short.toInt() and 0xFFFF
        if (frame.size != HEADER_LEN + count * SAMPLE_LEN) {
            throw invalid("pen frame announces $count samples but carries ${frame.size - HEADER_LEN} bytes")
        }
        return List(count) {
            val timeMs = buffer.int.toLong() and 0xFFFF_FFFFL
            val x = buffer.short.toInt() and 0xFFFF
            val y = buffer.short.toInt() and 0xFFFF
            val pressure = buffer.short.toInt() and 0xFFFF
            val tiltX = buffer.get().toInt()
            val tiltY = buffer.get().toInt()
            val flags = buffer.get().toInt()
            buffer.get()
            PenSample(
                timeMs = timeMs,
                x = x,
                y = y,
                pressure = pressure,
                tiltX = tiltX,
                tiltY = tiltY,
                inContact = flags and IN_CONTACT != 0,
                inRange = flags and IN_RANGE != 0,
                barrel = flags and BARREL != 0,
                eraser = flags and ERASER != 0,
            )
        }
    }

    /** Packs the state bits of [sample]. */
    private fun flagsOf(sample: PenSample): Int =
        (if (sample.inContact) IN_CONTACT else 0) or
            (if (sample.inRange) IN_RANGE else 0) or
            (if (sample.barrel) BARREL else 0) or
            (if (sample.eraser) ERASER else 0)

    /** A decoding failure with a [ProtocolException]'s shape. */
    private fun invalid(message: String) = ProtocolException(message, IllegalArgumentException(message))
}
