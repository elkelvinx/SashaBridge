package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message from the PC to the phone (protocol v2).
 *
 * See [PhoneMessage] for how the `type` field and the validation work. All subtypes are
 * immutable and can be shared between threads.
 */
@Serializable
sealed interface PcMessage {

    /**
     * First message of every session.
     *
     * @property protocol Protocol version of the PC client.
     * @property client Client identification as `name/version`, for logging only.
     * @throws IllegalArgumentException if [protocol] is lower than 1.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val client: String,
    ) : PcMessage {
        init {
            require(protocol >= 1) { "protocol must be at least 1, was $protocol" }
        }
    }

    /**
     * Order for the active player.
     *
     * @property action Requested action.
     * @property positionMs Target position in milliseconds; required with
     *   [CommandAction.SEEK] and `null` with every other action.
     * @throws IllegalArgumentException if [positionMs] does not match [action], or is negative.
     */
    @Serializable
    @SerialName("cmd")
    data class Command(
        val action: CommandAction,
        val positionMs: Long? = null,
    ) : PcMessage {
        init {
            if (action == CommandAction.SEEK) {
                require(positionMs != null && positionMs >= 0) {
                    "SEEK requires a positionMs of at least 0, was $positionMs"
                }
            } else {
                require(positionMs == null) { "$action does not accept positionMs" }
            }
        }
    }
}

/**
 * Values of the `action` field of [PcMessage.Command].
 *
 * [PLAY] and [PAUSE] are explicit: repeating [PAUSE] on a paused player does not resume it.
 * [TOGGLE] serves the play/pause keys that do not say which state they want.
 */
@Serializable
enum class CommandAction {
    /** Starts playback; does nothing if already playing. */
    PLAY,

    /** Pauses playback; does nothing if already paused. */
    PAUSE,

    /** Switches between playing and paused. */
    TOGGLE,

    /** Skips to the next track. */
    NEXT,

    /** Goes back to the previous track. */
    PREVIOUS,

    /** Moves the playback position to [PcMessage.Command.positionMs]. */
    SEEK,
}
