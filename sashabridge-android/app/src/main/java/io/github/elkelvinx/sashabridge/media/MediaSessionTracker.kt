package io.github.elkelvinx.sashabridge.media

import android.content.ComponentName
import android.content.ContentResolver
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Observes every active media session and publishes the state of the player the user is
 * listening to.
 *
 * It replaces reading the state when a notification is posted (R1): players update their
 * notification and their session at different times, and a pause or a seek does not always
 * post a notification. Here the sources are the session callbacks themselves:
 * `OnActiveSessionsChangedListener` for players that appear or disappear, and a
 * [MediaController.Callback] per player for metadata and playback state changes.
 *
 * Players emit bursts of updates for a single event: Spotify sends more than 20 metadata
 * callbacks for one track change (A12). The first change after a quiet period is published
 * right away, so that an isolated change such as a pause reaches the PC without delay. The
 * rest of the burst is grouped and published once no change has arrived for [QUIET_PERIOD_MS].
 * Identical consecutive snapshots are dropped, so a burst produces at most two messages.
 *
 * [hide] pauses the video and pins the music player until a player starts playing again;
 * meanwhile the video is never published.
 *
 * Threading: [start], [stop] and every callback run on the main thread, which owns the
 * session map. [media] can be collected and [currentController] called from any thread.
 *
 * @param sessionManager System media session service.
 * @param listenerComponent The app's notification listener, which grants access to the sessions.
 * @param scope Scope bound to the service lifetime, with a main-thread dispatcher.
 * @param contentResolver Reads the album art that players publish as a `content://` address.
 * @param sharpArt Fetches sharper covers for the screen than the players give, or `null` for
 *   none; see [SharpArtCache].
 */
class MediaSessionTracker(
    private val sessionManager: MediaSessionManager,
    private val listenerComponent: ComponentName,
    private val scope: CoroutineScope,
    contentResolver: ContentResolver,
    private val sharpArt: SharpArtLoader? = null,
) {

    /** Handler for the system callbacks, so that they arrive on the main thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Tracked sessions by token. Accessed only on the main thread. */
    private val sessions = LinkedHashMap<MediaSession.Token, TrackedSession>()

    /**
     * Signals that something changed. Conflated, since only the latest state matters, and a
     * channel rather than a SharedFlow so that a signal sent while a snapshot is being built
     * is kept until it is received.
     */
    private val changes = Channel<Unit>(Channel.CONFLATED)

    /** Reads the chosen player into a snapshot; a sharper cover arriving builds it again. */
    private val reader = SnapshotReader(AlbumArtEncoder(contentResolver), SharpArtCache(sharpArt, scope) { changes.trySend(Unit) })

    /** Backing state of [media]. */
    private val mutableMedia = MutableStateFlow<MediaSnapshot?>(null)

    /**
     * State of the active player, or `null` when no player has metadata.
     *
     * As a [StateFlow], a new collector receives the current state right away, which is
     * what lets a PC that connects late get the current track immediately (R2).
     */
    val media: StateFlow<MediaSnapshot?> = mutableMedia.asStateFlow()

    /** Backing state of [queue]. */
    private val mutableQueue = MutableStateFlow<PlayerQueue?>(null)

    /** Play queue of the active player, or `null` when it publishes none. */
    val queue: StateFlow<PlayerQueue?> = mutableQueue.asStateFlow()

    /** Controller chosen by [ActiveControllerPolicy], written on the main thread. */
    @Volatile
    private var activeController: MediaController? = null

    /** The hiding in progress ([hide]), or `null`. Written on the main thread, by [setHiding]. */
    @Volatile
    private var hiding: Hiding? = null

    /** Backing state of [hiddenSince]. */
    private val mutableHiddenSince = MutableStateFlow<Long?>(null)

    /**
     * When the hiding in progress started (`SystemClock.elapsedRealtime()`), or `null` while
     * nothing is hidden. Each hiding has its own value, also one that follows another.
     */
    val hiddenSince: StateFlow<Long?> = mutableHiddenSince.asStateFlow()

    /**
     * Whether a video is hidden and the music player shown instead has no session, so the
     * published track is the history's and no player takes commands. Callable from any thread.
     */
    val isHidingWithoutPlayer: Boolean
        get() = hiding != null && activeController == null

    /** Loop that turns [changes] into snapshots while the tracker runs. */
    private var job: Job? = null

    /** Receives the list of active sessions whenever a player appears or disappears. */
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateSessions(controllers.orEmpty())
    }

    /**
     * Returns the controller that commands should go to. Callable from any thread.
     *
     * @return The active controller, or `null` when there is no player.
     */
    fun currentController(): MediaController? = activeController

    /**
     * Starts observing the sessions. Does nothing if already started. Main thread only.
     *
     * @throws SecurityException if the app does not have notification access.
     */
    fun start() {
        if (job != null) return
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
        updateSessions(sessionManager.getActiveSessions(listenerComponent))
        job = scope.launch {
            publish()
            while (true) {
                changes.receive()
                // Leading edge: the first change after a quiet period goes out immediately.
                publish()
                // Trailing edge: wait until the burst is over, then publish its final state.
                do {
                    val anotherChange = withTimeoutOrNull(QUIET_PERIOD_MS) { changes.receive() }
                } while (anotherChange != null)
                publish()
            }
        }
        Log.i(LOG_TAG, "Media session tracker started with ${sessions.size} sessions")
    }

    /**
     * Builds a snapshot and publishes it if it differs from the current one; [MutableStateFlow]
     * ignores equal values. Main thread; suspends while the album art is encoded.
     */
    private suspend fun publish() {
        val snapshot = buildSnapshot()
        if (snapshot != mutableMedia.value) {
            mutableMedia.value = snapshot
            Log.d(LOG_TAG, "Media state: $snapshot")
        }
        mutableQueue.value = activeController?.let(::buildQueue)
    }

    /**
     * Reads the play queue of [controller]. Main thread.
     *
     * @return The queue, or `null` if the player publishes none.
     */
    private fun buildQueue(controller: MediaController): PlayerQueue? {
        val items = controller.queue?.takeIf { it.isNotEmpty() } ?: return null
        return PlayerQueue(
            source = controller.packageName,
            title = controller.queueTitle?.toString(),
            entries = items.map { item ->
                QueueEntry(
                    queueId = item.queueId,
                    title = item.description.title?.toString(),
                    subtitle = item.description.subtitle?.toString(),
                    artUri = item.description.iconUri?.toString(),
                )
            },
            activeQueueId = controller.playbackState?.activeQueueItemId
                ?.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() },
        )
    }

    /**
     * Hides the video: pauses every player that plays and follows only [musicSource], paused,
     * until a player starts playing again ([endHiding]) or [unhide]. If [musicSource] has no
     * session, [fallback] is published instead. The video is never published meanwhile.
     *
     * The video to go back to is the player shown when hiding, if it is not a music app,
     * whether it played or the user had paused it. Hiding again keeps the first hiding's video
     * and the players it paused, to go back to them. Main thread.
     *
     * @param musicSource Package of the music player to show, or `null` to show none.
     * @param fallback The track to show if that player has no session, or `null` for none.
     */
    fun hide(musicSource: String?, fallback: MediaSnapshot?) {
        val paused = sessions.values
            .filter { it.controller.playbackState?.state.isActiveState() }
            .map { tracked ->
                Log.i(LOG_TAG, "Hiding: pausing ${tracked.controller.packageName}")
                tracked.controller.transportControls.pause()
                tracked.controller.packageName
            }
        setHiding(
            Hiding.start(
                earlier = hiding,
                musicSource = musicSource,
                fallback = fallback,
                nowMs = SystemClock.elapsedRealtime(),
                shownSource = activeController?.packageName,
                pausedNow = paused,
            ),
        )
        signalChange()
    }

    /**
     * Ends the hiding to go back to what it hid. Main thread.
     *
     * @return What to go back to, or `null` if nothing is hidden.
     */
    fun unhide(): Unhidden? {
        val current = hiding ?: return null
        setHiding(null)
        signalChange()
        val controllers = sessions.values.map { it.controller }
        return Unhidden(
            video = current.video,
            videoSession = controllers.firstOrNull { it.packageName == current.video },
            paused = controllers.filter { it.packageName in current.paused },
        )
    }

    /**
     * What a hiding hid, for [unhide]'s caller to go back to.
     *
     * @property video Package of the video shown when hiding, or `null` if a music app was.
     * @property videoSession Its session, or `null` if it has none any more.
     * @property paused Sessions of the players the hiding paused, to resume; the user's own
     *   pauses stay.
     */
    class Unhidden(val video: String?, val videoSession: MediaController?, val paused: List<MediaController>)

    /**
     * Ends the hiding once a player starts playing, as [Hiding.endsWhenPlayingAt] allows.
     * Main thread.
     *
     * @param nowMs `SystemClock.elapsedRealtime()` of the start.
     */
    private fun endHiding(nowMs: Long) {
        val current = hiding ?: return
        if (!current.endsWhenPlayingAt(nowMs)) return
        setHiding(null)
        Log.i(LOG_TAG, "A player started playing; nothing is hidden any more")
    }

    /** Records the hiding in progress and publishes its start. Main thread. */
    private fun setHiding(value: Hiding?) {
        hiding = value
        mutableHiddenSince.value = value?.sinceMs
    }

    /** Stops observing and forgets every session. Safe to call when not started. Main thread only. */
    fun stop() {
        job?.cancel()
        job = null
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        sessions.values.forEach { it.controller.unregisterCallback(it) }
        sessions.clear()
        setHiding(null)
        activeController = null
        mutableMedia.value = null
        mutableQueue.value = null
    }

    /**
     * Synchronizes the tracked sessions with [controllers]: registers callbacks on new sessions,
     * removes the ones that disappeared and records the system order. Main thread.
     */
    private fun updateSessions(controllers: List<MediaController>) {
        val current = controllers.map { it.sessionToken }.toSet()
        sessions.entries.removeAll { (token, tracked) ->
            val gone = token !in current
            if (gone) tracked.controller.unregisterCallback(tracked)
            gone
        }
        controllers.forEachIndexed { index, controller ->
            val tracked = sessions.getOrPut(controller.sessionToken) {
                TrackedSession(controller).also { controller.registerCallback(it, mainHandler) }
            }
            tracked.systemOrder = index
        }
        signalChange()
    }

    /** Stops tracking the session of [token] after it was destroyed. Main thread. */
    private fun untrack(token: MediaSession.Token) {
        sessions.remove(token)?.let { it.controller.unregisterCallback(it) }
        signalChange()
    }

    /** Updates the active controller right away and schedules a new snapshot. Main thread. */
    private fun signalChange() {
        activeController = chooseController()
        changes.trySend(Unit)
    }

    /**
     * Applies [ActiveControllerPolicy] to the sessions that have metadata. Sessions without
     * metadata (for example, an app that registered a session but plays nothing) are ignored.
     * While hiding, only the music player may be chosen. Main thread.
     */
    private fun chooseController(): MediaController? {
        val current = hiding
        if (current != null && current.musicSource == null) return null
        return ActiveControllerPolicy.choose(
            sessions.values
                .filter { it.controller.metadata != null }
                .map { tracked ->
                    ControllerCandidate(
                        player = tracked.controller,
                        source = tracked.controller.packageName,
                        isActive = tracked.controller.playbackState?.state.isActiveState(),
                        lastStateChangeMs = tracked.lastStateChangeMs,
                        systemOrder = tracked.systemOrder,
                    )
                },
            pinnedSource = current?.musicSource,
        )
    }

    /**
     * Reads the active player into a [MediaSnapshot]. Runs on the main thread; suspends while
     * the album art is encoded.
     *
     * @return The snapshot, or `null` when no player has metadata. While hiding without the
     *   music player, the track given to [hide].
     */
    private suspend fun buildSnapshot(): MediaSnapshot? {
        val controller = chooseController()
        activeController = controller
        if (controller == null) return hiding?.fallback
        return reader.read(controller)
    }

    /**
     * A tracked session and its callback. Callbacks arrive on the main thread.
     *
     * @property controller Controller of the session.
     */
    private inner class TrackedSession(val controller: MediaController) : MediaController.Callback() {

        /** Position in the latest list of active sessions. */
        var systemOrder: Int = 0

        /** `SystemClock.elapsedRealtime()` of the last playback state change, or 0. */
        var lastStateChangeMs: Long = 0L

        /** Last playback state code, to tell state changes from position updates. */
        private var lastStateCode: Int? = controller.playbackState?.state

        /** Last logged description of the custom actions, to log only changes. */
        private var lastCustomActions: String? = null

        init {
            logCustomActions(controller.playbackState)
        }

        /**
         * Records the time of real state changes (play, pause, stop), ends a hiding when the
         * player starts playing, and schedules a snapshot.
         */
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val code = state?.state
            if (code != lastStateCode) {
                lastStateCode = code
                lastStateChangeMs = SystemClock.elapsedRealtime()
                if (code == PlaybackState.STATE_PLAYING) endHiding(lastStateChangeMs)
            }
            logCustomActions(state)
            signalChange()
        }

        /**
         * Logs the player's custom actions when they change. Diagnostic for the shuffle control:
         * Spotify only exposes shuffle as a custom action, and its icon or extras may be the
         * only place where the current shuffle mode shows.
         */
        private fun logCustomActions(state: PlaybackState?) {
            val description = state?.customActions?.joinToString(separator = "; ") { action ->
                val extras = action.extras?.let { bundle ->
                    bundle.keySet().sorted().joinToString(prefix = "{", postfix = "}") { key ->
                        // The value types are unknown, so the untyped getter is the only option.
                        @Suppress("DEPRECATION")
                        "$key=${bundle.get(key)}"
                    }
                }
                "${action.action} '${action.name}' icon=${action.icon} extras=$extras"
            }
            if (description == lastCustomActions) return
            lastCustomActions = description
            Log.d(LOG_TAG, "Custom actions of ${controller.packageName}: $description")
        }

        /** Logs the available keys and schedules a snapshot. */
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            SnapshotReader.logMetadataKeys(controller.packageName, metadata)
            signalChange()
        }

        /** Schedules a snapshot, which also rereads the queue. */
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            signalChange()
        }

        /** Forgets the session. */
        override fun onSessionDestroyed() {
            untrack(controller.sessionToken)
        }
    }

    companion object {

        /** Time without changes after which a burst of updates is considered finished. */
        const val QUIET_PERIOD_MS: Long = 150L

    }
}

/** Whether a playback state code means that the player is playing or about to play. */
private fun Int?.isActiveState(): Boolean =
    this == PlaybackState.STATE_PLAYING || this == PlaybackState.STATE_BUFFERING
