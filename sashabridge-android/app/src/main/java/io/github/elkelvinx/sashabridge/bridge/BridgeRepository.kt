package io.github.elkelvinx.sashabridge.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.media.PlayerNotice
import io.github.elkelvinx.sashabridge.media.PlayerQueue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the bridge, shared by [io.github.elkelvinx.sashabridge.MainActivity]
 * and [io.github.elkelvinx.sashabridge.MusicListenerService].
 *
 * It replaces the toggle broadcast and the two copies of the "bridge active" flag that the
 * activity and the service kept separately (A7): the activity writes [enabled], the service
 * observes it, and both read [status] and [media].
 *
 * One instance per process, obtained with [get]. Every member is thread-safe.
 *
 * @param preferences Storage of [enabled], so that the choice survives restarts.
 */
class BridgeRepository private constructor(private val preferences: SharedPreferences) {

    /** Backing state of [enabled]. */
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))

    /** Backing state of [status]. */
    private val mutableStatus = MutableStateFlow<BridgeStatus>(BridgeStatus.Stopped)

    /** Backing state of [media]. */
    private val mutableMedia = MutableStateFlow<MediaSnapshot?>(null)

    /** Backing state of [queue]. */
    private val mutableQueue = MutableStateFlow<PlayerQueue?>(null)

    /** Backing state of [hiddenSince]. */
    private val mutableHiddenSince = MutableStateFlow<Long?>(null)

    /** Backing state of [listenerConnected]. */
    private val mutableListenerConnected = MutableStateFlow(false)

    /**
     * Backing flow of [commands]. A few commands are buffered for the moment the service takes
     * to collect them; if the buffer fills up, the oldest press is dropped.
     */
    private val mutableCommands = MutableSharedFlow<PlayerCommand>(
        extraBufferCapacity = COMMAND_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Backing flow of [notices]; a notice nobody shows is dropped. */
    private val mutableNotices = MutableSharedFlow<PlayerNotice>(
        extraBufferCapacity = NOTICE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Whether the user wants the bridge on. Persisted; `true` on the first run. */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** State of the WebSocket server. Written by the server. */
    val status: StateFlow<BridgeStatus> = mutableStatus.asStateFlow()

    /** State of the active player, or `null` when there is none. Written by the service. */
    val media: StateFlow<MediaSnapshot?> = mutableMedia.asStateFlow()

    /** Play queue of the active player, or `null` when it publishes none. Written by the service. */
    val queue: StateFlow<PlayerQueue?> = mutableQueue.asStateFlow()

    /**
     * When the hiding of a video in progress started, or `null` while nothing is hidden; see
     * [io.github.elkelvinx.sashabridge.media.MediaSessionTracker.hiddenSince]. Written by the service.
     */
    val hiddenSince: StateFlow<Long?> = mutableHiddenSince.asStateFlow()

    /**
     * Whether Android has bound the notification listener, which is what lets the bridge read
     * the players. Written by the service.
     */
    val listenerConnected: StateFlow<Boolean> = mutableListenerConnected.asStateFlow()

    /**
     * Commands for the active player pressed on the phone's own screen. The service runs them
     * the same way as the PC's, while the listener is connected.
     */
    val commands: SharedFlow<PlayerCommand> = mutableCommands.asSharedFlow()

    /**
     * What a player could not do, such as Spotify refusing to take a song out of "Tus me
     * gusta": the screen shows each one while visible, and the PC those its commands caused.
     */
    val notices: SharedFlow<PlayerNotice> = mutableNotices.asSharedFlow()

    /**
     * Turns the bridge on or off and remembers the choice. Any thread.
     *
     * @param value `true` to turn the bridge on.
     */
    fun setEnabled(value: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, value) }
        mutableEnabled.value = value
    }

    /**
     * Records the state of the server. Any thread.
     *
     * @param status New state.
     */
    fun reportStatus(status: BridgeStatus) {
        mutableStatus.value = status
    }

    /**
     * Records the state of the active player. Any thread.
     *
     * @param snapshot New state, or `null` when there is no player.
     */
    fun updateMedia(snapshot: MediaSnapshot?) {
        mutableMedia.value = snapshot
    }

    /**
     * Records the play queue of the active player. Any thread.
     *
     * @param queue New queue, or `null` when the player publishes none.
     */
    fun updateQueue(queue: PlayerQueue?) {
        mutableQueue.value = queue
    }

    /**
     * Records the start of the hiding in progress. Any thread.
     *
     * @param since Its start, or `null` when nothing is hidden.
     */
    fun updateHiddenSince(since: Long?) {
        mutableHiddenSince.value = since
    }

    /**
     * Records whether the notification listener is bound. Any thread.
     *
     * @param connected `true` between `onListenerConnected` and `onListenerDisconnected`.
     */
    fun setListenerConnected(connected: Boolean) {
        mutableListenerConnected.value = connected
    }

    /**
     * Asks the service to run [command] on the active player. Any thread.
     *
     * @param command Command to run.
     */
    fun sendCommand(command: PlayerCommand) {
        mutableCommands.tryEmit(command)
    }

    /** Tells the screen and the PC what a player could not do. Any thread. */
    fun postNotice(notice: PlayerNotice) {
        mutableNotices.tryEmit(notice)
    }

    companion object {

        /** Commands buffered for the service; see [mutableCommands]. */
        private const val COMMAND_BUFFER = 8

        /** Notices buffered for their readers; see [mutableNotices]. */
        private const val NOTICE_BUFFER = 4

        /** Name of the preferences file. */
        private const val PREFERENCES = "bridge"

        /** Preference key of [enabled]. */
        private const val KEY_ENABLED = "enabled"

        /** The process-wide instance, created on first use. */
        @Volatile
        private var instance: BridgeRepository? = null

        /**
         * Returns the process-wide instance, creating it on first use. Any thread.
         *
         * @param context Any context; only its application context is kept.
         * @return The repository.
         */
        fun get(context: Context): BridgeRepository =
            instance ?: synchronized(this) {
                instance ?: BridgeRepository(
                    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
