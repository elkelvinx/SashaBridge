package io.github.elkelvinx.sashabridge.protocol

import io.github.elkelvinx.sashabridge.media.MediaSnapshot
import io.github.elkelvinx.sashabridge.media.MetadataNormalizer
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Encoder of [MediaSnapshot] in the protocol v1 format, for one PC session.
 *
 * It lets the new media pipeline feed the PC clients that only understand v1, until the
 * server switches to protocol v2 in phase 5. It is removed then.
 *
 * v1 has no art identifier, so the art travels with the messages themselves:
 * - It is included in the first message of the session, whenever the title or the shown
 *   artist changes, and whenever the art itself changes. The phase 4 client treats a new
 *   title or artist without art as a track that has none, and removes the previous cover (R6).
 *   Both sides must use the same notion of "new track": the track id is not enough, because
 *   Spotify fills in the title in two steps without changing the media id.
 * - The previous code marked the art as sent before checking that it existed, so art that
 *   arrived after the title was never sent (R3). Here art that arrives later in the same
 *   track is sent as soon as it appears.
 *
 * v1 has no field for the playback context either, so it is appended to the artist the way
 * Spotify shows it.
 *
 * Not thread-safe: create one instance per session and use it from that session's coroutine.
 */
class LegacyV1Session {

    /** Title and shown artist of the last message sent in this session. */
    private var lastTrack: Pair<String?, String?>? = null

    /** Identifier of the last art sent in this session. */
    private var lastSentArtId: String? = null

    /**
     * Encodes [snapshot] as a v1 media message.
     *
     * @param snapshot State to send.
     * @param nowElapsedMs Current `SystemClock.elapsedRealtime()`, used to extrapolate the position.
     * @return The JSON text of the frame.
     */
    fun encode(snapshot: MediaSnapshot, nowElapsedMs: Long): String {
        val artist = when {
            snapshot.playbackContext == null -> snapshot.artist
            snapshot.artist == null -> snapshot.playbackContext
            else -> snapshot.artist + MetadataNormalizer.SPOTIFY_CONTEXT_SEPARATOR + snapshot.playbackContext
        }
        val track = snapshot.title to artist
        val art = snapshot.art?.takeIf { track != lastTrack || it.artId != lastSentArtId }
        val message = buildJsonObject {
            put("title", snapshot.title)
            put("artist", artist)
            // v1 uses 0 for an unknown duration.
            put("duration", snapshot.durationMs ?: 0L)
            put("isPlaying", snapshot.isPlaying)
            put("position", snapshot.positionAt(nowElapsedMs))
            if (art != null) put("albumArt", Base64.getEncoder().encodeToString(art.jpeg))
        }
        lastTrack = track
        if (art != null) lastSentArtId = art.artId
        return message.toString()
    }
}
