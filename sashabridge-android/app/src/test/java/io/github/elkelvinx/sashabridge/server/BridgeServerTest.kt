package io.github.elkelvinx.sashabridge.server

import io.github.elkelvinx.sashabridge.bridge.BridgeStatus
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import io.github.elkelvinx.sashabridge.protocol.ProtocolCodec
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.net.ServerSocket
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * The server on a real socket, with a Ktor client in the role of the PC: greeting and initial
 * state (R2), commands in both versions, closing sessions on stop, and the start and stop
 * races behind A4 and A5.
 */
class BridgeServerTest {

    /** A free port for this test. */
    private val port = ServerSocket(0).use { it.localPort }

    /** State of the active player that the server sends. */
    private val media = MutableStateFlow<MediaSnapshot?>(null)

    /** Commands the server received, in order. */
    private val commands = Channel<PcMessage.Command>(Channel.UNLIMITED)

    /** Client in the role of the PC. */
    private val client = HttpClient(ClientCIO) { install(ClientWebSockets) }

    /** Servers created by the test, stopped afterwards. */
    private val servers = mutableListOf<BridgeServer>()

    /** Creates a server on [port] whose state changes go to [status]. */
    private fun server(status: MutableStateFlow<BridgeStatus> = MutableStateFlow(BridgeStatus.Stopped)) =
        BridgeServer(
            media = media,
            deviceName = "Test phone",
            onStatus = { status.value = it },
            onCommand = { commands.trySend(it) },
            port = port,
            bindRetryDelay = 100.milliseconds,
            clock = { 1_000L },
        ).also { servers += it }

    @After
    fun tearDown() = runBlocking {
        servers.forEach { it.stop() }
        client.close()
    }

    /** Opens a session on the server and runs [block] in it. */
    private suspend fun connect(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        withTimeout(5.seconds) { client.webSocket(host = "127.0.0.1", port = port, path = "/") { block() } }

    /** Receives and decodes the next message from the phone. */
    private suspend fun DefaultClientWebSocketSession.next(): PhoneMessage =
        ProtocolCodec.decodePhoneMessage((incoming.receive() as Frame.Text).readText())

    /** Waits until [status] matches [predicate]. */
    private suspend fun awaitStatus(status: MutableStateFlow<BridgeStatus>, predicate: (BridgeStatus) -> Boolean) =
        withTimeout(5.seconds) { status.first(predicate) }

    /** A state with album art. */
    private fun snapshot(artId: String?) = MediaSnapshot(
        trackId = "t",
        source = "com.spotify.music",
        title = "Title",
        artist = "Artist",
        playbackContext = null,
        album = null,
        durationMs = 215_000,
        isPlaying = true,
        reportedPositionMs = 0,
        positionUpdateTimeMs = 1_000,
        playbackSpeed = 1.0f,
        shuffle = null,
        art = artId?.let { AlbumArt(it, byteArrayOf(1, 2, 3)) },
    )

    /** A PC that connects gets the greeting and the current state without any change (R2). */
    @Test
    fun newClientGetsHelloThenCurrentState() = runBlocking {
        media.value = snapshot("a")
        server().start()

        connect {
            val hello = next() as PhoneMessage.Hello
            assertEquals(ProtocolCodec.PROTOCOL_VERSION, hello.protocol)
            assertEquals("Test phone", hello.device)
            assertEquals("a", (next() as PhoneMessage.MediaArt).artId)
            assertEquals("a", (next() as PhoneMessage.MediaState).artId)

            media.value = null
            assertEquals(PhoneMessage.MediaCleared, next())
        }
    }

    /** With no player, the initial state is media.cleared. */
    @Test
    fun noPlayerIsSentAsCleared() = runBlocking {
        server().start()

        connect {
            assertTrue(next() is PhoneMessage.Hello)
            assertEquals(PhoneMessage.MediaCleared, next())
        }
    }

    /** v2 and v1 commands reach the executor; the PC's hello and invalid frames do not stop the session. */
    @Test
    fun commandsInBothVersionsAreExecuted() = runBlocking {
        server().start()

        connect {
            send(Frame.Text(ProtocolCodec.encode(PcMessage.Hello(2, "test/1"))))
            send(Frame.Text("garbage"))
            send(Frame.Text(ProtocolCodec.encode(PcMessage.Command(CommandAction.PAUSE))))
            send(Frame.Text("NEXT"))

            withTimeout(5.seconds) {
                assertEquals(PcMessage.Command(CommandAction.PAUSE), commands.receive())
                assertEquals(PcMessage.Command(CommandAction.NEXT), commands.receive())
            }
        }
    }

    /** The status follows the sessions, and stopping closes them. */
    @Test
    fun stopClosesOpenSessions() = runBlocking {
        val status = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)
        val server = server(status)
        server.start()
        assertEquals(BridgeStatus.Listening, status.value)

        connect {
            awaitStatus(status) { it == BridgeStatus.ClientConnected(1) }
            server.stop()

            assertTrue(next() is PhoneMessage.Hello)
            assertEquals(PhoneMessage.MediaCleared, next())
            val reason = withTimeout(5.seconds) { closeReason.await() }
            assertEquals(1001.toShort(), reason?.code)
        }
        assertEquals(BridgeStatus.Stopped, status.value)
    }

    /**
     * A busy port is reported as an error and retried until it is free (A5). The bind failure
     * must not escape as an uncaught exception, which on Android kills the app.
     */
    @Test
    fun busyPortIsReportedAndRetried() = runBlocking {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        try {
            val first = server()
            first.start()
            val status = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)
            val second = server(status)

            val starting = launch { second.start() }
            awaitStatus(status) { it is BridgeStatus.Error }
            first.stop()

            withTimeout(5.seconds) { starting.join() }
            assertEquals(BridgeStatus.Listening, status.value)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    /**
     * A new instance that starts right after the previous one is released gets the port
     * without an error, which is what happens when Android recreates the service (A5).
     */
    @Test
    fun releaseFreesThePortBeforeTheNextStart() = runBlocking {
        val old = server()
        old.start()
        val status = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)
        val errors = mutableListOf<BridgeStatus>()
        val new = BridgeServer(
            media = media,
            deviceName = "Test phone",
            onStatus = { if (it is BridgeStatus.Error) errors += it; status.value = it },
            onCommand = {},
            port = port,
        ).also { servers += it }

        old.release()
        new.start()

        assertEquals(BridgeStatus.Listening, status.value)
        assertEquals(emptyList<BridgeStatus>(), errors)
    }

    /** Switching the bridge on and off quickly leaves a consistent state that still works (E5). */
    @Test
    fun rapidStartStopStaysConsistent() = runBlocking {
        val status = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)
        val server = server(status)
        coroutineScope {
            repeat(5) {
                launch { server.start() }
                launch { server.stop() }
            }
        }
        assertEquals(BridgeStatus.Stopped, status.value)

        server.start()
        assertEquals(BridgeStatus.Listening, status.value)
        connect { assertTrue(next() is PhoneMessage.Hello) }
    }

    /** Stopping a server that never started only reports that it is stopped. */
    @Test
    fun stopWithoutStartIsHarmless() = runBlocking {
        val status = MutableStateFlow<BridgeStatus>(BridgeStatus.Listening)
        server(status).stop()

        assertEquals(BridgeStatus.Stopped, status.value)
        assertNull(commands.tryReceive().getOrNull())
    }
}
