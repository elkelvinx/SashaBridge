package io.github.elkelvinx.sashabridge.ui

/**
 * Which way the user last skipped, for a short while: the way the card's art should go.
 *
 * Players do not answer every press with its own track. Spotify, pressed several times in a
 * row, publishes only the track it ends on; and "previous" may start the track over or go
 * back, by the player's own rule. A track change shortly after a press is therefore the
 * press's doing, whichever way it went. The queue cannot tell instead: it lists the tracks
 * ahead, not the ones already played.
 *
 * Pure: the caller passes the time. Main thread.
 *
 * @param windowMs How long a press decides the way of the changes that follow it.
 */
internal class SkipIntent(private val windowMs: Long = WINDOW_MS) {

    /** Way of the latest press, 1 forward and -1 backward, or 0 before any. */
    private var direction = 0

    /** When the latest press was. */
    private var pressedAtMs = 0L

    /** Records a press towards [direction] at [nowMs]. */
    fun press(direction: Int, nowMs: Long) {
        this.direction = direction
        pressedAtMs = nowMs
    }

    /** The way of a press within [windowMs] before [nowMs], or `null` if there was none. */
    fun recent(nowMs: Long): Int? = direction.takeIf { it != 0 && nowMs - pressedAtMs <= windowMs }

    /**
     * Whether "previous" at [positionMs] into the track only starts it over, as Spotify and
     * YouTube Music do past [RESTART_MS]. Right after another press the track has just
     * started, whatever position the card still shows, so it goes back.
     */
    fun restarts(positionMs: Long, nowMs: Long): Boolean = positionMs > RESTART_MS && recent(nowMs) == null

    companion object {

        /** How long a press decides the way of the changes that follow it. */
        const val WINDOW_MS = 3_000L

        /** Position after which "previous" starts the track over instead of going back. */
        const val RESTART_MS = 3_000L
    }
}
