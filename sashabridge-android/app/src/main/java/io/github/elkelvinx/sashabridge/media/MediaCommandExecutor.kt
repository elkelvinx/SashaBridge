package io.github.elkelvinx.sashabridge.media

import android.content.Context
import android.media.AudioManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.history.HistoryEntry
import io.github.elkelvinx.sashabridge.history.HistoryRepository
import io.github.elkelvinx.sashabridge.history.PlayerLinks
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Executes the commands of the PC and of the app's own screen on the active player.
 *
 * Commands go to the controller chosen by [MediaSessionTracker], the same player whose state
 * the PC shows, instead of the first session in the system list (R4). When there is no
 * player, a media key event is dispatched instead, so that the system routes it to the last
 * player that had focus.
 *
 * [CommandAction.HIDE] hides the video behind the newest track of the history
 * ([MediaSessionTracker.hide]). While that track's player has no session, play opens the track
 * in it and the other commands are ignored: a media key would go to the video.
 *
 * Callable from any thread: the controller is read through a volatile field and
 * `TransportControls` are binder calls.
 *
 * @param tracker Source of the active controller.
 * @param audioManager Used to dispatch media keys when there is no player.
 * @param context Opens the hidden track in its player.
 * @param history Gives the track to show while a video is hidden.
 * @param scope Scope of the service, with a main-thread dispatcher.
 */
class MediaCommandExecutor(
    private val tracker: MediaSessionTracker,
    private val audioManager: AudioManager,
    private val context: Context,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
) {

    /** The track shown by the latest [CommandAction.HIDE], or `null`. */
    @Volatile
    private var hiddenTrack: HistoryEntry? = null

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
        if (command.action == CommandAction.HIDE) {
            hide()
            return
        }
        if (tracker.isHidingWithoutPlayer) {
            playHiddenTrack(command.action)
            return
        }
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
            CommandAction.TOGGLE_LIKE -> sendCustomAction("like", LikeControl::findToggleAction)
            CommandAction.HIDE -> Unit
        }
    }

    /**
     * Hides the video behind the newest track of the history: its player if it still has a
     * session, or else the track itself, paused, with its cover.
     */
    private fun hide() {
        scope.launch {
            val entry = history.latest()
            val fallback = entry?.let { HiddenPlayer.snapshotOf(it, history.latestCover(it)) }
            hiddenTrack = entry
            Log.i(LOG_TAG, "Hiding the video behind ${entry?.source ?: "no track"}")
            tracker.hide(entry?.source, fallback)
        }
    }

    /**
     * Runs [action] while a video is hidden and the hidden track's player has no session:
     * play opens the track in its player, which the app may do while its screen is in front;
     * anything else is ignored.
     */
    private fun playHiddenTrack(action: CommandAction) {
        val entry = hiddenTrack
        if (entry == null || (action != CommandAction.PLAY && action != CommandAction.TOGGLE)) {
            Log.d(LOG_TAG, "A video is hidden and no music player is open; $action ignored")
            return
        }
        scope.launch {
            if (!PlayerLinks.open(context, entry)) Log.w(LOG_TAG, "Cannot open ${entry.source} to play the hidden track")
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
            CommandAction.SEEK, CommandAction.TOGGLE_SHUFFLE, CommandAction.TOGGLE_LIKE, CommandAction.HIDE -> {
                Log.w(LOG_TAG, "No active player; $action has no media key and was ignored")
                return
            }
        }
        Log.d(LOG_TAG, "No active player; dispatching media key $keyCode")
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
