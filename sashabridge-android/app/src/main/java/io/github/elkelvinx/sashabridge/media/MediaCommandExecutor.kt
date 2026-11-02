package io.github.elkelvinx.sashabridge.media

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import io.github.elkelvinx.sashabridge.history.HistoryEntry
import io.github.elkelvinx.sashabridge.history.AppKind
import io.github.elkelvinx.sashabridge.history.AppKinds
import io.github.elkelvinx.sashabridge.history.HistoryRepository
import io.github.elkelvinx.sashabridge.history.PlayerLinks
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * [CommandAction.UNHIDE] goes back: it opens the video's app where it was and resumes it.
 *
 * Callable from any thread: the controller is read through a volatile field and
 * `TransportControls` are binder calls.
 *
 * @param tracker Source of the active controller.
 * @param audioManager Used to dispatch media keys when there is no player.
 * @param context Opens the hidden track in its player, and the video when going back.
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
     * The latest going back, while it may still ask the video to play ([resume]); a new hide
     * cancels it, or its late "play" would start the video behind SashaBridge. Main thread.
     */
    private var goingBack: Job? = null

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
        when (command.action) {
            CommandAction.HIDE -> return hide()
            CommandAction.UNHIDE -> return unhide()
            else -> Unit
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
            CommandAction.HIDE, CommandAction.UNHIDE -> Unit
        }
    }

    /**
     * Hides the video behind the newest track of the history: its player if it still has a
     * session, or else the track itself, paused, with its cover.
     */
    private fun hide() {
        scope.launch {
            goingBack?.cancel()
            goingBack = null
            val entry = history.latest()
            val fallback = entry?.let { HiddenPlayer.snapshotOf(it, history.latestCover(it)?.let { cover -> AlbumArtEncoder.fromImage(cover) }) }
            hiddenTrack = entry
            Log.i(LOG_TAG, "Hiding the video behind ${entry?.source ?: "no track"}")
            tracker.hide(entry?.source, fallback)
        }
    }

    /**
     * Goes back to what [hide] hid: opens the video's screen where it was, and resumes the
     * players the hiding paused. A video the user had paused opens paused. With the screen off
     * or locked nothing changes, so no sound plays from a phone nobody looks at; once it is
     * unlocked, the shortcut works again.
     */
    private fun unhide() {
        scope.launch {
            goingBack?.cancel()
            goingBack = launch { goBack() }
        }
    }

    /** The work of [unhide]. Main thread. */
    private suspend fun goBack() {
        if (!screenInUse()) {
            Log.i(LOG_TAG, "The screen is off or locked; the video stays hidden")
            return
        }
        val hidden = tracker.unhide() ?: return
        val paused = hidden.paused
        Log.i(LOG_TAG, "Back to ${hidden.video ?: "no video"}, resuming ${paused.map { it.packageName }.ifEmpty { listOf("nothing") }}")
        val screen = hidden.video?.let { it to hidden.videoSession?.sessionActivity }
            ?: (paused.firstOrNull { AppKinds.of(it.packageName) != AppKind.MUSIC } ?: paused.firstOrNull())
                ?.let { it.packageName to it.sessionActivity }
        if (screen != null && !PlayerScreen.open(context, screen.first, screen.second)) {
            Log.w(LOG_TAG, "Cannot open ${screen.first} to go back to the video")
        }
        resume(paused)
    }

    /**
     * Resumes [players]. A video app may ignore play until its screen is in front, which takes
     * a moment, so a player that is not playing yet is asked again, unless something was
     * hidden again meanwhile.
     */
    private suspend fun resume(players: List<MediaController>) {
        for (wait in RESUME_WAITS_MS) {
            delay(wait)
            if (tracker.hiddenSince.value != null) return
            players.filter { it.playbackState?.state != PlaybackState.STATE_PLAYING }
                .forEach { it.transportControls.play() }
        }
    }

    /** Whether the screen is on and unlocked. */
    private fun screenInUse(): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

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
        val actions = controller.playbackState?.customActions.orEmpty()
        val action = find(actions.map { it.action })
        if (action == null) {
            Log.w(LOG_TAG, "${controller.packageName} has no $name action; ignored")
            return
        }
        // With the action's own extras, as Android's media panel presses it: a player may
        // expect them back (Spotify's carry CUSTOM_ACTION_SHOW_ON_WEAR).
        val extras = actions.firstOrNull { it.action == action }?.extras
        Log.d(LOG_TAG, "Pressing $action of ${controller.packageName} with ${extras?.keySet()?.sorted() ?: "no extras"}")
        controller.transportControls.sendCustomAction(action, extras)
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
            CommandAction.SEEK, CommandAction.TOGGLE_SHUFFLE, CommandAction.TOGGLE_LIKE, CommandAction.HIDE, CommandAction.UNHIDE -> {
                Log.w(LOG_TAG, "No active player; $action has no media key and was ignored")
                return
            }
        }
        Log.d(LOG_TAG, "No active player; dispatching media key $keyCode")
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {

        /** Waits before each play when going back to a video: at once, then twice more. */
        val RESUME_WAITS_MS = listOf(0L, 600L, 900L)
    }
}
