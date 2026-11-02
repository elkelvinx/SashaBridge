package io.github.elkelvinx.sashabridge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.media.AlbumArt
import kotlin.math.roundToInt

/**
 * The player card's album art and the blurred copy of it behind the card, which tints the card
 * with the colors of the track. How blurred, and how strongly it shows, the user sets
 * ([setBackdrop]).
 *
 * The cover uses the art's image for the screen, as sharp as the player gave it; only art
 * without one falls back to the PC's small JPEG. A sharper image of the art shown, which may
 * arrive a moment later, replaces it in place: it looks the same, only finer.
 *
 * Main thread.
 *
 * @param root View that contains the card.
 */
internal class CoverArt(root: View) {

    private val cover: ImageView = root.findViewById(R.id.cover)
    private val backdrop: ImageView = root.findViewById(R.id.coverBackdrop)

    /** Veil over [backdrop]; its own copy of the drawable, so its opacity can change. */
    private val veil: Drawable = root.findViewById<View>(R.id.coverScrim).background.mutate()

    /** Art shown; [NO_ART] for the placeholder, `null` before the first. */
    private var shownArtId: String? = null

    /** The image on the cover, kept to blur it again when the user changes the blur. */
    private var shownBitmap: Bitmap? = null

    /** How blurred the backdrop is, from 0 to [Backdrop.MAX_LEVEL]. */
    private var blur = Backdrop.DEFAULT_LEVEL

    /** Whether any art, or the placeholder, has been shown yet. */
    val hasShownArt: Boolean
        get() = shownArtId != null

    /**
     * Shows [art] and its blurred backdrop, or the placeholder and no backdrop without art.
     *
     * @param crossFade Whether the new image fades in, as for art that changes within a track;
     *   otherwise it replaces the old one at once.
     */
    fun show(art: AlbumArt?, crossFade: Boolean) {
        val artId = art?.artId ?: NO_ART
        if (artId == shownArtId) {
            val sharper = art?.screen
            if (sharper != null && sharper !== shownBitmap) {
                shownBitmap = sharper
                cover.setImageBitmap(sharper)
            }
            return
        }
        shownArtId = artId
        val bitmap = art?.let { it.screen ?: BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
        shownBitmap = bitmap
        val blurred = bitmap?.let { Backdrop.of(it, blur) }
        val backdropAlpha = if (blurred != null) BACKDROP_ALPHA else 0f
        val apply = {
            if (bitmap == null) cover.setImageResource(R.drawable.cover_placeholder) else cover.setImageBitmap(bitmap)
        }
        backdrop.animate().cancel()
        backdrop.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            backdrop.setImageBitmap(blurred)
            backdrop.animate().alpha(backdropAlpha).setDuration(FADE_MS * 4).start()
        }.start()
        if (!crossFade) {
            apply()
            return
        }
        cover.animate().cancel()
        cover.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            apply()
            cover.animate().alpha(1f).setDuration(FADE_MS * 2).start()
        }.start()
    }

    /**
     * Sets how the backdrop looks, as chosen in the settings.
     *
     * @param blur How blurred it is, from 0 to [Backdrop.MAX_LEVEL].
     * @param strength How strongly it shows through the veil, from 0 to [Backdrop.MAX_LEVEL].
     */
    fun setBackdrop(blur: Int, strength: Int) {
        veil.alpha = (Backdrop.veilAlpha(strength) * OPAQUE).roundToInt()
        if (blur == this.blur) return
        this.blur = blur
        shownBitmap?.let { backdrop.setImageBitmap(Backdrop.of(it, blur)) }
    }

    private companion object {

        /** [shownArtId] of the placeholder. */
        const val NO_ART = ""

        /** Base duration of the fades. */
        const val FADE_MS = 120L

        /** Opacity of the blurred art; the scrim on top keeps the text readable. */
        const val BACKDROP_ALPHA = 0.9f

        /** Drawable alpha of a fully opaque veil. */
        const val OPAQUE = 255
    }
}
