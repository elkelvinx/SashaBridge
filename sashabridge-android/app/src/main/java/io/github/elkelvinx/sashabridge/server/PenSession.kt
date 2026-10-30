package io.github.elkelvinx.sashabridge.server

import android.os.SystemClock
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.pen.AnnotationRepository
import io.github.elkelvinx.sashabridge.pen.TabletOutgoing
import io.github.elkelvinx.sashabridge.pen.TabletRepository
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import io.github.elkelvinx.sashabridge.protocol.ProtocolCodec
import io.github.elkelvinx.sashabridge.protocol.ProtocolException
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Conversation with one PC on the `/pen` route (section 9 of `docs/protocolo-v2.md`).
 *
 * The phone greets first, then reports whether the annotation screen waits for captures and
 * whether the tablet screen is open, and keeps reporting every change.
 * - "Anotar": captures from the PC go to [annotations]; the screen's answers come back from
 *   it and are sent to the PC.
 * - "Tableta": the monitor and the pongs from the PC go to [tablet]; the screen's pen frames
 *   and messages come back from it and are sent to the PC, the frames as binary frames.
 *
 * Runs on the coroutine of the WebSocket handler, on Ktor's dispatcher.
 *
 * @param socket The open WebSocket.
 * @param annotations Shared state of the "Anotar" mode.
 * @param tablet Shared state of the "Tableta" mode.
 * @param deviceName Phone model announced in `hello`.
 * @param uptime Returns the phone's uptime in milliseconds, the clock of the pings.
 */
internal class PenSession(
    private val socket: WebSocketSession,
    private val annotations: AnnotationRepository,
    private val tablet: TabletRepository,
    private val deviceName: String,
    private val uptime: () -> Long = SystemClock::uptimeMillis,
) {

    /** Serves the session until the PC disconnects or the socket is closed. */
    suspend fun run() = coroutineScope {
        val sender = launch {
            try {
                send(PhoneMessage.Hello(ProtocolCodec.PROTOCOL_VERSION, deviceName))
                launch { annotations.armed.collect { send(PhoneMessage.AnnotateStatus(it)) } }
                launch { tablet.active.collect { send(PhoneMessage.TabletStatus(it)) } }
                launch { annotations.shared.collect { send(it) } }
                launch {
                    tablet.outgoing.collect { outgoing ->
                        when (outgoing) {
                            is TabletOutgoing.Samples -> socket.send(Frame.Binary(true, outgoing.frame))
                            is TabletOutgoing.Message -> send(outgoing.message)
                        }
                    }
                }
                annotations.replies.collect { send(it) }
            } catch (_: ClosedSendChannelException) {
                // The PC went away; the receive loop ends the session.
            }
        }
        try {
            for (frame in socket.incoming) {
                if (frame is Frame.Text) handle(frame.readText())
            }
        } finally {
            sender.cancel()
        }
    }

    /** Sends one message as a text frame. */
    private suspend fun send(message: PhoneMessage) {
        socket.send(Frame.Text(ProtocolCodec.encode(message)))
    }

    /**
     * Acts on a text frame from the PC: keeps captures and the tablet's monitor, measures
     * pongs, logs the greeting, and logs and drops anything else, as the protocol requires.
     *
     * @param text Content of the frame.
     */
    private fun handle(text: String) {
        val message = try {
            ProtocolCodec.decodePcMessage(text)
        } catch (error: ProtocolException) {
            Log.w(LOG_TAG, "Ignoring a frame on the pen route: ${error.message}")
            return
        }
        when (message) {
            is PcMessage.AnnotateRequest -> {
                Log.i(LOG_TAG, "Capture ${message.requestId} received: ${message.width}x${message.height}")
                annotations.offer(message)
            }
            is PcMessage.TabletTarget -> {
                Log.i(LOG_TAG, "Tablet mapped to monitor ${message.monitor + 1} of ${message.count}: ${message.name}")
                tablet.setTarget(message)
            }
            is PcMessage.TabletPong -> tablet.recordPong(message.timeMs, TabletRepository.wireTime(uptime()))
            is PcMessage.Hello -> Log.i(LOG_TAG, "PC client ${message.client} opened the pen route")
            is PcMessage.Command -> Log.w(LOG_TAG, "Ignoring a cmd on the pen route")
        }
    }
}
