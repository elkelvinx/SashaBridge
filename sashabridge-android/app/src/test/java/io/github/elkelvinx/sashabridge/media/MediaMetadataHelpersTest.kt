package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers used to build snapshots: artist cleanup, track ids and art geometry. */
class MediaMetadataHelpersTest {

    /** A13: the smart shuffle context that Spotify appends to the artist is kept apart from it. */
    @Test
    fun separatesSpotifyPlaybackContext() {
        assertEquals(
            ArtistParts("Shiro SAGISU, Hazel Fernandes", "Del Modo aleatorio inteligente"),
            MetadataNormalizer.splitArtist(
                "com.spotify.music",
                "Shiro SAGISU, Hazel Fernandes • Del Modo aleatorio inteligente",
            ),
        )
    }

    /** Artists without a context, and other players, are left untouched. */
    @Test
    fun leavesOtherArtistsUntouched() {
        assertEquals(ArtistParts("Ado", null), MetadataNormalizer.splitArtist("com.spotify.music", "Ado"))
        assertEquals(
            ArtistParts("A • B", null),
            MetadataNormalizer.splitArtist("com.google.android.apps.youtube.music", "A • B"),
        )
        assertEquals(ArtistParts(null, null), MetadataNormalizer.splitArtist("com.spotify.music", null))
    }

    /** A separator with nothing on one side is not a context. */
    @Test
    fun ignoresAnEmptySide() {
        assertEquals(
            ArtistParts("Ado • ", null),
            MetadataNormalizer.splitArtist("com.spotify.music", "Ado • "),
        )
    }

    /** With a media id, the track id ignores the title, which Spotify fills in two steps. */
    @Test
    fun trackIdPrefersTheMediaId() {
        val first = MediaSnapshot.trackIdOf("com.spotify.music", "spotify:track:1", "Title", "A", null, 1L)
        val second = MediaSnapshot.trackIdOf("com.spotify.music", "spotify:track:1", "Title - Full", "A", null, 1L)
        assertEquals(first, second)
        assertEquals(16, first.length)
    }

    /** Without a media id, the title, the artist and the player make the track id. */
    @Test
    fun trackIdWithoutMediaIdUsesTheMetadata() {
        val base = MediaSnapshot.trackIdOf("app", null, "Title", "Artist", "Album", 1_000L)
        assertEquals(base, MediaSnapshot.trackIdOf("app", null, "Title", "Artist", "Album", 1_000L))
        assertNotEquals(base, MediaSnapshot.trackIdOf("app", null, "Other", "Artist", "Album", 1_000L))
        assertNotEquals(base, MediaSnapshot.trackIdOf("app", null, "Title", "Other", "Album", 1_000L))
        assertNotEquals(base, MediaSnapshot.trackIdOf("other.app", null, "Title", "Artist", "Album", 1_000L))
    }

    /**
     * YouTube Music sends the length and then the album a moment after the title (seen on the
     * phone: three identifiers for one track); the track keeps one identifier throughout.
     */
    @Test
    fun trackIdSurvivesTheAlbumAndLengthArrivingLater() {
        val first = MediaSnapshot.trackIdOf("com.google.android.apps.youtube.music", null, "La Revacholiere", "Sea Power", "", null)
        val withLength = MediaSnapshot.trackIdOf("com.google.android.apps.youtube.music", null, "La Revacholiere", "Sea Power", "", 250_821L)
        val withAlbum = MediaSnapshot.trackIdOf(
            "com.google.android.apps.youtube.music", null, "La Revacholiere", "Sea Power", "Disco Elysium", 250_821L,
        )
        assertEquals(first, withLength)
        assertEquals(first, withAlbum)
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
