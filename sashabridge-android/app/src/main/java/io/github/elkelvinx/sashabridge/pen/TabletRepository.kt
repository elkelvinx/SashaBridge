package io.github.elkelvinx.sashabridge.pen

import io.github.elkelvinx.sashabridge.protocol.MAX_UPTIME_MS
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state of the "Tableta" mode, between the `/pen` route of the server and the tablet
 * screen.
 *
 * - The screen reports whether it is open ([active]), and the PC learns it through
 *   `tablet.status`.
 * - The PC says which monitor the tablet draws on ([target]).
 * - The screen's pen samples and messages go to the PC through [outgoing]; nothing waits for
 *   a PC that is not connected, since stale pen samples are worse than none.
 * - The round trips the screen measures become [latencyMs].
 *
 * One process-wide instance, [shared]; tests create their own. Every member is thread-safe.
 */
class TabletRepository {

    private val mutableActive = MutableStateFlow(false)
    private val mutableTarget = MutableStateFlow<PcMessage.TabletTarget?>(null)
    private val mutableLatency = MutableStateFlow<Long?>(null)
    private val mutableOutgoing = MutableSharedFlow<TabletOutgoing>(
        extraBufferCapacity = OUTGOING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Whether the tablet screen is open. */
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    /** Monitor the tablet draws on, or `null` while no PC said so. */
    val target: StateFlow<PcMessage.TabletTarget?> = mutableTarget.asStateFlow()

    /** Estimated one-way latency to the PC (half the last round trip), or `null`. */
    val latencyMs: StateFlow<Long?> = mutableLatency.asStateFlow()

    /** Pen frames and messages for the PC, in order. */
    val outgoing: SharedFlow<TabletOutgoing> = mutableOutgoing.asSharedFlow()

    /**
     * Records whether the tablet screen is open. Any thread.
     *
     * @param active `true` while the screen is visible.
     */
    fun setActive(active: Boolean) {
        mutableActive.value = active
    }

    /**
     * Records the monitor the PC maps the tablet to; `null` when the PC went away. Any thread.
     *
     * @param target The monitor, or `null`.
     */
    fun setTarget(target: PcMessage.TabletTarget?) {
        mutableTarget.value = target
        if (target == null) mutableLatency.value = null
    }

    /**
     * Sends a frame of pen samples to the PC, if one is connected. Any thread.
     *
     * @param frame A frame from [io.github.elkelvinx.sashabridge.protocol.PenSampleCodec].
     */
    fun sendSamples(frame: ByteArray) {
        mutableOutgoing.tryEmit(TabletOutgoing.Samples(frame))
    }

    /**
     * Sends a message to the PC, if one is connected. Any thread.
     *
     * @param message `tablet.select` or `tablet.ping`.
     */
    fun send(message: PhoneMessage) {
        mutableOutgoing.tryEmit(TabletOutgoing.Message(message))
    }

    /**
     * Records the answer to a ping. Any thread.
     *
     * @param sentMs The ping's `timeMs`.
     * @param nowMs Phone uptime now, in the same clock.
     */
    fun recordPong(sentMs: Long, nowMs: Long) {
        mutableLatency.value = roundTripMs(sentMs, nowMs) / 2
    }

    companion object {

        /** Frames buffered while the `/pen` session sends the previous ones. */
        private const val OUTGOING_BUFFER = 256

        /** The process-wide instance. */
        val shared = TabletRepository()

        /**
         * Time between [sentMs] and [nowMs] on the 32-bit clock of the pen samples, which
         * wraps around.
         */
        fun roundTripMs(sentMs: Long, nowMs: Long): Long = (nowMs - sentMs) and MAX_UPTIME_MS

        /** [uptimeMs] on the 32-bit clock of the pen samples and pings. */
        fun wireTime(uptimeMs: Long): Long = uptimeMs and MAX_UPTIME_MS
    }
}

/** What the tablet screen sends to the PC. Immutable. */
sealed interface TabletOutgoing {

    /** A binary frame of pen samples. */
    class Samples(val frame: ByteArray) : TabletOutgoing

    /** A JSON message. */
    data class Message(val message: PhoneMessage) : TabletOutgoing
}
