package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.history.AppKind
import io.github.elkelvinx.sashabridge.history.AppKinds

/**
 * A hiding in progress: the PC's `HIDE` paused a video and the card shows music instead.
 * Its rules are here, pure and tested; [MediaSessionTracker] applies them to the sessions.
 *
 * @property musicSource Package of the only player that may be followed, or `null` for none.
 * @property fallback What to publish while that player has no session, or `null`.
 * @property sinceMs `SystemClock.elapsedRealtime()` of the start of the hiding.
 * @property paused Packages of the players the hiding paused, to resume when going back; the
 *   user's own pauses are not in it.
 * @property video Package of the video to go back to, or `null` if a music app was shown.
 */
internal data class Hiding(
    val musicSource: String?,
    val fallback: MediaSnapshot?,
    val sinceMs: Long,
    val paused: Set<String>,
    val video: String?,
) {

    /**
     * Whether a player that starts playing at [nowMs] ends this hiding: not during the first
     * [GRACE_MS], when the paused video may still report the states it was going through.
     */
    fun endsWhenPlayingAt(nowMs: Long): Boolean = nowMs - sinceMs > GRACE_MS

    companion object {

        /** Time after a hide in which a player that starts playing does not end it. */
        const val GRACE_MS = 1_000L

        /**
         * The hiding that a hide at [nowMs] starts.
         *
         * The video to go back to is the player shown when hiding ([shownSource]), if it is
         * not a music app, whether it played or the user had paused it. Hiding again, while
         * [earlier] is still on, keeps its video and the players it paused, to go back to
         * them.
         *
         * @param pausedNow Packages of the players this hide paused.
         */
        fun start(
            earlier: Hiding?,
            musicSource: String?,
            fallback: MediaSnapshot?,
            nowMs: Long,
            shownSource: String?,
            pausedNow: Collection<String>,
        ): Hiding = Hiding(
            musicSource = musicSource,
            fallback = fallback,
            sinceMs = nowMs,
            paused = earlier?.paused.orEmpty() + pausedNow,
            video = earlier?.video ?: shownSource?.takeIf { AppKinds.of(it) != AppKind.MUSIC },
        )
    }
}
