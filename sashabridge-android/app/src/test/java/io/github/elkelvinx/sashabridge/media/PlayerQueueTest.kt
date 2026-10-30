package io.github.elkelvinx.sashabridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The direction of a track change, read from the queue. */
class PlayerQueueTest {

    private val queue = PlayerQueue(
        source = "com.google.android.apps.youtube.music",
        title = "Siguiente",
        entries = listOf("Wizard", "Slave of Love", "Lights Out", "Snake").mapIndexed { i, title ->
            QueueEntry(queueId = i.toLong(), title = title, subtitle = "VK Blanka")
        },
        activeQueueId = 2,
    )

    /** A later track is forward, an earlier one is back. */
    @Test
    fun laterIsForwardEarlierIsBack() {
        assertEquals(1, queue.directionBetween("Lights Out", "Snake"))
        assertEquals(-1, queue.directionBetween("Lights Out", "Slave of Love"))
    }

    /** Without both tracks in the queue there is no answer. */
    @Test
    fun unknownTracksGiveNoDirection() {
        assertNull(queue.directionBetween("Lights Out", "Not in the queue"))
        assertNull(queue.directionBetween(null, "Snake"))
        assertNull(queue.directionBetween("Snake", "Snake"))
    }
}
