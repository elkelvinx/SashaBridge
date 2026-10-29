package io.github.elkelvinx.sashabridge.pen

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A mark drawn on a capture. Coordinates are pixels of the capture, so the marks keep their
 * place at any zoom and the exported image has them exactly where they were drawn.
 *
 * Immutable; can be shared between threads.
 */
sealed interface InkShape {

    /** Color as ARGB. */
    val color: Int

    /** Line width in capture pixels. */
    val width: Float
}

/**
 * A free-hand line: the pen, or the highlighter.
 *
 * @property points x, y and pressure (0 to 1) of each point, one after the other.
 * @property highlighter Whether it is a translucent, wide highlighter stroke.
 */
class InkStroke(
    val points: FloatArray,
    override val color: Int,
    override val width: Float,
    val highlighter: Boolean,
) : InkShape {

    /** Number of points. */
    val size: Int get() = points.size / 3
}

/** A straight line, used to strike text out. */
data class InkLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    override val color: Int,
    override val width: Float,
) : InkShape

/** A rectangle outline between two corners. */
data class InkRect(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    override val color: Int,
    override val width: Float,
) : InkShape

/** An arrow from (x1, y1) with its head at (x2, y2). */
data class InkArrow(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    override val color: Int,
    override val width: Float,
) : InkShape

/**
 * A note written on the capture.
 *
 * @property x Left of the text.
 * @property y Baseline of the first line.
 * @property text The note; it may have several lines.
 * @property size Text size in capture pixels.
 */
data class InkText(
    val x: Float,
    val y: Float,
    val text: String,
    override val color: Int,
    val size: Float,
) : InkShape {
    override val width: Float get() = size * TEXT_STROKE_RATIO
}

/** Line width that matches a text size: a text is `width / TEXT_STROKE_RATIO` pixels tall. */
const val TEXT_STROKE_RATIO = 0.16f

/** Geometry of the marks. Pure and thread-safe. */
object InkGeometry {

    /** Angle between the shaft of an arrow and each side of its head. */
    private const val HEAD_ANGLE = Math.PI / 7

    /** Length of an arrow head, in line widths, and its minimum in capture pixels. */
    private const val HEAD_LENGTH_WIDTHS = 4.5f
    private const val HEAD_MIN_LENGTH = 14f

    /** Average width of a character, relative to the text size, to estimate a text's box. */
    private const val CHAR_WIDTH = 0.55f

    /**
     * Returns the ends of the two sides of an arrow head.
     *
     * @return x and y of the left end, then x and y of the right end.
     */
    fun arrowHead(arrow: InkArrow): FloatArray {
        val angle = atan2((arrow.y2 - arrow.y1).toDouble(), (arrow.x2 - arrow.x1).toDouble())
        val length = max(arrow.width * HEAD_LENGTH_WIDTHS, HEAD_MIN_LENGTH).toDouble()
        val back = angle + Math.PI
        return floatArrayOf(
            (arrow.x2 + length * cos(back - HEAD_ANGLE)).toFloat(),
            (arrow.y2 + length * sin(back - HEAD_ANGLE)).toFloat(),
            (arrow.x2 + length * cos(back + HEAD_ANGLE)).toFloat(),
            (arrow.y2 + length * sin(back + HEAD_ANGLE)).toFloat(),
        )
    }

    /**
     * Whether the point (x, y) touches [shape], for the eraser.
     *
     * @param tolerance Extra distance, in capture pixels, that still counts as a touch.
     */
    fun hits(shape: InkShape, x: Float, y: Float, tolerance: Float): Boolean {
        val reach = tolerance + shape.width / 2
        return when (shape) {
            is InkStroke -> {
                if (shape.size == 1) return hypot(x - shape.points[0], y - shape.points[1]) <= reach
                (1 until shape.size).any { i ->
                    val a = (i - 1) * 3
                    val b = i * 3
                    distanceToSegment(x, y, shape.points[a], shape.points[a + 1], shape.points[b], shape.points[b + 1]) <= reach
                }
            }
            is InkLine -> distanceToSegment(x, y, shape.x1, shape.y1, shape.x2, shape.y2) <= reach
            is InkArrow -> distanceToSegment(x, y, shape.x1, shape.y1, shape.x2, shape.y2) <= reach
            is InkRect -> {
                val (l, t, r, b) = listOf(min(shape.x1, shape.x2), min(shape.y1, shape.y2), max(shape.x1, shape.x2), max(shape.y1, shape.y2))
                distanceToSegment(x, y, l, t, r, t) <= reach ||
                    distanceToSegment(x, y, r, t, r, b) <= reach ||
                    distanceToSegment(x, y, r, b, l, b) <= reach ||
                    distanceToSegment(x, y, l, b, l, t) <= reach
            }
            is InkText -> {
                val lines = shape.text.lines()
                val right = shape.x + (lines.maxOfOrNull { it.length } ?: 0) * shape.size * CHAR_WIDTH
                val top = shape.y - shape.size
                val bottom = shape.y + (lines.size - 1) * shape.size * 1.2f + shape.size * 0.3f
                x in (shape.x - tolerance)..(right + tolerance) && y in (top - tolerance)..(bottom + tolerance)
            }
        }
    }

    /** Distance from (px, py) to the segment from (ax, ay) to (bx, by). */
    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
