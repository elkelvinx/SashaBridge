package io.github.elkelvinx.sashabridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.media.MediaCommandExecutor
import io.github.elkelvinx.sashabridge.media.MediaSessionTracker
import io.github.elkelvinx.sashabridge.protocol.LegacyCommandParser
import io.github.elkelvinx.sashabridge.protocol.LegacyV1Session
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Notification listener that hosts the bridge.
 *
 * Android binds this service once the user grants notification access, and that binding is
 * what allows reading other apps' media sessions. The service wires the parts together:
 * - [MediaSessionTracker] observes the players while the listener is connected.
 * - A Ktor WebSocket server on [BridgeConfig.PORT] sends the state of the active player to
 *   the PC in protocol v1, and runs the PC's commands through [MediaCommandExecutor].
 *
 * The server, its start and stop, and the broadcast that toggles it keep the behavior of the
 * original app; phase 5 replaces them (A4, A5, A6 and A11).
 *
 * Lifecycle callbacks run on the main thread; WebSocket sessions run on Ktor's threads.
 */
class MusicListenerService : NotificationListenerService() {

    /** Scope of the service; cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Observer of the media sessions. Created in [onCreate], started in [onListenerConnected]. */
    private lateinit var tracker: MediaSessionTracker

    /** Runs the PC's commands on the active player. Created in [onCreate]. */
    private lateinit var commandExecutor: MediaCommandExecutor

    /** Running WebSocket server, or `null` while the bridge is disabled. */
    private var server: NettyApplicationEngine? = null

    /** Session of the connected PC, closed when the bridge is disabled. */
    private var activeSession: DefaultWebSocketServerSession? = null

    /** Whether the bridge forwards state and commands. Written by the toggle broadcast. */
    @Volatile
    private var bridgeEnabled = true

    /** Starts or stops the server when [MainActivity] toggles the bridge. Main thread. */
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_TOGGLE_BRIDGE) return
            bridgeEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
            if (bridgeEnabled) {
                Log.i(LOG_TAG, "Bridge enabled, starting the server")
                startServer()
            } else {
                Log.i(LOG_TAG, "Bridge disabled, closing the PC session and stopping the server")
                serviceScope.launch(Dispatchers.IO) {
                    activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "Bridge disabled"))
                    server?.stop(100, 500)
                    server = null
                }
            }
        }
    }

    /** Creates the components and starts the server. Main thread. */
    override fun onCreate() {
        super.onCreate()
        Log.i(LOG_TAG, "Listener service created")
        tracker = MediaSessionTracker(
            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager,
            ComponentName(this, MusicListenerService::class.java),
            serviceScope,
        )
        commandExecutor = MediaCommandExecutor(tracker, getSystemService(AUDIO_SERVICE) as AudioManager)
        ContextCompat.registerReceiver(
            this,
            toggleReceiver,
            IntentFilter(ACTION_TOGGLE_BRIDGE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startServer()
    }

    /**
     * Starts observing the media sessions. Android calls this once the listener is bound,
     * which is when access to the sessions is granted. Main thread.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            tracker.start()
        } catch (error: SecurityException) {
            Log.e(LOG_TAG, "Cannot read the media sessions without notification access", error)
        }
    }

    /** Stops observing the media sessions when Android unbinds the listener. Main thread. */
    override fun onListenerDisconnected() {
        tracker.stop()
        super.onListenerDisconnected()
    }

    /** Releases the tracker, the scope, the server and the receiver. Main thread. */
    override fun onDestroy() {
        tracker.stop()
        serviceScope.cancel()
        // The service scope is already cancelled, so stopping the server needs its own scope.
        CoroutineScope(Dispatchers.IO).launch {
            activeSession?.close()
            server?.stop(100, 500)
        }
        unregisterReceiver(toggleReceiver)
        super.onDestroy()
    }

    /**
     * Starts the WebSocket server if it is not running.
     *
     * Each PC session receives the current state as soon as it connects, because
     * [MediaSessionTracker.media] is a StateFlow, and then every change.
     */
    private fun startServer() {
        if (server != null) return

        server = embeddedServer(Netty, port = BridgeConfig.PORT) {
            install(WebSockets)
            routing {
                webSocket("/") {
                    Log.i(LOG_TAG, "PC connected")
                    activeSession = this
                    val encoder = LegacyV1Session()

                    val sender = launch {
                        tracker.media.filterNotNull().collect { snapshot ->
                            if (bridgeEnabled) {
                                send(Frame.Text(encoder.encode(snapshot, SystemClock.elapsedRealtime())))
                            }
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handleCommand(frame.readText())
                        }
                    } finally {
                        sender.cancel()
                        activeSession = null
                        Log.i(LOG_TAG, "PC disconnected")
                    }
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) { server?.start(wait = true) }
    }

    /**
     * Runs a text command received from the PC. Ktor thread.
     *
     * @param text Content of the text frame, a v1 command such as `NEXT`.
     */
    private fun handleCommand(text: String) {
        if (!bridgeEnabled) {
            Log.d(LOG_TAG, "Bridge disabled, command ignored: $text")
            return
        }
        val command = LegacyCommandParser.parse(text.trim())
        if (command == null) {
            Log.w(LOG_TAG, "Unknown command from the PC: $text")
            return
        }
        commandExecutor.execute(command)
    }

    companion object {

        /** Action of the broadcast that [MainActivity] sends to toggle the bridge. */
        const val ACTION_TOGGLE_BRIDGE = "io.github.elkelvinx.sashabridge.TOGGLE_BRIDGE"

        /** Boolean extra of [ACTION_TOGGLE_BRIDGE]; the name comes from the original app. */
        const val EXTRA_ENABLED = "ESTADO"
    }
}
