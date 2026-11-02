package io.github.elkelvinx.sashabridge

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.bridge.BridgeRepository
import io.github.elkelvinx.sashabridge.bridge.BridgeStatus
import io.github.elkelvinx.sashabridge.history.AppKind
import io.github.elkelvinx.sashabridge.history.AppKinds
import io.github.elkelvinx.sashabridge.history.HistoryRecorder
import io.github.elkelvinx.sashabridge.history.HistoryRepository
import io.github.elkelvinx.sashabridge.history.UsageMeter
import io.github.elkelvinx.sashabridge.media.MediaCommandExecutor
import io.github.elkelvinx.sashabridge.media.MediaSessionTracker
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.pen.AnnotationRepository
import io.github.elkelvinx.sashabridge.pen.TabletRepository
import io.github.elkelvinx.sashabridge.pen.CaptureNotifier
import io.github.elkelvinx.sashabridge.server.BridgeServer
import io.github.elkelvinx.sashabridge.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * - The commands pressed on the app's own screen ([BridgeRepository.commands]) run through
 *   the same executor, whether the bridge is on or off.
 * - The songs listened to go to the history ([HistoryRecorder], [HistoryRepository]), and the
 *   time every app plays, videos included, to the statistics ([UsageMeter]); neither if the
 *   user turned the history off ([SettingsRepository.recordHistory]).
 * - A video hidden from the PC shows on the screen ([BridgeRepository.hiddenSince]), and the PC
 *   hears whether the next press goes back to it.
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

    /** The user's choices. Set in [onCreate]. */
    private lateinit var settings: SettingsRepository

    /** Observer of the media sessions. Created in [onCreate], started in [onListenerConnected]. */
    private lateinit var tracker: MediaSessionTracker

    /** WebSocket server. Created in [onCreate], run while the listener is connected. */
    private lateinit var server: BridgeServer

    /** Offers captures from the PC while the annotation screen is closed. Created in [onCreate]. */
    private lateinit var captureNotifier: CaptureNotifier

    /** Runs the commands of the PC and of the app's screen. Created in [onCreate]. */
    private lateinit var commandExecutor: MediaCommandExecutor

    /** What each app plays, found once per app. Main thread. */
    private val appKinds = HashMap<String, AppKind>()

    /** Work that lives while the listener is connected, or `null` while it is not. Main thread. */
    private var listenerJob: Job? = null

    /** Creates the components; nothing runs until the listener connects. Main thread. */
    override fun onCreate() {
        super.onCreate()
        Log.i(LOG_TAG, "Listener service created")
        repository = BridgeRepository.get(this)
        settings = SettingsRepository.get(this)
        tracker = MediaSessionTracker(
            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager,
            listenerComponent(),
            serviceScope,
            contentResolver,
        )
        commandExecutor = MediaCommandExecutor(
            tracker,
            getSystemService(AUDIO_SERVICE) as AudioManager,
            this,
            HistoryRepository.get(this),
            serviceScope,
        )
        server = BridgeServer(
            media = repository.media,
            deviceName = Build.MODEL,
            onStatus = repository::reportStatus,
            onCommand = commandExecutor::execute,
            annotations = AnnotationRepository.shared,
            tablet = TabletRepository.shared,
            // The next press of the PC's shortcut goes back to the video only if the user wants it.
            hidden = combine(repository.hiddenSince, settings.returnToVideo) { since, back -> since != null && back }
                .distinctUntilChanged(),
        )
        captureNotifier = CaptureNotifier(this)
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
        repository.setListenerConnected(true)
        listenerJob = serviceScope.launch {
            launch { tracker.media.collect(repository::updateMedia) }
            launch { tracker.queue.collect(repository::updateQueue) }
            launch { tracker.hiddenSince.collect(repository::updateHiddenSince) }
            launch { repository.commands.collect { commandExecutor.execute(it) } }
            launch { offerCaptures() }
            launch { recordHistory() }
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
        repository.updateQueue(null)
        repository.updateHiddenSince(null)
        repository.setListenerConnected(false)
        server.release()
    }

    /**
     * Shows the capture notification while a capture from the PC waits and the annotation
     * screen is closed; an open screen takes the capture itself. Main thread.
     */
    private suspend fun offerCaptures() {
        val annotations = AnnotationRepository.shared
        annotations.pendingCapture.collect { capture ->
            when {
                capture == null -> captureNotifier.cancel()
                !annotations.armed.value -> captureNotifier.show()
            }
        }
    }

    /**
     * Records the history until the listener disconnects: each state of the player goes to
     * [HistoryRecorder] (the songs) and [UsageMeter] (the time per app), and so does the time
     * every [HISTORY_TICK_MS], since a song that simply plays on sends no new state. Old plays
     * are pruned first. Main thread; the database works off it.
     */
    private suspend fun recordHistory() = coroutineScope {
        val history = HistoryRepository.get(this@MusicListenerService)
        val recorder = HistoryRecorder { appKind(it) == AppKind.MUSIC }
        val meter = UsageMeter()
        history.prune()
        launch {
            tracker.media.collect { snapshot ->
                val now = SystemClock.elapsedRealtime()
                recorder.update(snapshot, now)?.let { recordIfWanted(history, it) }
                meter.update(snapshot, now)?.let { addTimeIfWanted(history, it) }
            }
        }
        while (true) {
            delay(HISTORY_TICK_MS)
            val now = SystemClock.elapsedRealtime()
            recorder.tick(now)?.let { recordIfWanted(history, it) }
            meter.tick(now)?.let { addTimeIfWanted(history, it) }
        }
    }

    /** Adds [snapshot] to [history], unless the user turned the history off. */
    private suspend fun recordIfWanted(history: HistoryRepository, snapshot: MediaSnapshot) {
        if (settings.recordHistory.value) history.record(snapshot)
    }

    /** Adds the time of [usage] to the statistics, unless the user turned the history off. */
    private suspend fun addTimeIfWanted(history: HistoryRepository, usage: UsageMeter.Usage) {
        if (settings.recordHistory.value) history.addAppTime(usage.source, appKind(usage.source), usage.ms)
    }

    /** What [source] plays, from the known apps or the category it declares to Android. */
    private fun appKind(source: String): AppKind = appKinds.getOrPut(source) {
        val category = try {
            packageManager.getApplicationInfo(source, 0).category
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        AppKinds.of(source, category)
    }

    /** Component name of this listener. */
    private fun listenerComponent() = ComponentName(this, MusicListenerService::class.java)

    private companion object {

        /** How often the playing time is added up for the history. */
        const val HISTORY_TICK_MS = 10_000L
    }
}
