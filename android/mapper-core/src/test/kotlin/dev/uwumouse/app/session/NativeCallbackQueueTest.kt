package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class NativeCallbackQueueTest {
    @Test fun firstCallbackSchedulesImmediatelyAndABurstSharesThatDrain() {
        val queue=NativeCallbackQueue<Int>(512)
        assertEquals(NativeCallbackQueue.Offer.SCHEDULE,queue.offer(0))
        for(value in 1 until 512) assertEquals(NativeCallbackQueue.Offer.QUEUED,queue.offer(value))
        assertEquals(NativeCallbackQueue.Offer.FULL,queue.offer(512))
        assertEquals(NativeCallbackQueue.Metrics(512,512),queue.metrics())
        for(value in 0 until 512) assertEquals(value,queue.poll())
        assertNull(queue.poll())
        assertFalse(queue.finishDrain())
        assertEquals(NativeCallbackQueue.Offer.SCHEDULE,queue.offer(512))
    }

    @Test fun arrivalDuringDeliveryCannotCreateADuplicateDrainOrLoseItsWakeup() {
        val queue=NativeCallbackQueue<String>()
        assertEquals(NativeCallbackQueue.Offer.SCHEDULE,queue.offer("first"))
        assertEquals("first",queue.poll())
        // The last callback is still being delivered when Binder supplies more work.
        assertEquals(NativeCallbackQueue.Offer.QUEUED,queue.offer("during delivery"))
        assertTrue(queue.finishDrain())
        assertEquals("during delivery",queue.poll())
        assertFalse(queue.finishDrain())
        assertEquals(NativeCallbackQueue.Offer.SCHEDULE,queue.offer("after completion"))
    }

    @Test fun budgetYieldsKeepOneOwnerAndFifoAcrossWraparound() {
        val queue=NativeCallbackQueue<Int>(7)
        var next=0
        var delivered=0
        repeat(1000) {
            while(next-delivered<7) {
                val outcome=queue.offer(next++)
                assertEquals(if(next-delivered==1) NativeCallbackQueue.Offer.SCHEDULE else NativeCallbackQueue.Offer.QUEUED,outcome)
            }
            repeat(3) { assertEquals(delivered++,queue.poll()) }
            assertTrue(queue.finishDrain())
            // The reposted drain still owns scheduling while another producer fills space.
            repeat(3) { assertEquals(NativeCallbackQueue.Offer.QUEUED,queue.offer(next++)) }
            while(delivered<next) assertEquals(delivered++,queue.poll())
            assertFalse(queue.finishDrain())
        }
        assertEquals(NativeCallbackQueue.Metrics(0,7),queue.metrics())
    }

    @Test fun clicksAndFollowingMotionStayOrderedWhileMainIsBusyThenDirectMotionResumes() {
        val gate=NativeMotionDispatchGate()
        val queue=NativeCallbackQueue<(Boolean)->Unit>()
        val received=mutableListOf<String>()
        val expected=mutableListOf<String>()
        var mainButtons=0
        var sourceButtons=0
        var scheduled=0
        var direct=0
        fun route()=NativeMotionDispatchGate.Route(11,7,mainButtons,42)
        fun defer(name:String,buttons:Int?=null) {
            assertTrue(gate.defer())
            val result=queue.offer { deliver ->
                try {
                    if(deliver) {
                        if(buttons!=null) mainButtons=buttons
                        received+=name
                    }
                } finally { gate.complete(if(deliver) route() else null) }
            }
            assertNotEquals(NativeCallbackQueue.Offer.FULL,result)
            if(result==NativeCallbackQueue.Offer.SCHEDULE) scheduled++
        }
        fun motion(name:String) {
            expected+=name
            val outcome=gate.dispatch(11,sourceButtons) { received+=name;direct++;true }
            if(outcome==NativeMotionDispatchGate.Dispatch.DEFERRED) defer(name)
            else assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,outcome)
        }
        gate.publish(route())
        repeat(1000) { cycle ->
            motion("before:$cycle")
            // A whole clicking burst arrives while main cannot run, including reversals.
            repeat(8) { edge ->
                sourceButtons=sourceButtons xor 1
                val name="button:$cycle:$edge:$sourceButtons"
                expected+=name;defer(name,sourceButtons)
                repeat(4) { sample -> motion("motion:$cycle:$edge:$sample") }
            }
            assertNull(gate.directRoute(11,sourceButtons))
            // A budgeted drain must not enable direct input halfway through an older burst.
            repeat(7) { requireNotNull(queue.poll())(true) }
            assertTrue(queue.finishDrain())
            assertNull(gate.directRoute(11,sourceButtons))
            while(true) { val callback=queue.poll() ?: break;callback(true) }
            assertFalse(queue.finishDrain())
            assertEquals(0,gate.pendingCallbacks)
            assertNotNull(gate.directRoute(11,sourceButtons))
        }
        assertEquals(expected,received)
        assertEquals(1000,scheduled)
        assertEquals(1000,direct)
        assertEquals(40,queue.metrics().maxDepth)
    }

    @Test fun failedHandlerSubmissionBalancesEveryReservationWithoutDeliveringInput() {
        val gate=NativeMotionDispatchGate(capacity=3)
        val queue=NativeCallbackQueue<(Boolean)->Unit>(3)
        var capture=11L
        var generation=7L
        var samplesPending=0
        var delivered=0
        fun route()=NativeMotionDispatchGate.Route(capture,generation,0,42)
        gate.publish(route())
        repeat(3) {
            assertTrue(gate.defer());samplesPending+=256
            val originalCapture=capture
            queue.offer { deliver ->
                try {
                    if(deliver && originalCapture==capture) delivered++
                } finally {
                    samplesPending-=256
                    gate.complete(if(deliver) route() else null)
                }
            }
        }
        assertFalse(gate.defer())
        gate.publish(null);capture=12;generation=8
        for(callback in queue.abandon()) callback(false)
        assertEquals(0,delivered)
        assertEquals(0,samplesPending)
        assertEquals(0,gate.pendingCallbacks)
        assertEquals(0,queue.metrics().depth)
        assertNull(gate.directRoute(12,0))
        gate.publish(route())
        assertEquals(route(),gate.directRoute(12,0))
        assertEquals(NativeCallbackQueue.Offer.SCHEDULE,queue.offer { deliver -> if(deliver) delivered++ })
        requireNotNull(queue.poll())(true)
        assertFalse(queue.finishDrain())
        assertEquals(1,delivered)
    }

    @Test fun earlierQueuedCallbacksAreDroppedAfterCaptureLossWithoutRelabelingTheirInput() {
        val queue=NativeCallbackQueue<(Boolean)->Unit>()
        val gate=NativeMotionDispatchGate()
        var currentCapture=11L
        var delivered=0
        repeat(20) {
            assertTrue(gate.defer())
            val capture=currentCapture
            queue.offer { deliver ->
                try { if(deliver && capture==currentCapture) delivered++ }
                finally { gate.complete(null) }
            }
        }
        currentCapture=12;gate.publish(null)
        while(true) { val callback=queue.poll() ?: break;callback(true) }
        assertFalse(queue.finishDrain())
        assertEquals(0,delivered)
        assertEquals(0,gate.pendingCallbacks)
    }

    @Test fun deferredCallbacksObservePauseAndMenuOwnershipAtDeliveryInsteadOfEnqueueTime() {
        val queue=NativeCallbackQueue<(Boolean)->Unit>()
        val gate=NativeMotionDispatchGate()
        var capture=11L
        var active=true
        var menu=false
        var gameEffects=0
        var menuEffects=0
        fun currentRoute()=if(active && !menu) NativeMotionDispatchGate.Route(capture,7,0,42) else null
        fun enqueueMotion() {
            assertTrue(gate.defer())
            val originalCapture=capture
            queue.offer { deliver ->
                try {
                    if(deliver && originalCapture==capture) {
                        // Model NativeSessionHost's delivery-time routing. A paused game
                        // receives no mapping; a menu can still own its physical pointer.
                        if(menu) menuEffects++ else if(active) gameEffects++
                    }
                } finally { gate.complete(if(deliver) currentRoute() else null) }
            }
        }
        gate.publish(currentRoute())
        enqueueMotion();enqueueMotion()
        // Lifecycle/UI work wins before main's first drain. Queuing did not capture
        // the earlier active state or pre-authorize the callbacks for the game.
        active=false;gate.publish(null)
        while(true) { val callback=queue.poll() ?: break;callback(true) }
        assertFalse(queue.finishDrain())
        assertEquals(0,gameEffects)
        assertEquals(0,menuEffects)
        assertNull(gate.directRoute(capture,0))
        menu=true
        enqueueMotion();enqueueMotion()
        while(true) { val callback=queue.poll() ?: break;callback(true) }
        assertFalse(queue.finishDrain())
        assertEquals(0,gameEffects)
        assertEquals(2,menuEffects)
        assertEquals(0,gate.pendingCallbacks)
        assertNull(gate.directRoute(capture,0))
    }
}
