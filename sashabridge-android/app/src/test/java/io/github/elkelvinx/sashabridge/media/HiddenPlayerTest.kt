package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.history.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The last music track of the history, shown while a video is hidden. */
class HiddenPlayerTest {

    private fun entry(source: String, mediaId: String?, coverKey: String? = null) = HistoryEntry(
        id = 7,
        playedAtMs = 1_000,
        source = source,
        title = "Run Rabbit Run",
        artist = "Flanagan and Allen",
        album = "Run Rabbit Run",
        mediaId = mediaId,
        coverKey = coverKey,
    )

    /** The track shows paused, with no position or length the history does not know. */
    @Test
    fun showsTheTrackPaused() {
        val snapshot = HiddenPlayer.snapshotOf(entry("com.spotify.music", "spotify:track:abc"), null)

        assertEquals("com.spotify.music", snapshot.source)
        assertEquals("Run Rabbit Run", snapshot.title)
        assertEquals("Flanagan and Allen", snapshot.artist)
        assertEquals("spotify:track:abc", snapshot.mediaId)
        assertFalse(snapshot.isPlaying)
        assertNull(snapshot.durationMs)
        assertEquals(PlaybackPositionCalculator.UNKNOWN_POSITION, snapshot.reportedPositionMs)
        assertNull(snapshot.art)
    }

    /** Spotify's track keeps the id the player gave it, from its media id. */
    @Test
    fun keepsTheTrackIdOfSpotify() {
        val snapshot = HiddenPlayer.snapshotOf(entry("com.spotify.music", "spotify:track:abc"), null)

        val live = MediaSnapshot.trackIdOf("com.spotify.music", "spotify:track:abc", "Other title", null, null, 180_000)
        assertEquals(live, snapshot.trackId)
    }

    /** YouTube Music gives no media id: the title and the artist make the same id. */
    @Test
    fun keepsTheTrackIdOfYouTubeMusic() {
        val source = "com.google.android.apps.youtube.music"
        val snapshot = HiddenPlayer.snapshotOf(entry(source, null), null)

        val live = MediaSnapshot.trackIdOf(source, null, "Run Rabbit Run", "Flanagan and Allen", null, 180_000)
        assertEquals(live, snapshot.trackId)
    }

    /** The cover is the art made from the kept file, with its id, not the history's cover key. */
    @Test
    fun theCoverIsTheGivenArt() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val art = AlbumArt(AlbumArtEncoder.artIdOf(jpeg), jpeg)
        val snapshot = HiddenPlayer.snapshotOf(entry("com.spotify.music", null, coverKey = "art:old"), art)

        assertEquals(AlbumArtEncoder.artIdOf(jpeg), snapshot.art?.artId)
    }
}
