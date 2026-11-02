package io.github.elkelvinx.sashabridge.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.elkelvinx.sashabridge.R
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The main screen as a display left on next to the PC: what changes when it is one, in a
 * single place.
 *
 * Whether it is one comes from the resources (`R.bool.display_mode`): true in landscape, as
 * `values-land` says, the same way Android picks the landscape layouts. The code never asks
 * for the orientation itself. As a display, the screen:
 * - hides the status bar (the status card shows the battery and the time instead; a swipe
 *   from the top shows the bar for a moment);
 * - stays on while the phone charges, if the settings allow it ([keepsScreenOn]);
 * - moves its content a few pixels every minute, so no part of the OLED panel shows the same
 *   thing for hours ([shiftNowAndThen]).
 *
 * In both orientations it asks for 60 Hz ([preferRefreshRate]), the rate Sasha's frames are
 * made for.
 *
 * Main thread.
 *
 * @param activity The main screen.
 */
internal class DisplayMode(private val activity: Activity) {

    /** Whether the screen is a display now. */
    val active: Boolean = activity.resources.getBoolean(R.bool.display_mode)

    private val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

    /** Shows or hides the system bars as the screen needs; call once its content is set. */
    fun applyBars() {
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.show(WindowInsetsCompat.Type.navigationBars())
        if (active) insets.hide(WindowInsetsCompat.Type.statusBars()) else insets.show(WindowInsetsCompat.Type.statusBars())
    }

    /**
     * Keeps the screen on, or lets it turn off as usual; see [keepsScreenOn].
     *
     * @param charging Whether the phone charges.
     * @param allowed Whether the settings allow keeping it on.
     * @param covered Whether a black cover hides the screen.
     */
    fun updateKeepScreenOn(charging: Boolean, allowed: Boolean, covered: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (keepsScreenOn(active, charging, allowed, covered)) activity.window.addFlags(flag) else activity.window.clearFlags(flag)
    }

    /**
     * Every [SHIFT_INTERVAL_MS] moves [content] to the next of [SHIFTS], within [SHIFT_DP] of
     * its place, and puts it back when cancelled. Does nothing unless the screen is a display.
     * Runs while the screen is visible.
     */
    suspend fun shiftNowAndThen(content: View?) {
        if (!active || content == null) return
        val step = SHIFT_DP * activity.resources.displayMetrics.density
        var position = 0
        try {
            while (true) {
                delay(SHIFT_INTERVAL_MS)
                position = (position + 1) % SHIFTS.size
                content.translationX = SHIFTS[position].first * step
                content.translationY = SHIFTS[position].second * step
            }
        } finally {
            content.translationX = 0f
            content.translationY = 0f
        }
    }

    /**
     * Asks for the display mode of [REFRESH_HZ] at the current resolution, if the screen has
     * one. Android may still choose otherwise, for example in power saving.
     */
    fun preferRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { abs(it.refreshRate - REFRESH_HZ) } ?: return
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }

    companion object {

        /**
         * Whether the screen stays on: only as a display, while the phone charges, if the
         * settings allow it, and never under the black cover of a hidden video, which should
         * let the screen turn off as usual. Pure.
         */
        fun keepsScreenOn(display: Boolean, charging: Boolean, allowed: Boolean, covered: Boolean): Boolean =
            display && charging && allowed && !covered

        /**
         * Refresh rate the screen asks for: the 66 ms steps of its animations are exactly 4
         * frames, and a frame has 16.6 ms to be drawn instead of the 8.3 ms of 120 Hz.
         */
        const val REFRESH_HZ = 60f

        /** The content moves every minute, by up to 3 dp, through these offsets. */
        const val SHIFT_INTERVAL_MS = 60_000L
        const val SHIFT_DP = 3f
        val SHIFTS = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1)
    }
}
