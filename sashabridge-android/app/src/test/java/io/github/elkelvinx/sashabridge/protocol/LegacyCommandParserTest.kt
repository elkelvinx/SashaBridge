package io.github.elkelvinx.sashabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Translation of the v1 text commands that the current PC client sends. */
class LegacyCommandParserTest {

    /** Each v1 command maps to its v2 equivalent. */
    @Test
    fun parsesEveryV1Command() {
        assertEquals(PcMessage.Command(CommandAction.TOGGLE), LegacyCommandParser.parse("PLAY_PAUSE"))
        assertEquals(PcMessage.Command(CommandAction.NEXT), LegacyCommandParser.parse("NEXT"))
        assertEquals(PcMessage.Command(CommandAction.PREVIOUS), LegacyCommandParser.parse("PREV"))
        assertEquals(
            PcMessage.Command(CommandAction.SEEK, 60_000),
            LegacyCommandParser.parse("SEEK:60000"),
        )
    }

    /** Seeks without a valid, non-negative position are not commands. */
    @Test
    fun rejectsInvalidSeeks() {
        assertNull(LegacyCommandParser.parse("SEEK:"))
        assertNull(LegacyCommandParser.parse("SEEK:abc"))
        assertNull(LegacyCommandParser.parse("SEEK:-5"))
    }

    /** Unknown text, lowercase variants and v2 JSON frames are not v1 commands. */
    @Test
    fun rejectsOtherFrames() {
        assertNull(LegacyCommandParser.parse("SHUFFLE"))
        assertNull(LegacyCommandParser.parse("next"))
        assertNull(LegacyCommandParser.parse("""{"type":"cmd","action":"NEXT","positionMs":null}"""))
    }
}
