package io.github.elkelvinx.sashabridge.ui

import android.view.Choreographer

/**
 * One clock for the animations of a screen: Sasha's frames, the wave of the seek bar and the
 * elapsed time all advance on the same display frame, every [STEP_MS].
 *
 * Each of them on its own timer made the screen redraw at its own moments, about 35 times a
 * second with music playing, and frames that missed the display's deadline made Sasha's ball
 * move unevenly. Together they redraw once per step, about 15 times a second, always on a
 * display frame (through [Choreographer]).
 *
 * Between steps the thread sleeps: it wakes a display frame or two before the step instead of
 * on every frame. Main thread only.
 */
class FrameTicker {

    /** Called on each step, in the order they were added. */
    private val listeners = mutableListOf<() -> Unit>()

    private val choreographer = Choreographer.getInstance()
    private var running = false

    /** Display frame time of the last step, in nanoseconds; 0 before the first. */
    private var lastStepNanos = 0L

    private val onFrame = Choreographer.FrameCallback { frameNanos -> frame(frameNanos) }

    /** Calls [listener] on every step while the ticker runs. */
    fun add(listener: () -> Unit) {
        listeners += listener
    }

    /** Starts the steps; harmless if they already run. */
    fun start() {
        if (running) return
        running = true
        lastStepNanos = 0L
        choreographer.postFrameCallback(onFrame)
    }

    /** Stops the steps, for example while the screen is not visible. */
    fun stop() {
        running = false
        choreographer.removeFrameCallback(onFrame)
    }

    /** Steps when the time has come, then waits for the next one. */
    private fun frame(frameNanos: Long) {
        if (!running) return
        if (lastStepNanos == 0L || frameNanos - lastStepNanos >= STEP_NANOS - TOLERANCE_NANOS) {
            // Counted from the display frame itself, so every step spans the same number of
            // display frames: 4 at 60 Hz.
            lastStepNanos = frameNanos
            listeners.forEach { it() }
            choreographer.postFrameCallbackDelayed(onFrame, STEP_MS - WAKE_EARLY_MS)
        } else {
            choreographer.postFrameCallback(onFrame)
        }
    }

    companion object {

        /**
         * Time between steps: 4 display frames at 60 Hz, 2 at 30 Hz, 8 at 120 Hz, so every
         * step lasts the same on screen.
         */
        const val STEP_MS = 66L

        private const val STEP_NANOS = STEP_MS * 1_000_000L

        /** A step is due this close to its time: half a frame at 60 Hz. */
        private const val TOLERANCE_NANOS = 8_000_000L

        /** How long before a step the thread wakes to catch its display frame. */
        private const val WAKE_EARLY_MS = 20L
    }
}
