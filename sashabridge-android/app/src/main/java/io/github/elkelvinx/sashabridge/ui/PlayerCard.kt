package io.github.elkelvinx.sashabridge.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.media.RepeatMode
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
 * Track changes are a card swipe. The art starts sliding away as soon as the change begins
 * (a skip button, or the first state of a new track) and slows down while it waits. The new
 * track is only shown once its art has arrived, so the card never flashes the placeholder:
 * a quick load barely moves the old card, a slow one lets it drift further. Then the old art
 * leaves and the new one slides in from the other side.
 *
 * All animations follow the system's animation scale. Main thread only.
 *
 * @param root View that contains the card.
 * @param onCommand Sends a command to the active player.
 * @param onOpenQueue Opens the play queue of the active player.
 * @param onOpenPlayer Opens a player app, given its package, when its icon is touched.
 * @param appLabel Returns the name of a player package, such as "Spotify".
 * @param appIcon Returns the launcher icon of a player package, or `null` if it is unknown.
 * @param clock Returns `SystemClock.elapsedRealtime()`, the clock of the snapshot positions.
 */
class PlayerCard(
    root: View,
    private val onCommand: (PlayerCommand) -> Unit,
    private val onOpenQueue: () -> Unit,
    private val onOpenPlayer: (String) -> Unit,
    private val appLabel: (String) -> String,
    private val appIcon: (String) -> Drawable?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val context = root.context
    private val density = context.resources.displayMetrics.density
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

    /** Last snapshot received, or `null` when nothing is playing. */
    private var latest: MediaSnapshot? = null

    /** Snapshot on screen; it lags behind [latest] while a new track waits for its art. */
    private var displayed: MediaSnapshot? = null

    /** Art shown; [NO_ART] for the placeholder, `null` before the first update. */
    private var shownArtId: String? = null

    /** Player whose icon is shown. */
    private var shownSource: String? = null

    /** Whether the play/pause button shows the pause icon. */
    private var shownPlaying: Boolean? = null

    /** Track change in progress, or `null`. */
    private var swipe: Swipe? = null

    /** Whether the user is dragging the seek bar, which then stops following the player. */
    private var dragging = false

    /**
     * Position the user seeked to and the [clock] time of the request. Until the player
     * reports a position after that time, the bar stays at the requested position instead of
     * jumping back for a moment.
     */
    private var pendingSeek: Pair<Long, Long>? = null

    /** Shows the new track even without art, if none arrives in time. */
    private val showWithoutArt = Runnable { latest?.let(::finishSwipe) }

    /** Brings the old card back if a skip did not change the track. */
    private val cancelSwipe = Runnable { returnSwipe() }

    init {
        previousButton.setOnClickListener {
            nudge(it, -1)
            beginSwipe(BACKWARD, byUser = true)
            send(CommandAction.PREVIOUS)
        }
        nextButton.setOnClickListener {
            nudge(it, 1)
            beginSwipe(FORWARD, byUser = true)
            send(CommandAction.NEXT)
        }
        playPauseButton.setOnClickListener {
            val play = latest?.isPlaying != true
            // Show the change right away; the player's next state confirms or corrects it.
            showPlaying(play)
            send(if (play) CommandAction.PLAY else CommandAction.PAUSE)
        }
        shuffleButton.setOnClickListener { send(CommandAction.TOGGLE_SHUFFLE) }
        repeatButton.setOnClickListener {
            spin(it)
            onCommand(PlayerCommand.ToggleRepeat)
        }
        icon.setOnClickListener { shownSource?.let(onOpenPlayer) }
        queueButton.setOnClickListener { onOpenQueue() }
        seekBar.progressDrawable = wave
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // The wave moves a little, 20 times a second: the screen need not refresh at 120 Hz.
            seekBar.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_LOW
        }
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
        render(null)
    }

    /**
     * Receives the state of the active player.
     *
     * @param snapshot The state, or `null` when nothing is playing.
     */
    fun show(snapshot: MediaSnapshot?) {
        latest = snapshot
        val current = displayed
        when {
            snapshot == null || current == null -> {
                stopSwipe()
                render(snapshot)
            }
            snapshot.trackId == current.trackId -> {
                val activeSwipe = swipe
                if (activeSwipe != null && activeSwipe.byUser && restarted(snapshot, activeSwipe)) {
                    // "Previous" late in a track restarts it instead of changing it.
                    finishSwipe(snapshot)
                } else {
                    render(snapshot)
                }
            }
            else -> {
                if (swipe == null) beginSwipe(FORWARD, byUser = false)
                cover.removeCallbacks(cancelSwipe)
                if (snapshot.art != null) {
                    finishSwipe(snapshot)
                } else if (swipe?.waitingForArt == false) {
                    swipe?.waitingForArt = true
                    cover.postDelayed(showWithoutArt, ART_WAIT_MS)
                }
            }
        }
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
        val current = displayed
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

    /** Puts [snapshot] on screen at once. */
    private fun render(snapshot: MediaSnapshot?) {
        val first = displayed == null && shownArtId == null
        displayed = snapshot
        showArt(snapshot?.art, crossFade = !first && swipe == null)
        showTrack(snapshot)
        showSource(snapshot?.source)
        val playing = snapshot?.isPlaying == true
        showPlaying(playing)
        wave.setPlaying(playing)
        label.setText(if (snapshot != null && !playing) R.string.label_paused else R.string.label_now_playing)
        showShuffle(snapshot?.shuffle)
        showRepeat(snapshot)

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

    /** Starts sliding the art away, slower and slower, while the next track loads. */
    private fun beginSwipe(direction: Int, byUser: Boolean) {
        if (swipe != null) {
            if (byUser) restartCancelTimer()
            return
        }
        swipe = Swipe(direction, byUser, clock())
        cover.animate().cancel()
        cover.animate()
            .translationX(-direction * cover.width * HOLD_DISTANCE)
            .alpha(HOLD_ALPHA).scaleX(HOLD_SCALE).scaleY(HOLD_SCALE)
            .setDuration(HOLD_MS).setInterpolator(DecelerateInterpolator(2.5f)).start()
        trackInfo.animate().cancel()
        trackInfo.animate().alpha(HOLD_ALPHA).setDuration(FADE_MS * 2).start()
        if (byUser) restartCancelTimer()
    }

    /** (Re)starts the wait after which a skip that changed nothing is undone. */
    private fun restartCancelTimer() {
        cover.removeCallbacks(cancelSwipe)
        cover.postDelayed(cancelSwipe, CANCEL_MS)
    }

    /** Sends the old art away and slides [snapshot] in from the other side. */
    private fun finishSwipe(snapshot: MediaSnapshot) {
        val direction = swipe?.direction ?: FORWARD
        cover.removeCallbacks(showWithoutArt)
        cover.removeCallbacks(cancelSwipe)
        cover.animate().cancel()
        cover.animate()
            .translationX(-direction * cover.width * OUT_DISTANCE).alpha(0f)
            .setDuration(OUT_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Rendered while the swipe is still on, so the art is swapped at once: a
                // cross-fade here would be cancelled by the slide-in below.
                render(latest?.takeIf { it.trackId == snapshot.trackId } ?: snapshot)
                swipe = null
                cover.translationX = direction * cover.width * IN_DISTANCE
                cover.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(IN_MS).setInterpolator(DecelerateInterpolator(2f)).start()
                trackInfo.translationX = direction * density * SLIDE_DP
                trackInfo.animate().alpha(1f).translationX(0f).setDuration(IN_MS).start()
            }.start()
    }

    /** Brings the old art back to its place: the skip changed nothing. */
    private fun returnSwipe() {
        swipe = null
        cover.animate().cancel()
        cover.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(IN_MS).setInterpolator(DecelerateInterpolator(2f)).start()
        trackInfo.animate().alpha(1f).setDuration(IN_MS).start()
    }

    /** Ends any track change at once, putting the card back in place. */
    private fun stopSwipe() {
        if (swipe == null) return
        cover.removeCallbacks(showWithoutArt)
        cover.removeCallbacks(cancelSwipe)
        returnSwipe()
    }

    /** Whether [snapshot] shows the same track starting over after "previous" was pressed. */
    private fun restarted(snapshot: MediaSnapshot, swipe: Swipe): Boolean =
        swipe.direction == BACKWARD &&
            snapshot.positionUpdateTimeMs >= swipe.startedAt &&
            snapshot.reportedPositionMs in 0 until RESTART_MS

    /** Sends a protocol command to the player. */
    private fun send(action: CommandAction, positionMs: Long? = null) {
        onCommand(PlayerCommand.Transport(PcMessage.Command(action, positionMs)))
    }

    /**
     * Shows the album art and its blurred backdrop, or the placeholder and no backdrop
     * without art. Art that changes within the same track cross-fades.
     */
    private fun showArt(art: AlbumArt?, crossFade: Boolean) {
        val artId = art?.artId ?: NO_ART
        if (artId == shownArtId) return
        shownArtId = artId
        val bitmap = art?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
        val blurred = bitmap?.let(Backdrop::of)
        val backdropAlpha = if (blurred != null) BACKDROP_ALPHA else 0f
        val apply = {
            if (bitmap == null) cover.setImageResource(R.drawable.cover_placeholder) else cover.setImageBitmap(bitmap)
        }
        backdrop.animate().cancel()
        backdrop.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            backdrop.setImageBitmap(blurred)
            backdrop.animate().alpha(backdropAlpha).setDuration(FADE_MS * 4).start()
        }.start()
        if (!crossFade) {
            apply()
            return
        }
        cover.animate().cancel()
        cover.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            apply()
            cover.animate().alpha(1f).setDuration(FADE_MS * 2).start()
        }.start()
    }

    /** Shows title, artist and player. */
    private fun showTrack(snapshot: MediaSnapshot?) {
        if (snapshot == null) {
            trackTitle.setText(R.string.nothing_playing)
            trackArtist.isVisible = false
            trackSource.isVisible = false
            return
        }
        trackTitle.text = snapshot.title ?: context.getString(R.string.nothing_playing)
        val artist = listOfNotNull(snapshot.artist, snapshot.playbackContext).joinToString(" • ")
        trackArtist.text = artist
        trackArtist.isVisible = artist.isNotEmpty()
        trackSource.text = context.getString(R.string.now_playing_source, appLabel(snapshot.source))
        trackSource.isVisible = true
    }

    /** Shows the icon of the player app, which opens it, or nothing when it is unknown. */
    private fun showSource(source: String?) {
        if (source == shownSource) return
        shownSource = source
        val drawable = source?.let(appIcon)
        icon.setImageDrawable(drawable)
        icon.isVisible = drawable != null
        icon.contentDescription = source?.let { context.getString(R.string.open_player, appLabel(it)) }
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

    /**
     * Shows the repeat button when the player has one, in its mode: muted when off, wine when
     * everything repeats, and with a "1" when one track repeats. A player that does not reveal
     * its mode gets the plain icon.
     */
    private fun showRepeat(snapshot: MediaSnapshot?) {
        repeatButton.isVisible = snapshot?.canRepeat == true
        if (snapshot?.canRepeat != true) return
        val (image, color, description) = when (snapshot.repeat) {
            RepeatMode.OFF -> Triple(R.drawable.ic_repeat, R.color.on_surface_muted, R.string.control_repeat_off)
            RepeatMode.ALL -> Triple(R.drawable.ic_repeat, R.color.seek_thumb, R.string.control_repeat_all)
            RepeatMode.ONE -> Triple(R.drawable.ic_repeat_one, R.color.seek_thumb, R.string.control_repeat_one)
            null -> Triple(R.drawable.ic_repeat, R.color.on_surface, R.string.control_repeat)
        }
        repeatButton.setImageResource(image)
        repeatButton.imageTintList = ColorStateList.valueOf(color(color))
        repeatButton.contentDescription = context.getString(description)
    }

    /** Pushes a button briefly in the direction it skips. */
    private fun nudge(view: View, direction: Int) {
        val distance = density * NUDGE_DP * direction
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

    /**
     * A track change in progress.
     *
     * @property direction [FORWARD] when the art leaves to the left, [BACKWARD] to the right.
     * @property byUser Whether a skip button started it, so it may have to be undone.
     * @property startedAt [clock] time it started.
     * @property waitingForArt Whether the new track arrived and only its art is missing.
     */
    private class Swipe(
        val direction: Int,
        val byUser: Boolean,
        val startedAt: Long,
        var waitingForArt: Boolean = false,
    )

    private companion object {

        /** [Swipe.direction] towards the next track. */
        const val FORWARD = 1

        /** [Swipe.direction] towards the previous track. */
        const val BACKWARD = -1

        /** [shownArtId] of the placeholder. */
        const val NO_ART = ""

        /** Time the bar keeps a requested position while the player has not reported it yet. */
        const val SEEK_HOLD_MS = 2_000L

        /** Longest wait for the art of a new track before showing it without art. */
        const val ART_WAIT_MS = 1_500L

        /** Time after which a skip that changed nothing is undone. */
        const val CANCEL_MS = 3_000L

        /** Position below which the same track after "previous" counts as restarted. */
        const val RESTART_MS = 2_000L

        /** Time over which the waiting art drifts away, slowing down. */
        const val HOLD_MS = 1_500L

        /** How far, in art widths, the waiting art drifts at most. */
        const val HOLD_DISTANCE = 0.45f

        /** Opacity and scale of the waiting art. */
        const val HOLD_ALPHA = 0.55f
        const val HOLD_SCALE = 0.92f

        /** Time and distance, in art widths, of the old art leaving. */
        const val OUT_MS = 110L
        const val OUT_DISTANCE = 1.1f

        /** Time and starting distance, in art widths, of the new art arriving. */
        const val IN_MS = 260L
        const val IN_DISTANCE = 0.6f

        /** Base duration of the other animations. */
        const val FADE_MS = 120L

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

        /** Opacity of the blurred art; the scrim on top keeps the text readable. */
        const val BACKDROP_ALPHA = 0.9f
    }
}
