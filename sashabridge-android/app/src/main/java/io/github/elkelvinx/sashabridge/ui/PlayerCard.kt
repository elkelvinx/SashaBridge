package io.github.elkelvinx.sashabridge.ui

import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.PlayerCommand
import io.github.elkelvinx.sashabridge.media.PlayerQueue
import io.github.elkelvinx.sashabridge.protocol.CommandAction
import io.github.elkelvinx.sashabridge.protocol.PcMessage

/**
 * The "Sonando" card of the main screen: album art, track, controls and seek bar, over a
 * blurred copy of the art.
 *
 * It shows the [MediaSnapshot]s it receives and decides when the track on the card changes;
 * each part does the rest:
 * - [CoverArt]: the art and the blurred backdrop.
 * - [CoverMotion]: how the art moves when the track changes.
 * - [PlayerControls]: the buttons and the player's modes.
 * - [SeekRow]: the seek bar, the times and the wave.
 * - [TitleFitter]: the size of a title that must stay whole, in landscape.
 *
 * Track changes are a card swipe. The art starts leaving as soon as the change begins (a skip
 * button, or the first state of a new track) and floats while it waits. The new track is only
 * shown once its art has arrived, so the card never flashes the placeholder; if the art takes
 * too long ([ART_WAIT_MS]) the track shows without it, and a skip that changes nothing in
 * [CANCEL_MS] brings the old art back. The art leaves towards the side of the new track: with
 * a button, as the button says; for a change made elsewhere, by where both tracks are in the
 * queue.
 *
 * The card works the same in both orientations: it finds its views by id, and the views only
 * one layout has are optional.
 *
 * Main thread only.
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
    private val label: TextView = root.findViewById(R.id.playerLabel)
    private val icon: ImageView = root.findViewById(R.id.appIcon)
    private val cover: View = root.findViewById(R.id.cover)
    private val trackTitle: TextView = root.findViewById(R.id.trackTitle)
    private val trackArtist: TextView = root.findViewById(R.id.trackArtist)
    private val trackSource: TextView = root.findViewById(R.id.trackSource)
    private val queueButton: View = root.findViewById(R.id.queueButton)

    private val art = CoverArt(root)
    private val motion = CoverMotion(cover, root.findViewById(R.id.trackInfo))
    private val controls = PlayerControls(
        root,
        isPlaying = { latest?.isPlaying == true },
        onPrevious = ::previous,
        onNext = ::next,
        onCommand = onCommand,
    )
    private val seekRow = SeekRow(root, clock) { send(CommandAction.SEEK, it) }
    private val titleFitter = TitleFitter(trackTitle)

    /** Whether the title shrinks to fit, as in the landscape card. */
    private val fitTitle = trackTitle.tag == context.getString(R.string.tag_fit_title)

    /** Play queue of the active player, or `null`. */
    private var queue: PlayerQueue? = null

    /**
     * The queue as it was while the track on screen played. YouTube Music rebuilds its queue
     * when the user jumps back to a track that already played, and the new queue may no
     * longer hold the track that was playing; this one still does.
     */
    private var queueOfShownTrack: PlayerQueue? = null

    /** Last snapshot received, or `null` when nothing is playing. */
    private var latest: MediaSnapshot? = null

    /** Snapshot on screen; it lags behind [latest] while a new track waits for its art. */
    private var displayed: MediaSnapshot? = null

    /** Player whose icon is shown. */
    private var shownSource: String? = null

    /** Track change in progress, or `null`. */
    private var swipe: Swipe? = null

    /** Shows the new track even without art, if none arrives in time. */
    private val showWithoutArt = Runnable { latest?.let(::finishSwipe) }

    /** Shows the latest state again, once the queue had a moment to tell a change's way. */
    private val retryShow = Runnable { show(latest) }

    /** Track whose direction is awaited from the queue, and until when, in [clock] time. */
    private var queueWaitFor: String? = null
    private var queueWaitUntil = 0L

    /** Brings the old card back if a skip did not change the track. */
    private val cancelSwipe = Runnable { returnSwipe() }

    init {
        icon.setOnClickListener { shownSource?.let(onOpenPlayer) }
        queueButton.setOnClickListener { onOpenQueue() }
        root.findViewById<View>(R.id.historyButton).setOnClickListener { onOpenHistory() }
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

    /** Sets how the blurred art behind the card looks; see [CoverArt.setBackdrop]. */
    fun setBackdrop(blur: Int, strength: Int) {
        art.setBackdrop(blur, strength)
    }

    /** Draws the played part of the seek bar as a wave, or as a straight line. */
    fun setWaves(waves: Boolean) {
        seekRow.setWaves(waves)
    }

    /**
     * One step of the screen's [FrameTicker]: the wave travels and the seek bar follows the
     * position, on the same display frame as Sasha.
     */
    fun step() {
        seekRow.step()
    }

    /** "Previous": past the first seconds, players start the track over instead of going back. */
    private fun previous() {
        val position = displayed?.positionAt(clock()) ?: 0L
        if (position > RESTART_MS) motion.rewind() else beginSwipe(BACKWARD, byUser = true)
        send(CommandAction.PREVIOUS)
    }

    /** "Next". */
    private fun next() {
        beginSwipe(FORWARD, byUser = true)
        send(CommandAction.NEXT)
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

    /** Puts [snapshot] on screen at once. */
    private fun render(snapshot: MediaSnapshot?) {
        val first = displayed == null && !art.hasShownArt
        displayed = snapshot
        rememberQueueOfShownTrack()
        art.show(snapshot?.art, crossFade = !first && swipe == null && !motion.settling)
        showTrack(snapshot)
        showSource(snapshot?.source)
        controls.show(snapshot)
        val paused = snapshot != null && !snapshot.isPlaying
        label.setText(if (paused) R.string.label_paused else R.string.label_now_playing)
        seekRow.show(snapshot)
    }

    /** Starts a track change towards [direction]; see [CoverMotion.leave]. */
    private fun beginSwipe(direction: Int, byUser: Boolean) {
        if (swipe != null) {
            if (byUser) restartCancelTimer()
            return
        }
        swipe = Swipe(direction, byUser)
        motion.leave(direction)
        if (byUser) restartCancelTimer()
    }

    /** (Re)starts the wait after which a skip that changed nothing is undone. */
    private fun restartCancelTimer() {
        cover.removeCallbacks(cancelSwipe)
        cover.postDelayed(cancelSwipe, CANCEL_MS)
    }

    /** Ends a track change: the old art leaves and [snapshot] comes in; see [CoverMotion.exit]. */
    private fun finishSwipe(snapshot: MediaSnapshot) {
        val direction = swipe?.direction ?: FORWARD
        cover.removeCallbacks(showWithoutArt)
        cover.removeCallbacks(cancelSwipe)
        motion.exit(direction) {
            // Rendered while the swipe is still on, so the art is swapped at once.
            render(latest?.takeIf { it.trackId == snapshot.trackId } ?: snapshot)
            swipe = null
        }
    }

    /** Brings the old art back to its place: the skip changed nothing. */
    private fun returnSwipe() {
        swipe = null
        motion.comeBack()
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

    /** Shows title, artist and player. */
    private fun showTrack(snapshot: MediaSnapshot?) {
        if (snapshot == null) {
            trackTitle.setText(R.string.nothing_playing)
            trackArtist.isVisible = false
            trackSource.isVisible = false
            return
        }
        trackTitle.text = snapshot.title ?: context.getString(R.string.nothing_playing)
        if (fitTitle) trackTitle.doOnLayout { titleFitter.fit() }
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

        /** Longest wait for the art of a new track before showing it without art. */
        const val ART_WAIT_MS = 1_500L

        /** Time after which a skip that changed nothing is undone. */
        const val CANCEL_MS = 3_000L

        /**
         * Position after which "previous" starts the track over instead of going back, as
         * Spotify and YouTube Music do.
         */
        const val RESTART_MS = 3_000L

        /** How long a change made elsewhere waits for the queue to tell its way. */
        const val QUEUE_WAIT_MS = 150L
    }
}
