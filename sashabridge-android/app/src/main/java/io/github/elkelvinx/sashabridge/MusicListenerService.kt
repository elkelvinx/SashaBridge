package io.github.elkelvinx.sashabridge

import android.content.ComponentName
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.bridge.BridgeRepository
import io.github.elkelvinx.sashabridge.bridge.BridgeStatus
import io.github.elkelvinx.sashabridge.media.MediaCommandExecutor
import io.github.elkelvinx.sashabridge.media.MediaSessionTracker
import io.github.elkelvinx.sashabridge.server.BridgeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Notification listener that hosts the bridge.
 *
 * Android binds this service once the user grants notification access, and that binding is
 * what allows reading other apps' media sessions. Everything therefore runs between
 * [onListenerConnected] and [onListenerDisconnected] (A8):
 * - [MediaSessionTracker] observes the players and its state goes to [BridgeRepository.media].
 * - [BridgeServer] runs while [BridgeRepository.enabled] is `true`, sends that state to the
 *   PC and runs the PC's commands through [MediaCommandExecutor].
 *
 * When Android disconnects the listener, for example after the app is updated, the service
 * asks to be bound again with [requestRebind].
 *
 * Lifecycle callbacks run on the main thread.
 */
class MusicListenerService : NotificationListenerService() {

    /** Scope of the service; cancelled in [onDestroy]. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Shared state of the bridge. Set in [onCreate]. */
    private lateinit var repository: BridgeRepository

    /** Observer of the media sessions. Created in [onCreate], started in [onListenerConnected]. */
    private lateinit var tracker: MediaSessionTracker

    /** WebSocket server. Created in [onCreate], run while the listener is connected. */
    private lateinit var server: BridgeServer

    /** Work that lives while the listener is connected, or `null` while it is not. Main thread. */
    private var listenerJob: Job? = null

    /** Creates the components; nothing runs until the listener connects. Main thread. */
    override fun onCreate() {
        super.onCreate()
        Log.i(LOG_TAG, "Listener service created")
        repository = BridgeRepository.get(this)
        tracker = MediaSessionTracker(
            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager,
            listenerComponent(),
            serviceScope,
        )
        val commandExecutor = MediaCommandExecutor(tracker, getSystemService(AUDIO_SERVICE) as AudioManager)
        server = BridgeServer(
            media = repository.media,
            deviceName = Build.MODEL,
            onStatus = repository::reportStatus,
            onCommand = commandExecutor::execute,
        )
    }

    /**
     * Starts observing the media sessions and runs the server while the bridge is enabled.
     * Android calls this once the listener is bound, which is when access to the sessions is
     * granted. Main thread.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        if (listenerJob != null) return
        Log.i(LOG_TAG, "Listener connected")
        try {
            tracker.start()
        } catch (error: SecurityException) {
            Log.e(LOG_TAG, "Cannot read the media sessions without notification access", error)
            repository.reportStatus(BridgeStatus.Error("no notification access"))
            return
        }
        listenerJob = serviceScope.launch {
            launch { tracker.media.collect(repository::updateMedia) }
            repository.enabled.collectLatest { enabled ->
                if (enabled) {
                    Log.i(LOG_TAG, "Bridge enabled, starting the server")
                    server.start()
                } else {
                    Log.i(LOG_TAG, "Bridge disabled, stopping the server")
                    server.stop()
                }
            }
        }
    }

    /**
     * Releases everything that needs the listener and asks Android to bind it again. Android
     * ignores the request if the user revoked notification access. Main thread.
     */
    override fun onListenerDisconnected() {
        Log.i(LOG_TAG, "Listener disconnected, requesting a rebind")
        releaseListener()
        requestRebind(listenerComponent())
        super.onListenerDisconnected()
    }

    /** Releases the listener work and the scope. Main thread. */
    override fun onDestroy() {
        Log.i(LOG_TAG, "Listener service destroyed")
        releaseListener()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Stops the tracker and the server. Safe to call more than once. Main thread. */
    private fun releaseListener() {
        listenerJob?.cancel()
        listenerJob = null
        tracker.stop()
        repository.updateMedia(null)
        server.release()
    }

    /** Component name of this listener. */
    private fun listenerComponent() = ComponentName(this, MusicListenerService::class.java)
}
