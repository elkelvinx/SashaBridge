package io.github.elkelvinx.sashabridge.server

import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.protocol.MediaStateEncoder
import io.github.elkelvinx.sashabridge.protocol.PcFrame
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import io.github.elkelvinx.sashabridge.protocol.ProtocolCodec
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Protocol v2 conversation with one connected PC.
 *
 * The phone greets first and then sends the current state without waiting for a change (R2),
 * because [media] is a StateFlow: a new collector receives its value right away. Every later
 * change follows. Frames from the PC are read in either protocol version.
 *
 * Runs on the coroutine of the WebSocket handler, on Ktor's dispatcher. Only the sender
 * coroutine writes to the socket, so frames never interleave.
 *
 * @param socket The open WebSocket.
 * @param media State of the active player.
 * @param deviceName Phone model announced in `hello`.
 * @param clock Returns `SystemClock.elapsedRealtime()`, used to extrapolate the position.
 * @param onCommand Runs a command from the PC. Called on Ktor's dispatcher.
 */
internal class ClientSession(
    private val socket: WebSocketSession,
    private val media: StateFlow<MediaSnapshot?>,
    private val deviceName: String,
    private val clock: () -> Long,
    private val onCommand: (PcMessage.Command) -> Unit,
) {

    /** Serves the session until the PC disconnects or the socket is closed. */
    suspend fun run() = coroutineScope {
        val sender = launch {
            try {
                send(PhoneMessage.Hello(ProtocolCodec.PROTOCOL_VERSION, deviceName))
                val encoder = MediaStateEncoder()
                media.collect { snapshot ->
                    encoder.encode(snapshot, clock()).forEach { send(it) }
                }
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
     * Acts on a text frame from the PC: runs commands, logs the greeting, and logs and drops
     * anything invalid, as the protocol requires.
     *
     * @param text Content of the frame.
     */
    private fun handle(text: String) {
        when (val frame = PcFrame.parse(text)) {
            is PcFrame.V1 -> onCommand(frame.command)
            is PcFrame.V2 -> when (val message = frame.message) {
                is PcMessage.Command -> onCommand(message)
                is PcMessage.Hello -> {
                    Log.i(LOG_TAG, "PC client ${message.client} speaks protocol ${message.protocol}")
                    if (message.protocol > ProtocolCodec.PROTOCOL_VERSION) {
                        Log.w(LOG_TAG, "The PC uses a newer protocol; unknown messages will be ignored")
                    }
                }
            }
            is PcFrame.Invalid -> Log.w(LOG_TAG, "Ignoring a frame from the PC: ${frame.reason}")
        }
    }
}
