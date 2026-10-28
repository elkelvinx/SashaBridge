package io.github.elkelvinx.sashabridge.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.ShuffleMode

/**
 * The "Sonando" card of the main screen: album art, track, controls and seek bar.
 *
 * It shows the [MediaSnapshot]s it receives and turns the user's touches into commands for
 * the active player. The seek bar advances on its own between snapshots ([tick]), because the
 * snapshot carries the position the player reported and the time of that report.
 *
 * Changes are animated lightly: the art cross-fades, a new track slides its text in, and the
 * play/pause icon pops. All animations follow the system's animation scale.
 *
 * Main thread only.
 *
 * @param root View that contains the card.
 * @param onCommand Sends a command to the active player.
 * @param appLabel Returns the name of a player package, such as "Spotify".
 * @param clock Returns `SystemClock.elapsedRealtime()`, the clock of the snapshot positions.
 */
class PlayerCard(
    root: View,
    private val onCommand: (PcMessage.Command) -> Unit,
    private val appLabel: (String) -> String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val context = root.context
    private val cover: ImageView = root.findViewById(R.id.cover)
    private val trackInfo: View = root.findViewById(R.id.trackInfo)
    private val trackTitle: TextView = root.findViewById(R.id.trackTitle)
    private val trackArtist: TextView = root.findViewById(R.id.trackArtist)
    private val trackSource: TextView = root.findViewById(R.id.trackSource)
    private val previousButton: ImageButton = root.findViewById(R.id.previousButton)
    private val nextButton: ImageButton = root.findViewById(R.id.nextButton)
    private val playPauseButton: ImageButton = root.findViewById(R.id.playPauseButton)
    private val shuffleButton: ImageButton = root.findViewById(R.id.shuffleButton)
    private val seekBar: SeekBar = root.findViewById(R.id.seekBar)
    private val elapsedTime: TextView = root.findViewById(R.id.elapsedTime)
    private val durationTime: TextView = root.findViewById(R.id.durationTime)

    /** Last snapshot shown, or `null` when nothing is playing. */
    private var snapshot: MediaSnapshot? = null

    /** Art shown; [NO_ART] for the placeholder, `null` before the first update. */
    private var shownArtId: String? = null

    /** Track shown, to animate only real track changes. */
    private var shownTrackId: String? = null

    /** Whether the play/pause button shows the pause icon. */
    private var shownPlaying: Boolean? = null

    /** Whether the user is dragging the seek bar, which then stops following the player. */
    private var dragging = false

    /**
     * Position the user seeked to and the [clock] time of the request. Until the player
     * reports a position after that time, the bar stays at the requested position instead of
     * jumping back for a moment.
     */
    private var pendingSeek: Pair<Long, Long>? = null

    init {
        previousButton.setOnClickListener {
            nudge(it, -1)
            onCommand(PcMessage.Command(CommandAction.PREVIOUS))
        }
        nextButton.setOnClickListener {
            nudge(it, 1)
            onCommand(PcMessage.Command(CommandAction.NEXT))
        }
        playPauseButton.setOnClickListener {
            val play = snapshot?.isPlaying != true
            // Show the change right away; the player's next state confirms or corrects it.
            showPlaying(play)
            onCommand(PcMessage.Command(if (play) CommandAction.PLAY else CommandAction.PAUSE))
        }
        shuffleButton.setOnClickListener { onCommand(PcMessage.Command(CommandAction.TOGGLE_SHUFFLE)) }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedTime.text = TrackTime.format(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                val target = bar.progress.toLong()
                pendingSeek = target to clock()
                onCommand(PcMessage.Command(CommandAction.SEEK, target))
            }
        })
        trackTitle.isSelected = true // lets a long title scroll
        show(null)
    }

    /**
     * Shows the state of the active player.
     *
     * @param snapshot The state, or `null` when nothing is playing.
     */
    fun show(snapshot: MediaSnapshot?) {
        this.snapshot = snapshot
        showArt(snapshot?.art)
        showTrack(snapshot)
        showPlaying(snapshot?.isPlaying == true)
        showShuffle(snapshot?.shuffle)

        val active = snapshot != null
        for (button in listOf(previousButton, nextButton, playPauseButton)) {
            button.isEnabled = active
            button.alpha = if (active) 1f else DISABLED_ALPHA
        }
        val duration = snapshot?.durationMs
        seekBar.isEnabled = duration != null
        seekBar.max = duration?.toInt() ?: 0
        durationTime.text = duration?.let(TrackTime::format) ?: context.getString(R.string.unknown_time)
        pendingSeek?.let { (_, requestedAt) ->
            if (snapshot == null || snapshot.positionUpdateTimeMs >= requestedAt) pendingSeek = null
        }
        tick()
    }

    /** Moves the seek bar and the elapsed time to the current position. Call periodically. */
    fun tick() {
        if (dragging) return
        val current = snapshot
        if (current?.durationMs == null) {
            seekBar.progress = 0
            elapsedTime.text = context.getString(R.string.unknown_time)
            return
        }
        val now = clock()
        val position = pendingSeek?.let { (target, requestedAt) ->
            if (now - requestedAt < SEEK_HOLD_MS) target else null
        } ?: current.positionAt(now)
        seekBar.progress = position.toInt()
        elapsedTime.text = TrackTime.format(position)
    }

    /** Shows the album art, cross-fading when it changes, or the placeholder without art. */
    private fun showArt(art: AlbumArt?) {
        val artId = art?.artId ?: NO_ART
        if (artId == shownArtId) return
        val first = shownArtId == null
        shownArtId = artId
        val apply = {
            if (art == null) {
                cover.setImageResource(R.drawable.cover_placeholder)
            } else {
                cover.setImageBitmap(BitmapFactory.decodeByteArray(art.jpeg, 0, art.jpeg.size))
            }
        }
        if (first) {
            apply()
            return
        }
        cover.animate().cancel()
        cover.animate().alpha(0f).scaleX(ART_SHRINK).scaleY(ART_SHRINK).setDuration(FADE_MS).withEndAction {
            apply()
            cover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_MS * 2).start()
        }.start()
    }

    /** Shows title, artist and player; a new track slides its text in. */
    private fun showTrack(snapshot: MediaSnapshot?) {
        if (snapshot == null) {
            trackTitle.setText(R.string.nothing_playing)
            trackArtist.isVisible = false
            trackSource.isVisible = false
        } else {
            trackTitle.text = snapshot.title ?: context.getString(R.string.nothing_playing)
            val artist = listOfNotNull(snapshot.artist, snapshot.playbackContext).joinToString(" • ")
            trackArtist.text = artist
            trackArtist.isVisible = artist.isNotEmpty()
            trackSource.text = context.getString(R.string.now_playing_source, appLabel(snapshot.source))
            trackSource.isVisible = true
        }
        val trackId = snapshot?.trackId
        if (trackId != shownTrackId && shownTrackId != null) {
            trackInfo.alpha = 0f
            trackInfo.translationX = context.resources.displayMetrics.density * SLIDE_DP
            trackInfo.animate().alpha(1f).translationX(0f).setDuration(FADE_MS * 2).start()
        }
        shownTrackId = trackId
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
            playPauseButton.animate().scaleX(1f).scaleY(1f).setDuration(FADE_MS * 2)
                .setInterpolator(OvershootInterpolator()).start()
        }
    }

    /** Shows the shuffle button in the player's mode, or hides it when the mode is unknown. */
    private fun showShuffle(mode: ShuffleMode?) {
        shuffleButton.isVisible = mode != null
        if (mode == null) return
        val (icon, color, description) = when (mode) {
            ShuffleMode.OFF -> Triple(R.drawable.ic_shuffle, R.color.on_surface_muted, R.string.control_shuffle_off)
            ShuffleMode.ON -> Triple(R.drawable.ic_shuffle, R.color.seek_thumb, R.string.control_shuffle_on)
            ShuffleMode.SMART -> Triple(R.drawable.ic_shuffle_smart, R.color.accent_text, R.string.control_shuffle_smart)
        }
        shuffleButton.setImageResource(icon)
        shuffleButton.imageTintList = ColorStateList.valueOf(color(color))
        shuffleButton.contentDescription = context.getString(description)
    }

    /** Pushes a button briefly in the direction it skips. */
    private fun nudge(view: View, direction: Int) {
        val distance = context.resources.displayMetrics.density * NUDGE_DP * direction
        view.animate().cancel()
        view.animate().translationX(distance).setDuration(FADE_MS / 2).withEndAction {
            view.animate().translationX(0f).setDuration(FADE_MS).start()
        }.start()
    }

    /** Resolves a color resource for the current theme. */
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)

    private companion object {

        /** [shownArtId] of the placeholder. */
        const val NO_ART = ""

        /** Time the bar keeps a requested position while the player has not reported it yet. */
        const val SEEK_HOLD_MS = 2_000L

        /** Base duration of the animations. */
        const val FADE_MS = 120L

        /** Scale the art shrinks to while it fades out. */
        const val ART_SHRINK = 0.94f

        /** Scale the play/pause icon starts from when it changes. */
        const val POP_SCALE = 0.7f

        /** Distance a new track's text slides in from. */
        const val SLIDE_DP = 12f

        /** Distance the skip buttons move when pressed. */
        const val NUDGE_DP = 6f

        /** Opacity of the controls while nothing is playing. */
        const val DISABLED_ALPHA = 0.38f
    }
}
