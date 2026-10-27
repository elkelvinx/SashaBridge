package io.github.elkelvinx.sashabridge.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/**
 * Encoder and decoder of protocol v2 messages.
 *
 * Encoding writes every nullable field, with `null` when it has no value, as the
 * specification requires. Decoding treats a missing nullable field as `null` and ignores
 * unknown fields, so that new optional fields do not break older receivers.
 *
 * The functions are pure and thread-safe and can run on any dispatcher. Messages with
 * album art can be tens of kilobytes, so they are better handled off the main thread.
 */
object ProtocolCodec {

    /** Protocol version implemented by this app, announced in `hello`. */
    const val PROTOCOL_VERSION: Int = 2

    /** JSON configuration shared by every message. */
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Encodes a message for the PC.
     *
     * @param message Message to encode.
     * @return The JSON text of the frame, including the `type` field.
     */
    fun encode(message: PhoneMessage): String =
        json.encodeToString(PhoneMessage.serializer(), message)

    /**
     * Encodes a message for the phone. The PC sends these; the phone uses this function
     * only in tests.
     *
     * @param message Message to encode.
     * @return The JSON text of the frame, including the `type` field.
     */
    fun encode(message: PcMessage): String =
        json.encodeToString(PcMessage.serializer(), message)

    /**
     * Decodes a frame received from the PC.
     *
     * @param text Content of a text frame.
     * @return The decoded message.
     * @throws ProtocolException if [text] is not a valid v2 message for the phone.
     */
    fun decodePcMessage(text: String): PcMessage = decode(text, PcMessage.serializer())

    /**
     * Decodes a frame sent by the phone. The PC receives these; the phone uses this
     * function only in tests.
     *
     * @param text Content of a text frame.
     * @return The decoded message.
     * @throws ProtocolException if [text] is not a valid v2 message for the PC.
     */
    fun decodePhoneMessage(text: String): PhoneMessage = decode(text, PhoneMessage.serializer())

    /**
     * Decodes [text] with [deserializer], turning every decoding failure into a [ProtocolException].
     *
     * @param text JSON text.
     * @param deserializer Strategy of the expected message family.
     * @return The decoded message.
     * @throws ProtocolException if the text is malformed or violates a message constraint.
     */
    private fun <T> decode(text: String, deserializer: DeserializationStrategy<T>): T =
        try {
            json.decodeFromString(deserializer, text)
        } catch (error: IllegalArgumentException) {
            // Covers SerializationException, which extends IllegalArgumentException, and
            // the constraint checks in the init blocks of the messages.
            throw ProtocolException("Invalid protocol v2 message: ${error.message}", error)
        }
}

/**
 * A frame that is not a valid protocol v2 message.
 *
 * @param message Description of the problem.
 * @param cause Underlying decoding or validation error.
 */
class ProtocolException(message: String, cause: Throwable) : Exception(message, cause)
