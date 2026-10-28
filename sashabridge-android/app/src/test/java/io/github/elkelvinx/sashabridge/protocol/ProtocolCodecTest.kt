package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Conformance of [ProtocolCodec] with the fixtures in `protocol/fixtures`, shared with the
 * Rust client.
 *
 * The fixtures are listed when the tests run, so a new fixture is covered without changing
 * this class.
 */
class ProtocolCodecTest {

    /** Valid phone messages decode, re-encode to the same JSON and survive a round trip. */
    @Test
    fun validPhoneMessagesRoundTrip() = assertRoundTrip(
        "phone-to-pc/valid",
        ProtocolCodec::decodePhoneMessage,
    ) { message: PhoneMessage -> ProtocolCodec.encode(message) }

    /** Invalid phone messages are rejected with [ProtocolException]. */
    @Test
    fun invalidPhoneMessagesAreRejected() =
        assertRejected("phone-to-pc/invalid", ProtocolCodec::decodePhoneMessage)

    /** Valid PC messages decode, re-encode to the same JSON and survive a round trip. */
    @Test
    fun validPcMessagesRoundTrip() = assertRoundTrip(
        "pc-to-phone/valid",
        ProtocolCodec::decodePcMessage,
    ) { message: PcMessage -> ProtocolCodec.encode(message) }

    /** Invalid PC messages are rejected with [ProtocolException]. */
    @Test
    fun invalidPcMessagesAreRejected() =
        assertRejected("pc-to-phone/invalid", ProtocolCodec::decodePcMessage)

    /** The hello fixture announces the version this app implements. */
    @Test
    fun helloFixtureUsesTheCurrentVersion() {
        val (_, text) = fixtures("phone-to-pc/valid").single { (name, _) -> name == "hello.json" }
        val hello = ProtocolCodec.decodePhoneMessage(text) as PhoneMessage.Hello
        assertEquals(ProtocolCodec.PROTOCOL_VERSION, hello.protocol)
    }

    /**
     * Checks every fixture in [dir]: it decodes, it re-encodes to the same JSON (ignoring
     * field order) and the re-encoded text decodes to an equal message.
     */
    private fun <T> assertRoundTrip(dir: String, decode: (String) -> T, encode: (T) -> String) {
        for ((name, text) in fixtures(dir)) {
            val message = decode(text)
            val encoded = encode(message)
            assertEquals(
                "$dir/$name must re-encode to the same JSON",
                Json.parseToJsonElement(text),
                Json.parseToJsonElement(encoded),
            )
            assertEquals("$dir/$name must survive a round trip", message, decode(encoded))
        }
    }

    /** Checks that [decode] rejects every fixture in [dir] with a [ProtocolException]. */
    private fun assertRejected(dir: String, decode: (String) -> Any) {
        for ((name, text) in fixtures(dir)) {
            assertThrows("$dir/$name must be rejected", ProtocolException::class.java) {
                decode(text)
            }
        }
    }

    /** Shorthand for [Fixtures.all]. */
    private fun fixtures(dir: String): List<Pair<String, String>> = Fixtures.all(dir)
}
