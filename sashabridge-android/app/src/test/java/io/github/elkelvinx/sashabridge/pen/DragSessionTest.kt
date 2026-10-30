package io.github.elkelvinx.sashabridge.pen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dragging a mark: tap distance at the start, no snapping back, and a lifted pen resuming. */
class DragSessionTest {

    private val box = InkRect(0f, 0f, 100f, 50f, 0xFFE53935.toInt(), 4f)
    private val marks = listOf<InkShape>(box)
    private val slop = 10f

    /** Within the tap distance the mark stays; past it, it follows the pen exactly. */
    @Test
    fun followsThePenOnlyPastTheTapDistance() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)

        assertNull(drag.moveTo(55f, 25f, slop))
        assertEquals(12f to 0f, drag.moveTo(62f, 25f, slop))
        assertEquals("no dead zone once moving", 3f to 0f, drag.moveTo(53f, 25f, slop))
    }

    /** A short drag that went past the tap distance is kept, not put back, and is one step. */
    @Test
    fun shortDragIsKept() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)
        drag.moveTo(62f, 25f, slop)

        assertSame(marks, drag.release(now = 100))
        assertNull("only one step", drag.release(now = 120))
    }

    /** Touching a mark without moving it leaves nothing to undo. */
    @Test
    fun selectingLeavesNoStep() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)
        drag.moveTo(52f, 26f, slop)

        assertNull(drag.release(now = 100))
    }

    /** A pen that loses contact for a moment resumes the same drag without jumping. */
    @Test
    fun liftedPenResumesTheDrag() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)
        drag.moveTo(70f, 25f, slop)
        val step = drag.release(now = 100)

        val moved = listOf<InkShape>(InkGeometry.translate(box, 20f, 0f))
        assertTrue(drag.press(0, moved[0], moved, 73f, 26f, now = 250))
        assertEquals("same offset as before lifting", 20f to 0f, drag.moveTo(73f, 26f, slop))
        assertEquals(24f to 0f, drag.moveTo(77f, 26f, slop))
        assertNull("still one step", drag.release(now = 400))
        assertSame(marks, step)
    }

    /** Short contacts in a row, each under the tap distance, add up to a drag. */
    @Test
    fun flickeringContactsAddUp() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)
        assertNull(drag.moveTo(56f, 25f, slop))
        assertNull(drag.release(now = 10))

        assertTrue(drag.press(0, box, marks, 57f, 25f, now = 70))
        assertEquals(12f to 0f, drag.moveTo(62f, 25f, slop))
        assertSame(marks, drag.release(now = 200))
    }

    /** Too late, or on another mark, the touch starts a new drag from where the mark is. */
    @Test
    fun lateTouchStartsANewDrag() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)
        drag.moveTo(70f, 25f, slop)
        drag.release(now = 100)

        assertFalse(drag.press(0, box, marks, 50f, 25f, now = 100 + DragSession.RESUME_MS))
        assertNull(drag.moveTo(52f, 25f, slop))
    }

    /** Letting go for good while the pen still drags returns the step to undo. */
    @Test
    fun finishCommitsAMovingDrag() {
        val drag = DragSession()
        drag.press(0, box, marks, 50f, 25f, now = 0)
        drag.moveTo(70f, 25f, slop)

        assertSame(marks, drag.finish(now = 50))
        assertEquals(-1, drag.index)
        assertNull(drag.origin)
    }
}
