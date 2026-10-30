package io.github.elkelvinx.sashabridge.pen

import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Shared state of the "Anotar" mode, between the `/pen` route of the server and the
 * annotation screen.
 *
 * - The screen reports whether it waits for captures ([armed]), and the PC learns it through
 *   `annotate.status`.
 * - Captures from the PC wait in [pendingCapture] until the screen takes them. A capture with
 *   the same image as the one taken last, arriving within [REPEAT_WINDOW_MS], is turned down
 *   with `annotate.cancelled`, so a capture the PC sends twice opens once.
 * - The screen's answers ([replies]) go back to the PC.
 *
 * One process-wide instance, [shared]; tests create their own. Every member is thread-safe.
 *
 * @param clock Milliseconds of a monotonic clock, replaceable in tests.
 */
class AnnotationRepository(private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {

    private val mutableArmed = MutableStateFlow(false)
    private val mutablePenConnected = MutableStateFlow(false)
    private val mutablePendingCapture = MutableStateFlow<PcMessage.AnnotateRequest?>(null)
    private val mutableReplies = MutableSharedFlow<PhoneMessage>(
        extraBufferCapacity = REPLY_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Image of the capture taken last and when, or `null`. Guarded by `this`. */
    private var lastTaken: TakenImage? = null

    /** Whether the annotation screen is open and waiting, so the PC may send copied images. */
    val armed: StateFlow<Boolean> = mutableArmed.asStateFlow()

    /** Whether a PC is connected to the `/pen` route, so an annotation can be sent back. */
    val penConnected: StateFlow<Boolean> = mutablePenConnected.asStateFlow()

    /** Capture received from the PC and not yet opened, or `null`. */
    val pendingCapture: StateFlow<PcMessage.AnnotateRequest?> = mutablePendingCapture.asStateFlow()

    /** Answers for the PC: `annotate.result` or `annotate.cancelled`. */
    val replies: SharedFlow<PhoneMessage> = mutableReplies.asSharedFlow()

    /**
     * Records whether the annotation screen waits for captures. Any thread.
     *
     * @param armed `true` while the screen is visible and has no capture open.
     */
    fun setArmed(armed: Boolean) {
        mutableArmed.value = armed
    }

    /**
     * Records how many PCs are connected to the `/pen` route. Any thread.
     *
     * @param sessions Number of open `/pen` sessions.
     */
    fun setPenSessions(sessions: Int) {
        mutablePenConnected.value = sessions > 0
    }

    /**
     * Keeps a capture from the PC until the screen opens it. A newer capture replaces one that
     * was never opened; a repeat of the capture taken last is turned down instead. Any thread.
     *
     * @param capture The capture.
     */
    fun offer(capture: PcMessage.AnnotateRequest) {
        val repeated = synchronized(this) {
            lastTaken?.let { it.matches(capture.data) && clock() - it.at < REPEAT_WINDOW_MS } == true
        }
        if (repeated) {
            reply(PhoneMessage.AnnotateCancelled(capture.requestId))
            return
        }
        mutablePendingCapture.value = capture
    }

    /**
     * Takes the pending capture, if there is one. Any thread.
     *
     * @return The capture, which is no longer pending, or `null`.
     */
    fun take(): PcMessage.AnnotateRequest? {
        val capture = mutablePendingCapture.getAndUpdate { null } ?: return null
        synchronized(this) { lastTaken = TakenImage(capture.data.length, capture.data.hashCode(), clock()) }
        return capture
    }

    /**
     * Sends an answer to the PC. Any thread.
     *
     * @param reply `annotate.result` or `annotate.cancelled`.
     */
    fun reply(reply: PhoneMessage) {
        mutableReplies.tryEmit(reply)
    }

    /**
     * Fingerprint of a taken capture's base64 image: its length and hash.
     *
     * @property at When it was taken, in [clock] milliseconds.
     */
    private data class TakenImage(val length: Int, val hash: Int, val at: Long) {

        /** Whether [data] is, in all likelihood, the same image. */
        fun matches(data: String): Boolean = data.length == length && data.hashCode() == hash
    }

    companion object {

        /** Answers buffered while the `/pen` session sends the previous ones. */
        private const val REPLY_BUFFER = 4

        /** How long a repeat of the capture taken last is turned down. */
        const val REPEAT_WINDOW_MS = 10_000L

        private const val NANOS_PER_MILLI = 1_000_000L

        /** The process-wide instance. */
        val shared = AnnotationRepository()
    }
}
