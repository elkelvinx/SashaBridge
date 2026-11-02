package io.github.elkelvinx.sashabridge.media

import android.graphics.Bitmap
import android.util.Log
import io.github.elkelvinx.sashabridge.bridge.BridgeConfig.LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sharper images of the latest covers for the screen ([SharpArtLoader]), by art id.
 *
 * When the player's image is smaller than the card shows it and its server has a larger one,
 * [ask] fetches it once; when it arrives, [onSharper] asks for the state to be built again,
 * and [sharpest] puts it in the art. The PC keeps the same art: only [AlbumArt.screen] changes.
 *
 * Main thread.
 *
 * @param loader Fetches the sharper images, or `null` for none.
 * @param scope Where the fetches run.
 * @param onSharper Called on the main thread when a sharper image arrived.
 */
internal class SharpArtCache(
    private val loader: SharpArtLoader?,
    private val scope: CoroutineScope,
    private val onSharper: () -> Unit,
) {

    /** Sharper images of the latest arts, by art id. */
    private val screens = LinkedHashMap<String, Bitmap>()

    /** Art ids whose sharper image was asked for, found or not, so each is asked once. */
    private val asked = LinkedHashSet<String>()

    /** [art] with the sharper image fetched for it, if there is one. */
    fun sharpest(art: AlbumArt): AlbumArt = screens[art.artId]?.let { AlbumArt(art.artId, art.jpeg, it) } ?: art

    /**
     * Fetches a sharper image of [art] if it needs one and may have one.
     *
     * @param artUrl The web address of the art, or `null` if the player gives none.
     */
    fun ask(art: AlbumArt, artUrl: String?) {
        val loader = loader ?: return
        val shown = art.screen ?: return
        if (maxOf(shown.width, shown.height) >= SharpArtLoader.MIN_SIDE_PX) return
        val url = artUrl?.let(ThumbnailSources::sharpUrl) ?: return
        if (art.artId in asked || !loader.allowed()) return
        asked += art.artId
        if (asked.size > ASKED_KEPT) asked.remove(asked.first())
        scope.launch {
            val sharp = loader.load(url) ?: return@launch
            if (sharp.width <= shown.width) return@launch
            Log.d(LOG_TAG, "Sharper art ${sharp.width}x${sharp.height} for ${art.artId}")
            screens[art.artId] = sharp
            if (screens.size > SCREENS_KEPT) screens.remove(screens.keys.first())
            onSharper()
        }
    }

    private companion object {

        /** Sharper images kept: the track playing and the one before, for going back. */
        const val SCREENS_KEPT = 2

        /** Art ids remembered as asked for a sharper image. */
        const val ASKED_KEPT = 64
    }
}
