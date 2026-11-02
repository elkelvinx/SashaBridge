package io.github.elkelvinx.sashabridge.ui

import android.content.res.ColorStateList
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.media.RepeatMode
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.ShuffleMode

/**
 * The player card's buttons: previous and next, play/pause, and the player's own modes
 * (shuffle, repeat, like), each showing the player's state and answering a touch at once with
 * a small movement. A mode the player lacks is hidden.
 *
 * What a skip does to the card is the card's business ([onPrevious], [onNext]); the rest
 * becomes a command for the player.
 *
 * Main thread.
 *
 * @param root View that contains the buttons.
 * @param isPlaying Whether the player plays now, which play/pause toggles.
 * @param onPrevious Called when "previous" is touched.
 * @param onNext Called when "next" is touched.
 * @param onCommand Sends a command to the active player.
 */
internal class PlayerControls(
    root: View,
    private val isPlaying: () -> Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    private val onCommand: (PlayerCommand) -> Unit,
) {

    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private val previousButton: ImageButton = root.findViewById(R.id.previousButton)
    private val nextButton: ImageButton = root.findViewById(R.id.nextButton)
    private val playPauseButton: ImageButton = root.findViewById(R.id.playPauseButton)
    private val shuffleButton: ImageButton = root.findViewById(R.id.shuffleButton)
    private val repeatButton: ImageButton = root.findViewById(R.id.repeatButton)
    private val likeButton: ImageButton = root.findViewById(R.id.likeButton)

    /** Groups of the mode buttons and of the heart; only the landscape card has them. */
    private val modeGroup: View? = root.findViewById(R.id.modeGroup)
    private val likeGroup: View? = root.findViewById(R.id.likeGroup)

    /** Strong shade of the accent the user chose: active modes and the liked heart. */
    private val accent = MaterialColors.getColor(root, R.attr.sashaAccent)

    /** Whether play/pause shows the pause icon, or `null` before the first state. */
    private var shownPlaying: Boolean? = null

    init {
        previousButton.setOnClickListener {
            nudge(it, -1)
            onPrevious()
        }
        nextButton.setOnClickListener {
            nudge(it, 1)
            onNext()
        }
        playPauseButton.setOnClickListener {
            val play = !isPlaying()
            // Show the change right away; the player's next state confirms or corrects it.
            showPlaying(play)
            send(if (play) CommandAction.PLAY else CommandAction.PAUSE)
        }
        shuffleButton.setOnClickListener { send(CommandAction.TOGGLE_SHUFFLE) }
        likeButton.setOnClickListener {
            pop(it)
            send(CommandAction.TOGGLE_LIKE)
        }
        repeatButton.setOnClickListener {
            spin(it)
            onCommand(PlayerCommand.ToggleRepeat)
        }
    }

    /** Shows the buttons for [snapshot]; with nothing playing the transport is disabled. */
    fun show(snapshot: MediaSnapshot?) {
        showPlaying(snapshot?.isPlaying == true)
        showShuffle(snapshot?.shuffle)
        showRepeat(snapshot)
        showLike(snapshot)
        modeGroup?.isVisible = shuffleButton.isVisible || repeatButton.isVisible
        likeGroup?.isVisible = likeButton.isVisible
        val active = snapshot != null
        for (button in listOf(previousButton, nextButton, playPauseButton)) {
            button.isEnabled = active
            button.alpha = if (active) 1f else DISABLED_ALPHA
        }
    }

    /** Sends a protocol command to the player. */
    private fun send(action: CommandAction) {
        onCommand(PlayerCommand.Transport(PcMessage.Command(action)))
    }

    /** Shows the play or the pause icon; a change makes the icon pop. */
    private fun showPlaying(playing: Boolean) {
        if (playing == shownPlaying) return
        val changed = shownPlaying != null
        shownPlaying = playing
        playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseButton.contentDescription =
            context.getString(if (playing) R.string.control_pause else R.string.control_play)
        if (changed) {
            playPauseButton.scaleX = POP_SCALE
            playPauseButton.scaleY = POP_SCALE
            playPauseButton.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_MS * 2)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    /** Shows the shuffle button in the player's mode, or hides it when the mode is unknown. */
    private fun showShuffle(mode: ShuffleMode?) {
        shuffleButton.isVisible = mode != null
        if (mode == null) return
        val (image, color, description) = when (mode) {
            ShuffleMode.OFF -> Triple(R.drawable.ic_shuffle, color(R.color.on_surface_muted), R.string.control_shuffle_off)
            ShuffleMode.ON -> Triple(R.drawable.ic_shuffle, accent, R.string.control_shuffle_on)
            ShuffleMode.SMART -> Triple(R.drawable.ic_shuffle_smart, color(R.color.accent_text), R.string.control_shuffle_smart)
        }
        shuffleButton.setImageResource(image)
        shuffleButton.imageTintList = ColorStateList.valueOf(color)
        shuffleButton.contentDescription = context.getString(description)
    }

    /**
     * Shows the repeat button when the player has one, in its mode: muted when off, in the
     * accent when everything repeats, and with a "1" when one track repeats. A player that
     * does not reveal its mode gets the plain icon.
     */
    private fun showRepeat(snapshot: MediaSnapshot?) {
        repeatButton.isVisible = snapshot?.canRepeat == true
        if (snapshot?.canRepeat != true) return
        val (image, color, description) = when (snapshot.repeat) {
            RepeatMode.OFF -> Triple(R.drawable.ic_repeat, color(R.color.on_surface_muted), R.string.control_repeat_off)
            RepeatMode.ALL -> Triple(R.drawable.ic_repeat, accent, R.string.control_repeat_all)
            RepeatMode.ONE -> Triple(R.drawable.ic_repeat_one, accent, R.string.control_repeat_one)
            null -> Triple(R.drawable.ic_repeat, color(R.color.on_surface), R.string.control_repeat)
        }
        repeatButton.setImageResource(image)
        repeatButton.imageTintList = ColorStateList.valueOf(color)
        repeatButton.contentDescription = context.getString(description)
    }

    /**
     * Shows the like button when the player has one: a filled heart in the accent when the
     * track is liked, an outline otherwise, and a plain outline when the player does not say.
     */
    private fun showLike(snapshot: MediaSnapshot?) {
        likeButton.isVisible = snapshot?.canLike == true
        if (snapshot?.canLike != true) return
        val liked = snapshot.liked == true
        likeButton.setImageResource(if (liked) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        likeButton.imageTintList = ColorStateList.valueOf(
            if (liked) accent else color(if (snapshot.liked == null) R.color.on_surface else R.color.on_surface_muted),
        )
        likeButton.contentDescription = context.getString(if (liked) R.string.control_liked else R.string.control_like)
    }

    /** Makes a button pop, as a heart does when it is tapped. */
    private fun pop(view: View) {
        view.animate().cancel()
        view.scaleX = POP_SCALE
        view.scaleY = POP_SCALE
        view.animate().scaleX(1f).scaleY(1f).setDuration(ANIM_MS * 2).setInterpolator(OvershootInterpolator()).start()
    }

    /** Pushes a button briefly in the direction it skips. */
    private fun nudge(view: View, direction: Int) {
        val distance = density * NUDGE_DP * direction
        view.animate().cancel()
        view.animate().translationX(distance).setDuration(ANIM_MS / 2).withEndAction {
            view.animate().translationX(0f).setDuration(ANIM_MS).start()
        }.start()
    }

    /** Turns the repeat button once around. */
    private fun spin(view: View) {
        view.animate().cancel()
        view.rotation = 0f
        view.animate().rotation(FULL_TURN).setDuration(ANIM_MS * 3).start()
    }

    /** Resolves a color resource for the current theme. */
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    private companion object {

        /** Base duration of the buttons' movements. */
        const val ANIM_MS = 120L

        /** Scale a popping icon starts from. */
        const val POP_SCALE = 0.7f

        /** Distance the skip buttons move when pressed. */
        const val NUDGE_DP = 6f

        /** Degrees the repeat button turns when pressed. */
        const val FULL_TURN = 360f

        /** Opacity of the controls while nothing is playing. */
        const val DISABLED_ALPHA = 0.38f
    }
}
