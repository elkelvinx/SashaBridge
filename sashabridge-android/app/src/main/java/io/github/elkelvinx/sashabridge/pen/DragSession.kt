package io.github.elkelvinx.sashabridge.pen

import kotlin.math.hypot

/**
 * The pen dragging one mark with the move tool: where it grabbed the mark, whether it already
 * moved it, and the step to undo.
 *
 * - The mark stays put until the pen goes farther than a tap from where it grabbed it, so
 *   selecting a mark never nudges it; from then on it follows the pen exactly.
 * - Lifting the pen leaves the mark where it is. The S Pen can lose contact for a moment on a
 *   light, slow drag: if it touches the same mark again within [RESUME_MS], the drag goes on
 *   as if it never lifted, and all of it is one step to undo.
 *
 * Plain state with no Android dependency, so it is tested on the JVM. Main thread only.
 */
class DragSession {

    /** Index of the held mark, or -1. */
    var index: Int = -1
        private set

    /** The held mark as it was when the drag started, or `null`. */
    var origin: InkShape? = null
        private set

    /** Marks before the drag, the step to undo. */
    private var before: List<InkShape> = emptyList()

    private var grabX = 0f
    private var grabY = 0f
    private var lastDx = 0f
    private var lastDy = 0f

    /** Whether the pen went past the tap distance. */
    private var moving = false

    /** Whether [before] was already handed out as an undo step. */
    private var committed = false

    /** When the pen lifted, or `null` while it touches. */
    private var liftedAt: Long? = null

    /**
     * The pen touched the mark at [index] at capture coordinates (x, y).
     *
     * @param shape The mark now.
     * @param marks All the marks now, the step to undo if this starts a new drag.
     * @param now Milliseconds of a monotonic clock.
     * @return `true` if it resumed the drag the pen lifted from a moment ago.
     */
    fun press(index: Int, shape: InkShape, marks: List<InkShape>, x: Float, y: Float, now: Long): Boolean {
        val lifted = liftedAt
        if (lifted != null && now - lifted < RESUME_MS && index == this.index && origin != null) {
            // Once moving, same offset as when it lifted, so the mark does not jump to the new
            // contact point; before that, the first grab point still counts toward the tap distance.
            if (moving) {
                grabX = x - lastDx
                grabY = y - lastDy
            }
            liftedAt = null
            return true
        }
        this.index = index
        origin = shape
        before = marks
        grabX = x
        grabY = y
        lastDx = 0f
        lastDy = 0f
        moving = false
        committed = false
        liftedAt = null
        return false
    }

    /**
     * The pen moved to capture coordinates (x, y).
     *
     * @param slop Tap distance in capture pixels.
     * @return How far to move the mark from [origin], as (dx, dy), or `null` to leave it.
     */
    fun moveTo(x: Float, y: Float, slop: Float): Pair<Float, Float>? {
        if (origin == null || liftedAt != null) return null
        val dx = x - grabX
        val dy = y - grabY
        if (!moving && hypot(dx, dy) < slop) return null
        moving = true
        lastDx = dx
        lastDy = dy
        return dx to dy
    }

    /**
     * The pen lifted, or the system took the touch away. The mark stays where it is.
     *
     * @param now Milliseconds of a monotonic clock.
     * @return The marks before the drag, to push as the step to undo the first time the drag
     *   moved something; `null` otherwise.
     */
    fun release(now: Long): List<InkShape>? {
        if (origin == null || liftedAt != null) return null
        liftedAt = now
        if (!moving || committed) return null
        committed = true
        return before
    }

    /**
     * Lets go of the mark for good, as when another tool is chosen.
     *
     * @return As [release], for a drag still in progress.
     */
    fun finish(now: Long): List<InkShape>? {
        val step = release(now)
        index = -1
        origin = null
        before = emptyList()
        liftedAt = null
        return step
    }

    companion object {

        /** How soon after lifting the pen a touch on the same mark resumes the drag. */
        const val RESUME_MS = 300L
    }
}
