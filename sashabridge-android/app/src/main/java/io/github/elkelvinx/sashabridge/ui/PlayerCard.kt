package io.github.elkelvinx.sashabridge.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
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
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.AlbumArt
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.media.PlayerQueue
import io.github.elkelvinx.sashabridge.media.RepeatMode
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.ShuffleMode
import kotlin.math.roundToInt

/**
 * The "Sonando" card of the main screen: album art, track, controls and seek bar, over a
 * blurred copy of the art that tints the card with the colors of the track. How blurred, and
 * how strongly it shows, the user sets ([setBackdrop]).
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
 * @param onOpenHistory Opens the history of the tracks listened to.
 * @param onOpenPlayer Opens a player app, given its package, when its icon is touched.
 * @param appLabel Returns the name of a player package, such as "Spotify".
 * @param appIcon Returns the launcher icon of a player package, or `null` if it is unknown.
 * @param clock Returns `SystemClock.elapsedRealtime()`, the clock of the snapshot positions.
 */
class PlayerCard(
    root: View,
    private val onCommand: (PlayerCommand) -> Unit,
    private val onOpenQueue: () -> Unit,
    private val onOpenHistory: () -> Unit,
    private val onOpenPlayer: (String) -> Unit,
    private val appLabel: (String) -> String,
    private val appIcon: (String) -> Drawable?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private val backdrop: ImageView = root.findViewById(R.id.coverBackdrop)

    /** Veil over [backdrop]; its own copy of the drawable, so its opacity can change. */
    private val veil: Drawable = root.findViewById<View>(R.id.coverScrim).background.mutate()

    /** The art on screen, kept to blur it again when the user changes the blur. */
    private var shownBitmap: Bitmap? = null

    /** How blurred the backdrop is, from 0 to [Backdrop.MAX_LEVEL]. */
    private var backdropBlur = Backdrop.DEFAULT_LEVEL
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
    private val likeButton: ImageButton = root.findViewById(R.id.likeButton)

    /** Groups of the mode buttons and of the heart; only the landscape card has them. */
    private val modeGroup: View? = root.findViewById(R.id.modeGroup)
    private val likeGroup: View? = root.findViewById(R.id.likeGroup)

    /** Whether the title shrinks to fit, as in the landscape card. */
    private val fitTitle = trackTitle.tag == context.getString(R.string.tag_fit_title)

    /** Size the shrinking title has now, in sp; the layout starts it at [TITLE_MAX_SP]. */
    private var titleSp = TITLE_MAX_SP
    private val queueButton: View = root.findViewById(R.id.queueButton)
    private val seekBar: SeekBar = root.findViewById(R.id.seekBar)
    private val elapsedTime: TextView = root.findViewById(R.id.elapsedTime)
    private val durationTime: TextView = root.findViewById(R.id.durationTime)
    private val wave = WavyProgressDrawable(context)

    /** Strong shade of the accent the user chose: active modes and the liked heart. */
    private val accent = MaterialColors.getColor(root, R.attr.sashaAccent)

    /** Play queue of the active player, or `null`. */
    private var queue: PlayerQueue? = null

    /**
     * The queue as it was while the track on screen played. YouTube Music rebuilds its queue
     * when the user jumps back to a track that already played, and the new queue may no
     * longer hold the track that was playing; this one still does.
     */
    private var queueOfShownTrack: PlayerQueue? = null

    /** The art floating while the next track loads, until the change ends. */
    private var waitFloat: Animator? = null

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

    /** Shows the latest state again, once the queue had a moment to tell a change's way. */
    private val retryShow = Runnable { show(latest) }

    /** Track whose direction is awaited from the queue, and until when, in [clock] time. */
    private var queueWaitFor: String? = null
    private var queueWaitUntil = 0L

    /**
     * Whether the art is sliding into place (a new track, or back after a skip that changed
     * nothing). A new image then replaces the old one at once: fading it would stop the slide
     * half way and leave the art out of place.
     */
    private var settling = false

    /** Brings the old card back if a skip did not change the track. */
    private val cancelSwipe = Runnable { returnSwipe() }

    init {
        previousButton.setOnClickListener {
            nudge(it, -1)
            // Past the first seconds, players start the track over instead of going back.
            val position = displayed?.positionAt(clock()) ?: 0L
            if (position > RESTART_MS) rewind() else beginSwipe(BACKWARD, byUser = true)
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
        likeButton.setOnClickListener {
            pop(it)
            send(CommandAction.TOGGLE_LIKE)
        }
        repeatButton.setOnClickListener {
            spin(it)
            onCommand(PlayerCommand.ToggleRepeat)
        }
        icon.setOnClickListener { shownSource?.let(onOpenPlayer) }
        queueButton.setOnClickListener { onOpenQueue() }
        root.findViewById<View>(R.id.historyButton).setOnClickListener { onOpenHistory() }
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
            snapshot.trackId == current.trackId -> render(snapshot)
            else -> {
                if (swipe == null) {
                    // A change made elsewhere (the PC, the player): the queue tells its way,
                    // best the one from before the change. The new queue may come a moment
                    // after the track, so a change it cannot place yet waits for it briefly.
                    val direction = queueOfShownTrack?.directionBetween(current.title, snapshot.title)
                        ?: queue?.directionBetween(current.title, snapshot.title)
                    if (direction == null && waitForQueue(snapshot.trackId)) return
                    beginSwipe(direction ?: FORWARD, byUser = false)
                }
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
     * Receives the play queue of the active player: it shows the queue button, and tells
     * which way a track change made outside the app goes.
     *
     * @param queue The queue, or `null` if the player publishes none.
     */
    fun setQueue(queue: PlayerQueue?) {
        this.queue = queue
        queueButton.isVisible = queue != null
        rememberQueueOfShownTrack()
    }

    /**
     * Starts the track change at once, before the player answers, when the user picks a track
     * of the queue: the way it goes is already known.
     *
     * @param direction 1 for a later track, -1 for an earlier one.
     */
    fun skipTo(direction: Int) {
        beginSwipe(if (direction < 0) BACKWARD else FORWARD, byUser = true)
    }

    /**
     * Whether to wait a moment for the queue before animating the change to [trackId]: true
     * the first [QUEUE_WAIT_MS] after the track appears, false afterwards.
     */
    private fun waitForQueue(trackId: String): Boolean {
        val now = clock()
        if (queueWaitFor != trackId) {
            queueWaitFor = trackId
            queueWaitUntil = now + QUEUE_WAIT_MS
        }
        if (now >= queueWaitUntil) return false
        cover.removeCallbacks(retryShow)
        cover.postDelayed(retryShow, queueWaitUntil - now)
        return true
    }

    /** Keeps [queue] as [queueOfShownTrack] when it is the queue of the track on screen. */
    private fun rememberQueueOfShownTrack() {
        val current = queue ?: return
        val title = current.activeTitle() ?: return
        if (title == displayed?.title) queueOfShownTrack = current
    }

    /**
     * Sets how the blurred art behind the card looks, as chosen in the settings.
     *
     * @param blur How blurred it is, from 0 to [Backdrop.MAX_LEVEL].
     * @param strength How strongly it shows through the veil, from 0 to [Backdrop.MAX_LEVEL].
     */
    fun setBackdrop(blur: Int, strength: Int) {
        veil.alpha = (Backdrop.veilAlpha(strength) * OPAQUE).roundToInt()
        if (blur == backdropBlur) return
        backdropBlur = blur
        shownBitmap?.let { backdrop.setImageBitmap(Backdrop.of(it, blur)) }
    }

    /**
     * Draws the played part of the seek bar as a wave, or as a straight line.
     *
     * @param waves Whether to draw the wave, as chosen in the settings.
     */
    fun setWaves(waves: Boolean) {
        wave.setWaves(waves)
    }

    /**
     * One step of the screen's [FrameTicker]: the wave travels and the seek bar follows the
     * position, on the same display frame as Sasha.
     */
    fun step() {
        wave.step()
        tick()
    }

    /**
     * Moves the seek bar and the elapsed time to the current position. The text is only set
     * when the second changes, so it does not ask for a redraw on every step.
     */
    fun tick() {
        if (dragging) return
        val current = displayed
        if (current?.durationMs == null) {
            seekBar.progress = 0
            setElapsed(context.getString(R.string.unknown_time))
            return
        }
        val now = clock()
        val position = pendingSeek?.let { (target, requestedAt) ->
            if (now - requestedAt < SEEK_HOLD_MS) target else null
        } ?: current.positionAt(now)
        seekBar.progress = position.toInt()
        setElapsed(TrackTime.format(position))
    }

    /** Shows [text] as the elapsed time, unless it already does. */
    private fun setElapsed(text: String) {
        if (elapsedTime.text.toString() != text) elapsedTime.text = text
    }

    /** Puts [snapshot] on screen at once. */
    private fun render(snapshot: MediaSnapshot?) {
        val first = displayed == null && shownArtId == null
        displayed = snapshot
        rememberQueueOfShownTrack()
        showArt(snapshot?.art, crossFade = !first && swipe == null && !settling)
        showTrack(snapshot)
        showSource(snapshot?.source)
        val playing = snapshot?.isPlaying == true
        showPlaying(playing)
        wave.setPlaying(playing)
        label.setText(if (snapshot != null && !playing) R.string.label_paused else R.string.label_now_playing)
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
     * Starts a track change: the art dips and shrinks at once, so the tap gets an answer,
     * then rises in a curve towards the side it leaves by ([Swipe.direction]) and floats there,
     * as long as the next track takes to load.
     */
    private fun beginSwipe(direction: Int, byUser: Boolean) {
        if (swipe != null) {
            if (byUser) restartCancelTimer()
            return
        }
        swipe = Swipe(direction, byUser)
        stopFloating()
        settling = false
        cover.animate().cancel()
        cover.animate()
            .translationX(0f).translationY(cover.height * DIP_DROP).scaleX(DIP_SCALE).scaleY(DIP_SCALE).alpha(1f)
            .setDuration(DIP_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                cover.animate()
                    .translationX(-direction * cover.width * ARC_SHIFT).translationY(-cover.height * ARC_RISE)
                    .scaleX(ARC_SCALE).scaleY(ARC_SCALE).alpha(ARC_ALPHA)
                    .setDuration(ARC_MS).setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction(::startFloating)
                    .start()
            }.start()
        trackInfo.animate().cancel()
        trackInfo.animate().alpha(INFO_WAIT_ALPHA).setDuration(FADE_MS * 2).start()
        if (byUser) restartCancelTimer()
    }

    /** Floats the waiting art gently up and down until the change ends. */
    private fun startFloating() {
        if (swipe == null) return
        val top = cover.translationY
        waitFloat = ObjectAnimator.ofFloat(cover, View.TRANSLATION_Y, top, top - cover.height * FLOAT_RISE).apply {
            duration = FLOAT_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** Stops the floating, leaving the art where it is. */
    private fun stopFloating() {
        waitFloat?.cancel()
        waitFloat = null
    }

    /** (Re)starts the wait after which a skip that changed nothing is undone. */
    private fun restartCancelTimer() {
        cover.removeCallbacks(cancelSwipe)
        cover.postDelayed(cancelSwipe, CANCEL_MS)
    }

    /**
     * Ends a track change: the old art carries on its curve and leaves the card, and [snapshot]
     * comes in from the other side, rising a little into place.
     */
    private fun finishSwipe(snapshot: MediaSnapshot) {
        val direction = swipe?.direction ?: FORWARD
        cover.removeCallbacks(showWithoutArt)
        cover.removeCallbacks(cancelSwipe)
        stopFloating()
        cover.animate().cancel()
        cover.animate()
            .translationX(-direction * cover.width * OUT_SHIFT).translationY(-cover.height * OUT_RISE)
            .scaleX(OUT_SCALE).scaleY(OUT_SCALE).alpha(0f)
            .setDuration(OUT_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Rendered while the swipe is still on, so the art is swapped at once: a
                // cross-fade here would be cancelled by the slide-in below.
                render(latest?.takeIf { it.trackId == snapshot.trackId } ?: snapshot)
                swipe = null
                cover.translationX = direction * cover.width * IN_SHIFT
                cover.translationY = cover.height * IN_DROP
                cover.scaleX = IN_SCALE
                cover.scaleY = IN_SCALE
                settleCover()
                trackInfo.translationX = direction * density * SLIDE_DP
                trackInfo.animate().alpha(1f).translationX(0f).setDuration(IN_MS).start()
            }.start()
    }

    /** Brings the old art back to its place: the skip changed nothing. */
    private fun returnSwipe() {
        swipe = null
        stopFloating()
        cover.animate().cancel()
        settleCover()
        trackInfo.animate().alpha(1f).setDuration(IN_MS).start()
    }

    /** Slides the art into its place, whatever state it is in, and marks it [settling] meanwhile. */
    private fun settleCover() {
        settling = true
        cover.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(IN_MS).setInterpolator(DecelerateInterpolator(2f))
            .withEndAction { settling = false }
            .start()
    }

    /**
     * Answers "previous" late in a track, which only starts it over: the art nudges towards the
     * past and comes back, with no track change.
     */
    private fun rewind() {
        if (swipe != null || settling) return
        cover.animate().cancel()
        cover.animate().translationX(cover.width * REWIND_SHIFT).setDuration(REWIND_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                cover.animate().translationX(0f).setDuration(REWIND_MS * 2).setInterpolator(OvershootInterpolator()).start()
            }.start()
    }

    /** Ends any track change at once, putting the card back in place. */
    private fun stopSwipe() {
        if (swipe == null) return
        cover.removeCallbacks(showWithoutArt)
        cover.removeCallbacks(cancelSwipe)
        returnSwipe()
    }

    /** Sends a protocol command to the player. */
    private fun send(action: CommandAction, positionMs: Long? = null) {
        onCommand(PlayerCommand.Transport(PcMessage.Command(action, positionMs)))
    }

    /**
     * Shows the album art and its blurred backdrop, or the placeholder and no backdrop
     * without art. Art that changes within the same track cross-fades.
     *
     * The cover uses the art's image for the screen, as sharp as the player gave it; only art
     * without one falls back to the PC's small JPEG.
     */
    private fun showArt(art: AlbumArt?, crossFade: Boolean) {
        val artId = art?.artId ?: NO_ART
        if (artId == shownArtId) return
        shownArtId = artId
        val bitmap = art?.let { it.screen ?: BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
        shownBitmap = bitmap
        val blurred = bitmap?.let { Backdrop.of(it, backdropBlur) }
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
        if (fitTitle) trackTitle.doOnLayout { shrinkTitle() }
        val artist = listOfNotNull(snapshot.artist, snapshot.playbackContext).joinToString(" • ")
        trackArtist.text = artist
        trackArtist.isVisible = artist.isNotEmpty()
        trackSource.text = context.getString(R.string.now_playing_source, appLabel(snapshot.source))
        trackSource.isVisible = true
    }

    /**
     * Sizes the title so it stays whole: as large as [TITLE_MAX_SP] on one line, shrinking down
     * to [TITLE_ONE_LINE_MIN_SP]; a longer title takes two lines from [TITLE_TWO_LINES_MAX_SP]
     * down to [TITLE_MIN_SP], and only past that ends with "...". The size changes only when
     * it must, so a track change does not reflow the card for nothing.
     */
    private fun shrinkTitle() {
        val width = trackTitle.width - trackTitle.totalPaddingLeft - trackTitle.totalPaddingRight
        val text = trackTitle.text
        if (width <= 0 || text.isEmpty()) return
        val paint = TextPaint(trackTitle.paint)
        fun lines(sizeSp: Float): Int {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, context.resources.displayMetrics)
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).build().lineCount
        }
        val oneLine = generateSequence(TITLE_MAX_SP) { it - TITLE_STEP_SP }
            .takeWhile { it >= TITLE_ONE_LINE_MIN_SP }
            .firstOrNull { lines(it) == 1 }
        val size = oneLine ?: generateSequence(TITLE_TWO_LINES_MAX_SP) { it - TITLE_STEP_SP }
            .takeWhile { it > TITLE_MIN_SP }
            .firstOrNull { lines(it) <= 2 } ?: TITLE_MIN_SP
        if (size != titleSp) {
            titleSp = size
            trackTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
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
     * accent when everything repeats, and with a "1" when one track repeats. A player that does not reveal
     * its mode gets the plain icon.
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
     * Shows the like button when the player has one: a filled heart in the accent when the track is
     * liked, an outline otherwise, and a plain outline when the player does not say.
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
        view.animate().scaleX(1f).scaleY(1f).setDuration(FADE_MS * 2).setInterpolator(OvershootInterpolator()).start()
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
     * @property waitingForArt Whether the new track arrived and only its art is missing.
     */
    private class Swipe(
        val direction: Int,
        val byUser: Boolean,
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

        /**
         * Position after which "previous" starts the track over instead of going back, as
         * Spotify and YouTube Music do.
         */
        const val RESTART_MS = 3_000L

        /** The nudge of a restart: time and distance, in art widths. */
        const val REWIND_MS = 110L
        const val REWIND_SHIFT = 0.06f

        /** How long a change made elsewhere waits for the queue to tell its way. */
        const val QUEUE_WAIT_MS = 150L

        /** The dip that answers the tap: time, drop and scale, in art heights. */
        const val DIP_MS = 140L
        const val DIP_DROP = 0.06f
        const val DIP_SCALE = 0.94f

        /** The curve up towards the exit while the track loads, in art sizes. */
        const val ARC_MS = 520L
        const val ARC_SHIFT = 0.3f
        const val ARC_RISE = 0.07f
        const val ARC_SCALE = 0.8f
        const val ARC_ALPHA = 0.7f

        /** The floating while the track still loads: half a period and height, in art heights. */
        const val FLOAT_MS = 700L
        const val FLOAT_RISE = 0.025f

        /** Opacity of the title and artist while the change waits. */
        const val INFO_WAIT_ALPHA = 0.55f

        /** The old art leaving along its curve: time, distance, rise and scale, in art sizes. */
        const val OUT_MS = 170L
        const val OUT_SHIFT = 1.05f
        const val OUT_RISE = 0.14f
        const val OUT_SCALE = 0.6f

        /** The new art arriving from the other side, a little low: time, start and scale. */
        const val IN_MS = 320L
        const val IN_SHIFT = 0.35f
        const val IN_DROP = 0.08f
        const val IN_SCALE = 0.85f

        /** Base duration of the other animations. */
        const val FADE_MS = 120L

        /** Scale the play/pause icon starts from when it changes. */
        const val POP_SCALE = 0.7f

        /** Sizes of a title that shrinks to fit: on one line, then on two, and the step. */
        const val TITLE_MAX_SP = 30f
        const val TITLE_ONE_LINE_MIN_SP = 22f
        const val TITLE_TWO_LINES_MAX_SP = 24f
        const val TITLE_MIN_SP = 18f
        const val TITLE_STEP_SP = 2f

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

        /** Drawable alpha of a fully opaque veil. */
        const val OPAQUE = 255
    }
}
