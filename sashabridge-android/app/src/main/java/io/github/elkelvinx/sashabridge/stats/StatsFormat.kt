package io.github.elkelvinx.sashabridge.stats

import java.util.concurrent.TimeUnit

/** Numbers of the statistics as people read them. Pure and thread-safe. */
internal object StatsFormat {

    /**
     * [ms] as whole hours and the minutes left, rounded to the nearest minute: 3 h 59 min 40 s
     * is 4 h 0 min.
     */
    fun hoursAndMinutes(ms: Long): Pair<Long, Long> {
        val minutes = (ms.coerceAtLeast(0) + TimeUnit.SECONDS.toMillis(SECONDS_PER_MINUTE / 2)) / TimeUnit.MINUTES.toMillis(1)
        return minutes / MINUTES_PER_HOUR to minutes % MINUTES_PER_HOUR
    }

    /** The share of [part] in [whole], from 0 to [scale]; 0 when [whole] is 0. */
    fun share(part: Long, whole: Long, scale: Int): Int =
        if (whole <= 0) 0 else (part.coerceIn(0, whole) * scale / whole).toInt()

    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
}
