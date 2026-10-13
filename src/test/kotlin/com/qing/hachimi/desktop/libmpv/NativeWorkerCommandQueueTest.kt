package com.qing.hachimi.desktop.libmpv

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeWorkerCommandQueueTest {
    @Test
    fun `rejects commands offered after the worker queue stops`() {
        val queue = NativeWorkerCommandQueue<String>()
        val rejections = AtomicInteger()
        val failure = IllegalStateException("worker stopped")

        queue.stop(failure) { _, _ -> error("empty queue has no command to reject") }
        val accepted = queue.offer("late") { rejected ->
            assertEquals(failure, rejected)
            rejections.incrementAndGet()
        }

        assertFalse(accepted)
        assertEquals(1, rejections.get())
    }

    @Test
    fun `stop rejects every command accepted before shutdown`() {
        val queue = NativeWorkerCommandQueue<String>()
        val rejected = mutableListOf<Pair<String, Throwable>>()
        val failure = IllegalStateException("worker stopped")

        assertTrue(queue.offer("first") { error("still accepting") })
        assertTrue(queue.offer("second") { error("still accepting") })
        queue.stop(failure) { command, cause -> rejected += command to cause }

        assertEquals(listOf("first", "second"), rejected.map { it.first })
        assertTrue(rejected.all { it.second === failure })
        assertEquals(null, queue.poll())
    }

    @Test
    fun `concurrent offer and stop always reject an accepted command or the offer`() {
        repeat(100) {
            val queue = NativeWorkerCommandQueue<String>()
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val rejects = AtomicInteger()
            val failure = IllegalStateException("worker stopped")
            try {
                val offered = executor.submit<Boolean> {
                    start.await()
                    queue.offer("command") { rejected ->
                        assertEquals(failure, rejected)
                        rejects.incrementAndGet()
                    }
                }
                val stopped = executor.submit {
                    start.await()
                    queue.stop(failure) { _, cause ->
                        assertEquals(failure, cause)
                        rejects.incrementAndGet()
                    }
                }
                start.countDown()
                stopped.get(2, TimeUnit.SECONDS)
                val wasOffered = offered.get(2, TimeUnit.SECONDS)

                assertEquals(1, rejects.get(), "command was offered before shutdown: $wasOffered")
                assertEquals(null, queue.poll())
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
