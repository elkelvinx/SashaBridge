package io.github.elkelvinx.sashabridge.pen

import kotlin.math.abs
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

/**
 * A picture placed on the capture, such as a photo from the phone, filling the box from
 * (left, top) to (right, bottom). The pixels live with the canvas, keyed by [pictureId], so
 * the mark stays plain data.
 *
 * @property pictureId Key of the picture's pixels.
 */
data class InkImage(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pictureId: Int,
) : InkShape {
    override val color: Int get() = 0
    override val width: Float get() = 0f
}

/** Line width that matches a text size: a text is `width / TEXT_STROKE_RATIO` pixels tall. */
const val TEXT_STROKE_RATIO = 0.16f

/** Distance between the baselines of a text's lines, relative to its size. */
const val TEXT_LINE_SPACING = 1.2f

/** A box in capture pixels, such as the area a mark covers. */
data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    /** Width of the box. */
    val width: Float get() = right - left

    /** Height of the box. */
    val height: Float get() = bottom - top

    /** Whether (x, y) is inside the box grown by [margin] on every side. */
    fun contains(x: Float, y: Float, margin: Float = 0f): Boolean =
        x in (left - margin)..(right + margin) && y in (top - margin)..(bottom + margin)

    /** The corner closest to (x, y). */
    fun nearestCorner(x: Float, y: Float): InkCorner {
        val right = x > (left + right) / 2
        val bottom = y > (top + bottom) / 2
        return InkCorner.entries.first { it.right == right && it.bottom == bottom }
    }
}

/**
 * A corner of an [InkBounds], the one the pen drags to resize a mark.
 *
 * @property right Whether it is on the right side.
 * @property bottom Whether it is on the bottom side.
 */
enum class InkCorner(val right: Boolean, val bottom: Boolean) {
    TOP_LEFT(right = false, bottom = false),
    TOP_RIGHT(right = true, bottom = false),
    BOTTOM_LEFT(right = false, bottom = true),
    BOTTOM_RIGHT(right = true, bottom = true),
}

/** Geometry of the marks. Pure and thread-safe. */
object InkGeometry {

    /** Angle between the shaft of an arrow and each side of its head. */
    private const val HEAD_ANGLE = Math.PI / 7

    /** Length of an arrow head, in line widths, and its minimum in capture pixels. */
    private const val HEAD_LENGTH_WIDTHS = 4.5f
    private const val HEAD_MIN_LENGTH = 14f

    /** Average width of a character, relative to the text size, to estimate a text's box. */
    private const val CHAR_WIDTH = 0.55f

    /** How far a text's last line reaches below its baseline, relative to its size. */
    private const val DESCENT = 0.3f

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
            is InkText, is InkImage -> bounds(shape).contains(x, y, tolerance)
        }
    }

    /**
     * Returns the area [shape] covers, line width included; a text's box is an estimate from
     * its number of characters.
     */
    fun bounds(shape: InkShape): InkBounds = when (shape) {
        is InkStroke -> box(
            (0 until shape.size).map { shape.points[it * 3] },
            (0 until shape.size).map { shape.points[it * 3 + 1] },
            shape.width,
        )
        is InkLine -> box(listOf(shape.x1, shape.x2), listOf(shape.y1, shape.y2), shape.width)
        is InkRect -> box(listOf(shape.x1, shape.x2), listOf(shape.y1, shape.y2), shape.width)
        is InkArrow -> {
            val head = arrowHead(shape)
            box(listOf(shape.x1, shape.x2, head[0], head[2]), listOf(shape.y1, shape.y2, head[1], head[3]), shape.width)
        }
        is InkImage -> InkBounds(shape.left, shape.top, shape.right, shape.bottom)
        is InkText -> {
            val lines = shape.text.lines()
            InkBounds(
                left = shape.x,
                top = shape.y - shape.size,
                right = shape.x + (lines.maxOfOrNull { it.length } ?: 0) * shape.size * CHAR_WIDTH,
                bottom = shape.y + (lines.size - 1) * shape.size * TEXT_LINE_SPACING + shape.size * DESCENT,
            )
        }
    }

    /** The box around points [xs], [ys], grown by half of a line [width]. */
    private fun box(xs: List<Float>, ys: List<Float>, width: Float): InkBounds {
        val half = width / 2
        return InkBounds(
            (xs.minOrNull() ?: 0f) - half,
            (ys.minOrNull() ?: 0f) - half,
            (xs.maxOrNull() ?: 0f) + half,
            (ys.maxOrNull() ?: 0f) + half,
        )
    }

    /** Returns a copy of [shape] moved by (dx, dy) capture pixels. */
    fun translate(shape: InkShape, dx: Float, dy: Float): InkShape = when (shape) {
        is InkStroke -> InkStroke(
            FloatArray(shape.points.size) { i ->
                when (i % 3) {
                    0 -> shape.points[i] + dx
                    1 -> shape.points[i] + dy
                    else -> shape.points[i]
                }
            },
            shape.color,
            shape.width,
            shape.highlighter,
        )
        is InkLine -> shape.copy(x1 = shape.x1 + dx, y1 = shape.y1 + dy, x2 = shape.x2 + dx, y2 = shape.y2 + dy)
        is InkRect -> shape.copy(x1 = shape.x1 + dx, y1 = shape.y1 + dy, x2 = shape.x2 + dx, y2 = shape.y2 + dy)
        is InkArrow -> shape.copy(x1 = shape.x1 + dx, y1 = shape.y1 + dy, x2 = shape.x2 + dx, y2 = shape.y2 + dy)
        is InkText -> shape.copy(x = shape.x + dx, y = shape.y + dy)
        is InkImage -> shape.copy(left = shape.left + dx, top = shape.top + dy, right = shape.right + dx, bottom = shape.bottom + dy)
    }

    /**
     * Whether resizing [shape] keeps its proportions: a stretched text, handwriting or
     * picture would look distorted, while rectangles, lines and arrows stretch freely.
     */
    fun keepsProportions(shape: InkShape): Boolean = shape is InkStroke || shape is InkText || shape is InkImage

    /**
     * Returns a copy of [shape] scaled by [fx] across and [fy] down, around (pivotX, pivotY).
     * Strokes also scale their line width, and text its size, by [fx]: pass the same factor
     * for both axes for those. Rectangles, lines and arrows keep their line width.
     */
    fun scale(shape: InkShape, fx: Float, fy: Float, pivotX: Float, pivotY: Float): InkShape {
        fun x(value: Float) = pivotX + (value - pivotX) * fx
        fun y(value: Float) = pivotY + (value - pivotY) * fy
        return when (shape) {
            is InkStroke -> InkStroke(
                FloatArray(shape.points.size) { i ->
                    when (i % 3) {
                        0 -> x(shape.points[i])
                        1 -> y(shape.points[i])
                        else -> shape.points[i]
                    }
                },
                shape.color,
                shape.width * fx,
                shape.highlighter,
            )
            is InkLine -> shape.copy(x1 = x(shape.x1), y1 = y(shape.y1), x2 = x(shape.x2), y2 = y(shape.y2))
            is InkRect -> shape.copy(x1 = x(shape.x1), y1 = y(shape.y1), x2 = x(shape.x2), y2 = y(shape.y2))
            is InkArrow -> shape.copy(x1 = x(shape.x1), y1 = y(shape.y1), x2 = x(shape.x2), y2 = y(shape.y2))
            is InkText -> shape.copy(x = x(shape.x), y = y(shape.y), size = shape.size * fx)
            is InkImage -> shape.copy(left = x(shape.left), top = y(shape.top), right = x(shape.right), bottom = y(shape.bottom))
        }
    }

    /**
     * Resizes [shape], whose box is [from], by dragging [corner] (dx, dy) capture pixels while
     * the opposite corner stays put. Shapes that [keepsProportions] follow the axis the pen
     * moved most along. Neither side shrinks below [minSize], nor below its size if it is
     * already smaller, and the shape never flips.
     */
    fun resize(shape: InkShape, from: InkBounds, corner: InkCorner, dx: Float, dy: Float, minSize: Float): InkShape {
        val lowX = lowestFactor(from.width, minSize)
        val lowY = lowestFactor(from.height, minSize)
        var fx = factor(from.width, if (corner.right) dx else -dx).coerceAtLeast(lowX)
        var fy = factor(from.height, if (corner.bottom) dy else -dy).coerceAtLeast(lowY)
        if (keepsProportions(shape)) {
            val f = (if (abs(fx - 1) >= abs(fy - 1)) fx else fy).coerceAtLeast(max(lowX, lowY))
            fx = f
            fy = f
        }
        val pivotX = if (corner.right) from.left else from.right
        val pivotY = if (corner.bottom) from.top else from.bottom
        return scale(shape, fx, fy, pivotX, pivotY)
    }

    /** Scale that takes a side of [size] to `size + grow`; 1 for a side with no size. */
    private fun factor(size: Float, grow: Float): Float = if (size <= 0f) 1f else (size + grow) / size

    /** Smallest scale for a side of [size]: down to [minSize], or none if it is smaller already. */
    private fun lowestFactor(size: Float, minSize: Float): Float = if (size <= 0f) 1f else min(minSize, size) / size

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
