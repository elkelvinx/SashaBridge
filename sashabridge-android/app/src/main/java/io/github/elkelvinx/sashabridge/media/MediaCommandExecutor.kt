package io.github.elkelvinx.sashabridge.media

import android.media.AudioManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage

/**
 * Executes the commands received from the PC on the active player.
 *
 * Commands go to the controller chosen by [MediaSessionTracker], the same player whose state
 * the PC shows, instead of the first session in the system list (R4). When there is no
 * player, a media key event is dispatched instead, so that the system routes it to the last
 * player that had focus.
 *
 * Callable from any thread: the controller is read through a volatile field and
 * `TransportControls` are binder calls.
 *
 * @param tracker Source of the active controller.
 * @param audioManager Used to dispatch media keys when there is no player.
 */
class MediaCommandExecutor(
    private val tracker: MediaSessionTracker,
    private val audioManager: AudioManager,
) {

    /**
     * Executes [command].
     *
     * @param command Command received from the PC.
     */
    fun execute(command: PcMessage.Command) {
        val controller = tracker.currentController()
        if (controller == null) {
            dispatchMediaKey(command.action)
            return
        }
        Log.d(LOG_TAG, "Command ${command.action} for ${controller.packageName}")
        val controls = controller.transportControls
        when (command.action) {
            CommandAction.PLAY -> controls.play()
            CommandAction.PAUSE -> controls.pause()
            CommandAction.TOGGLE ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            CommandAction.NEXT -> controls.skipToNext()
            CommandAction.PREVIOUS -> controls.skipToPrevious()
            CommandAction.SEEK -> command.positionMs?.let(controls::seekTo)
        }
    }

    /**
     * Dispatches the media key that matches [action]. Seeking has no key and is ignored.
     *
     * @param action Requested action.
     */
    private fun dispatchMediaKey(action: CommandAction) {
        val keyCode = when (action) {
            CommandAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            CommandAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            CommandAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            CommandAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            CommandAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            CommandAction.SEEK -> {
                Log.w(LOG_TAG, "No active player; SEEK has no media key and was ignored")
                return
            }
        }
        Log.d(LOG_TAG, "No active player; dispatching media key $keyCode")
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
