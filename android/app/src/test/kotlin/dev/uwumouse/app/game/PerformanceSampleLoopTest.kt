package dev.uwumouse.app.game

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PerformanceSampleLoopTest {
    private class Task(val delay:Long,val action:()->Unit)
    private class Harness {
        private val queueLock=Any()
        private val tasks=ArrayList<Task>()
        private val deliveries=ArrayList<Runnable>()
        val calls=Collections.synchronizedList(mutableListOf<Pair<String,Int>>())
        val results=mutableListOf<Float?>()
        var sampling:(String,Int)->Float?={ _,display -> display.toFloat() }
        val loop=PerformanceSampleLoop(
            schedule={ delay,action ->
                val task=Task(delay,action)
                synchronized(queueLock) { tasks.add(task) }
                val cancel:()->Unit={ synchronized(queueLock) { tasks.remove(task) } }
                cancel
            },
            post={ synchronized(queueLock) { deliveries.add(it) } },
            remove={ synchronized(queueLock) { deliveries.remove(it) } },
            sample={ name,display -> calls.add(name to display);sampling(name,display) },
            onSample={ results.add(it) },
        )
        fun nextTask():Task=synchronized(queueLock) { tasks.removeAt(0) }
        fun taskDelays():List<Long> = synchronized(queueLock) { tasks.map { it.delay } }
        fun deliveryCount():Int=synchronized(queueLock) { deliveries.size }
        fun deliver() {
            val callback=synchronized(queueLock) { deliveries.removeAt(0) }
            callback.run()
        }
    }

    @Test fun repeatedStartKeepsOneTaskAndSamplesThreeSecondsAfterEachCompletion() {
        val h=Harness()
        repeat(1_000) { h.loop.start("game",2) }
        assertEquals(listOf(0L),h.taskDelays())
        h.nextTask().action()
        assertEquals(listOf("game" to 2),h.calls)
        assertEquals(listOf(3_000L),h.taskDelays())
        h.deliver()
        assertEquals(listOf(2f),h.results)
        h.nextTask().action()
        assertEquals(listOf(3_000L),h.taskDelays())
        h.loop.stop()
        assertTrue(h.taskDelays().isEmpty())
        assertEquals(0,h.deliveryCount())
    }

    @Test fun blockedSampleKeepsOnlyTheLatestRestartWithoutQueueingMoreWork() {
        val h=Harness()
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        h.sampling={ _,display ->
            if(display==0) {
                entered.countDown()
                check(release.await(5,TimeUnit.SECONDS))
            }
            display.toFloat()
        }
        h.loop.start("old",0)
        val old=h.nextTask()
        val worker=Thread { old.action() }.apply { isDaemon=true;start() }
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(1_000) {
                h.loop.stop()
                h.loop.start("intermediate",it+1)
            }
            h.loop.start("latest",42)
            assertTrue(h.taskDelays().isEmpty())
            assertEquals(1,h.calls.size)
            assertEquals(0,h.deliveryCount())
        } finally { release.countDown();worker.join(2_000) }
        assertFalse(worker.isAlive)
        assertEquals(listOf(0L),h.taskDelays())
        assertEquals(0,h.deliveryCount())
        h.nextTask().action()
        assertEquals(listOf("old" to 0,"latest" to 42),h.calls)
        h.deliver()
        assertEquals(listOf(42f),h.results)
        assertEquals(listOf(3_000L),h.taskDelays())
    }

    @Test fun stoppingAnInFlightSamplePreventsItsDeliveryAndEveryLaterWakeup() {
        val h=Harness()
        h.sampling={ _,_ -> h.loop.stop();90f }
        h.loop.start("game",0)
        h.nextTask().action()
        assertTrue(h.taskDelays().isEmpty())
        assertEquals(0,h.deliveryCount())
        assertTrue(h.results.isEmpty())
    }

    @Test fun blockedMainRetainsOneLatestDeliveryAndStopRemovesIt() {
        val h=Harness()
        var sample=0f
        h.sampling={ _,_ -> ++sample }
        h.loop.start("game",0)
        repeat(1_000) {
            h.nextTask().action()
            assertEquals(1,h.deliveryCount())
            assertEquals(listOf(3_000L),h.taskDelays())
        }
        h.deliver()
        assertEquals(listOf(1_000f),h.results)
        h.nextTask().action()
        h.loop.stop()
        assertEquals(0,h.deliveryCount())
        assertTrue(h.taskDelays().isEmpty())
    }

    @Test fun cancelledTaskCannotSampleOrReplaceTheLatestTargetsScheduledTask() {
        val h=Harness()
        h.loop.start("old",0)
        val stale=h.nextTask()
        h.loop.start("new",1)
        stale.action()
        assertTrue(h.calls.isEmpty())
        assertEquals(listOf(0L),h.taskDelays())
        h.nextTask().action()
        h.loop.stop()
        assertTrue(h.taskDelays().isEmpty())
        assertEquals(0,h.deliveryCount())
    }

    @Test fun sampleFailureProducesUnavailableAndRetainsTheFixedDelay() {
        val h=Harness()
        h.sampling={ _,_ -> error("Backend unavailable") }
        h.loop.start("game",0)
        h.nextTask().action()
        h.deliver()
        assertEquals(listOf<Float?>(null),h.results)
        assertEquals(listOf(3_000L),h.taskDelays())
    }
}
