package io.github.elkelvinx.sashabridge.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.view.isVisible
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.ShuffleMode

/**
 * The "Sonando" card of the main screen: album art, track, controls and seek bar, over a
 * heavily blurred copy of the art that tints the card with the colors of the track.
 *
 * It shows the [MediaSnapshot]s it receives and turns the user's touches into commands for
 * the active player. The seek bar advances on its own between snapshots ([tick]), because the
 * snapshot carries the position the player reported and the time of that report; its played
 * part is a sound wave that travels while the track plays.
 *
 * Changes are animated lightly: the art and its backdrop cross-fade, a new track slides its
 * text in, and the play/pause icon pops. All animations follow the system's animation scale.
 *
 * Main thread only.
 *
 * @param root View that contains the card.
 * @param onCommand Sends a command to the active player.
 * @param onOpenQueue Opens the play queue of the active player.
 * @param appLabel Returns the name of a player package, such as "Spotify".
 * @param appIcon Returns the launcher icon of a player package, or `null` if it is unknown.
 * @param clock Returns `SystemClock.elapsedRealtime()`, the clock of the snapshot positions.
 */
class PlayerCard(
    root: View,
    private val onCommand: (PlayerCommand) -> Unit,
    private val onOpenQueue: () -> Unit,
    private val appLabel: (String) -> String,
    private val appIcon: (String) -> Drawable?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val context = root.context
    private val backdrop: ImageView = root.findViewById(R.id.coverBackdrop)
    private val label: TextView = root.findViewById(R.id.playerLabel)
    private val icon: ImageView = root.findViewById(R.id.appIcon)
    private val cover: ImageView = root.findViewById(R.id.cover)
    private val trackInfo: View = root.findViewById(R.id.trackInfo)
    private val trackTitle: TextView = root.findViewById(R.id.trackTitle)
    private val trackArtist: TextView = root.findViewById(R.id.trackArtist)
    private val trackSource: TextView = root.findViewById(R.id.trackSource)
    private val previousButton: ImageButton = root.findViewById(R.id.previousButton)
    private val nextButton: ImageButton = root.findViewById(R.id.nextButton)
    private val playPauseButton: ImageButton = root.findViewById(R.id.playPauseButton)
    private val shuffleButton: ImageButton = root.findViewById(R.id.shuffleButton)
    private val repeatButton: ImageButton = root.findViewById(R.id.repeatButton)
    private val queueButton: View = root.findViewById(R.id.queueButton)
    private val seekBar: SeekBar = root.findViewById(R.id.seekBar)
    private val elapsedTime: TextView = root.findViewById(R.id.elapsedTime)
    private val durationTime: TextView = root.findViewById(R.id.durationTime)
    private val wave = WavyProgressDrawable(context)

    /** Last snapshot shown, or `null` when nothing is playing. */
    private var snapshot: MediaSnapshot? = null

    /** Art shown; [NO_ART] for the placeholder, `null` before the first update. */
    private var shownArtId: String? = null

    /** Track shown, to animate only real track changes. */
    private var shownTrackId: String? = null

    /** Player whose icon is shown. */
    private var shownSource: String? = null

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
            send(CommandAction.PREVIOUS)
        }
        nextButton.setOnClickListener {
            nudge(it, 1)
            send(CommandAction.NEXT)
        }
        playPauseButton.setOnClickListener {
            val play = snapshot?.isPlaying != true
            // Show the change right away; the player's next state confirms or corrects it.
            showPlaying(play)
            send(if (play) CommandAction.PLAY else CommandAction.PAUSE)
        }
        shuffleButton.setOnClickListener { send(CommandAction.TOGGLE_SHUFFLE) }
        repeatButton.setOnClickListener {
            spin(it)
            onCommand(PlayerCommand.ToggleRepeat)
        }
        queueButton.setOnClickListener { onOpenQueue() }
        seekBar.progressDrawable = wave
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
                send(CommandAction.SEEK, target)
            }
        })
        // A selected single-line text with a marquee ellipsis scrolls when it does not fit.
        trackTitle.isSelected = true
        trackArtist.isSelected = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(BLUR_RADIUS_PX, BLUR_RADIUS_PX, Shader.TileMode.CLAMP),
            )
        }
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
        showSource(snapshot?.source)
        val playing = snapshot?.isPlaying == true
        showPlaying(playing)
        wave.setPlaying(playing)
        label.setText(if (snapshot != null && !playing) R.string.label_paused else R.string.label_now_playing)
        showShuffle(snapshot?.shuffle)
        repeatButton.isVisible = snapshot?.canRepeat == true

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

    /**
     * Shows or hides the queue button.
     *
     * @param available Whether the active player publishes a queue.
     */
    fun setQueueAvailable(available: Boolean) {
        queueButton.isVisible = available
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

    /** Sends a protocol command to the player. */
    private fun send(action: CommandAction, positionMs: Long? = null) {
        onCommand(PlayerCommand.Transport(PcMessage.Command(action, positionMs)))
    }

    /**
     * Shows the album art and its blurred backdrop, cross-fading when they change, or the
     * placeholder and no backdrop without art.
     */
    private fun showArt(art: AlbumArt?) {
        val artId = art?.artId ?: NO_ART
        if (artId == shownArtId) return
        val first = shownArtId == null
        shownArtId = artId
        val bitmap = art?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
        // Shrinking the image first is a cheap blur of its own, and the only one before Android 12.
        val blurred = bitmap?.scale(BACKDROP_SIZE_PX, BACKDROP_SIZE_PX)
        val apply = {
            if (bitmap == null) cover.setImageResource(R.drawable.cover_placeholder) else cover.setImageBitmap(bitmap)
            backdrop.setImageBitmap(blurred)
        }
        if (first) {
            apply()
            backdrop.alpha = if (blurred != null) BACKDROP_ALPHA else 0f
            return
        }
        cover.animate().cancel()
        backdrop.animate().cancel()
        backdrop.animate().alpha(0f).setDuration(FADE_MS).start()
        cover.animate().alpha(0f).scaleX(ART_SHRINK).scaleY(ART_SHRINK).setDuration(FADE_MS).withEndAction {
            apply()
            cover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_MS * 2).start()
            backdrop.animate().alpha(if (blurred != null) BACKDROP_ALPHA else 0f).setDuration(FADE_MS * 4).start()
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

    /** Shows the icon of the player app, or nothing when it is unknown. */
    private fun showSource(source: String?) {
        if (source == shownSource) return
        shownSource = source
        val drawable = source?.let(appIcon)
        icon.setImageDrawable(drawable)
        icon.isVisible = drawable != null
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
        val (image, color, description) = when (mode) {
            ShuffleMode.OFF -> Triple(R.drawable.ic_shuffle, R.color.on_surface_muted, R.string.control_shuffle_off)
            ShuffleMode.ON -> Triple(R.drawable.ic_shuffle, R.color.seek_thumb, R.string.control_shuffle_on)
            ShuffleMode.SMART -> Triple(R.drawable.ic_shuffle_smart, R.color.accent_text, R.string.control_shuffle_smart)
        }
        shuffleButton.setImageResource(image)
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

    /** Turns the repeat button once around. */
    private fun spin(view: View) {
        view.animate().cancel()
        view.rotation = 0f
        view.animate().rotation(FULL_TURN).setDuration(FADE_MS * 3).start()
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

        /** Degrees the repeat button turns when pressed. */
        const val FULL_TURN = 360f

        /** Opacity of the controls while nothing is playing. */
        const val DISABLED_ALPHA = 0.38f

        /** Side of the shrunken art behind the card; smaller is blurrier. */
        const val BACKDROP_SIZE_PX = 24

        /** Extra blur on Android 12 and later, in pixels. */
        const val BLUR_RADIUS_PX = 60f

        /** Opacity of the blurred art; the scrim on top keeps the text readable. */
        const val BACKDROP_ALPHA = 0.9f
    }
}
