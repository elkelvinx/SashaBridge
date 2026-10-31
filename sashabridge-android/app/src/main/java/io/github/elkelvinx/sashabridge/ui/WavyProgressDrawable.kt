package io.github.elkelvinx.sashabridge.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import io.github.elkelvinx.sashabridge.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Track of the seek bar: what is left is a straight light coffee line, and what was played is
 * a red sound wave that travels while the track plays and flattens when it pauses.
 *
 * The seek bar sets the progress through the drawable's level (0 to 10000). The wave travels
 * one step on each [step] of the screen's [FrameTicker], together with Sasha, not at the
 * screen's refresh rate (up to 120 Hz): a slow wave looks the same, and the screen, often left
 * on as a display, redraws once per step for both. It only travels while the drawable is
 * visible, so a hidden screen does not keep redrawing.
 *
 * Main thread only.
 *
 * @param context Context that resolves the colors and the density.
 */
class WavyProgressDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = TRACK_WIDTH_DP * density
        color = ContextCompat.getColor(context, R.color.seek_track)
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = WAVE_WIDTH_DP * density
        color = ContextCompat.getColor(context, R.color.seek_played)
    }

    private val path = Path()

    /** Horizontal shift of the wave, in radians. */
    private var phase = 0f

    /** Current height of the wave; 0 draws a straight line. */
    private var amplitude = 0f

    /** Whether the track plays, which makes the wave rise and travel. */
    private var playing = false

    private var rise: ValueAnimator? = null

    /**
     * Makes the wave rise and travel while the track plays, and flatten when it pauses.
     *
     * @param playing Whether the track is playing.
     */
    fun setPlaying(playing: Boolean) {
        if (playing == this.playing) return
        this.playing = playing
        rise?.cancel()
        rise = ValueAnimator.ofFloat(amplitude, if (playing) AMPLITUDE_DP * density else 0f).apply {
            duration = RISE_MS
            addUpdateListener {
                amplitude = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    /** Moves the wave one step, if the track plays and the bar can be seen. Call on each [FrameTicker] step. */
    fun step() {
        if (!playing || !isVisible) return
        phase = (phase + PHASE_STEP) % FULL_TURN
        invalidateSelf()
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val inset = trackPaint.strokeWidth / 2
        val left = bounds.left + inset
        val right = bounds.right - inset
        val centerY = bounds.exactCenterY()
        val progressX = left + (right - left) * level / MAX_LEVEL

        if (progressX < right) canvas.drawLine(progressX, centerY, right, centerY, trackPaint)
        if (progressX <= left) return

        val wavelength = WAVELENGTH_DP * density
        val step = density * 2
        path.reset()
        var x = left
        path.moveTo(x, centerY + waveY(x - left, wavelength))
        while (x < progressX) {
            x = min(x + step, progressX)
            path.lineTo(x, centerY + waveY(x - left, wavelength))
        }
        canvas.drawPath(path, wavePaint)
    }

    /** Height of the wave at distance [dx] from its start. */
    private fun waveY(dx: Float, wavelength: Float): Float =
        amplitude * sin(2 * PI * dx / wavelength - phase).toFloat()

    override fun getIntrinsicHeight(): Int = (HEIGHT_DP * density).toInt()

    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = alpha
        wavePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        trackPaint.colorFilter = colorFilter
        wavePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val MAX_LEVEL = 10_000f
        const val TRACK_WIDTH_DP = 5f
        const val WAVE_WIDTH_DP = 4f
        const val AMPLITUDE_DP = 3f
        const val WAVELENGTH_DP = 22f
        const val HEIGHT_DP = 16f
        const val WAVE_PERIOD_MS = 1_400L
        const val RISE_MS = 300L

        val FULL_TURN = (2 * PI).toFloat()

        /** How far the wave moves each step, to cross a wavelength every [WAVE_PERIOD_MS]. */
        val PHASE_STEP = FULL_TURN * FrameTicker.STEP_MS / WAVE_PERIOD_MS
    }
}
