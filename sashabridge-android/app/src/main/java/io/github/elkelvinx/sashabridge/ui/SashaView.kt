package io.github.elkelvinx.sashabridge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.github.elkelvinx.sashabridge.R
import kotlin.random.Random

/**
 * Sasha, the dachshund the app is named after, showing the state of the bridge.
 *
 * Everything about her lives here, so a new sprite only needs `tools/sasha_sprite.py` and this
 * class: the poses ([pose]), their animations, the hourglass she waits next to, and the drinks
 * she takes now and then while she plays.
 *
 * She is drawn as pixel art: each frame is turned into a bitmap of its own size once (60 x 20
 * sprite pixels), then drawn enlarged by a whole number of screen pixels per sprite pixel,
 * without smoothing. Every sprite pixel then covers the same screen pixels wherever it is, so
 * the ball keeps its shape as it rolls (scaled vectors gave its edges 7 or 8 screen pixels
 * depending on where it was). The view keeps the sprite's proportions in whatever size the
 * layout gives it.
 *
 * Her frames advance with the screen's [FrameTicker], together with the rest of its
 * animations, and nothing moves while the view is not visible.
 *
 * Main thread only.
 */
class SashaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What Sasha is doing. */
    enum class Pose(@param:DrawableRes internal val drawable: Int) {
        /** Standing still, looking ahead. */
        AWAKE(R.drawable.sasha_awake),

        /** Wagging her tail next to a turning hourglass. */
        WAITING(R.drawable.sasha_wagging),

        /** Galloping after a tennis ball; now and then she stops to drink from her bucket. */
        PLAYING(R.drawable.sasha_running),

        /** Asleep. */
        SLEEPING(R.drawable.sasha_sleeping),
    }

    /** Pose Sasha shows; setting the same pose again does not restart her animation. */
    var pose: Pose = Pose.AWAKE
        set(value) {
            if (value == field) return
            field = value
            drinking = false
            show()
            scheduleDrink()
        }

    /**
     * Clock of the screen's animations. Without one, she keeps her own while she can be seen.
     */
    var ticker: FrameTicker? = null
        set(value) {
            field = value
            value?.add(::step)
            ownTicker?.stop()
            ownTicker = null
        }

    /** Her own clock, used only when no [ticker] is given. */
    private var ownTicker: FrameTicker? = null

    /** Whether she is taking one of her drinks, which she only does while [Pose.PLAYING]. */
    private var drinking = false

    /** The animation shown and its position. */
    private val sprite = Playback()

    /** The hourglass, shown while she waits. */
    private val hourglass = Playback()

    private val pixels = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val target = Rect()

    private val startDrink = Runnable {
        drinking = true
        show()
        postDelayed(endDrink, DRINK_MS)
    }

    private val endDrink = Runnable {
        drinking = false
        show()
        scheduleDrink()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            requestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_LOW
        }
        show()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, fittedWidthSpec(widthMeasureSpec, heightMeasureSpec)),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    /**
     * Width for the sprite at the height given: as wide as its proportions ask, but never wider
     * than the room, so a narrow card shrinks Sasha whole instead of cutting off her ball.
     */
    private fun fittedWidthSpec(widthMeasureSpec: Int, heightMeasureSpec: Int): Int {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
        ) {
            return widthMeasureSpec
        }
        val byHeight = MeasureSpec.getSize(heightMeasureSpec) * SPRITE_WIDTH_PX / SPRITE_HEIGHT_PX
        return MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), byHeight), MeasureSpec.EXACTLY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = sprite.frame() ?: return
        // A whole number of screen pixels per sprite pixel, at whole-pixel offsets.
        val scale = maxOf(1, minOf(width / SPRITE_WIDTH_PX, height / SPRITE_HEIGHT_PX))
        val left = (width - SPRITE_WIDTH_PX * scale) / 2
        val top = (height - SPRITE_HEIGHT_PX * scale) / 2
        target.set(left, top, left + SPRITE_WIDTH_PX * scale, top + SPRITE_HEIGHT_PX * scale)
        canvas.drawBitmap(frame, null, target, pixels)
        if (pose == Pose.WAITING) {
            val glass = hourglass.frame() ?: return
            // Drawn on the sprite's grid, in its bottom-right corner.
            val glassLeft = left + (SPRITE_WIDTH_PX - HOURGLASS_PX) * scale
            val glassTop = top + (SPRITE_HEIGHT_PX - HOURGLASS_PX) * scale
            target.set(glassLeft, glassTop, glassLeft + HOURGLASS_PX * scale, glassTop + HOURGLASS_PX * scale)
            canvas.drawBitmap(glass, null, target, pixels)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (ticker == null) {
                ownTicker = ownTicker ?: FrameTicker().also { it.add(::step) }
                ownTicker?.start()
            }
            scheduleDrink()
        } else {
            ownTicker?.stop()
            removeCallbacks(startDrink)
            removeCallbacks(endDrink)
            if (drinking) {
                drinking = false
                show()
            }
        }
    }

    /** Moves her animations one [FrameTicker] step, redrawing only if a frame changed. */
    private fun step() {
        if (!isShown) return
        var changed = sprite.advance()
        if (pose == Pose.WAITING) changed = hourglass.advance() || changed
        if (changed) invalidate()
    }

    /** Shows the current pose, or the drink, from its first frame. */
    private fun show() {
        sprite.play(framesOf(if (drinking) R.drawable.sasha_drinking else pose.drawable, SPRITE_WIDTH_PX, SPRITE_HEIGHT_PX))
        // Every wait starts with the sand at the top.
        if (pose == Pose.WAITING) hourglass.play(framesOf(R.drawable.hourglass, HOURGLASS_PX, HOURGLASS_PX))
        invalidate()
    }

    /** While she plays and can be seen, plans her next drink in one or two minutes. */
    private fun scheduleDrink() {
        removeCallbacks(startDrink)
        removeCallbacks(endDrink)
        if (pose == Pose.PLAYING && isShown) {
            postDelayed(startDrink, Random.nextLong(DRINK_INTERVAL_MIN_MS, DRINK_INTERVAL_MAX_MS))
        }
    }

    /** The frames of the drawable [res], a single image or an animation-list, as bitmaps of [width] x [height]. */
    private fun framesOf(@DrawableRes res: Int, width: Int, height: Int): Frames = FRAMES.getOrPut(res) {
        val drawable = ContextCompat.getDrawable(context, res) ?: return@getOrPut Frames(emptyList(), emptyList())
        if (drawable is AnimationDrawable) {
            Frames(
                (0 until drawable.numberOfFrames).map { rasterize(drawable.getFrame(it), width, height) },
                (0 until drawable.numberOfFrames).map { drawable.getDuration(it).toLong() },
            )
        } else {
            Frames(listOf(rasterize(drawable, width, height)), listOf(Long.MAX_VALUE))
        }
    }

    /** Draws [drawable] at one bitmap pixel per sprite pixel. */
    private fun rasterize(drawable: Drawable, width: Int, height: Int): Bitmap = createBitmap(width, height).also { bitmap ->
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
    }

    /** The bitmaps of an animation and how long each one shows, in milliseconds. */
    private class Frames(val bitmaps: List<Bitmap>, val durationsMs: List<Long>)

    /** An animation being played: its frames, the one shown, and the time it has shown. */
    private class Playback {
        private var frames: Frames? = null
        private var index = 0
        private var shownMs = 0L

        /** Starts [frames] from the first one. */
        fun play(frames: Frames) {
            this.frames = frames
            index = 0
            shownMs = 0L
        }

        /** The bitmap to show now, or `null` without frames. */
        fun frame(): Bitmap? = frames?.bitmaps?.getOrNull(index)

        /** Adds one [FrameTicker] step of time; returns whether the frame changed. */
        fun advance(): Boolean {
            val current = frames ?: return false
            if (current.bitmaps.size < 2) return false
            shownMs += FrameTicker.STEP_MS
            var changed = false
            // Half a step of slack, so a 66 ms frame lasts exactly one step.
            while (shownMs + FrameTicker.STEP_MS / 2 >= current.durationsMs[index]) {
                shownMs -= current.durationsMs[index]
                index = (index + 1) % current.bitmaps.size
                changed = true
            }
            shownMs = shownMs.coerceAtLeast(0L)
            return changed
        }
    }

    private companion object {
        /** Size of the sprite and of the hourglass, in sprite pixels (see tools/sasha_sprite.py). */
        const val SPRITE_WIDTH_PX = 60
        const val SPRITE_HEIGHT_PX = 20
        const val HOURGLASS_PX = 11

        /** Sasha drinks once every 60 to 120 seconds while she plays, for about 4 seconds. */
        const val DRINK_INTERVAL_MIN_MS = 60_000L
        const val DRINK_INTERVAL_MAX_MS = 120_000L
        const val DRINK_MS = 4_000L

        /** Frames already turned into bitmaps, by drawable; they never change while the app runs. */
        val FRAMES = HashMap<Int, Frames>()
    }
}
