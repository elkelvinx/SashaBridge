package io.github.elkelvinx.sashabridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How frames from the PC are told apart by version during the transition. */
class PcFrameTest {

    /** Every valid v2 fixture is read as v2. */
    @Test
    fun validV2FixturesAreV2() {
        for ((name, text) in Fixtures.all("pc-to-phone/valid")) {
            assertTrue(name, PcFrame.parse(text) is PcFrame.V2)
        }
    }

    /** Every invalid v2 fixture is invalid, not mistaken for a v1 command. */
    @Test
    fun invalidV2FixturesAreInvalid() {
        for ((name, text) in Fixtures.all("pc-to-phone/invalid")) {
            assertTrue(name, PcFrame.parse(text) is PcFrame.Invalid)
        }
    }

    /** Plain text is a v1 command, surrounding whitespace included. */
    @Test
    fun plainTextIsV1() {
        assertEquals(PcFrame.V1(PcMessage.Command(CommandAction.TOGGLE)), PcFrame.parse("PLAY_PAUSE\n"))
        assertEquals(PcFrame.V1(PcMessage.Command(CommandAction.SEEK, 60_000)), PcFrame.parse("SEEK:60000"))
    }

    /** Text that is neither version is invalid. */
    @Test
    fun unknownTextIsInvalid() {
        assertTrue(PcFrame.parse("JUMP") is PcFrame.Invalid)
        assertTrue(PcFrame.parse("{not json") is PcFrame.Invalid)
    }
}
