package io.github.elkelvinx.sashabridge.history

import io.github.elkelvinx.sashabridge.media.MediaSnapshot

/**
 * Decides when a song counts as listened to, so it goes to the history once per play: after
 * [LISTEN_MS] of actual playing (pauses do not count), or half the song if it is shorter.
 * Skipped songs never reach the history, and neither do videos nor anything without a title.
 *
 * A song that starts over (repeat one, or back to its start) is a new play: the player keeps
 * the same track, but its position jumps back to the beginning.
 *
 * Fed with every state of the player ([update]) and, while one plays, with the time now and
 * then ([tick]): players send nothing while a song simply plays on.
 *
 * Plain state with no Android dependency, so it is tested on the JVM. One thread at a time.
 *
 * @param isMusic Whether a player plays music; see [AppKinds].
 */
class HistoryRecorder(private val isMusic: (String) -> Boolean = { AppKinds.of(it) == AppKind.MUSIC }) {

    /** Song being timed, or `null`. */
    private var current: MediaSnapshot? = null

    /** Playing time of [current] before [playingSince]. */
    private var playedMs = 0L

    /** When [current] started playing this stretch, or `null` while it is paused. */
    private var playingSince: Long? = null

    /** Whether [current] already went to the history. */
    private var recorded = false

    /**
     * The player changed state.
     *
     * @param snapshot The new state, or `null` when nothing plays.
     * @param nowMs Milliseconds of a monotonic clock, the one of the snapshots' positions.
     * @return The song to add to the history now, or `null`.
     */
    fun update(snapshot: MediaSnapshot?, nowMs: Long): MediaSnapshot? {
        settle(nowMs)
        if (snapshot == null || snapshot.title.isNullOrBlank() || !isMusic(snapshot.source)) {
            current = null
            playingSince = null
            return null
        }
        val previous = current
        if (snapshot.trackId != previous?.trackId || startedOver(previous, snapshot, nowMs)) {
            playedMs = 0L
            recorded = false
        }
        current = snapshot
        playingSince = if (snapshot.isPlaying) nowMs else null
        return due()
    }

    /**
     * Time passed with no new state.
     *
     * @param nowMs Milliseconds of a monotonic clock.
     * @return The song to add to the history now, or `null`.
     */
    fun tick(nowMs: Long): MediaSnapshot? {
        settle(nowMs)
        return due()
    }

    /**
     * Whether the same song went back to its start after playing a good part: it was past
     * [STARTED_OVER_AFTER_MS] (or half a shorter song) and is now within [START_MS] of the start.
     */
    private fun startedOver(previous: MediaSnapshot, snapshot: MediaSnapshot, nowMs: Long): Boolean {
        val before = previous.positionAt(nowMs)
        val after = snapshot.positionAt(nowMs)
        val farEnough = snapshot.durationMs?.let { minOf(STARTED_OVER_AFTER_MS, it / 2) } ?: STARTED_OVER_AFTER_MS
        return after in 0 until START_MS && before >= farEnough
    }

    /** Adds the stretch played up to [nowMs] to [playedMs], and starts the next one there. */
    private fun settle(nowMs: Long) {
        playingSince?.let { playedMs += (nowMs - it).coerceAtLeast(0L) }
        playingSince = playingSince?.let { nowMs }
    }

    /** The current song if it was just listened to long enough. */
    private fun due(): MediaSnapshot? {
        val track = current ?: return null
        if (recorded) return null
        val needed = track.durationMs?.let { minOf(LISTEN_MS, it / 2) } ?: LISTEN_MS
        if (playedMs < needed) return null
        recorded = true
        return track
    }

    companion object {

        /** Playing time after which a song counts as listened to. */
        const val LISTEN_MS = 30_000L

        /** A position this close to the start is the start of the song. */
        const val START_MS = 5_000L

        /** A song must have played this far before going back to its start counts as a new play. */
        const val STARTED_OVER_AFTER_MS = 30_000L
    }
}
