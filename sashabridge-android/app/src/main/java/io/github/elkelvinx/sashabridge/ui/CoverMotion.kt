package io.github.elkelvinx.sashabridge.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * How the album art moves when the track changes; when it moves is [PlayerCard]'s decision.
 *
 * A change is a card swipe, as in the user's sketch "animations could.png":
 * 1. [leave]: the art dips and shrinks at once, so the tap gets an answer, then rises in a
 *    curve towards the side it leaves by and floats there, as long as the next track takes to
 *    load. The track's text fades a little meanwhile.
 * 2. [exit]: the old art carries on its curve and leaves the card; the new one comes in from
 *    the other side, a little low, and rises into place, and the text slides in.
 * 3. Or [comeBack]: the skip changed nothing, and the art returns to its place.
 *
 * [rewind] answers a "previous" that only starts the track over: a nudge towards the past.
 *
 * All animations follow the system's animation scale. Main thread.
 *
 * @param cover The album art.
 * @param info The track's title and artist.
 */
internal class CoverMotion(private val cover: View, private val info: View) {

    private val density = cover.resources.displayMetrics.density

    /** The art floating while the next track loads. */
    private var floating: Animator? = null

    /** Whether the art is away from its place, waiting for the next track. */
    private var away = false

    /**
     * Whether the art is sliding into its place. A new image then replaces the old one at once:
     * fading it would stop the slide half way and leave the art out of place.
     */
    var settling = false
        private set

    /** Sends the art away towards [direction], 1 for the next track, -1 for the previous one. */
    fun leave(direction: Int) {
        away = true
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
                    .setDuration(ARC_MS).setInterpolator(DecelerateInterpolator(ARC_EASE))
                    .withEndAction(::startFloating)
                    .start()
            }.start()
        info.animate().cancel()
        info.animate().alpha(INFO_WAIT_ALPHA).setDuration(INFO_FADE_MS).start()
    }

    /**
     * Sends the old art out along its curve; then [swap] puts the new track on the card, and
     * its art comes in from the side opposite [direction].
     */
    fun exit(direction: Int, swap: () -> Unit) {
        stopFloating()
        cover.animate().cancel()
        cover.animate()
            .translationX(-direction * cover.width * OUT_SHIFT).translationY(-cover.height * OUT_RISE)
            .scaleX(OUT_SCALE).scaleY(OUT_SCALE).alpha(0f)
            .setDuration(OUT_MS).setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Swapped while the art is still away, so the new image replaces the old one
                // at once: a cross-fade here would be cancelled by the slide-in below.
                swap()
                away = false
                cover.translationX = direction * cover.width * IN_SHIFT
                cover.translationY = cover.height * IN_DROP
                cover.scaleX = IN_SCALE
                cover.scaleY = IN_SCALE
                settle()
                info.translationX = direction * density * SLIDE_DP
                info.animate().alpha(1f).translationX(0f).setDuration(IN_MS).start()
            }.start()
    }

    /** Brings the art back to its place: the skip changed nothing. */
    fun comeBack() {
        away = false
        stopFloating()
        cover.animate().cancel()
        settle()
        info.animate().alpha(1f).setDuration(IN_MS).start()
    }

    /** Nudges the art towards the past and back, for a track that only started over. */
    fun rewind() {
        if (away || settling) return
        cover.animate().cancel()
        cover.animate().translationX(cover.width * REWIND_SHIFT).setDuration(REWIND_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                cover.animate().translationX(0f).setDuration(REWIND_MS * 2).setInterpolator(OvershootInterpolator()).start()
            }.start()
    }

    /** Floats the waiting art gently up and down until the change ends. */
    private fun startFloating() {
        if (!away) return
        val top = cover.translationY
        floating = ObjectAnimator.ofFloat(cover, View.TRANSLATION_Y, top, top - cover.height * FLOAT_RISE).apply {
            duration = FLOAT_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** Stops the floating, leaving the art where it is. */
    private fun stopFloating() {
        floating?.cancel()
        floating = null
    }

    /** Slides the art into its place, whatever state it is in, and marks it [settling] meanwhile. */
    private fun settle() {
        settling = true
        cover.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(IN_MS).setInterpolator(DecelerateInterpolator(SETTLE_EASE))
            .withEndAction { settling = false }
            .start()
    }

    private companion object {

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
        const val ARC_EASE = 1.5f

        /** The floating while the track still loads: half a period and height, in art heights. */
        const val FLOAT_MS = 700L
        const val FLOAT_RISE = 0.025f

        /** Opacity of the title and artist while the change waits, and how fast they fade. */
        const val INFO_WAIT_ALPHA = 0.55f
        const val INFO_FADE_MS = 240L

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
        const val SETTLE_EASE = 2f

        /** Distance a new track's text slides in from. */
        const val SLIDE_DP = 12f

        /** The nudge of a restart: time and distance, in art widths. */
        const val REWIND_MS = 110L
        const val REWIND_SHIFT = 0.06f
    }
}
