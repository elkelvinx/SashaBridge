package io.github.elkelvinx.sashabridge.pen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.protocol.PenSample
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Drawing surface of the "Tableta" mode: turns the S Pen into samples for the PC's pen.
 *
 * - The active area has the shape of the PC's monitor ([targetAspect]), centered with bands
 *   around it, so a circle drawn on the phone is a circle on the monitor. Its corners are the
 *   monitor's corners; a pen outside it counts as being on its nearest edge.
 * - The tip, hovering, the side button and the eraser end become [PenSample]s, historical
 *   points included, one batch per input event ([onSamples]).
 * - Fingers do nothing, so a palm resting on the screen draws nothing.
 * - A dot shows where the pen is: a ring while hovering, filled while touching.
 *
 * Main thread only.
 */
class TabletSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Receives the samples of each pen event, in order. */
    var onSamples: ((List<PenSample>) -> Unit)? = null

    /** Width divided by height of the monitor the tablet draws on. */
    var targetAspect: Float = DEFAULT_ASPECT
        set(value) {
            if (value <= 0f || value == field) return
            field = value
            layoutArea()
            invalidate()
        }

    /** Keeps the back gesture away from the edges where the pen is. */
    private val edges = EdgeGuard(this)

    /** The part of the view that maps onto the monitor. */
    private val area = RectF()

    /** Where the pen is in view coordinates, and whether it hovers or touches. */
    private var penX = 0f
    private var penY = 0f
    private var penState = PenDot.NONE

    /** Last sample sent, to take the pen out of range where it was. */
    private var lastSample: PenSample? = null

    /** Whether the pen was used yet; until then the area shows how to use it. */
    private var used = false

    private val density = resources.displayMetrics.density
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.tablet_area) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_DP * density
        color = ContextCompat.getColor(context, R.color.tablet_border)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.tablet_grid) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = DOT_RING_DP * density
        color = ContextCompat.getColor(context, R.color.tablet_pen)
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HINT_SP, resources.displayMetrics)
        color = ContextCompat.getColor(context, R.color.annotate_muted)
    }
    private val hint = context.getString(R.string.tablet_hint)

    /** The hint laid out for the current area, rebuilt when the area changes. */
    private var hintLayout: StaticLayout? = null

    /** Takes the pen out of range a moment after the system says it left, unless it touches. */
    private val leaveRange = Runnable { leave() }

    /**
     * Takes the pen out of range at once, for example when the screen closes, so the PC's pen
     * does not stay pressed or hovering.
     */
    fun lift() {
        removeCallbacks(leaveRange)
        leave()
        edges.release()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutArea()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = CORNER_DP * density
        canvas.drawRoundRect(area, radius, radius, areaPaint)
        drawGrid(canvas)
        canvas.drawRoundRect(area, radius, radius, borderPaint)
        if (!used) drawHint(canvas)
        when (penState) {
            PenDot.HOVER -> {
                dotPaint.style = Paint.Style.STROKE
                canvas.drawCircle(penX, penY, DOT_DP * density, dotPaint)
            }
            PenDot.CONTACT -> {
                dotPaint.style = Paint.Style.FILL
                canvas.drawCircle(penX, penY, DOT_DP * density * DOT_CONTACT_SCALE, dotPaint)
            }
            PenDot.NONE -> Unit
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event)) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                removeCallbacks(leaveRange)
                emit(event, inContact = false, inRange = true)
            }
            // Android also ends hovering right before the tip touches; a short wait tells the
            // two apart, so a stroke does not flicker out of range on the PC.
            MotionEvent.ACTION_HOVER_EXIT -> postDelayed(leaveRange, LEAVE_DELAY_MS)
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Fingers do nothing; they are consumed so they cannot reach anything else either.
        if (!isPen(event)) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                removeCallbacks(leaveRange)
                emit(event, inContact = true, inRange = true)
            }
            MotionEvent.ACTION_UP -> {
                emit(event, inContact = false, inRange = true)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> emit(event, inContact = false, inRange = false)
        }
        return true
    }

    /** Pen strokes are drawing, not clicks; this keeps accessibility services informed. */
    override fun performClick(): Boolean = super.performClick()

    /** Sends the samples of [event], historical points first, and moves the dot. */
    private fun emit(event: MotionEvent, inContact: Boolean, inRange: Boolean) {
        val eraser = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        val barrel = event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
        val samples = ArrayList<PenSample>(event.historySize + 1)
        for (h in 0 until event.historySize) {
            samples += sample(
                event.getHistoricalEventTime(h), event.getHistoricalX(h), event.getHistoricalY(h),
                if (inContact) event.getHistoricalPressure(h) else 0f,
                event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, h),
                inContact, inRange, barrel, eraser,
            )
        }
        samples += sample(
            event.eventTime, event.x, event.y,
            if (inContact) event.pressure else 0f,
            event.getAxisValue(MotionEvent.AXIS_TILT),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION),
            inContact, inRange, barrel, eraser,
        )
        lastSample = samples.last()
        onSamples?.invoke(samples)
        if (inRange) edges.follow(event.y)
        used = true
        penX = event.x.coerceIn(area.left, area.right)
        penY = event.y.coerceIn(area.top, area.bottom)
        penState = when {
            inContact -> PenDot.CONTACT
            inRange -> PenDot.HOVER
            else -> PenDot.NONE
        }
        invalidate()
    }

    /** Sends the last position again, out of range. */
    private fun leave() {
        val last = lastSample ?: return
        if (!last.inRange && !last.inContact) return
        val out = last.copy(
            timeMs = TabletRepository.wireTime(SystemClock.uptimeMillis()),
            pressure = 0,
            inContact = false,
            inRange = false,
        )
        lastSample = out
        onSamples?.invoke(listOf(out))
        penState = PenDot.NONE
        invalidate()
    }

    /**
     * One sample at view coordinates (x, y).
     *
     * @param tilt Angle from the perpendicular, in radians ([MotionEvent.AXIS_TILT]).
     * @param orientation Direction the pen points, in radians, 0 being up and positive
     *   clockwise ([MotionEvent.AXIS_ORIENTATION]).
     */
    private fun sample(
        timeMs: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        inContact: Boolean,
        inRange: Boolean,
        barrel: Boolean,
        eraser: Boolean,
    ): PenSample {
        val tiltDegrees = Math.toDegrees(tilt.toDouble())
        return PenSample(
            timeMs = TabletRepository.wireTime(timeMs),
            x = unit((x - area.left) / area.width()),
            y = unit((y - area.top) / area.height()),
            pressure = unit(pressure),
            tiltX = (tiltDegrees * sin(orientation.toDouble())).roundToInt(),
            tiltY = (-tiltDegrees * cos(orientation.toDouble())).roundToInt(),
            inContact = inContact,
            inRange = inRange,
            barrel = barrel,
            eraser = eraser,
        )
    }

    /** Fits the area with the monitor's shape in the view, centered, with a margin. */
    private fun layoutArea() {
        val margin = MARGIN_DP * density
        val maxWidth = width - 2 * margin
        val maxHeight = height - 2 * margin
        if (maxWidth <= 0f || maxHeight <= 0f) return
        val areaWidth = min(maxWidth, maxHeight * targetAspect)
        val areaHeight = areaWidth / targetAspect
        val left = (width - areaWidth) / 2
        val top = (height - areaHeight) / 2
        area.set(left, top, left + areaWidth, top + areaHeight)
        val textWidth = (areaWidth * HINT_WIDTH).toInt().coerceAtLeast(1)
        hintLayout = StaticLayout.Builder.obtain(hint, 0, hint.length, hintPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    /** Faint dots every sixteenth of the area, to judge positions on the monitor. */
    private fun drawGrid(canvas: Canvas) {
        val radius = GRID_DOT_DP * density
        for (i in 1 until GRID_STEPS) {
            for (j in 1 until GRID_STEPS) {
                canvas.drawCircle(
                    area.left + area.width() * i / GRID_STEPS,
                    area.top + area.height() * j / GRID_STEPS,
                    radius,
                    gridPaint,
                )
            }
        }
    }

    /** How to use the tablet, centered in the area until the pen is first used. */
    private fun drawHint(canvas: Canvas) {
        val layout = hintLayout ?: return
        canvas.withTranslation(area.centerX() - layout.width / 2f, area.centerY() - layout.height / 2f) {
            layout.draw(this)
        }
    }

    /** Whether the S Pen, either end, produced [event]. */
    private fun isPen(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /** What the pen dot shows. */
    private enum class PenDot { NONE, HOVER, CONTACT }

    private companion object {
        /** Shape assumed until the PC names its monitor: 16:9. */
        const val DEFAULT_ASPECT = 16f / 9f

        /** Largest value of a sample coordinate or pressure. */
        const val UNIT_MAX = 0xFFFF

        /** Wait after the pen stops hovering before it counts as out of range. */
        const val LEAVE_DELAY_MS = 120L

        const val MARGIN_DP = 12f
        const val CORNER_DP = 14f
        const val BORDER_DP = 1.5f
        const val DOT_DP = 7f
        const val DOT_RING_DP = 2f
        const val DOT_CONTACT_SCALE = 0.7f
        const val GRID_STEPS = 16
        const val GRID_DOT_DP = 1.2f
        const val HINT_SP = 16f

        /** Width of the hint, as a share of the area's width. */
        const val HINT_WIDTH = 0.7f

        /** Maps 0..1 (clamped) to 0..65535. */
        fun unit(value: Float): Int = (value.coerceIn(0f, 1f) * UNIT_MAX).roundToInt()
    }
}
