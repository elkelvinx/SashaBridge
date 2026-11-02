package io.github.elkelvinx.sashabridge.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.history.DayUsage

/**
 * A bar per day, as tall as the music of that day next to the day with most, in the primary
 * color; a day without music is a short stub in the outline color. Today, the last bar, is
 * at full strength and the others a little softer.
 *
 * Drawn by hand: a chart library would weigh more than the whole statistics screen.
 * Main thread.
 */
class DayBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MaterialColors.getColor(this@DayBarsView, R.attr.sashaAccent) }
    private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.outline) }
    private val bar = RectF()

    /** Milliseconds of music per day, oldest first. */
    private var values: List<Long> = emptyList()

    /** Shows [days], oldest first. */
    fun setDays(days: List<DayUsage>) {
        values = days.map { it.playedMs }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val gap = GAP_DP * density
        val width = (width - paddingLeft - paddingRight - gap * (values.size - 1)) / values.size
        val bottom = (height - paddingBottom).toFloat()
        val room = bottom - paddingTop
        val most = values.max().coerceAtLeast(1)
        val radius = width / 2
        values.forEachIndexed { index, value ->
            val left = paddingLeft + index * (width + gap)
            val tall = if (value == 0L) STUB_DP * density else maxOf(MIN_DP * density, room * value / most)
            bar.set(left, bottom - tall, left + width, bottom)
            val paint = if (value == 0L) stubPaint else barPaint
            paint.alpha = if (index == values.lastIndex || value == 0L) OPAQUE else SOFT
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    private companion object {
        const val GAP_DP = 3f
        const val STUB_DP = 2f
        const val MIN_DP = 4f
        const val OPAQUE = 255
        const val SOFT = 190
    }
}
