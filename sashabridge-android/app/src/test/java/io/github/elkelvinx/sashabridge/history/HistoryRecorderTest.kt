package io.github.elkelvinx.sashabridge.history

import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a track counts as listened to. */
class HistoryRecorderTest {

    private fun track(id: String, playing: Boolean = true, durationMs: Long? = 200_000, source: String = "com.spotify.music") =
        MediaSnapshot(
            trackId = id,
            source = source,
            title = "Title $id",
            artist = "Artist",
            playbackContext = null,
            album = null,
            durationMs = durationMs,
            isPlaying = playing,
            reportedPositionMs = 0,
            positionUpdateTimeMs = 0,
            playbackSpeed = if (playing) 1f else 0f,
            shuffle = null,
            art = null,
        )

    /** Thirty seconds of playing records the track once, even with no new state. */
    @Test
    fun thirtySecondsRecordOnce() {
        val recorder = HistoryRecorder()
        assertNull(recorder.update(track("a"), nowMs = 0))
        assertNull(recorder.tick(nowMs = 29_000))

        assertEquals("a", recorder.tick(nowMs = 30_000)?.trackId)
        assertNull(recorder.tick(nowMs = 60_000))
    }

    /** A track skipped before thirty seconds is never recorded. */
    @Test
    fun skippedTrackIsNotRecorded() {
        val recorder = HistoryRecorder()
        recorder.update(track("a"), nowMs = 0)
        assertNull(recorder.update(track("b"), nowMs = 10_000))

        assertEquals("b", recorder.tick(nowMs = 40_000)?.trackId)
    }

    /** Paused time does not count. */
    @Test
    fun pausesDoNotCount() {
        val recorder = HistoryRecorder()
        recorder.update(track("a"), nowMs = 0)
        recorder.update(track("a", playing = false), nowMs = 20_000)
        assertNull(recorder.tick(nowMs = 120_000))
        recorder.update(track("a"), nowMs = 120_000)

        assertNull(recorder.tick(nowMs = 129_000))
        assertEquals("a", recorder.tick(nowMs = 130_000)?.trackId)
    }

    /** A short track counts after half of it. */
    @Test
    fun shortTrackCountsAfterHalf() {
        val recorder = HistoryRecorder()
        recorder.update(track("a", durationMs = 40_000), nowMs = 0)

        assertEquals("a", recorder.tick(nowMs = 20_000)?.trackId)
    }

    /** Videos are not songs. */
    @Test
    fun videosAreNotRecorded() {
        val recorder = HistoryRecorder()
        recorder.update(track("v", source = "com.google.android.youtube"), nowMs = 0)

        assertNull(recorder.tick(nowMs = 600_000))
    }
}
