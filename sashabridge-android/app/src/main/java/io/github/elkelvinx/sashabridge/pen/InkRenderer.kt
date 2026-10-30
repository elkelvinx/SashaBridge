package io.github.elkelvinx.sashabridge.pen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Draws [InkShape]s on a canvas whose coordinates are capture pixels. The annotation screen
 * and the exported image use the same renderer, so the result looks exactly like the screen.
 *
 * Not thread-safe: it reuses its paints. Use one instance per thread.
 */
class InkRenderer {

    /** Pixels of the [InkImage] marks, by [InkImage.pictureId]. */
    var pictures: Map<Int, Bitmap> = emptyMap()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val pictureBox = RectF()
    private val pictureFilter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val selectionShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SELECTION_SHADOW
    }
    private val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = SELECTION_COLOR
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_COLOR
    }

    /** Draws every shape, in order. */
    fun draw(canvas: Canvas, shapes: List<InkShape>) {
        shapes.forEach { draw(canvas, it) }
    }

    /** Draws one shape. */
    fun draw(canvas: Canvas, shape: InkShape) {
        stroke.color = shape.color
        stroke.strokeWidth = shape.width
        when (shape) {
            is InkStroke -> drawStroke(canvas, shape)
            is InkLine -> canvas.drawLine(shape.x1, shape.y1, shape.x2, shape.y2, stroke)
            is InkRect -> canvas.drawRect(
                min(shape.x1, shape.x2), min(shape.y1, shape.y2),
                max(shape.x1, shape.x2), max(shape.y1, shape.y2), stroke,
            )
            is InkArrow -> {
                canvas.drawLine(shape.x1, shape.y1, shape.x2, shape.y2, stroke)
                val head = InkGeometry.arrowHead(shape)
                path.reset()
                path.moveTo(head[0], head[1])
                path.lineTo(shape.x2, shape.y2)
                path.lineTo(head[2], head[3])
                canvas.drawPath(path, stroke)
            }
            is InkImage -> pictures[shape.pictureId]?.let { picture ->
                pictureBox.set(shape.left, shape.top, shape.right, shape.bottom)
                canvas.drawBitmap(picture, null, pictureBox, pictureFilter)
            }
            is InkText -> {
                text.color = shape.color
                text.textSize = shape.size
                shape.text.lines().forEachIndexed { index, line ->
                    canvas.drawText(line, shape.x, shape.y + index * shape.size * TEXT_LINE_SPACING, text)
                }
            }
        }
    }

    /**
     * Draws the dashed box around the mark the move or resize tool holds: white dashes over a
     * dark line, so it shows on any capture. Screen only; exported images never have it.
     *
     * @param dp Capture pixels per screen dp at the current zoom, so the box looks the same
     *   at any zoom.
     * @param handles Whether to draw a square handle on each corner, for resizing.
     */
    fun drawSelection(canvas: Canvas, bounds: InkBounds, dp: Float, handles: Boolean = false) {
        val pad = SELECTION_PADDING_DP * dp
        val left = bounds.left - pad
        val top = bounds.top - pad
        val right = bounds.right + pad
        val bottom = bounds.bottom + pad
        selectionShadow.strokeWidth = (SELECTION_WIDTH_DP + 1) * dp
        selection.strokeWidth = SELECTION_WIDTH_DP * dp
        selection.pathEffect = DashPathEffect(floatArrayOf(DASH_DP * dp, DASH_DP * dp), 0f)
        for (paint in listOf(selectionShadow, selection)) {
            canvas.drawRect(left, top, right, bottom, paint)
        }
        if (!handles) return
        val half = HANDLE_DP * dp / 2
        for (x in floatArrayOf(left, right)) {
            for (y in floatArrayOf(top, bottom)) {
                canvas.drawRect(x - half, y - half, x + half, y + half, handleFill)
                canvas.drawRect(x - half, y - half, x + half, y + half, selectionShadow)
            }
        }
    }

    /**
     * Draws a free-hand stroke. The pen follows the pressure segment by segment; the
     * highlighter is one translucent path, so its overlaps do not get darker.
     */
    private fun drawStroke(canvas: Canvas, shape: InkStroke) {
        val p = shape.points
        if (shape.highlighter) {
            stroke.color = ColorUtils.setAlphaComponent(shape.color, HIGHLIGHTER_ALPHA)
            stroke.strokeCap = Paint.Cap.SQUARE
            path.reset()
            path.moveTo(p[0], p[1])
            for (i in 1 until shape.size) path.lineTo(p[i * 3], p[i * 3 + 1])
            if (shape.size == 1) path.lineTo(p[0] + 0.1f, p[1])
            canvas.drawPath(path, stroke)
            stroke.strokeCap = Paint.Cap.ROUND
            return
        }
        if (shape.size == 1) {
            stroke.strokeWidth = widthAt(shape, p[2])
            canvas.drawPoint(p[0], p[1], stroke)
            return
        }
        for (i in 1 until shape.size) {
            val a = (i - 1) * 3
            val b = i * 3
            stroke.strokeWidth = widthAt(shape, (p[a + 2] + p[b + 2]) / 2)
            canvas.drawLine(p[a], p[a + 1], p[b], p[b + 1], stroke)
        }
    }

    /** Width of the pen at a pressure from 0 to 1: light touches draw thinner. */
    private fun widthAt(shape: InkStroke, pressure: Float): Float =
        shape.width * (MIN_PRESSURE_WIDTH + (1 - MIN_PRESSURE_WIDTH) * pressure.coerceIn(0f, 1f))

    private companion object {
        /** Width of the lightest pen touch, relative to a full press. */
        const val MIN_PRESSURE_WIDTH = 0.35f

        /** Opacity of the highlighter, out of 255. */
        const val HIGHLIGHTER_ALPHA = 0x66

        /** The selection box: white dashes over a translucent dark line, in screen dp. */
        const val SELECTION_COLOR = 0xFFFFFFFF.toInt()
        const val SELECTION_SHADOW = 0x99000000.toInt()
        const val SELECTION_WIDTH_DP = 1.5f
        const val SELECTION_PADDING_DP = 6f
        const val DASH_DP = 6f

        /** Side of a resize handle, in screen dp. */
        const val HANDLE_DP = 10f
    }
}
