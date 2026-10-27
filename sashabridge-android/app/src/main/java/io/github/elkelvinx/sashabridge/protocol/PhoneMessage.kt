package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message from the phone to the PC (protocol v2).
 *
 * The specification is `docs/protocolo-v2.md`. On the wire each subtype is identified by
 * the `type` field, whose value is the subtype's [SerialName]. Constraints that the types
 * cannot express are checked in `init` blocks, which also run during deserialization, so an
 * invalid message never becomes an instance.
 *
 * All subtypes are immutable and can be shared between threads.
 */
@Serializable
sealed interface PhoneMessage {

    /**
     * First message of every session.
     *
     * @property protocol Protocol version of the phone app.
     * @property device Phone model ([android.os.Build.MODEL]), for logging only.
     * @throws IllegalArgumentException if [protocol] is lower than 1.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val device: String,
    ) : PhoneMessage {
        init {
            require(protocol >= 1) { "protocol must be at least 1, was $protocol" }
        }
    }

    /**
     * Album art image, referenced by [MediaState.artId].
     *
     * @property artId Opaque identifier; equal identifiers always carry equal bytes.
     * @property mime Media type of the image, currently always `image/jpeg`.
     * @property data Image bytes in standard Base64, without line breaks.
     */
    @Serializable
    @SerialName("media.art")
    data class MediaArt(
        val artId: String,
        val mime: String,
        val data: String,
    ) : PhoneMessage

    /**
     * Full state of the active player. Each message replaces the previous one.
     *
     * @property trackId Opaque identifier that changes when the track changes.
     * @property source Android package of the player, such as `com.spotify.music`.
     * @property title Track title, or `null` if the player does not report it.
     * @property artist Track artist, or `null` if the player does not report it.
     * @property album Album name, or `null` if the player does not report it.
     * @property durationMs Track length in milliseconds, or `null` if unknown.
     * @property positionMs Playback position in milliseconds when the message is sent.
     * @property isPlaying Whether the player is in the playing state.
     * @property playbackSpeed Playback speed: `1.0` is normal speed and `0.0` a pause.
     * @property artId Album art of the track, or `null` if the track has none.
     * @throws IllegalArgumentException if [durationMs] is not positive or [positionMs] is negative.
     */
    @Serializable
    @SerialName("media.state")
    data class MediaState(
        val trackId: String,
        val source: String,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
        val positionMs: Long,
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val artId: String? = null,
    ) : PhoneMessage {
        init {
            require(durationMs == null || durationMs > 0) {
                "durationMs must be positive or null, was $durationMs"
            }
            require(positionMs >= 0) { "positionMs must not be negative, was $positionMs" }
        }
    }

    /** No player is active; the PC clears its media overlay. */
    @Serializable
    @SerialName("media.cleared")
    data object MediaCleared : PhoneMessage
}
