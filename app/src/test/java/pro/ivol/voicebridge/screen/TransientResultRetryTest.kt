package pro.ivol.voicebridge.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientResultRetryTest {
    @Test
    fun `null results are retried until a ready result arrives`() {
        val clock = FakeClock()
        val results = ArrayDeque<String?>().apply {
            add(null)
            add(null)
            add("ready")
        }
        var readCount = 0

        val result = retryTransientResultUntilDeadline(
            deadlineElapsedRealtimeMs = 100L,
            retryDelayMs = 10L,
            elapsedRealtimeMs = clock::now,
            wait = clock::advance,
            isReady = { true },
            read = {
                readCount += 1
                results.removeFirst()
            },
        )

        assertEquals("ready", result)
        assertEquals(3, readCount)
        assertEquals(listOf(10L, 10L), clock.waits)
    }

    @Test
    fun `available but non-ready result is retried until ready`() {
        val clock = FakeClock()
        val results = ArrayDeque(listOf("", "still loading", "ready"))

        val result = retryTransientResultUntilDeadline(
            deadlineElapsedRealtimeMs = 100L,
            retryDelayMs = 8L,
            elapsedRealtimeMs = clock::now,
            wait = clock::advance,
            isReady = { it == "ready" },
            read = results::removeFirst,
        )

        assertEquals("ready", result)
        assertEquals(listOf(8L, 8L), clock.waits)
    }

    @Test
    fun `deadline returns the latest available non-ready result`() {
        val clock = FakeClock()
        val results = ArrayDeque<String?>().apply {
            add("first partial")
            add(null)
            add("latest partial")
        }

        val result = retryTransientResultUntilDeadline(
            deadlineElapsedRealtimeMs = 25L,
            retryDelayMs = 10L,
            elapsedRealtimeMs = clock::now,
            wait = clock::advance,
            isReady = { false },
            read = results::removeFirst,
        )

        assertEquals("latest partial", result)
        assertEquals(listOf(10L, 10L, 5L), clock.waits)
        assertEquals(25L, clock.now())
    }

    @Test
    fun `permanent null returns null at deadline`() {
        val clock = FakeClock()
        var readCount = 0

        val result = retryTransientResultUntilDeadline<String>(
            deadlineElapsedRealtimeMs = 25L,
            retryDelayMs = 10L,
            elapsedRealtimeMs = clock::now,
            wait = clock::advance,
            isReady = { true },
            read = {
                readCount += 1
                null
            },
        )

        assertNull(result)
        assertEquals(3, readCount)
        assertEquals(listOf(10L, 10L, 5L), clock.waits)
        assertEquals(25L, clock.now())
    }

    @Test
    fun `interrupted wait preserves interrupt and returns last available result`() {
        Thread.interrupted()
        try {
            val result = retryTransientResultUntilDeadline(
                deadlineElapsedRealtimeMs = 100L,
                retryDelayMs = 10L,
                elapsedRealtimeMs = { 0L },
                wait = { throw InterruptedException("cancelled") },
                isReady = { false },
                read = { "partial" },
            )

            assertEquals("partial", result)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `zero or negative retry delay throws before reading`() {
        var readCount = 0

        listOf(0L, -1L).forEach { invalidDelay ->
            assertThrows(IllegalArgumentException::class.java) {
                retryTransientResultUntilDeadline(
                    deadlineElapsedRealtimeMs = 100L,
                    retryDelayMs = invalidDelay,
                    elapsedRealtimeMs = { 0L },
                    wait = {},
                    isReady = { _: String -> true },
                    read = {
                        readCount += 1
                        "ready"
                    },
                )
            }
        }

        assertEquals(0, readCount)
    }

    private class FakeClock {
        private var elapsedMs = 0L
        val waits = mutableListOf<Long>()

        fun now(): Long = elapsedMs

        fun advance(delayMs: Long) {
            waits += delayMs
            elapsedMs += delayMs
        }
    }
}
