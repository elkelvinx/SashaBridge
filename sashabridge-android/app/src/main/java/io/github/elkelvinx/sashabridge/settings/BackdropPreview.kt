package io.github.elkelvinx.sashabridge.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.graphics.createBitmap
import io.github.elkelvinx.sashabridge.R
import io.github.elkelvinx.sashabridge.bridge.BridgeRepository
import io.github.elkelvinx.sashabridge.history.HistoryRepository
import io.github.elkelvinx.sashabridge.ui.Backdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The example of Ajustes > Fondo de la tarjeta: a small player card over the blurred art, as
 * the real card looks with the blur and strength chosen.
 *
 * The art is the one of the track playing; failing that, of the last track listened to; and
 * failing that, a drawn sample. A line under the example says which.
 *
 * Main thread.
 *
 * @param view The example's layout (`preview_backdrop`).
 * @param scope Scope of the screen, to read the art without blocking it.
 */
internal class BackdropPreview(view: View, private val scope: CoroutineScope) {

    private val backdrop: ImageView = view.findViewById(R.id.previewBackdrop)
    private val veil: Drawable = view.findViewById<View>(R.id.previewVeil).background.mutate()
    private val cover: ImageView = view.findViewById(R.id.previewCover)
    private val title: TextView = view.findViewById(R.id.previewTitle)
    private val artist: TextView = view.findViewById(R.id.previewArtist)
    private val source: TextView = view.findViewById(R.id.previewSource)

    /** The art, once read. */
    private var art: Bitmap? = null

    /** Reading of the art in progress, or done. */
    private var loading: Job? = null

    private var blur = Backdrop.DEFAULT_LEVEL
    private var strength = Backdrop.DEFAULT_LEVEL

    /**
     * Shows the example with [blur] and [strength]; the art is read the first time.
     *
     * @param blur How blurred, from 0 to [Backdrop.MAX_LEVEL].
     * @param strength How strongly it shows through the veil, from 0 to [Backdrop.MAX_LEVEL].
     */
    fun show(blur: Int, strength: Int) {
        this.blur = blur
        this.strength = strength
        veil.alpha = (Backdrop.veilAlpha(strength) * OPAQUE).roundToInt()
        val shown = art
        if (shown != null) {
            backdrop.setImageBitmap(Backdrop.of(shown, blur))
        } else if (loading == null) {
            loading = scope.launch { load(cover.context) }
        }
    }

    /** Reads the art and its track, then draws the example. */
    private suspend fun load(context: Context) {
        val sample = withContext(Dispatchers.Default) { find(context) }
        art = sample.art
        cover.setImageBitmap(sample.art)
        title.text = sample.title ?: context.getString(R.string.settings_preview_sample_title)
        artist.text = sample.artist ?: context.getString(R.string.app_name)
        source.setText(sample.origin)
        show(blur, strength)
    }

    /** The art of the track playing, of the last one listened to, or the drawn sample. */
    private suspend fun find(context: Context): Sample {
        BridgeRepository.get(context).media.value?.let { playing ->
            playing.art?.let { decode(it.jpeg) }?.let { return Sample(it, playing.title, playing.artist, R.string.settings_preview_art_playing) }
        }
        val history = HistoryRepository.get(context)
        history.latest()?.let { last ->
            history.latestCover(last)?.let(::decode)?.let { return Sample(it, last.title, last.artist, R.string.settings_preview_art_last) }
        }
        return Sample(drawnArt(), null, null, R.string.settings_preview_art_sample)
    }

    /** Decodes JPEG or WebP [bytes], or `null` if they are not an image. */
    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** A colorful square for when there is no art at all: a diagonal gradient with two circles. */
    private fun drawnArt(): Bitmap {
        val size = SAMPLE_PX.toFloat()
        val bitmap = createBitmap(SAMPLE_PX, SAMPLE_PX)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size, size, SAMPLE_COLORS, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = SAMPLE_LIGHT
        canvas.drawCircle(size * 0.32f, size * 0.34f, size * 0.22f, paint)
        paint.color = SAMPLE_SHADE
        canvas.drawCircle(size * 0.7f, size * 0.7f, size * 0.26f, paint)
        return bitmap
    }

    /**
     * Art for the example and where it comes from.
     *
     * @property title Its track's title, or `null` for the sample.
     * @property artist Its track's artist, or `null` for the sample.
     * @property origin The line that says where it comes from.
     */
    private class Sample(val art: Bitmap, val title: String?, val artist: String?, @StringRes val origin: Int)

    private companion object {

        /** Drawable alpha of a fully opaque veil. */
        const val OPAQUE = 255

        /** Side of the drawn sample. */
        const val SAMPLE_PX = 192

        /** Blue, purple and orange, from the ink colors of Anotar. */
        val SAMPLE_COLORS = intArrayOf(0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFFB8C00.toInt())
        const val SAMPLE_LIGHT = 0x66FFFFFF
        const val SAMPLE_SHADE = 0x55000000
    }
}
