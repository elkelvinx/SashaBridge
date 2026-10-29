package io.github.elkelvinx.sashabridge.pen

import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps the back gesture off the side edges of [view] where the S Pen is.
 *
 * With gesture navigation, a swipe in from a side edge means "back", and a pen stroke that
 * starts at the edge would leave the screen. Android lets an app take back only part of each
 * edge (200 dp), so a band of both edges follows the pen, as it hovers and as it draws.
 *
 * Main thread only.
 *
 * @param view Full-screen view whose edges are guarded; its coordinates are the screen's.
 */
class EdgeGuard(private val view: View) {

    /** Height on screen where the band was last placed, or NaN when there is none. */
    private var bandCenter = Float.NaN
    private val bands = listOf(Rect(), Rect())

    /**
     * Places the band on both side edges around height [y], where the pen is.
     *
     * The band moves only once the pen leaves its middle, so hovering does not ask the system
     * to recompute the exclusion for every sample.
     */
    fun follow(y: Float) {
        val density = view.resources.displayMetrics.density
        val band = BAND_DP * density
        if (!bandCenter.isNaN() && abs(y - bandCenter) < band / SLACK) return
        bandCenter = y
        val top = (y - band / 2).coerceIn(0f, max(0f, view.height - band)).toInt()
        val bottom = top + band.toInt()
        val edge = (WIDTH_DP * density).toInt()
        bands[0].set(0, top, edge, bottom)
        bands[1].set(view.width - edge, top, view.width, bottom)
        ViewCompat.setSystemGestureExclusionRects(view, bands)
    }

    /** Gives the side edges back to the back gesture. */
    fun release() {
        bandCenter = Float.NaN
        ViewCompat.setSystemGestureExclusionRects(view, emptyList())
    }

    private companion object {
        /**
         * Height of the band kept off the back gesture on each side edge, under the 200 dp per
         * edge that Android honors, and how wide it reaches into the screen.
         */
        const val BAND_DP = 190f
        const val WIDTH_DP = 48f

        /** The band moves once the pen is more than 1/4 of its height from its middle. */
        const val SLACK = 4f
    }
}
