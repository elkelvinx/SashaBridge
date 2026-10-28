package io.github.elkelvinx.sashabridge.ui

import java.util.Locale

/** Formatting of playback times for the screen. Pure; any thread. */
object TrackTime {

    /**
     * Formats a time as `mm:ss`, or `h:mm:ss` from one hour on.
     *
     * @param ms Time in milliseconds; negative values count as zero.
     * @return The formatted time, such as `03:07`.
     */
    fun format(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }
}
