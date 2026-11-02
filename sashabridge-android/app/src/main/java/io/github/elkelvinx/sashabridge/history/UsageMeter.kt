package io.github.elkelvinx.sashabridge.history

import io.github.elkelvinx.sashabridge.media.MediaSnapshot

/**
 * Measures how long each app plays, music or video, for the statistics: the time between a
 * state that plays and the next state or [tick]. Paused time does not count.
 *
 * Plain state with no Android dependency, so it is tested on the JVM. One thread at a time.
 */
class UsageMeter {

    /** App playing now, or `null`. */
    private var source: String? = null

    /** When the time of [source] not yet reported began. */
    private var since = 0L

    /**
     * The player changed state.
     *
     * @param snapshot The new state, or `null` when nothing plays.
     * @param nowMs Milliseconds of a monotonic clock.
     * @return The time the previous state played, to add up now, or `null`.
     */
    fun update(snapshot: MediaSnapshot?, nowMs: Long): Usage? {
        val played = take(nowMs)
        source = snapshot?.takeIf { it.isPlaying }?.source
        since = nowMs
        return played
    }

    /**
     * Time passed with no new state.
     *
     * @param nowMs Milliseconds of a monotonic clock.
     * @return The time played since the last report, to add up now, or `null`.
     */
    fun tick(nowMs: Long): Usage? {
        val played = take(nowMs)
        since = nowMs
        return played
    }

    /** The time [source] played until [nowMs], if it plays. */
    private fun take(nowMs: Long): Usage? {
        val app = source ?: return null
        val ms = nowMs - since
        return if (ms > 0) Usage(app, ms) else null
    }

    /** [ms] milliseconds of playing in [source]. */
    data class Usage(val source: String, val ms: Long)
}
