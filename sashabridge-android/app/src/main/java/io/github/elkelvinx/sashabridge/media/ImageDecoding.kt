package io.github.elkelvinx.sashabridge.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes images no larger than needed: whole halvings are skipped while the result would
 * still be at least the size asked for, which costs a fraction of the memory and time of
 * decoding a 2000 px cover in full to show it at 192 px.
 *
 * The only place the app decodes covers, for the screen ([AlbumArtEncoder]), the sharper ones
 * ([SharpArtLoader]) and the lists ([ThumbnailStore]).
 *
 * Thread-safe; call off the main thread.
 */
internal object ImageDecoding {

    /** Which side of the image must stay at least the size asked for. */
    enum class Side {
        /** The longer one: the image fits a square of that size. */
        LONGEST,

        /** The shorter one: the image covers a square of that size, for cropping. */
        SHORTEST,
    }

    /**
     * Decodes [bytes], a JPEG, PNG or WebP image.
     *
     * @param keepAtLeast Pixels its [side] keeps at least, unless the image is smaller.
     * @return The image, or `null` if [bytes] are no image.
     */
    fun decode(bytes: ByteArray, keepAtLeast: Int, side: Side = Side.LONGEST): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, keepAtLeast, side)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * The power of two to divide a [width] x [height] image by: the largest that leaves its
     * [side] at least [keepAtLeast]. Pure.
     */
    fun sampleSize(width: Int, height: Int, keepAtLeast: Int, side: Side): Int {
        val measured = if (side == Side.LONGEST) maxOf(width, height) else minOf(width, height)
        var sample = 1
        while (measured / (sample * 2) >= keepAtLeast) sample *= 2
        return sample
    }
}
