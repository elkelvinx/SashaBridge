package io.github.elkelvinx.sashabridge.history

import io.github.elkelvinx.sashabridge.media.MediaSnapshot

/**
 * Decides when a track counts as listened to, so it goes to the history once per play:
 * after [LISTEN_MS] of actual playing (pauses do not count), or half the track if it is
 * shorter. Skipped tracks never reach the history, and neither do video apps ([isMusic]).
 *
 * Fed with every state of the player ([update]) and, while one plays, with the time now and
 * then ([tick]): players send nothing while a track simply plays on.
 *
 * Plain state with no Android dependency, so it is tested on the JVM. One thread at a time.
 */
class HistoryRecorder {

    /** Track being timed, or `null`. */
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
     * @param nowMs Milliseconds of a monotonic clock.
     * @return The track to add to the history now, or `null`.
     */
    fun update(snapshot: MediaSnapshot?, nowMs: Long): MediaSnapshot? {
        settle(nowMs)
        if (snapshot == null || !isMusic(snapshot.source)) {
            current = null
            playingSince = null
            return null
        }
        if (snapshot.trackId != current?.trackId) {
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
     * @return The track to add to the history now, or `null`.
     */
    fun tick(nowMs: Long): MediaSnapshot? {
        settle(nowMs)
        return due()
    }

    /** Adds the stretch played up to [nowMs] to [playedMs], and starts the next one there. */
    private fun settle(nowMs: Long) {
        playingSince?.let { playedMs += (nowMs - it).coerceAtLeast(0L) }
        playingSince = playingSince?.let { nowMs }
    }

    /** The current track if it was just listened to long enough. */
    private fun due(): MediaSnapshot? {
        val track = current ?: return null
        if (recorded) return null
        val needed = track.durationMs?.let { minOf(LISTEN_MS, it / 2) } ?: LISTEN_MS
        if (playedMs < needed) return null
        recorded = true
        return track
    }

    companion object {

        /** Playing time after which a track counts as listened to. */
        const val LISTEN_MS = 30_000L

        /** Players of video, whose titles are not songs. */
        private val VIDEO_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "com.wbd.stream",
            "tv.twitch.android.app",
            "com.crunchyroll.crunchyroid",
        )

        /** Whether [source] plays music rather than video. */
        fun isMusic(source: String): Boolean = source !in VIDEO_APPS
    }
}
