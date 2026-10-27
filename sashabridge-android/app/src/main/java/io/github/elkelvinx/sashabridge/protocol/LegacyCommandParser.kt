package io.github.elkelvinx.sashabridge.protocol

/**
 * Parser of the protocol v1 text commands.
 *
 * Until phase 4 the PC client only sends these plain-text frames, and afterwards it keeps
 * sending them until it receives the phone's `hello`. The parser translates them into
 * [PcMessage.Command] so that the rest of the app handles a single command type. It is
 * removed in phase 7.
 *
 * Pure and thread-safe; can run on any dispatcher.
 */
object LegacyCommandParser {

    /** Prefix of the v1 seek command, followed by the position in milliseconds. */
    private const val SEEK_PREFIX = "SEEK:"

    /**
     * Converts a v1 text command into its v2 equivalent.
     *
     * `PLAY_PAUSE` becomes [CommandAction.TOGGLE], because v1 has no explicit play or pause.
     *
     * @param text Content of a text frame.
     * @return The command, or `null` when [text] is not a valid v1 command.
     */
    fun parse(text: String): PcMessage.Command? = when {
        text == "PLAY_PAUSE" -> PcMessage.Command(CommandAction.TOGGLE)
        text == "NEXT" -> PcMessage.Command(CommandAction.NEXT)
        text == "PREV" -> PcMessage.Command(CommandAction.PREVIOUS)
        text.startsWith(SEEK_PREFIX) ->
            text.removePrefix(SEEK_PREFIX)
                .toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.let { PcMessage.Command(CommandAction.SEEK, it) }
        else -> null
    }
}
