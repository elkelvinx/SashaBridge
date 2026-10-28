package io.github.elkelvinx.sashabridge.protocol

import java.io.File
import org.junit.Assert.assertTrue

/**
 * Access to the protocol fixtures shared with the Rust client.
 *
 * The fixtures reach the test classpath under `fixtures/` through the `test` source set in
 * `app/build.gradle.kts`.
 */
internal object Fixtures {

    /**
     * Returns the name and content of every JSON fixture in [dir], sorted by name.
     *
     * Fails if the directory is missing or empty, so that a wrong path cannot pass silently.
     *
     * @param dir Directory relative to `protocol/fixtures`, such as `phone-to-pc/valid`.
     * @return Pairs of file name and UTF-8 content.
     */
    fun all(dir: String): List<Pair<String, String>> {
        val files = File(resource(dir).toURI())
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no fixtures in fixtures/$dir", files.isNotEmpty())
        return files.map { it.name to it.readText(Charsets.UTF_8) }
    }

    /**
     * Returns the content of one fixture.
     *
     * @param path Path relative to `protocol/fixtures`, such as `phone-to-pc/valid/media-art.json`.
     * @return The UTF-8 content.
     */
    fun read(path: String): String = File(resource(path).toURI()).readText(Charsets.UTF_8)

    /**
     * Returns the bytes of one binary fixture.
     *
     * @param path Path relative to `protocol/fixtures`, such as `pen-samples/samples.bin`.
     * @return The file contents.
     */
    fun bytes(path: String): ByteArray = File(resource(path).toURI()).readBytes()

    /** Resolves [path] under `fixtures/` on the test classpath. */
    private fun resource(path: String) =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$path")) {
            "fixtures/$path is not on the test classpath"
        }
}
