package io.github.elkelvinx.sashabridge.media

import io.github.elkelvinx.sashabridge.media.ImageDecoding.Side
import org.junit.Assert.assertEquals
import org.junit.Test

/** How much a cover is reduced while it is decoded, with the sizes seen on the phone. */
class ImageDecodingTest {

    /** Spotify's 2000 px cover becomes 1000 px for the card (at least 900), 1000 px for the screen (at least 1080 would need 2000). */
    @Test
    fun halvesWhileTheLongestSideStaysLargeEnough() {
        assertEquals(2, ImageDecoding.sampleSize(2000, 2000, keepAtLeast = 900, side = Side.LONGEST))
        assertEquals(1, ImageDecoding.sampleSize(2000, 2000, keepAtLeast = 1080, side = Side.LONGEST))
        assertEquals(1, ImageDecoding.sampleSize(640, 640, keepAtLeast = 1080, side = Side.LONGEST))
    }

    /** A list cover must still cover its 192 px square, so the shorter side decides. */
    @Test
    fun theShortestSideDecidesForSquareCovers() {
        assertEquals(2, ImageDecoding.sampleSize(640, 640, keepAtLeast = 192, side = Side.SHORTEST))
        // A 16:9 video thumbnail: 1280 x 720 halves twice by its height only once.
        assertEquals(2, ImageDecoding.sampleSize(1280, 720, keepAtLeast = 192, side = Side.SHORTEST))
        assertEquals(4, ImageDecoding.sampleSize(1280, 720, keepAtLeast = 192, side = Side.LONGEST))
    }

    /** An image already smaller than asked is never reduced. */
    @Test
    fun smallImagesStayWhole() {
        assertEquals(1, ImageDecoding.sampleSize(60, 60, keepAtLeast = 192, side = Side.SHORTEST))
    }
}
