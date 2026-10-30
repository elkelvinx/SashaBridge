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
}
