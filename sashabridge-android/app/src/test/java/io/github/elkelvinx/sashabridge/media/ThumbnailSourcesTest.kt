package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the queue covers come from, with the addresses seen on the phone. */
class ThumbnailSourcesTest {

    /** Spotify: its own content URI first, then the 300 px cover on its public server. */
    @Test
    fun spotifyContentThenPublicServer() {
        val uri = "content://com.spotify.mobile.android.mediaapi/spotify%3Aimage%3Aab67616d0000b2737dcaf4cd40b76811e031f38c?transformation=NONE"

        assertEquals(
            listOf(uri, "https://i.scdn.co/image/ab67616d00001e027dcaf4cd40b76811e031f38c"),
            ThumbnailSources.candidates(uri),
        )
    }

    /** YouTube Music: the same address, asking for the list size instead of 60 px. */
    @Test
    fun youTubeMusicAsksForTheListSize() {
        val uri = "https://yt3.googleusercontent.com/kl2cI3Z3P0fuE9jT2WDqUmGpy5KSaKp5jpAOk8WuHKNL6id4APJ7DI6_9hndOUq25V82tVP078105gKL=w60-h60-l90-rj"

        assertEquals(
            listOf("https://yt3.googleusercontent.com/kl2cI3Z3P0fuE9jT2WDqUmGpy5KSaKp5jpAOk8WuHKNL6id4APJ7DI6_9hndOUq25V82tVP078105gKL=w192-h192-l90-rj"),
            ThumbnailSources.candidates(uri),
        )
        assertEquals(
            listOf("https://lh3.googleusercontent.com/abc=s192"),
            ThumbnailSources.candidates("https://lh3.googleusercontent.com/abc=s60"),
        )
    }

    /** Any other address is used as it is. */
    @Test
    fun otherAddressesAreKept() {
        assertEquals(listOf("https://example.com/a.jpg"), ThumbnailSources.candidates("https://example.com/a.jpg"))
        assertEquals(listOf("content://other/1"), ThumbnailSources.candidates("content://other/1"))
    }

    /** The sharp cover: Spotify's 2000 px id on either of its servers, Google's at the screen size. */
    @Test
    fun sharpCovers() {
        assertEquals(
            "https://image-cdn.spotifycdn.com/image/ab67616d000082c1c6d81ed73dcd376f5d23cb36",
            ThumbnailSources.sharpUrl("https://image-cdn.spotifycdn.com/image/ab67616d0000b273c6d81ed73dcd376f5d23cb36"),
        )
        assertEquals(
            "https://i.scdn.co/image/ab67616d000082c17dcaf4cd40b76811e031f38c",
            ThumbnailSources.sharpUrl("https://i.scdn.co/image/ab67616d00001e027dcaf4cd40b76811e031f38c"),
        )
        assertEquals(
            "https://yt3.googleusercontent.com/kl2c=w1080-h1080-l90-rj",
            ThumbnailSources.sharpUrl("https://yt3.googleusercontent.com/kl2c=w60-h60-l90-rj"),
        )
        assertEquals("https://lh3.googleusercontent.com/abc=s1080", ThumbnailSources.sharpUrl("https://lh3.googleusercontent.com/abc=s60"))
    }

    /** No sharper cover for an address without a size to change, or a podcast's image. */
    @Test
    fun noSharpCoverElsewhere() {
        assertEquals(null, ThumbnailSources.sharpUrl("https://example.com/a.jpg"))
        assertEquals(null, ThumbnailSources.sharpUrl("https://lh3.googleusercontent.com/abc"))
        assertEquals(null, ThumbnailSources.sharpUrl("https://i.scdn.co/image/ab6765630000ba8a7dcaf4cd40b76811e031f38c"))
    }

    /** A key of a cover the history keeps is not fetched from anywhere. */
    @Test
    fun keysAreNotAddresses() {
        assertEquals(emptyList<String>(), ThumbnailSources.candidates("art:1a2b3c"))
    }
}
