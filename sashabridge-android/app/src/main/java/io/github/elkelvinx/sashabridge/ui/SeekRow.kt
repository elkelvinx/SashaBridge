package io.github.elkelvinx.sashabridge.ui

import android.os.Build
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.MediaSnapshot

/**
 * The player card's seek row: elapsed time, seek bar and length. The bar advances on its own
 * between states ([tick]), because a state carries the position the player reported and the
 * time of that report; its played part is a sound wave that travels while the track plays.
 *
 * Dragging the bar seeks; until the player reports a position after the request, the bar
 * stays where the user left it instead of jumping back for a moment.
 *
 * Main thread.
 *
 * @param root View that contains the row.
 * @param clock Returns `SystemClock.elapsedRealtime()`, the clock of the state positions.
 * @param onSeek Asks the player to move to a position, in milliseconds.
 */
internal class SeekRow(root: View, private val clock: () -> Long, private val onSeek: (Long) -> Unit) {

    private val context = root.context
    private val seekBar: SeekBar = root.findViewById(R.id.seekBar)
    private val elapsedTime: TextView = root.findViewById(R.id.elapsedTime)
    private val durationTime: TextView = root.findViewById(R.id.durationTime)
    private val wave = WavyProgressDrawable(context)

    /** The state shown. */
    private var snapshot: MediaSnapshot? = null

    /** Whether the user is dragging the bar, which then stops following the player. */
    private var dragging = false

    /** Position the user seeked to and the [clock] time of the request, until the player reports it. */
    private var pendingSeek: Pair<Long, Long>? = null

    init {
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
                onSeek(target)
            }
        })
    }

    /** Shows [snapshot]: its length, whether the wave travels, and its position. */
    fun show(snapshot: MediaSnapshot?) {
        this.snapshot = snapshot
        wave.setPlaying(snapshot?.isPlaying == true)
        val duration = snapshot?.durationMs
        seekBar.isEnabled = duration != null
        seekBar.max = duration?.toInt() ?: 0
        durationTime.text = duration?.let(TrackTime::format) ?: context.getString(R.string.unknown_time)
        pendingSeek?.let { (_, requestedAt) ->
            if (snapshot == null || snapshot.positionUpdateTimeMs >= requestedAt) pendingSeek = null
        }
        tick()
    }

    /** Draws the played part as a wave, or as a straight line, as chosen in the settings. */
    fun setWaves(waves: Boolean) {
        wave.setWaves(waves)
    }

    /** One step of the screen's [FrameTicker]: the wave travels and the bar follows the position. */
    fun step() {
        wave.step()
        tick()
    }

    /**
     * Moves the bar and the elapsed time to the current position. The text is only set when
     * the second changes, so it does not ask for a redraw on every step.
     */
    private fun tick() {
        if (dragging) return
        val current = snapshot
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

    private companion object {

        /** Time the bar keeps a requested position while the player has not reported it yet. */
        const val SEEK_HOLD_MS = 2_000L
    }
}
