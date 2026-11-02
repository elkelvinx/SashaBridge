package io.github.elkelvinx.sashabridge.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.history.DayUsage
import kotlin.math.abs

/**
 * A bar per day, as tall as the music of that day next to the day with most, in the primary
 * color; a day without music is a short stub in the outline color. Today, the last bar, is
 * at full strength and the others a little softer.
 *
 * Touching a bar opens a bubble with its day and exact time ([DayTooltip]), and that bar stands
 * out. Sliding sideways moves it from day to day, with a light tick on each; touching the same
 * bar again closes it. A gesture that is mostly vertical scrolls the page instead.
 *
 * Drawn by hand: a chart library would weigh more than the whole statistics screen.
 * Main thread.
 */
class DayBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val accent = MaterialColors.getColor(this, R.attr.sashaAccent)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.outline) }
    private val bar = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val tooltip = DayTooltip(context, accent)
    private val tooltipRoom = RectF()

    /** The days shown, oldest first. */
    private var days: List<DayUsage> = emptyList()

    /** Index of the day with the bubble, or [NONE]. */
    private var selected = NONE

    /** Where the touch began, the day selected before it, and whether it slides along the bars. */
    private var downX = 0f
    private var downY = 0f
    private var selectedBefore = NONE
    private var sliding = false

    /**
     * The two lines of the bubble of a day: the day, and how long music played. Set by the
     * page, which knows how dates and times are written.
     */
    var describe: ((DayUsage) -> Pair<String, String>)? = null

    /** Shows [days], oldest first. A day that has the bubble keeps it, with its new time. */
    fun setDays(days: List<DayUsage>) {
        this.days = days
        if (selected > days.lastIndex) selected = NONE
        placeTooltip()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.overlay?.add(tooltip)
    }

    override fun onDetachedFromWindow() {
        (parent as? ViewGroup)?.overlay?.remove(tooltip)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) placeTooltip()
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val width = barWidth()
        val most = mostPlayed()
        val radius = width / 2
        days.forEachIndexed { index, day ->
            barBounds(index, width, most)
            val paint = if (day.playedMs == 0L) stubPaint else barPaint
            val strong = if (selected == NONE) index == days.lastIndex else index == selected
            paint.alpha = if (strong || day.playedMs == 0L) OPAQUE else SOFT
            canvas.drawRoundRect(bar, radius, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                selectedBefore = selected
                sliding = false
                // The pager would take a sideways slide for a change of tab.
                parent?.requestDisallowInterceptTouchEvent(true)
                select(indexAt(event.x))
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (!sliding && dy > touchSlop && dy > dx) {
                    // A scroll of the page that began on the chart: the page takes it, and
                    // this touch ends with ACTION_CANCEL.
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else {
                    if (dx > touchSlop) sliding = true
                    if (sliding) select(indexAt(event.x))
                }
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // A second touch on the day that has the bubble closes it.
                if (!sliding && selected == selectedBefore) select(NONE, tick = false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> select(selectedBefore, tick = false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Gives the bubble to the day at [index], or closes it for [NONE]. */
    private fun select(index: Int, tick: Boolean = true) {
        if (index == selected) return
        selected = index
        if (tick && index != NONE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        placeTooltip()
        invalidate()
    }

    /** Puts the bubble over the selected bar, or hides it. */
    private fun placeTooltip() {
        val day = days.getOrNull(selected)
        val lines = day?.let { describe?.invoke(it) }
        val host = parent as? ViewGroup
        if (lines == null || host == null || width == 0) {
            tooltip.hide()
            return
        }
        barBounds(selected, barWidth(), mostPlayed())
        tooltipRoom.set(host.paddingLeft.toFloat(), 0f, (host.width - host.paddingRight).toFloat(), (top + height).toFloat())
        tooltip.show(lines.first, lines.second, anchorX = left + bar.centerX(), anchorY = top + bar.top, limit = tooltipRoom)
    }

    /** Width of each bar, so that the bars and their gaps fill the view. */
    private fun barWidth(): Float = (width - paddingLeft - paddingRight - GAP_DP * density * (days.size - 1)) / days.size

    /** The longest time of a day, which fills the height; at least 1. */
    private fun mostPlayed(): Long = days.maxOf { it.playedMs }.coerceAtLeast(1)

    /** Sets [bar] to the bar of the day at [index], [width] wide, with [most] filling the height. */
    private fun barBounds(index: Int, width: Float, most: Long) {
        val bottom = (height - paddingBottom).toFloat()
        val room = bottom - paddingTop
        val value = days[index].playedMs
        val left = paddingLeft + index * (width + GAP_DP * density)
        val tall = if (value == 0L) STUB_DP * density else maxOf(MIN_DP * density, room * value / most)
        bar.set(left, bottom - tall, left + width, bottom)
    }

    /** Index of the bar under [x], the nearest one at the ends. */
    private fun indexAt(x: Float): Int {
        val step = barWidth() + GAP_DP * density
        return ((x - paddingLeft) / step).toInt().coerceIn(0, days.lastIndex)
    }

    private companion object {
        const val GAP_DP = 3f
        const val STUB_DP = 2f
        const val MIN_DP = 4f
        const val OPAQUE = 255
        const val SOFT = 190
        const val NONE = -1
    }
}
