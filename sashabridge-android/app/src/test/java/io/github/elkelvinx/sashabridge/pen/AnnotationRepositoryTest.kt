package io.github.elkelvinx.sashabridge.pen

import io.github.elkelvinx.sashabridge.protocol.PcMessage
import io.github.elkelvinx.sashabridge.protocol.PhoneMessage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Captures from the PC, opened once each, and images shared from other apps. */
class AnnotationRepositoryTest {

    private var now = 0L
    private val repository = AnnotationRepository(clock = { now })

    private fun capture(id: String, data: String) = PcMessage.AnnotateRequest(id, "image/png", 4, 3, data)

    /** Runs [block] and returns the answers it sent to the PC. */
    private fun repliesOf(block: () -> Unit): List<PhoneMessage> = runBlocking {
        val replies = mutableListOf<PhoneMessage>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { repository.replies.collect { replies += it } }
        block()
        yield()
        collector.cancel()
        replies
    }

    /** The same image right after the one taken is turned down and never pending. */
    @Test
    fun repeatOfTheTakenCaptureIsTurnedDown() {
        repository.offer(capture("c1", "AAAA"))
        repository.take()
        now += 300

        val replies = repliesOf { repository.offer(capture("c2", "AAAA")) }

        assertNull(repository.pendingCapture.value)
        assertEquals(listOf(PhoneMessage.AnnotateCancelled("c2")), replies)
    }

    /** A shared image waits until a session takes it. */
    @Test
    fun sharedImageWaitsForTheSession() = runBlocking {
        val image = PhoneMessage.AnnotateShared("image/png", 4, 3, "AAAA")
        repository.share(image)

        assertEquals(image, repository.shared.first())
    }

    /** A different image, or the same one long after, opens as usual. */
    @Test
    fun otherImagesAndLaterRepeatsAreKept() {
        repository.offer(capture("c1", "AAAA"))
        repository.take()

        repository.offer(capture("c2", "BBBB"))
        assertEquals("c2", repository.take()?.requestId)

        now += AnnotationRepository.REPEAT_WINDOW_MS
        repository.offer(capture("c3", "BBBB"))
        assertEquals("c3", repository.pendingCapture.value?.requestId)
    }
}
