package io.github.elkelvinx.sashabridge.protocol

/**
 * A text frame received from the PC, in either protocol version.
 *
 * A frame that starts with `{` is a JSON object and is read as v2; anything else is a v1
 * text command. The v1 branch is removed in phase 7.
 *
 * Immutable; can be shared between threads.
 */
sealed interface PcFrame {

    /**
     * A valid protocol v2 message.
     *
     * @property message The decoded message.
     */
    data class V2(val message: PcMessage) : PcFrame

    /**
     * A valid protocol v1 text command, already translated to v2.
     *
     * @property command The equivalent v2 command.
     */
    data class V1(val command: PcMessage.Command) : PcFrame

    /**
     * A frame that is valid in neither version. The receiver logs it and goes on.
     *
     * @property reason Description of the problem.
     */
    data class Invalid(val reason: String) : PcFrame

    companion object {

        /**
         * Reads a text frame. Pure; any thread.
         *
         * @param text Content of the frame.
         * @return The frame in the version it was written in, or [Invalid].
         */
        fun parse(text: String): PcFrame {
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) {
                return try {
                    V2(ProtocolCodec.decodePcMessage(trimmed))
                } catch (error: ProtocolException) {
                    Invalid(error.message ?: "invalid protocol v2 message")
                }
            }
            return LegacyCommandParser.parse(trimmed)?.let(::V1)
                ?: Invalid("unknown protocol v1 command: $trimmed")
        }
    }
}
