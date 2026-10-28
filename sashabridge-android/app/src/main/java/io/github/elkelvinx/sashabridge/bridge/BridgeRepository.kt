package io.github.elkelvinx.sashabridge.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Whether the user wants the bridge on. Persisted; `true` on the first run. */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** State of the WebSocket server. Written by the server. */
    val status: StateFlow<BridgeStatus> = mutableStatus.asStateFlow()

    /** State of the active player, or `null` when there is none. Written by the service. */
    val media: StateFlow<MediaSnapshot?> = mutableMedia.asStateFlow()

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

    companion object {

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
