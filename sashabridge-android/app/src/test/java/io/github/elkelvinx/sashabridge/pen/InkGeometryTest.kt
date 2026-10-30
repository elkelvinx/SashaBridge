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

    /** The corner nearest to a point, by the half of the box it is in. */
    @Test
    fun nearestCorner() {
        val box = InkBounds(0f, 0f, 100f, 50f)

        assertEquals(InkCorner.TOP_LEFT, box.nearestCorner(10f, 10f))
        assertEquals(InkCorner.BOTTOM_RIGHT, box.nearestCorner(90f, 40f))
        assertEquals(InkCorner.TOP_RIGHT, box.nearestCorner(90f, 5f))
    }

    /** Dragging the bottom-right corner stretches a rectangle with its top-left corner fixed. */
    @Test
    fun rectangleStretchesFreely() {
        val rect = InkRect(0f, 0f, 100f, 50f, red, 4f)
        val box = InkBounds(0f, 0f, 100f, 50f)

        val resized = InkGeometry.resize(rect, box, InkCorner.BOTTOM_RIGHT, 100f, 0f, minSize = 10f) as InkRect

        assertEquals(InkRect(0f, 0f, 200f, 50f, red, 4f), resized)
    }

    /** Dragging the top-left corner moves that corner; the bottom-right one stays. */
    @Test
    fun oppositeCornerStaysPut() {
        val rect = InkRect(0f, 0f, 100f, 50f, red, 4f)
        val box = InkBounds(0f, 0f, 100f, 50f)

        val resized = InkGeometry.resize(rect, box, InkCorner.TOP_LEFT, 50f, 25f, minSize = 10f) as InkRect

        assertEquals(InkRect(50f, 25f, 100f, 50f, red, 4f), resized)
    }

    /** A note grows in proportion: its letters get bigger, from the fixed corner. */
    @Test
    fun textGrowsInProportion() {
        val note = InkText(0f, 20f, "hola", red, 20f)
        val box = InkBounds(0f, 0f, 44f, 26f)

        val resized = InkGeometry.resize(note, box, InkCorner.BOTTOM_RIGHT, 44f, 2f, minSize = 10f) as InkText

        assertEquals(40f, resized.size, 0.001f)
        assertEquals(0f, resized.x, 0.001f)
        assertEquals(40f, resized.y, 0.001f)
    }

    /** A stroke scales its points and its line width alike. */
    @Test
    fun strokeScalesItsWidth() {
        val stroke = InkStroke(floatArrayOf(0f, 0f, 0.5f, 10f, 10f, 0.5f), red, 2f, highlighter = false)

        val scaled = InkGeometry.scale(stroke, 2f, 2f, 0f, 0f) as InkStroke

        assertTrue(scaled.points.contentEquals(floatArrayOf(0f, 0f, 0.5f, 20f, 20f, 0.5f)))
        assertEquals(4f, scaled.width, 0.001f)
    }

    /** A picture is grabbed anywhere inside it, moves whole and grows without stretching. */
    @Test
    fun pictureMovesAndGrowsInProportion() {
        val picture = InkImage(0f, 0f, 100f, 50f, pictureId = 1)
        val box = InkGeometry.bounds(picture)

        assertTrue(InkGeometry.hits(picture, 50f, 25f, 0f))
        assertEquals(InkImage(10f, 5f, 110f, 55f, 1), InkGeometry.translate(picture, 10f, 5f))
        val resized = InkGeometry.resize(picture, box, InkCorner.BOTTOM_RIGHT, 100f, 0f, minSize = 10f)
        assertEquals(InkImage(0f, 0f, 200f, 100f, 1), resized)
    }

    /** Shrinking stops at the smallest size, and pulling past the fixed corner never flips. */
    @Test
    fun resizeStopsAtTheSmallestSize() {
        val rect = InkRect(0f, 0f, 100f, 50f, red, 4f)
        val box = InkBounds(0f, 0f, 100f, 50f)

        val resized = InkGeometry.resize(rect, box, InkCorner.BOTTOM_RIGHT, -500f, -500f, minSize = 10f) as InkRect

        assertEquals(InkRect(0f, 0f, 10f, 10f, red, 4f), resized)
    }
}
