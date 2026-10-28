package io.github.elkelvinx.sashabridge.media

import android.media.AudioManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage

/**
 * Executes the commands of the PC and of the app's own screen on the active player.
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
     * Executes a command of the app's screen.
     *
     * @param command Command from the screen.
     */
    fun execute(command: PlayerCommand) {
        when (command) {
            is PlayerCommand.Transport -> execute(command.command)
            PlayerCommand.ToggleRepeat -> sendCustomAction("repeat", RepeatControl::findToggleAction)
            is PlayerCommand.SkipToQueueItem -> {
                val controller = tracker.currentController() ?: return
                Log.d(LOG_TAG, "Skipping to queue item ${command.queueId} of ${controller.packageName}")
                controller.transportControls.skipToQueueItem(command.queueId)
            }
        }
    }

    /**
     * Executes [command].
     *
     * @param command Command from the PC or from the screen.
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
            CommandAction.TOGGLE_SHUFFLE -> sendCustomAction("shuffle", ShuffleControl::findToggleAction)
        }
    }

    /**
     * Presses one of the active player's own buttons, found among its custom actions.
     *
     * @param name What the button does, for the log.
     * @param find Picks the button's action identifier from the player's custom actions.
     */
    private fun sendCustomAction(name: String, find: (List<String>) -> String?) {
        val controller = tracker.currentController() ?: return
        val actionIds = controller.playbackState?.customActions?.map { it.action }.orEmpty()
        val action = find(actionIds)
        if (action == null) {
            Log.w(LOG_TAG, "${controller.packageName} has no $name action; ignored")
            return
        }
        Log.d(LOG_TAG, "Pressing $action of ${controller.packageName}")
        controller.transportControls.sendCustomAction(action, null)
    }

    /**
     * Dispatches the media key that matches [action]. Seeking and shuffling have no key and
     * are ignored.
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
            CommandAction.SEEK, CommandAction.TOGGLE_SHUFFLE -> {
                Log.w(LOG_TAG, "No active player; $action has no media key and was ignored")
                return
            }
        }
        Log.d(LOG_TAG, "No active player; dispatching media key $keyCode")
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
