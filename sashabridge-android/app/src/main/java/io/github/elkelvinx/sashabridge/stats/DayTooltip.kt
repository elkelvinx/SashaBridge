package io.github.elkelvinx.sashabridge.stats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import androidx.core.content.ContextCompat
import io.github.elkelvinx.sashabridge.R

/**
 * The bubble over a bar of [DayBarsView]: the day, and below it how long music played, on the
 * bars' color with an arrow pointing at the bar.
 *
 * A drawable in the overlay of the chart's card, not a view or a window, so it floats over the
 * card's title without taking room in the layout. [DayBarsView] places it ([show]) and hides it.
 *
 * Main thread.
 *
 * @param color The fill, the color of the bars.
 */
internal class DayTooltip(context: Context, color: Int) : Drawable() {

    private val resources = context.resources
    private val density = resources.displayMetrics.density
    private val text = ContextCompat.getColor(context, R.color.on_primary)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(DATE_SP)
        this.color = text
        alpha = DATE_ALPHA
    }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(TIME_SP)
        typeface = Typeface.DEFAULT_BOLD
        this.color = text
    }

    private val body = RectF()
    private val arrow = Path()
    private var date = ""
    private var time = ""
    private var shown = false

    /** Fades the bubble in when it appears; moving it between bars is instant. */
    private val fadeIn = ValueAnimator.ofInt(0, OPAQUE).apply {
        duration = FADE_MS
        addUpdateListener { alpha = it.animatedValue as Int }
    }

    /**
     * Shows [date] and [time] above the point ([anchorX], [anchorY]), the top of a bar, both
     * in the coordinates of the overlay's view. The bubble stays inside [limit], that view's
     * room, moving sideways near its edges while the arrow keeps pointing at the bar.
     */
    fun show(date: String, time: String, anchorX: Float, anchorY: Float, limit: RectF) {
        this.date = date
        this.time = time
        val padH = PAD_H_DP * density
        val padV = PAD_V_DP * density
        val width = maxOf(datePaint.measureText(date), timePaint.measureText(time)) + 2 * padH
        val height = lineHeight(datePaint) + LINE_GAP_DP * density + lineHeight(timePaint) + 2 * padV
        val arrowHeight = ARROW_DP * density
        val left = (anchorX - width / 2).coerceIn(limit.left, maxOf(limit.left, limit.right - width))
        val bottom = maxOf(anchorY - GAP_DP * density - arrowHeight, limit.top + height)
        body.set(left, bottom - height, left + width, bottom)

        val radius = RADIUS_DP * density
        val tipX = anchorX.coerceIn(body.left + radius + arrowHeight, body.right - radius - arrowHeight)
        arrow.rewind()
        arrow.moveTo(tipX - arrowHeight, bottom)
        arrow.lineTo(tipX, bottom + arrowHeight)
        arrow.lineTo(tipX + arrowHeight, bottom)
        arrow.close()

        setBounds(body.left.toInt(), body.top.toInt(), body.right.toInt() + 1, (bottom + arrowHeight).toInt() + 1)
        if (!shown) {
            shown = true
            fadeIn.start()
        }
        invalidateSelf()
    }

    /** Hides the bubble. */
    fun hide() {
        if (!shown) return
        shown = false
        fadeIn.cancel()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!shown) return
        val radius = RADIUS_DP * density
        canvas.drawRoundRect(body, radius, radius, fill)
        canvas.drawPath(arrow, fill)
        val padV = PAD_V_DP * density
        val centerX = body.centerX()
        val dateBaseline = body.top + padV - datePaint.fontMetrics.ascent
        canvas.drawText(date, centerX - datePaint.measureText(date) / 2, dateBaseline, datePaint)
        val timeBaseline = dateBaseline + datePaint.fontMetrics.descent + LINE_GAP_DP * density - timePaint.fontMetrics.ascent
        canvas.drawText(time, centerX - timePaint.measureText(time) / 2, timeBaseline, timePaint)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        datePaint.alpha = alpha * DATE_ALPHA / OPAQUE
        timePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        datePaint.colorFilter = colorFilter
        timePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    private fun sp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val DATE_SP = 12f
        const val TIME_SP = 15f
        const val PAD_H_DP = 12f
        const val PAD_V_DP = 7f
        const val LINE_GAP_DP = 1f
        const val RADIUS_DP = 10f

        /** Height of the arrow, and half its width. */
        const val ARROW_DP = 6f

        /** Space between the arrow's tip and the bar. */
        const val GAP_DP = 4f

        const val OPAQUE = 255

        /** The day is a little softer than the time, which is what the bubble is for. */
        const val DATE_ALPHA = 215

        const val FADE_MS = 120L
    }
}
