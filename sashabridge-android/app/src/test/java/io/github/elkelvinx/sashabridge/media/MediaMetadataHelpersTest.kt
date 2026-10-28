package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers used to build snapshots: artist cleanup, track ids and art geometry. */
class MediaMetadataHelpersTest {

    /** A13: the smart shuffle context that Spotify appends to the artist is removed. */
    @Test
    fun removesSpotifyPlaybackContext() {
        assertEquals(
            "Shiro SAGISU, Hazel Fernandes",
            MetadataNormalizer.artist(
                "com.spotify.music",
                "Shiro SAGISU, Hazel Fernandes • Del Modo aleatorio inteligente",
            ),
        )
    }

    /** Artists without the context, and other players, are left untouched. */
    @Test
    fun leavesOtherArtistsUntouched() {
        assertEquals("Ado", MetadataNormalizer.artist("com.spotify.music", "Ado"))
        assertEquals(
            "A • B",
            MetadataNormalizer.artist("com.google.android.apps.youtube.music", "A • B"),
        )
        assertNull(MetadataNormalizer.artist("com.spotify.music", null))
    }

    /** With a media id, the track id ignores the title, which Spotify fills in two steps. */
    @Test
    fun trackIdPrefersTheMediaId() {
        val first = MediaSnapshot.trackIdOf("com.spotify.music", "spotify:track:1", "Title", "A", null, 1L)
        val second = MediaSnapshot.trackIdOf("com.spotify.music", "spotify:track:1", "Title - Full", "A", null, 1L)
        assertEquals(first, second)
        assertEquals(16, first.length)
    }

    /** Without a media id, any descriptive field changes the track id, and so does the player. */
    @Test
    fun trackIdWithoutMediaIdUsesTheMetadata() {
        val base = MediaSnapshot.trackIdOf("app", null, "Title", "Artist", "Album", 1_000L)
        assertEquals(base, MediaSnapshot.trackIdOf("app", null, "Title", "Artist", "Album", 1_000L))
        assertNotEquals(base, MediaSnapshot.trackIdOf("app", null, "Other", "Artist", "Album", 1_000L))
        assertNotEquals(base, MediaSnapshot.trackIdOf("other.app", null, "Title", "Artist", "Album", 1_000L))
    }

    /** Art is scaled down keeping its proportions; a 16:9 thumbnail is not squashed into a square. */
    @Test
    fun artKeepsItsProportions() {
        assertEquals(300 to 169, AlbumArtEncoder.fitWithin(1280, 720, 300))
        assertEquals(300 to 300, AlbumArtEncoder.fitWithin(640, 640, 300))
        assertEquals(169 to 300, AlbumArtEncoder.fitWithin(720, 1280, 300))
    }

    /** Small art is not enlarged. */
    @Test
    fun smallArtIsNotEnlarged() {
        assertEquals(200 to 100, AlbumArtEncoder.fitWithin(200, 100, 300))
    }

    /** The art id depends only on the bytes. */
    @Test
    fun artIdIsDerivedFromTheBytes() {
        val id = AlbumArtEncoder.artIdOf(byteArrayOf(1, 2, 3))
        assertEquals(id, AlbumArtEncoder.artIdOf(byteArrayOf(1, 2, 3)))
        assertNotEquals(id, AlbumArtEncoder.artIdOf(byteArrayOf(1, 2, 4)))
        assertTrue(id.matches(Regex("[0-9a-f]{16}")))
    }
}
