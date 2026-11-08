package dev.mobimapper.windowcontext

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Publish an operation only after every task and its shared-resource cleanup finish. */
internal class DumpCompletion(taskCount: Int, private val release: () -> Unit) {
    private val remaining = AtomicInteger(taskCount.also { require(it > 0) })
    private val published = CountDownLatch(1)

    fun taskFinished() {
        if (remaining.decrementAndGet() == 0) {
            release()
            published.countDown()
        }
    }

    fun awaitUntil(deadlineNanos: Long): Boolean =
        published.await(maxOf(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS)
}
