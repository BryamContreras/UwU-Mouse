package dev.uwumouse.app.session

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

    @Test fun aRejectedClaimIsDiscardedInsteadOfBeingRelabeledForTheNewMode() {
        val gate=NativeMotionDispatchGate()
        gate.publish(route())
        var submissions=0
        val outcome=gate.dispatch(11,0) { previous ->
            submissions++
            gate.publish(route(generation=8)) // Lifecycle changes between claim and queue admission.
            assertEquals(7L,previous.generation)
            false
        }
        assertEquals(NativeMotionDispatchGate.Dispatch.STALE,outcome)
        assertEquals(1,submissions)
        assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatch(11,0) { current ->
            assertEquals(8L,current.generation)
            true
        })
        assertTrue(gate.defer())
        assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatch(11,0) {
            fail("A prior edge must prevent direct admission")
            true
        })
        gate.complete(null)
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

    @Test fun scalarMotionEligibilityKeepsWheelsKeysAndButtonOnlyFramesOnTheirOrderedPath() {
        fun eligible(kind:Int=0,time:Long=100L,x:Float=.25f,y:Float=-.5f,wheel:Float=0f,
            horizontal:Float=0f,buttons:Int=0,scan:Int=-1,down:Boolean=false,repeat:Boolean=false)=
            NativeMotionDispatchGate.isMotionOnlyFrame(kind,time,x,y,wheel,horizontal,buttons,scan,down,repeat)
        assertTrue(eligible())
        assertTrue(eligible(x=0f,buttons=31)) // Physical held buttons are checked against the published route.
        assertFalse(eligible(x=0f,y=0f)) // Button-only frame must reach main even with unchanged aggregate buttons.
        assertFalse(eligible(kind=1,scan=30,down=true))
        assertFalse(eligible(scan=30))
        assertFalse(eligible(down=true))
        assertFalse(eligible(repeat=true))
        assertFalse(eligible(wheel=1f))
        assertFalse(eligible(horizontal=-1f))
        assertFalse(eligible(time=-1))
        assertFalse(eligible(x=Float.NaN))
        assertFalse(eligible(y=Float.POSITIVE_INFINITY))
        assertFalse(eligible(buttons=32))
        assertFalse(eligible(kind=2))
    }

    @Test fun scalarAndFullBatchesKeepEveryDeltaAndCannotPassAnEarlierButtonOrKey() {
        val gate=NativeMotionDispatchGate()
        val queue=InputQueueBuffer()
        val main=ArrayDeque<()->Unit>()
        val expected=mutableListOf<String>()
        val received=mutableListOf<String>()
        val entry=InputQueueEntry()
        var sourceTime=1L
        var buttons=0
        var scalarDirect=0
        var batchDirect=0
        var deferred=0
        gate.publish(route())
        fun drain() {
            while(queue.pollInto(entry)) {
                assertEquals(7L,entry.generation)
                when(entry.type) {
                    3 -> {
                        assertEquals(42,entry.code)
                        received+="motion:${entry.lastSampleTimeNanos}:${entry.x.toFloat()}:${entry.y.toFloat()}"
                    }
                    8 -> {
                        assertEquals(42,entry.code)
                        val batch=requireNotNull(entry.motionBatch)
                        for(index in batch.timesNanos.indices)
                            received+="motion:${batch.timesNanos[index]}:${batch.xs[index]}:${batch.ys[index]}"
                    }
                    else -> received+="edge:${entry.type}:${entry.code}"
                }
            }
        }
        fun completeMain() { while(main.isNotEmpty()) main.removeFirst().invoke() }
        fun motion(size:Int,sourceButtons:Int) {
            val times=LongArray(size) { sourceTime++ }
            val xs=FloatArray(size) { if(it%2==0) .25f else -.5f }
            val ys=FloatArray(size) { -xs[it] }
            for(index in times.indices) expected+="motion:${times[index]}:${xs[index]}:${ys[index]}"
            val batch=if(size==1) null else RawMouseMotionBatch(times,xs,ys)
            val outcome=gate.dispatch(11,sourceButtons) { target ->
                assertEquals(42,target.pointerEpoch)
                if(batch==null) {
                    scalarDirect++
                    queue.offer(3,target.pointerEpoch,xs[0],ys[0],times[0],target.generation,times[0])
                } else {
                    batchDirect++
                    queue.offerMotionBatch(batch,times[0],target.generation,target.pointerEpoch)
                }
            }
            if(outcome==NativeMotionDispatchGate.Dispatch.DEFERRED) {
                deferred++
                assertTrue(gate.defer())
                main.addLast {
                    if(batch==null) assertTrue(queue.offer(3,42,xs[0],ys[0],times[0],7L,times[0]))
                    else assertTrue(queue.offerMotionBatch(batch,times[0],7L,42))
                    gate.complete(route(buttons))
                }
            } else assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,outcome)
        }
        repeat(100) { cycle ->
            motion(1,buttons)
            motion(256,buttons)
            assertTrue(gate.defer())
            val nextButtons=buttons xor 1
            expected+="edge:2:$cycle"
            main.addLast {
                buttons=nextButtons
                assertTrue(queue.offer(2,cycle,buttons.toFloat(),0f,sourceTime,7L))
                gate.complete(route(buttons))
            }
            // Even the unchanged old mask cannot bypass an edge that has already reached Binder.
            motion(1,buttons)
            motion(256,nextButtons)
            assertTrue(gate.defer())
            expected+="edge:1:$cycle"
            main.addLast {
                assertTrue(queue.offer(1,cycle,1f,0f,sourceTime,7L))
                gate.complete(route(buttons))
            }
            motion(1,nextButtons)
            completeMain()
            drain()
        }
        assertEquals(expected,received)
        assertEquals(100,scalarDirect)
        assertEquals(100,batchDirect)
        assertEquals(300,deferred)
        assertEquals(0,gate.pendingCallbacks)
        assertEquals(0,queue.size)
    }

    @Test fun aScalarClaimCannotInjectAfterItsCaptureOrModeWasInvalidated() {
        val gate=NativeMotionDispatchGate()
        val pointer=PointerTransitionGate().apply { begin(false) }
        val oldEpoch=requireNotNull(pointer.motionEpoch())
        gate.publish(NativeMotionDispatchGate.Route(11,7L,0,oldEpoch))
        var admitted=0
        assertEquals(NativeMotionDispatchGate.Dispatch.STALE,gate.dispatch(11,0) { claimed ->
            assertEquals(oldEpoch,claimed.pointerEpoch)
            pointer.invalidate()
            gate.publish(null)
            val valid=pointer.admitsMotion(claimed.pointerEpoch)
            if(valid) admitted++
            valid
        })
        assertEquals(0,admitted)
        assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatch(11,0) {
            fail("No scalar may inject while there is no active route")
            true
        })
        gate.publish(route(captureId=12,generation=8))
        assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatch(11,0) {
            fail("A previous capture must not inject into its replacement")
            true
        })
        assertEquals(0,admitted)
    }
}
