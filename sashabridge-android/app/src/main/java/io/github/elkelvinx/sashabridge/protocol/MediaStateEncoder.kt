package io.github.elkelvinx.sashabridge.protocol

import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import java.util.Base64

/**
 * Turns the states of the active player into the protocol v2 messages of one PC session.
 *
 * It applies the ordering rules of `docs/protocolo-v2.md`:
 * - A `null` state, meaning no player, becomes `media.cleared`.
 * - A `media.art` goes out before the first `media.state` that names its `artId`, and only
 *   when that `artId` differs from the last art sent in the session. The PC keeps only the
 *   latest art, so going back to an earlier track sends its art again.
 *
 * Not thread-safe: create one instance per session and use it from that session's coroutine.
 */
class MediaStateEncoder {

    /** Identifier of the last art sent in this session. */
    private var lastSentArtId: String? = null

    /**
     * Returns the messages that bring the PC to [snapshot].
     *
     * @param snapshot State of the active player, or `null` when there is none.
     * @param nowElapsedMs Current `SystemClock.elapsedRealtime()`, used to extrapolate the position.
     * @return `media.cleared`, or `media.state` preceded by `media.art` when the art is new.
     */
    fun encode(snapshot: MediaSnapshot?, nowElapsedMs: Long): List<PhoneMessage> {
        if (snapshot == null) return listOf(PhoneMessage.MediaCleared)
        val state = PhoneMessage.MediaState(
            trackId = snapshot.trackId,
            source = snapshot.source,
            title = snapshot.title,
            artist = snapshot.artist,
            context = snapshot.playbackContext,
            album = snapshot.album,
            durationMs = snapshot.durationMs,
            positionMs = snapshot.positionAt(nowElapsedMs),
            isPlaying = snapshot.isPlaying,
            playbackSpeed = snapshot.playbackSpeed,
            shuffle = snapshot.shuffle,
            artId = snapshot.art?.artId,
            liked = snapshot.liked,
        )
        val art = snapshot.art?.takeIf { it.artId != lastSentArtId } ?: return listOf(state)
        lastSentArtId = art.artId
        val artMessage = PhoneMessage.MediaArt(
            artId = art.artId,
            mime = JPEG_MIME,
            data = Base64.getEncoder().encodeToString(art.jpeg),
        )
        return listOf(artMessage, state)
    }

    private companion object {

        /** Media type of the art produced by [io.github.elkelvinx.sashabridge.media.AlbumArtEncoder]. */
        const val JPEG_MIME = "image/jpeg"
    }
}
