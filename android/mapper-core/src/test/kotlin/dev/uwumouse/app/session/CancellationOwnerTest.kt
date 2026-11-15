package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CancellationOwnerTest {
    private class Resource { val closes=AtomicInteger() }
    private fun owner()=CancellationOwner<Resource> { it.closes.incrementAndGet();Unit }

    @Test fun cancellationBeforeRegistrationClosesTheNewResourceAndPermanentlyRejectsRpcClaims() {
        val owner=owner();val resource=Resource()
        owner.cancel();assertTrue(owner.cancelled)
        assertFalse(owner.register(resource));assertEquals(1,resource.closes.get())
        assertFalse(owner.claim(resource));owner.cancel();owner.clear(resource)
        assertTrue(owner.cancelled);assertEquals(1,resource.closes.get())
    }

    @Test fun cancellationBetweenRegistrationAndRpcClaimClosesExactlyTheOwnedReader() {
        val owner=owner();val resource=Resource()
        assertTrue(owner.register(resource));owner.cancel()
        assertFalse(owner.claim(resource));assertEquals(1,resource.closes.get())
        owner.clear(resource);owner.cancel();assertEquals(1,resource.closes.get())
    }

    @Test fun claimedRpcStillLosesItsReaderOnCancellationAndCannotBeClaimedTwice() {
        val owner=owner();val resource=Resource()
        assertTrue(owner.register(resource));assertTrue(owner.claim(resource))
        assertFalse(owner.claim(resource));owner.cancel()
        assertEquals(1,resource.closes.get());assertFalse(owner.claim(resource))
        owner.clear(resource)
        val next=Resource();assertFalse(owner.register(next));assertEquals(1,next.closes.get())
    }

    @Test fun aCompletedCallCanReleaseOwnershipButAStaleFinallyCannotClearTheNextReader() {
        val owner=owner();val old=Resource();val current=Resource()
        assertTrue(owner.register(old));assertTrue(owner.claim(old));owner.clear(old)
        assertEquals(0,old.closes.get()) // The caller's finally closes its completed descriptor.
        assertTrue(owner.register(current));owner.clear(old)
        assertTrue(owner.claim(current));owner.cancel()
        assertEquals(0,old.closes.get());assertEquals(1,current.closes.get())
    }

    @Test fun aCompetingRegistrationDoesNotReplaceOrCloseTheExistingReader() {
        val owner=owner();val first=Resource();val duplicate=Resource()
        assertTrue(owner.register(first));assertFalse(owner.register(duplicate))
        assertEquals(1,duplicate.closes.get());assertEquals(0,first.closes.get())
        assertTrue(owner.claim(first));owner.cancel();assertEquals(1,first.closes.get())
    }

    @Test fun cancelDoesNotAcquireTheInjectorsMonitorWhileItsRpcIsBlocked() {
        val owner=owner();val resource=Resource();val injectMonitor=Any()
        val inRpc=CountDownLatch(1);val finishRpc=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val injection=executor.submit { synchronized(injectMonitor) {
                check(owner.register(resource));check(owner.claim(resource));inRpc.countDown()
                check(finishRpc.await(2,TimeUnit.SECONDS))
                owner.clear(resource)
            } }
            assertTrue(inRpc.await(2,TimeUnit.SECONDS))
            executor.submit { owner.cancel() }.get(1,TimeUnit.SECONDS)
            assertTrue(owner.cancelled);assertEquals(1,resource.closes.get())
            finishRpc.countDown();injection.get(2,TimeUnit.SECONDS)
        } finally { finishRpc.countDown();executor.shutdownNow() }
    }

    @Test fun concurrentPublicationAndCancellationNeverLeakOrResurrectAReader() {
        val executor=Executors.newFixedThreadPool(2)
        try {
            repeat(250) {
                val owner=owner();val resource=Resource();val start=CyclicBarrier(2)
                val registration=executor.submit<Boolean> {
                    start.await(2,TimeUnit.SECONDS)
                    if(owner.register(resource)) owner.claim(resource) else false
                }
                val cancellation=executor.submit { start.await(2,TimeUnit.SECONDS);owner.cancel() }
                registration.get(2,TimeUnit.SECONDS);cancellation.get(2,TimeUnit.SECONDS)
                assertTrue(owner.cancelled);assertFalse(owner.claim(resource));assertEquals(1,resource.closes.get())
                owner.clear(resource);owner.cancel();assertEquals(1,resource.closes.get())
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun closeFailureDoesNotUndoCancellationOrEscapeIntoThePauseCaller() {
        val owner=CancellationOwner<Resource> { it.closes.incrementAndGet();throw IOException("already closed") }
        val resource=Resource();assertTrue(owner.register(resource));owner.cancel()
        assertTrue(owner.cancelled);assertFalse(owner.claim(resource));assertEquals(1,resource.closes.get())
        val next=Resource();assertFalse(owner.register(next));assertEquals(1,next.closes.get())
    }
}
