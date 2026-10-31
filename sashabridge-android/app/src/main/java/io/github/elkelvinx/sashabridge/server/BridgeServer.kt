package io.github.elkelvinx.sashabridge.server

import android.os.SystemClock
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.bridge.BridgeStatus
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.pen.AnnotationRepository
import io.github.elkelvinx.sashabridge.pen.TabletRepository
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WebSocket server that the PC connects to through `adb forward`.
 *
 * - Ktor's CIO engine on [BridgeConfig.HOST], so that only connections from inside the phone
 *   reach it (A6). Pings every 15 s detect a PC that vanished without closing.
 * - [start] and [stop] are serialized by a lock shared by every instance in the process
 *   (A4). The port is a process-wide resource: when Android recreates the listener service,
 *   the new instance's [start] waits until the old instance's [stop] has released the port.
 * - A port that cannot be bound is reported as [BridgeStatus.Error] and retried, instead of
 *   crashing the app (A5).
 *
 * Each connection to `/` is served by a [ClientSession], and each connection to `/pen`, the
 * S Pen route, by a [PenSession]. Only the music sessions count as connected PCs in the
 * status; a PC opens one of each. Every state change goes to [onStatus].
 *
 * [start] and [stop] can be called from any thread; they suspend and do the blocking work on
 * [Dispatchers.IO].
 *
 * @param media State of the active player, sent to every PC.
 * @param deviceName Phone model announced in `hello`.
 * @param onStatus Receives every state change. Called on any thread.
 * @param onCommand Runs a command from a PC. Called on Ktor's dispatcher.
 * @param port TCP port to listen on.
 * @param bindRetryDelay Wait between attempts to bind the port.
 * @param clock Returns `SystemClock.elapsedRealtime()`.
 * @param annotations Shared state of the "Anotar" mode, served on the `/pen` route.
 * @param tablet Shared state of the "Tableta" mode, served on the `/pen` route.
 * @param hidden Whether a video is hidden that the phone can go back to, reported to every PC.
 */
class BridgeServer(
    private val media: StateFlow<MediaSnapshot?>,
    private val deviceName: String,
    private val onStatus: (BridgeStatus) -> Unit,
    private val onCommand: (PcMessage.Command) -> Unit,
    private val port: Int = BridgeConfig.PORT,
    private val bindRetryDelay: Duration = BIND_RETRY_DELAY,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val annotations: AnnotationRepository = AnnotationRepository(),
    private val tablet: TabletRepository = TabletRepository(),
    private val hidden: Flow<Boolean> = flowOf(false),
) {

    /** Running engine, or `null` while stopped. Guarded by [portLock]. */
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Open music sessions, closed on [stop]. Guarded by its own monitor. */
    private val sessions = mutableSetOf<WebSocketSession>()

    /** Open `/pen` sessions, closed on [stop]. Guarded by the monitor of [sessions]. */
    private val penSessions = mutableSetOf<WebSocketSession>()

    /** Whether the engine is listening; sessions that end after [stop] must not report a state. */
    @Volatile
    private var running = false

    /**
     * Parent of the engine's coroutines and of [release], independent of the caller's lifetime.
     *
     * Ktor binds the port in a coroutine of its own. Without a parent, a failure there, such
     * as a BindException, is an uncaught exception that kills the app (A5); with this scope it
     * reaches the handler. The handler only logs: [start] gets the same failure as the result
     * of starting the engine and reports it, while the handler may run later, after a retry
     * already succeeded.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.w(LOG_TAG, "Server coroutine failed: $error")
        },
    )

    /**
     * Starts listening, retrying every [bindRetryDelay] while the port is busy. Returns once
     * the server listens, or right away if it already does. Cancel the caller to stop retrying.
     */
    suspend fun start() {
        while (true) {
            val failure = portLock.withLock {
                if (engine != null) return
                bind() ?: return
            }
            Log.w(LOG_TAG, "$failure; retrying in $bindRetryDelay")
            onStatus(BridgeStatus.Error(failure))
            delay(bindRetryDelay)
        }
    }

    /**
     * Closes every session and stops listening. Does nothing but report
     * [BridgeStatus.Stopped] if the server is not running. Not cancellable once called, so
     * that the port is always released.
     */
    suspend fun stop() = withContext(NonCancellable) {
        portLock.withLock {
            running = false
            engine?.let { server ->
                engine = null
                val open = synchronized(sessions) {
                    (sessions + penSessions).also {
                        sessions.clear()
                        penSessions.clear()
                    }
                }
                open.forEach { session ->
                    try {
                        session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Bridge stopped"))
                    } catch (error: Exception) {
                        Log.d(LOG_TAG, "Session already closed: ${error.message}")
                    }
                }
                withContext(Dispatchers.IO) { server.stopSuspend(STOP_GRACE_MS, STOP_TIMEOUT_MS) }
                Log.i(LOG_TAG, "Server stopped")
            }
            onStatus(BridgeStatus.Stopped)
        }
    }

    /**
     * Stops the server without waiting, for callers that cannot suspend, such as
     * `Service.onDestroy`. Any thread.
     *
     * The stop takes its place in the queue of the lock before this function returns, so a
     * [start] called afterwards, even by another instance, runs after the port is released.
     */
    fun release() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { stop() }
    }

    /**
     * Creates the engine and binds the port. Called with [portLock] held.
     *
     * @return `null` once listening, or the reason it could not.
     */
    private suspend fun bind(): String? {
        val server = scope.embeddedServer(CIO, port = port, host = BridgeConfig.HOST) { module() }
        try {
            withContext(Dispatchers.IO) { server.startSuspend(wait = false) }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { server.stopSuspend(0, 0) }
            // A cancelled caller ends here too; that is not a failure to report.
            currentCoroutineContext().ensureActive()
            // Usually a BindException: the port is still held by a previous instance or by
            // another app. Ktor may wrap it in a CancellationException, so report the cause.
            return "cannot listen on ${BridgeConfig.HOST}:$port: ${error.rootCause()}"
        }
        engine = server
        running = true
        Log.i(LOG_TAG, "Server listening on ${BridgeConfig.HOST}:$port")
        reportSessions()
        return null
    }

    /** Installs the WebSocket endpoint. Called by Ktor while the engine starts. */
    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = PING_PERIOD
            timeout = PING_TIMEOUT
        }
        routing {
            webSocket("/") {
                synchronized(sessions) { sessions += this }
                Log.i(LOG_TAG, "PC connected")
                reportSessions()
                try {
                    ClientSession(this, media, deviceName, clock, onCommand, hidden).run()
                } finally {
                    synchronized(sessions) { sessions -= this }
                    Log.i(LOG_TAG, "PC disconnected")
                    reportSessions()
                }
            }
            webSocket("/pen") {
                val count = synchronized(sessions) { penSessions += this; penSessions.size }
                annotations.setPenSessions(count)
                Log.i(LOG_TAG, "PC opened the pen route")
                try {
                    PenSession(this, annotations, tablet, deviceName).run()
                } finally {
                    val left = synchronized(sessions) { penSessions -= this; penSessions.size }
                    annotations.setPenSessions(left)
                    if (left == 0) tablet.setTarget(null)
                    Log.i(LOG_TAG, "PC closed the pen route")
                }
            }
        }
    }

    /** Reports [BridgeStatus.Listening] or [BridgeStatus.ClientConnected] while running. */
    private fun reportSessions() {
        synchronized(sessions) {
            if (!running) return
            val count = sessions.size
            onStatus(if (count == 0) BridgeStatus.Listening else BridgeStatus.ClientConnected(count))
        }
    }

    /** Returns the innermost cause of this exception, which names the actual problem. */
    private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()

    companion object {

        /** Serializes [start] and [stop] across every instance in the process. */
        private val portLock = Mutex()

        /** Default wait between attempts to bind a busy port. */
        val BIND_RETRY_DELAY: Duration = 2.seconds

        /** Interval of the WebSocket pings. */
        private val PING_PERIOD: Duration = 15.seconds

        /** Time without a pong after which a session is closed. */
        private val PING_TIMEOUT: Duration = 30.seconds

        /** Time that open requests get to finish when the server stops. */
        private const val STOP_GRACE_MS = 100L

        /** Maximum time to stop the server. */
        private const val STOP_TIMEOUT_MS = 500L
    }
}
