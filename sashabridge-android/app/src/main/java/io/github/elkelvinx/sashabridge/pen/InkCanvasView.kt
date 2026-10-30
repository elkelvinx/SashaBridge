package io.github.elkelvinx.sashabridge.pen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the S Pen draws on the canvas. */
enum class InkTool {
    /** Free-hand line that follows the pressure. */
    PEN,

    /** Wide translucent free-hand line. */
    HIGHLIGHTER,

    /** Straight line, to strike text out. */
    LINE,

    /** Rectangle outline. */
    RECTANGLE,

    /** Arrow pointing where the pen lifts. */
    ARROW,

    /** A note, typed or handwritten with the keyboard, placed where the pen taps. */
    TEXT,

    /** Picks up a mark and drags it somewhere else. */
    MOVE,

    /** Picks up a mark and drags a corner of its box to make it bigger or smaller. */
    RESIZE,
    ;

    /** Whether the tool holds a mark, rather than drawing one. */
    val holdsMarks: Boolean get() = this == MOVE || this == RESIZE
}

/**
 * Canvas where a capture is annotated with the S Pen.
 *
 * - Only the pen draws, so a palm resting on the screen does nothing. Its eraser end, or the
 *   side button held down, erases whole marks.
 * - With [InkTool.MOVE], the pen picks up the mark it touches (or whose box it touches) and
 *   drags it; a dashed box shows which one it holds. [DragSession] keeps the drag whole when
 *   the pen loses contact for a moment.
 * - With [InkTool.RESIZE], the pen holds a mark the same way and drags the corner of its box
 *   nearest to where it touched; the opposite corner stays put ([InkGeometry.resize]).
 * - Fingers move and zoom the capture; a double tap fits it to the screen again.
 * - With gesture navigation, a stroke that starts at a side edge draws instead of going back
 *   ([EdgeGuard]).
 * - Marks are kept in capture pixels ([InkShape]), so [export] draws them on the capture at its
 *   own resolution.
 *
 * Main thread only.
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * Tool the pen draws with. Switching between moving and resizing keeps the held mark;
     * any other tool lets go of it.
     */
    var tool: InkTool = InkTool.PEN
        set(value) {
            field = value
            if (value.holdsMarks) endDrag() else deselect()
        }

    /** Color of the next marks, as ARGB. */
    var color: Int = DEFAULT_COLOR

    /** Thickness of the next marks: 0 thin, 1 medium, 2 thick. */
    var thickness: Int = 1

    /**
     * Called when the text tool taps the capture, with the capture coordinates of the tap and,
     * if the tap is on a note, that note, to edit it. The screen asks for the text and its size
     * and calls [placeText].
     */
    var onTextRequest: ((Float, Float, InkText?) -> Unit)? = null

    /** Text size step a new note starts with: it follows the thickness (thin is small). */
    val defaultTextStep: Int get() = thickness.coerceIn(TEXT_SIZES.indices)

    /** Called after every change of the marks, to update the undo and redo buttons. */
    var onChange: (() -> Unit)? = null

    private var image: Bitmap? = null
    private val shapes = mutableListOf<InkShape>()
    private val undoStack = ArrayDeque<List<InkShape>>()
    private val redoStack = ArrayDeque<List<InkShape>>()

    /** Mark being drawn, shown but not yet committed. */
    private var current: InkShape? = null

    /** Points of the stroke being drawn: x, y and pressure. */
    private val strokePoints = ArrayList<Float>()

    /** Start of the shape being drawn, in capture pixels. */
    private var startX = 0f
    private var startY = 0f

    /** Whether the current pen gesture erases. */
    private var erasing = false

    /** Index in [shapes] of the mark the move tool holds, or -1. */
    private var selected = -1

    /** The drag of the held mark. */
    private val drag = DragSession()

    /** Corner of the held mark's box that the resize tool drags. */
    private var resizeCorner = InkCorner.BOTTOM_RIGHT

    /** Capture pixels to screen pixels, and back. */
    private val toScreen = Matrix()
    private val toCapture = Matrix()
    private var fitScale = 1f
    private val point = FloatArray(2)
    private val renderer = InkRenderer()

    /** Keeps the back gesture away from the edges where the pen is. */
    private val edges = EdgeGuard(this)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = currentScale()
            val factor = (detector.scaleFactor).coerceIn(fitScale / scale, fitScale * MAX_ZOOM / scale)
            toScreen.postScale(factor, factor, detector.focusX, detector.focusY)
            updateInverse()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            toScreen.postTranslate(-distanceX, -distanceY)
            updateInverse()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            fit()
            return true
        }
    })

    /** Whether there is something to undo. */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** Whether there is something to redo. */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Shows [capture] with no marks, fitted to the view.
     *
     * @param capture The image to annotate, or `null` to empty the canvas.
     */
    fun setCapture(capture: Bitmap?) {
        image = capture
        if (capture == null) edges.release()
        deselect()
        shapes.clear()
        undoStack.clear()
        redoStack.clear()
        current = null
        fit()
        onChange?.invoke()
    }

    /**
     * Text size of [step], from 0 (small) to 3 (huge), in capture pixels: relative to the
     * capture's size, so a note reads the same on a small or a large capture.
     */
    fun textSize(step: Int): Float = mediumLineWidth() / TEXT_STROKE_RATIO * TEXT_SIZES[step.coerceIn(TEXT_SIZES.indices)]

    /** The size step closest to [size], to show a note's size when editing it. */
    fun textStep(size: Float): Int = TEXT_SIZES.indices.minBy { abs(textSize(it) - size) }

    /**
     * Places a note at capture coordinates (x, y) in the current color, or edits [replacing]
     * in place, keeping its position and color. Either way it is one step to undo.
     *
     * @param text The note; blank text places nothing, or deletes [replacing].
     * @param size Text size in capture pixels, from [textSize].
     * @param replacing The note being edited, or `null` for a new one.
     */
    fun placeText(x: Float, y: Float, text: String, size: Float, replacing: InkText?) {
        val note = text.trimEnd().takeIf { it.isNotBlank() }?.let {
            InkText(replacing?.x ?: x, replacing?.y ?: y, it, replacing?.color ?: color, size)
        }
        val index = shapes.indexOfFirst { it === replacing }
        if (index < 0) {
            note?.let(::commit)
            return
        }
        deselect()
        undoStack.addLast(shapes.toList())
        redoStack.clear()
        if (note == null) shapes.removeAt(index) else shapes[index] = note
        changed()
    }

    /** Takes back the last change. */
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        deselect()
        redoStack.addLast(shapes.toList())
        shapes.clear()
        shapes.addAll(previous)
        changed()
    }

    /** Applies again the last change taken back. */
    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        deselect()
        undoStack.addLast(shapes.toList())
        shapes.clear()
        shapes.addAll(next)
        changed()
    }

    /**
     * Draws the marks on a copy of the capture, at its own resolution.
     *
     * @return The annotated capture, or `null` if there is no capture.
     */
    fun export(): Bitmap? {
        val source = image ?: return null
        val out = createBitmap(source.width, source.height)
        val canvas = Canvas(out)
        canvas.drawBitmap(source, 0f, 0f, null)
        InkRenderer().draw(canvas, shapes)
        return out
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestImmediatePen()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = image ?: return
        canvas.withMatrix(toScreen) {
            drawBitmap(source, 0f, 0f, null)
            renderer.draw(this, shapes)
            current?.let { renderer.draw(this, it) }
            shapes.getOrNull(selected)?.let { held ->
                renderer.drawSelection(
                    this,
                    InkGeometry.bounds(held),
                    resources.displayMetrics.density / currentScale(),
                    handles = tool == InkTool.RESIZE,
                )
            }
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (image != null && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT && isPen(event)) edges.follow(event.y)
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        val tool = event.getToolType(event.actionIndex)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            edges.follow(event.y)
            handlePen(event, tool == MotionEvent.TOOL_TYPE_ERASER)
        } else {
            scaler.onTouchEvent(event)
            if (!scaler.isInProgress) gestures.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        invalidate()
        return true
    }

    /** Touches are drawing, not clicks; this keeps accessibility services informed. */
    override fun performClick(): Boolean = super.performClick()

    /** Draws or erases with the pen. */
    private fun handlePen(event: MotionEvent, eraserEnd: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestImmediatePen(event)
                erasing = eraserEnd || event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
                val (x, y) = capturePoint(event.x, event.y)
                when {
                    erasing -> {
                        undoStack.addLast(shapes.toList())
                        redoStack.clear()
                        erase(x, y)
                    }
                    tool.holdsMarks -> pickUp(x, y, event.eventTime)
                    else -> begin(x, y, event.pressure)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    move(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalPressure(h))
                }
                move(event.x, event.y, event.pressure)
            }
            MotionEvent.ACTION_UP -> {
                // The lift point of the move tool adds nothing and can land off the last one.
                if (!tool.holdsMarks || erasing) move(event.x, event.y, event.pressure)
                when {
                    erasing -> {
                        // An eraser pass that touched nothing leaves no step to undo.
                        if (undoStack.lastOrNull() == shapes) undoStack.removeLastOrNull()
                        erasing = false
                        changed()
                    }
                    tool.holdsMarks -> drop(event.eventTime)
                    else -> end()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                erasing = false
                // The mark stays where the pen left it.
                drop(event.eventTime)
            }
        }
    }

    /** Whether the S Pen, either end, produced [event]. */
    private fun isPen(event: MotionEvent): Boolean {
        val tool = event.getToolType(event.actionIndex)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    /** Starts a mark at capture coordinates. */
    private fun begin(x: Float, y: Float, pressure: Float) {
        startX = x
        startY = y
        strokePoints.clear()
        current = when (tool) {
            InkTool.PEN, InkTool.HIGHLIGHTER -> {
                strokePoints.addAll(listOf(x, y, pressure))
                stroke()
            }
            InkTool.LINE -> InkLine(x, y, x, y, color, lineWidth())
            InkTool.RECTANGLE -> InkRect(x, y, x, y, color, lineWidth())
            InkTool.ARROW -> InkArrow(x, y, x, y, color, lineWidth())
            InkTool.TEXT, InkTool.MOVE, InkTool.RESIZE -> null
        }
    }

    /** Extends the current mark, or erases, at screen coordinates. */
    private fun move(screenX: Float, screenY: Float, pressure: Float) {
        val (x, y) = capturePoint(screenX, screenY)
        if (erasing) {
            erase(x, y)
            return
        }
        if (tool.holdsMarks) {
            drag(x, y)
            return
        }
        current = when (val shape = current) {
            is InkStroke -> {
                strokePoints.addAll(listOf(x, y, pressure))
                stroke()
            }
            is InkLine -> shape.copy(x2 = x, y2 = y)
            is InkRect -> shape.copy(x2 = x, y2 = y)
            is InkArrow -> shape.copy(x2 = x, y2 = y)
            else -> shape
        }
        if (tool == InkTool.TEXT) {
            startX = startX.takeIf { hypot(x - startX, y - startY) < tapSlop() } ?: Float.NaN
        }
    }

    /** Commits the current mark, or asks for the text of a tap. */
    private fun end() {
        if (tool == InkTool.TEXT) {
            if (startX.isNaN()) return
            val tolerance = ERASER_RADIUS_PX / currentScale()
            val note = shapes.lastOrNull { it is InkText && InkGeometry.hits(it, startX, startY, tolerance) } as? InkText
            onTextRequest?.invoke(startX, startY, note)
            return
        }
        val shape = current ?: return
        current = null
        val tiny = when (shape) {
            is InkLine -> hypot(shape.x2 - shape.x1, shape.y2 - shape.y1) < tapSlop()
            is InkRect -> hypot(shape.x2 - shape.x1, shape.y2 - shape.y1) < tapSlop()
            is InkArrow -> hypot(shape.x2 - shape.x1, shape.y2 - shape.y1) < tapSlop()
            else -> false
        }
        if (!tiny) commit(shape)
    }

    /** Adds a finished mark as an undoable step. */
    private fun commit(shape: InkShape) {
        undoStack.addLast(shapes.toList())
        redoStack.clear()
        shapes.add(shape)
        changed()
    }

    /** Removes the topmost mark under (x, y). */
    private fun erase(x: Float, y: Float) {
        val tolerance = ERASER_RADIUS_PX / currentScale()
        val index = shapes.indexOfLast { InkGeometry.hits(it, x, y, tolerance) }
        if (index >= 0) {
            // The indices after it shift; the move tool lets go rather than hold the wrong mark.
            deselect()
            shapes.removeAt(index)
        }
    }

    /**
     * Holds the mark at capture coordinates (x, y) for dragging: the held one if the pen is in
     * its box, else the topmost mark the pen touches, else the topmost whose box it is in (so a
     * rectangle can be grabbed by its middle). Touching nothing lets go.
     *
     * @param now Event time, to resume a drag the pen lifted from a moment ago.
     */
    private fun pickUp(x: Float, y: Float, now: Long) {
        val tolerance = ERASER_RADIUS_PX / currentScale()
        val held = shapes.getOrNull(selected)
        selected = when {
            held != null && InkGeometry.bounds(held).contains(x, y, tolerance) -> selected
            else -> shapes.indexOfLast { InkGeometry.hits(it, x, y, tolerance) }
                .takeIf { it >= 0 }
                ?: shapes.indexOfLast { InkGeometry.bounds(it).contains(x, y, tolerance) }
        }
        val shape = shapes.getOrNull(selected) ?: return
        if (!drag.press(selected, shape, shapes.toList(), x, y, now)) {
            resizeCorner = InkGeometry.bounds(shape).nearestCorner(x, y)
        }
    }

    /**
     * Moves the held mark so the point the pen grabbed follows the pen to (x, y), or, when
     * resizing, so the corner it grabbed does.
     */
    private fun drag(x: Float, y: Float) {
        val (dx, dy) = drag.moveTo(x, y, tapSlop()) ?: return
        val origin = drag.origin ?: return
        if (drag.index != selected) return
        shapes[selected] = if (tool == InkTool.RESIZE) {
            InkGeometry.resize(origin, InkGeometry.bounds(origin), resizeCorner, dx, dy, MIN_MARK_PX / currentScale())
        } else {
            InkGeometry.translate(origin, dx, dy)
        }
    }

    /**
     * The pen lifted, or the system took the touch away: the mark stays where it is, and the
     * first time a drag moved it, that is one step to undo.
     */
    private fun drop(now: Long) {
        drag.release(now)?.let(::pushStep)
    }

    /** Lets go of the mark the move or resize tool holds. */
    private fun deselect() {
        endDrag()
        selected = -1
        invalidate()
    }

    /** Ends the drag for good, keeping the mark held: a new touch starts a new drag. */
    private fun endDrag() {
        drag.finish(SystemClock.uptimeMillis())?.let(::pushStep)
        invalidate()
    }

    /** Records [before], the marks before a drag, as the step to undo it. */
    private fun pushStep(before: List<InkShape>) {
        undoStack.addLast(before)
        redoStack.clear()
        changed()
    }

    /** The stroke being drawn, from [strokePoints]. */
    private fun stroke(): InkStroke {
        val highlighter = tool == InkTool.HIGHLIGHTER
        val width = if (highlighter) lineWidth() * HIGHLIGHTER_WIDTHS else lineWidth()
        return InkStroke(strokePoints.toFloatArray(), color, width, highlighter)
    }

    /** Line width of the current thickness, in capture pixels, relative to the capture size. */
    private fun lineWidth(): Float = mediumLineWidth() * THICKNESSES[thickness.coerceIn(THICKNESSES.indices)]

    /** The medium line width for this capture, in capture pixels. */
    private fun mediumLineWidth(): Float {
        val source = image ?: return 4f
        return max(source.width, source.height) / WIDTH_DIVISOR
    }

    /** Movement under which a gesture counts as a tap, in capture pixels. */
    private fun tapSlop(): Float = TAP_SLOP_PX / currentScale()

    /** Converts screen coordinates to capture coordinates. */
    private fun capturePoint(x: Float, y: Float): Pair<Float, Float> {
        point[0] = x
        point[1] = y
        toCapture.mapPoints(point)
        return point[0] to point[1]
    }

    /** Fits the capture to the view, centered. */
    private fun fit() {
        val source = image ?: return
        if (width == 0 || height == 0) return
        fitScale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        toScreen.setScale(fitScale, fitScale)
        toScreen.postTranslate((width - source.width * fitScale) / 2, (height - source.height * fitScale) / 2)
        updateInverse()
    }

    /** Scale of capture pixels on screen. */
    private fun currentScale(): Float {
        val values = FloatArray(9)
        toScreen.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun updateInverse() {
        toScreen.invert(toCapture)
        invalidate()
    }

    private fun changed() {
        invalidate()
        onChange?.invoke()
    }

    private companion object {
        /** Red, the first color of the palette. */
        const val DEFAULT_COLOR = 0xFFE53935.toInt()

        /** The longer side of the capture divided by this gives the medium line width. */
        const val WIDTH_DIVISOR = 260f

        /** Multipliers of the medium width for each thickness. */
        val THICKNESSES = floatArrayOf(0.55f, 1f, 1.9f)

        /** Text sizes (small, medium, large, huge), relative to the medium one. */
        val TEXT_SIZES = floatArrayOf(0.6f, 1f, 1.7f, 2.6f)

        /** Highlighter width, in line widths. */
        const val HIGHLIGHTER_WIDTHS = 4f

        /** Largest zoom, relative to the fitted size. */
        const val MAX_ZOOM = 8f

        /** Reach of the eraser and size of a tap, in screen pixels. */
        const val ERASER_RADIUS_PX = 24f
        const val TAP_SLOP_PX = 18f

        /** Smallest side a resized mark can shrink to, in screen pixels. */
        const val MIN_MARK_PX = 36f
    }
}
