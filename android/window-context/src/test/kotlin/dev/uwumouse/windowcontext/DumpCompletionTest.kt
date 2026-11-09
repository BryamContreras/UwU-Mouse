package dev.uwumouse.windowcontext

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class DumpCompletionTest {
    @Test fun zeroTaskCountDoesNotPublishUntilActiveOperationIsReleased() {
        val releaseEntered = CountDownLatch(1)
        val releaseAllowed = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val completion = DumpCompletion(2) {
            releaseEntered.countDown()
            check(releaseAllowed.await(2, TimeUnit.SECONDS))
            active.set(false)
        }
        completion.taskFinished()
        val lastTask = Thread { completion.taskFinished() }
        lastTask.start()
        try {
            assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
            assertTrue(active.get())
            // The last task has already decremented the counter to zero, but its
            // cleanup is deliberately blocked at the old early-return race.
            assertFalse(completion.awaitUntil(System.nanoTime()))
        } finally {
            releaseAllowed.countDown()
            lastTask.join(2_000)
        }
        assertFalse(lastTask.isAlive)
        assertFalse(active.get())
        assertTrue(completion.awaitUntil(System.nanoTime()))
    }

    @Test fun waitsForBothReaderAndWriterBeforePublishingWriterAcceptance() {
        val accepted = AtomicBoolean(false)
        val completion = DumpCompletion(2) { }
        // An EOF/decoded result is insufficient until the Binder writer finishes.
        completion.taskFinished()
        assertFalse(completion.awaitUntil(System.nanoTime()))
        accepted.set(true)
        completion.taskFinished()
        assertTrue(completion.awaitUntil(System.nanoTime()))
        assertTrue(accepted.get())
    }

    @Test fun completedOperationCanBeObservedAfterItsDeadlineWithoutWaiting() {
        val completion = DumpCompletion(2) { }
        completion.taskFinished()
        completion.taskFinished()
        assertTrue(completion.awaitUntil(System.nanoTime() - 1_000_000))
    }
}
