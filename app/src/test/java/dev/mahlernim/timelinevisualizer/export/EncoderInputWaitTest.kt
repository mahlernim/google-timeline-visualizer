package dev.mahlernim.timelinevisualizer.export

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class EncoderInputWaitTest {
    @Test
    fun starvationObservesCancellationAndUnwindsToCleanup() {
        val job = Job()
        var attempts = 0
        var cleaned = false
        var cancelled = false
        try {
            runBlocking(job) {
                try {
                    awaitEncoderInputBuffer(
                        dequeue = {
                            attempts++
                            if (attempts == 3) job.cancel()
                            check(attempts <= 3) { "Cancellation was ignored" }
                            -1
                        },
                        drain = {},
                    )
                } finally {
                    cleaned = true
                }
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(cleaned)
        assertEquals(3, attempts)
    }

    @Test
    fun availableBufferIsReturnedAfterDraining() = runBlocking {
        var attempts = 0
        var drains = 0
        val index = awaitEncoderInputBuffer(
            dequeue = { if (++attempts < 3) -1 else 7 },
            drain = { drains++ },
        )
        assertEquals(7, index)
        assertEquals(3, drains)
    }
}
