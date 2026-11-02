package io.github.elkelvinx.sashabridge.media

import android.content.ComponentName
import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.history.AppKind
import io.github.elkelvinx.sashabridge.history.AppKinds
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
 *   none; see [askSharper].
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

    /** Encoder of the album art, with its cache. */
    private val artEncoder = AlbumArtEncoder(contentResolver)

    /** Sharper images for the screen of the latest arts, by art id ([askSharper]). Main thread. */
    private val sharpScreens = LinkedHashMap<String, Bitmap>()

    /** Art ids whose sharper image was asked for, found or not, so each is asked once. Main thread. */
    private val sharpAsked = LinkedHashSet<String>()

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
        val shownVideo = activeController?.packageName?.takeIf { AppKinds.of(it) != AppKind.MUSIC }
        val paused = sessions.values
            .filter { it.controller.playbackState?.state.isActiveState() }
            .map { tracked ->
                Log.i(LOG_TAG, "Hiding: pausing ${tracked.controller.packageName}")
                tracked.controller.transportControls.pause()
                tracked.controller.packageName
            }
        val earlier = hiding
        setHiding(
            Hiding(
                musicSource = musicSource,
                fallback = fallback,
                sinceMs = SystemClock.elapsedRealtime(),
                paused = earlier?.paused.orEmpty() + paused,
                video = earlier?.video ?: shownVideo,
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
     * Ends the hiding once a player starts playing, except during the first [HIDE_GRACE_MS],
     * when the paused video may still report the states it was going through. Main thread.
     *
     * @param nowMs `SystemClock.elapsedRealtime()` of the start.
     */
    private fun endHiding(nowMs: Long) {
        val current = hiding ?: return
        if (nowMs - current.sinceMs <= HIDE_GRACE_MS) return
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
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState
        val source = controller.packageName

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val (artist, playbackContext) = MetadataNormalizer.splitArtist(
            source,
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
        )
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotEmpty() }
        val actionIds = state?.customActions?.map { it.action }.orEmpty()
        val actionNames = state?.customActions?.associate { it.action to it.name.toString() }.orEmpty()
        val artUrl = artUrlOf(controller, metadata, title)
        val art = artEncoder.encode(metadata)?.let(::sharpest)
        art?.let { askSharper(it, artUrl) }

        return MediaSnapshot(
            trackId = MediaSnapshot.trackIdOf(
                source,
                mediaId,
                title,
                artist,
                album,
                durationMs,
            ),
            source = source,
            title = title,
            artist = artist,
            playbackContext = playbackContext,
            album = album,
            durationMs = durationMs,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            reportedPositionMs = state?.position ?: PlaybackPositionCalculator.UNKNOWN_POSITION,
            positionUpdateTimeMs = state?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = state?.playbackSpeed ?: 0f,
            shuffle = ShuffleControl.modeOf(actionIds, actionNames),
            art = art,
            canRepeat = RepeatControl.findToggleAction(actionIds) != null,
            repeat = RepeatControl.modeOf(actionIds, actionNames),
            canLike = LikeControl.findToggleAction(actionIds) != null,
            liked = LikeControl.likedOf(actionIds, ratedUp(metadata)),
            mediaId = mediaId,
            artUrl = artUrl,
        )
    }

    /** [art] with the sharper image fetched for it, if there is one. Main thread. */
    private fun sharpest(art: AlbumArt): AlbumArt = sharpScreens[art.artId]?.let { AlbumArt(art.artId, art.jpeg, it) } ?: art

    /**
     * Fetches a sharper image of [art] for the screen when the player's is smaller than the
     * card shows it and its server has a larger one ([SharpArtLoader]); once it arrives, the
     * snapshot is built again with it. The PC keeps the same art. Main thread.
     *
     * @param artUrl The web address of the art, or `null` if the player gives none.
     */
    private fun askSharper(art: AlbumArt, artUrl: String?) {
        val loader = sharpArt ?: return
        val shown = art.screen ?: return
        if (maxOf(shown.width, shown.height) >= SharpArtLoader.MIN_SIDE_PX) return
        val url = artUrl?.let(ThumbnailSources::sharpUrl) ?: return
        if (art.artId in sharpAsked || !loader.allowed()) return
        sharpAsked += art.artId
        if (sharpAsked.size > SHARP_ASKED_KEPT) sharpAsked.remove(sharpAsked.first())
        scope.launch {
            val sharp = loader.load(url) ?: return@launch
            if (sharp.width <= shown.width) return@launch
            Log.d(LOG_TAG, "Sharper art ${sharp.width}x${sharp.height} for ${art.artId}")
            sharpScreens[art.artId] = sharp
            if (sharpScreens.size > SHARP_SCREENS_KEPT) sharpScreens.remove(sharpScreens.keys.first())
            changes.trySend(Unit)
        }
    }

    /**
     * The web address of the cover, if the player gives one: Spotify's own extra, a standard
     * art address that is on the web, or else the icon of the queue item playing, which is how
     * YouTube Music gives it. The queue item must have the track's [title], since the queue
     * may still point at the previous track. Main thread.
     */
    private fun artUrlOf(controller: MediaController, metadata: MediaMetadata, title: String?): String? {
        val fromMetadata = sequenceOf(SPOTIFY_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            .mapNotNull { metadata.getString(it) }
            .firstOrNull { it.startsWith(HTTPS) }
        if (fromMetadata != null) return fromMetadata
        val activeId = controller.playbackState?.activeQueueItemId ?: return null
        return controller.queue
            ?.firstOrNull { it.queueId == activeId && it.description.title?.toString() == title }
            ?.description?.iconUri?.toString()
            ?.takeIf { it.startsWith(HTTPS) }
    }

    /**
     * Whether the track's user rating is a thumb up or a heart: `false` if it is not rated,
     * `null` if the player gives no rating or rates in another style (stars, percentage).
     */
    private fun ratedUp(metadata: MediaMetadata): Boolean? {
        val rating = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING) ?: return null
        if (!rating.isRated) return false
        return when (rating.ratingStyle) {
            Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
            Rating.RATING_HEART -> rating.hasHeart()
            else -> null
        }
    }

    /**
     * Logs which metadata keys and art sources a player provides. Diagnostic for R3: it shows
     * whether a player sends the art as a bitmap, only as a URI, or later than the title.
     * Main thread.
     */
    private fun logMetadataKeys(source: String, metadata: MediaMetadata?) {
        if (metadata == null) {
            Log.d(LOG_TAG, "Metadata from $source: none")
            return
        }
        val bitmaps = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).mapNotNull { key -> metadata.getBitmap(key)?.let { "$key=${it.width}x${it.height}" } }
        val uris = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).mapNotNull { key -> metadata.getString(key)?.let { "$key=$it" } }
        Log.d(
            LOG_TAG,
            "Metadata from $source: keys=${metadata.keySet().sorted()} bitmaps=$bitmaps uris=$uris",
        )
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
            logMetadataKeys(controller.packageName, metadata)
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

    /**
     * A hiding in progress.
     *
     * @property musicSource Package of the only player that may be followed, or `null` for none.
     * @property fallback What to publish while that player has no session, or `null`.
     * @property sinceMs `SystemClock.elapsedRealtime()` of the start of the hiding.
     * @property paused Packages of the players the hiding paused, to resume when going back.
     * @property video Package of the video to go back to, or `null`; see [hide].
     */
    private class Hiding(
        val musicSource: String?,
        val fallback: MediaSnapshot?,
        val sinceMs: Long,
        val paused: Set<String>,
        val video: String?,
    )

    companion object {

        /** Time without changes after which a burst of updates is considered finished. */
        const val QUIET_PERIOD_MS: Long = 150L

        /** Sharper images kept: the track playing and the one before, for going back. */
        private const val SHARP_SCREENS_KEPT = 2

        /** Art ids remembered as asked for a sharper image. */
        private const val SHARP_ASKED_KEPT = 64

        /** Spotify's extra with the web address of the cover (i.scdn.co). */
        private const val SPOTIFY_ART_URI = "com.spotify.music.extra.ART_HTTPS_URI"

        private const val HTTPS = "https://"

        /**
         * Time after [hide] in which a player that starts playing does not end the hiding: a
         * video paused while it loads may still report that it plays before it pauses.
         */
        const val HIDE_GRACE_MS: Long = 1_000L
    }
}

/** Whether a playback state code means that the player is playing or about to play. */
private fun Int?.isActiveState(): Boolean =
    this == PlaybackState.STATE_PLAYING || this == PlaybackState.STATE_BUFFERING
