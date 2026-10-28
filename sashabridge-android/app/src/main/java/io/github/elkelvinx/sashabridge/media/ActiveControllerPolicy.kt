package io.github.elkelvinx.sashabridge.media

/**
 * A player that [ActiveControllerPolicy] can choose.
 *
 * @param T Type of the player handle: `MediaController` in the app, plain values in tests.
 * @property player The player handle.
 * @property isActive Whether the player is playing or buffering.
 * @property lastStateChangeMs `SystemClock.elapsedRealtime()` of the last change of its
 *   playback state observed by the tracker, or 0 if none has been observed.
 * @property systemOrder Position in the list returned by `MediaSessionManager.getActiveSessions`.
 */
data class ControllerCandidate<T>(
    val player: T,
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
     * @return The chosen player, or `null` if there are no candidates.
     */
    fun <T> choose(candidates: List<ControllerCandidate<T>>): T? {
        val pool = candidates.filter { it.isActive }.ifEmpty { candidates }
        return pool.minWithOrNull(preferred)?.player
    }
}
