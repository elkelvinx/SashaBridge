package io.github.elkelvinx.sashabridge.media

/**
 * Play queue that the active player publishes in its media session, such as Spotify's
 * "Cola de reproducción". Each player only publishes its own queue.
 *
 * Immutable; can be shared between threads.
 *
 * @property source Android package of the player.
 * @property title Name the player gives the queue, or `null`.
 * @property entries Items in playing order.
 * @property activeQueueId [QueueEntry.queueId] of the item playing now, or `null` if unknown.
 */
data class PlayerQueue(
    val source: String,
    val title: String?,
    val entries: List<QueueEntry>,
    val activeQueueId: Long?,
) {

    /**
     * Whether going from the track titled [from] to the one titled [to] moved forward or back
     * in the queue, so a change made outside the app ("previous" on the PC or in the player)
     * animates the right way.
     *
     * @return 1 forward, -1 back, or `null` if either track is not in the queue.
     */
    fun directionBetween(from: String?, to: String?): Int? {
        if (from == null || to == null || from == to) return null
        val fromIndex = entries.indexOfFirst { it.title == from }.takeIf { it >= 0 } ?: return null
        val toIndex = entries.indexOfFirst { it.title == to }.takeIf { it >= 0 } ?: return null
        return if (toIndex > fromIndex) 1 else -1
    }
}

/**
 * One item of a [PlayerQueue].
 *
 * @property queueId Identifier the player gave the item, used to jump to it.
 * @property title Track title, or `null`.
 * @property subtitle Usually the artist, or `null`.
 */
data class QueueEntry(
    val queueId: Long,
    val title: String?,
    val subtitle: String?,
)
