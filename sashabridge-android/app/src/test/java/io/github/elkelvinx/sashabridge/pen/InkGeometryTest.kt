package io.github.elkelvinx.sashabridge.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arrow heads and what the eraser touches. */
class InkGeometryTest {

    private val red = 0xFFE53935.toInt()

    /** The head of an arrow pointing right opens back to the left, above and below the tip. */
    @Test
    fun arrowHeadOpensBackwards() {
        val head = InkGeometry.arrowHead(InkArrow(0f, 0f, 100f, 0f, red, 4f))

        assertTrue("both ends behind the tip", head[0] < 100f && head[2] < 100f)
        assertEquals("symmetric", head[1], -head[3], 0.001f)
        assertTrue("one above, one below", head[1] * head[3] < 0f)
    }

    /** The distance to a segment is measured to its nearest point, ends included. */
    @Test
    fun distanceToSegment() {
        assertEquals(5f, InkGeometry.distanceToSegment(50f, 5f, 0f, 0f, 100f, 0f), 0.001f)
        assertEquals(5f, InkGeometry.distanceToSegment(-3f, 4f, 0f, 0f, 100f, 0f), 0.001f)
        assertEquals(5f, InkGeometry.distanceToSegment(3f, 4f, 0f, 0f, 0f, 0f), 0.001f)
    }

    /** A rectangle is touched on its outline, not in its empty middle. */
    @Test
    fun rectangleIsTouchedOnItsOutline() {
        val rect = InkRect(10f, 10f, 110f, 60f, red, 4f)

        assertTrue(InkGeometry.hits(rect, 60f, 11f, 6f))
        assertTrue(InkGeometry.hits(rect, 109f, 35f, 6f))
        assertFalse(InkGeometry.hits(rect, 60f, 35f, 6f))
    }

    /** A stroke is touched along any of its segments, within its width and the tolerance. */
    @Test
    fun strokeIsTouchedAlongItsSegments() {
        val stroke = InkStroke(floatArrayOf(0f, 0f, 1f, 50f, 0f, 1f, 50f, 50f, 1f), red, 6f, highlighter = false)

        assertTrue(InkGeometry.hits(stroke, 25f, 4f, 2f))
        assertTrue(InkGeometry.hits(stroke, 52f, 30f, 2f))
        assertFalse(InkGeometry.hits(stroke, 20f, 30f, 2f))
    }

    /** A text is touched anywhere in the box its lines take. */
    @Test
    fun textIsTouchedInItsBox() {
        val text = InkText(10f, 40f, "Mover\nesto", red, 20f)

        assertTrue(InkGeometry.hits(text, 20f, 30f, 2f))
        assertTrue(InkGeometry.hits(text, 20f, 60f, 2f))
        assertFalse(InkGeometry.hits(text, 200f, 30f, 2f))
    }

    /** Moving a mark shifts every one of its points and keeps its color and width. */
    @Test
    fun translateMovesEveryKindOfMark() {
        val shapes = listOf(
            InkStroke(floatArrayOf(0f, 0f, 0.5f, 10f, 20f, 1f), red, 6f, highlighter = true),
            InkLine(0f, 0f, 10f, 20f, red, 4f),
            InkRect(0f, 0f, 10f, 20f, red, 4f),
            InkArrow(0f, 0f, 10f, 20f, red, 4f),
            InkText(0f, 20f, "nota", red, 20f),
        )

        for (shape in shapes) {
            val before = InkGeometry.bounds(shape)
            val moved = InkGeometry.translate(shape, 30f, -5f)
            val after = InkGeometry.bounds(moved)

            assertEquals("left of ${shape::class.simpleName}", before.left + 30f, after.left, 0.001f)
            assertEquals("top of ${shape::class.simpleName}", before.top - 5f, after.top, 0.001f)
            assertEquals(shape.color, moved.color)
            assertEquals(shape.width, moved.width, 0.001f)
        }
    }

    /** A stroke moves its points but keeps their pressure. */
    @Test
    fun translateKeepsThePressure() {
        val stroke = InkStroke(floatArrayOf(1f, 2f, 0.25f, 3f, 4f, 0.75f), red, 6f, highlighter = false)

        val moved = InkGeometry.translate(stroke, 10f, 100f) as InkStroke

        assertTrue(moved.points.contentEquals(floatArrayOf(11f, 102f, 0.25f, 13f, 104f, 0.75f)))
        assertFalse(moved.highlighter)
    }

    /** A box reaches half a line width past the corners, whichever corner was drawn first. */
    @Test
    fun boundsIncludeTheLineWidth() {
        val box = InkGeometry.bounds(InkRect(110f, 60f, 10f, 10f, red, 4f))

        assertEquals(InkBounds(8f, 8f, 112f, 62f), box)
        assertTrue(box.contains(60f, 35f))
        assertFalse(box.contains(120f, 35f))
        assertTrue(box.contains(120f, 35f, margin = 10f))
    }
}
