package io.github.elkelvinx.sashabridge.media

/**
 * Extrapolates the playback position reported by a player.
 *
 * `PlaybackState.getPosition()` is the position at `getLastPositionUpdateTime()`, not the
 * current one. Players only report it when something changes, so the current position is
 * `position + (now - lastPositionUpdateTime) * playbackSpeed` while playing (R5).
 *
 * Pure and thread-safe.
 */
object PlaybackPositionCalculator {

    /** Value of `PlaybackState.PLAYBACK_POSITION_UNKNOWN`, repeated here so that this runs on the JVM. */
    const val UNKNOWN_POSITION: Long = -1L

    /**
     * Returns the position at [nowMs].
     *
     * @param reportedPositionMs Position reported by the player, or [UNKNOWN_POSITION].
     * @param updateTimeMs `SystemClock.elapsedRealtime()` of the report; 0 or less means unknown.
     * @param speed Playback speed reported by the player.
     * @param isPlaying Whether the player is playing; a paused player does not advance.
     * @param durationMs Track length, or `null` if unknown.
     * @param nowMs Current `SystemClock.elapsedRealtime()`.
     * @return Position in milliseconds, at least 0 and at most [durationMs] when it is known.
     */
    fun positionAt(
        reportedPositionMs: Long,
        updateTimeMs: Long,
        speed: Float,
        isPlaying: Boolean,
        durationMs: Long?,
        nowMs: Long,
    ): Long {
        if (reportedPositionMs < 0) return 0
        val advanced = if (isPlaying && updateTimeMs > 0 && nowMs > updateTimeMs) {
            ((nowMs - updateTimeMs) * speed.toDouble()).toLong()
        } else {
            0L
        }
        val upperBound = durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return (reportedPositionMs + advanced).coerceIn(0L, upperBound)
    }
}
