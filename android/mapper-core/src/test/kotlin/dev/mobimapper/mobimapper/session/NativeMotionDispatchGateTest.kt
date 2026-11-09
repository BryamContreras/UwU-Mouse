package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class NativeMotionDispatchGateTest {
    private fun route(buttons:Int=0,generation:Long=7L,captureId:Long=11L)=
        NativeMotionDispatchGate.Route(captureId,generation,buttons,42)

    @Test fun noEarlierCallbackCanBeOvertakenEvenAfterMainPublishesANewerButtonState() {
        val gate=NativeMotionDispatchGate()
        gate.publish(route())
        assertEquals(route(),gate.directRoute(11,0))
        assertTrue(gate.defer()) // M1 DOWN has reached Binder, but main is occupied.
        assertTrue(gate.defer()) // The following movement must use main as well.
        assertNull(gate.directRoute(11,0))
        gate.complete(route(buttons=1)) // Main queued DOWN; movement is still pending.
        assertNull(gate.directRoute(11,1))
        gate.complete(route(buttons=1))
        assertEquals(route(buttons=1),gate.directRoute(11,1))
        assertNull(gate.directRoute(11,0))
    }

    @Test fun interleavedDirectAndDeferredBurstsKeepEverySampleAndEdgeInTheirSourceOrder() {
        val gate=NativeMotionDispatchGate()
        val queue=InputQueueBuffer()
        val main=ArrayDeque<()->Unit>()
        val expected=mutableListOf<Pair<Int,Int>>()
        val actual=mutableListOf<Pair<Int,Int>>()
        val entry=InputQueueEntry()
        var buttons=0
        var direct=0
        var deferred=0
        gate.publish(route())
        fun poll() {
            while(queue.pollInto(entry)) {
                assertEquals(7L,entry.generation)
                actual+=if(entry.type==8) 8 to requireNotNull(entry.motionBatch).xs.single().toInt()
                    else entry.type to entry.code
            }
        }
        repeat(10_000) { index ->
            if(index%19==0) {
                assertTrue(gate.defer())
                expected+=2 to index
                main.addLast {
                    buttons=buttons xor 1
                    assertTrue(queue.offer(2,index,buttons.toFloat(),0f,index.toLong(),7L))
                    gate.complete(route(buttons))
                }
            }
            val batch=RawMouseMotionBatch(longArrayOf(index.toLong()),floatArrayOf(index.toFloat()),floatArrayOf(1f))
            expected+=8 to index
            // Binder carries the physical state even while main has yet to see its edge.
            val sourceButtons=((index/19)+1) and 1
            val candidate=gate.directRoute(11,sourceButtons)
            if(candidate!=null) {
                direct++
                assertTrue(queue.offerMotionBatch(batch,index.toLong(),candidate.generation))
            } else {
                deferred++
                assertTrue(gate.defer())
                main.addLast {
                    assertTrue(queue.offerMotionBatch(batch,index.toLong(),7L))
                    gate.complete(route(buttons))
                }
            }
            if(index%13==0) while(main.isNotEmpty()) main.removeFirst().invoke()
            poll()
        }
        while(main.isNotEmpty()) main.removeFirst().invoke()
        poll()
        assertEquals(expected,actual)
        assertTrue(direct>0)
        assertTrue(deferred>0)
        assertEquals(0,gate.pendingCallbacks)
    }

    @Test fun captureAndGenerationChangesCannotRelabelAnAlreadyClaimedSample() {
        val gate=NativeMotionDispatchGate()
        gate.publish(route())
        val previous=requireNotNull(gate.directRoute(11,0))
        gate.publish(null)
        assertNull(gate.directRoute(11,0))
        gate.publish(route(generation=8,captureId=12))
        assertNull(gate.directRoute(11,0))
        assertEquals(7L,previous.generation)
        assertEquals(11L,previous.captureId)
        assertEquals(8L,requireNotNull(gate.directRoute(12,0)).generation)
    }

    @Test fun rejectedOverflowDoesNotConsumeACompletionOrGrantDirectDispatch() {
        val gate=NativeMotionDispatchGate(capacity=2)
        gate.publish(route())
        assertTrue(gate.defer());assertTrue(gate.defer());assertFalse(gate.defer())
        assertEquals(2,gate.pendingCallbacks)
        gate.complete(null);gate.complete(null)
        assertEquals(0,gate.pendingCallbacks)
        assertNull(gate.directRoute(11,0))
        assertThrows(IllegalStateException::class.java) { gate.complete(route()) }
    }

    @Test fun queuedMotionCannotCrossACompleteCursorRoundTripWithinTheSameSession() {
        val pointer=PointerTransitionGate().apply { begin(false) }
        val oldEpoch=requireNotNull(pointer.motionEpoch())
        val queue=InputQueueBuffer()
        val batch=RawMouseMotionBatch(longArrayOf(10),floatArrayOf(1f),floatArrayOf(2f))
        assertTrue(queue.offerMotionBatch(batch,20,7L,oldEpoch))
        val enter=requireNotNull(pointer.toggleBase())
        assertNull(pointer.motionEpoch())
        assertFalse(pointer.admitsMotion(oldEpoch))
        assertNull(pointer.acknowledge(enter,true))
        val leave=requireNotNull(pointer.toggleBase())
        assertNull(pointer.acknowledge(leave,false))
        val current=requireNotNull(pointer.motionEpoch())
        assertNotEquals(oldEpoch,current)
        val entry=InputQueueEntry()
        assertTrue(queue.pollInto(entry))
        assertEquals(7L,entry.generation)
        assertEquals(oldEpoch,entry.code)
        assertFalse("Old deltas must not move the newly resumed camera",pointer.admitsMotion(entry.code))
        assertTrue(queue.offerMotionBatch(batch,21,7L,current))
        assertTrue(queue.pollInto(entry))
        assertTrue(pointer.admitsMotion(entry.code))
        pointer.invalidate()
        assertFalse(pointer.admitsMotion(current))
    }
}
