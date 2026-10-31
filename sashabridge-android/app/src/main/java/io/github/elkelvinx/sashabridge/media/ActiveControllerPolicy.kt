package io.github.elkelvinx.sashabridge.media

/**
 * A player that [ActiveControllerPolicy] can choose.
 *
 * @param T Type of the player handle: `MediaController` in the app, plain values in tests.
 * @property player The player handle.
 * @property source Android package of the player, such as `com.spotify.music`.
 * @property isActive Whether the player is playing or buffering.
 * @property lastStateChangeMs `SystemClock.elapsedRealtime()` of the last change of its
 *   playback state observed by the tracker, or 0 if none has been observed.
 * @property systemOrder Position in the list returned by `MediaSessionManager.getActiveSessions`.
 */
data class ControllerCandidate<T>(
    val player: T,
    val source: String,
    val isActive: Boolean,
    val lastStateChangeMs: Long,
    val systemOrder: Int,
)

/**
 * Chooses the player whose state the bridge publishes and to which it sends commands.
 *
 * Priority:
 * 1. Players that are playing or buffering.
 * 2. Among those (or among all, if none is playing), the most recent playback state change.
 *    After pausing Spotify, the bridge keeps following Spotify instead of jumping to another
 *    paused player.
 * 3. On a tie, the order given by the system.
 *
 * While a video is hidden (the PC's `HIDE`), the music player is pinned: only it can be
 * chosen, even if another one plays.
 *
 * This replaces reading `getActiveSessions()[0]`, which could pick a paused player while
 * another one was playing (R4).
 *
 * Pure and thread-safe.
 */
object ActiveControllerPolicy {

    /** Most recent change first; on a tie, lowest system order first. */
    private val preferred: Comparator<ControllerCandidate<*>> =
        compareByDescending<ControllerCandidate<*>> { it.lastStateChangeMs }.thenBy { it.systemOrder }

    /**
     * Applies the policy.
     *
     * @param candidates Players with an active media session.
     * @param pinnedSource Package of the only player that may be chosen, or `null` for any.
     * @return The chosen player, or `null` if there are no candidates (or none of [pinnedSource]).
     */
    fun <T> choose(candidates: List<ControllerCandidate<T>>, pinnedSource: String? = null): T? {
        val allowed = if (pinnedSource == null) candidates else candidates.filter { it.source == pinnedSource }
        val pool = allowed.filter { it.isActive }.ifEmpty { allowed }
        return pool.minWithOrNull(preferred)?.player
    }
}
