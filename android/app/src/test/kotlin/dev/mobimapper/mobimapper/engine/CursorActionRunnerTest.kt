package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.session.PointerTransitionGate
import org.junit.Assert.*
import org.junit.Test

class CursorActionRunnerTest {
    private data class Job(val task:()->Unit,var cancelled:Boolean=false)
    private class Rig(cursor:Boolean=false) {
        var now=1L
        val frames=mutableListOf<TouchFrame>();val jobs=mutableListOf<Job>()
        val composer=TouchComposer({ now++ },{ frames.add(it);true })
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f))
        val gate=PointerTransitionGate().apply { begin(cursor) }
        var ticket:Int?=null;var destination=false;var finished=false
        val action=PreparedCursorAction("cursor:one",300f,400f,"key",40)
        val runner=CursorActionRunner(listOf(action,action.copy(owner="cursor:two",triggerKind="mouse",code=4)),
            { gate.requestCommand()?.let { ticket=it;true } ?: false },
            { delay,task -> assertEquals(20L,delay);val job=Job(task);jobs.add(job);{ job.cancelled=true } },
            { owner,x,y -> composer.begin(owner,x,y) },{ composer.end(it) })
        init { if(cursor) engine.enterCursorMode() else engine.setCameraEnabled(true) }
        fun transition() {
            destination=gate.requestedCursor(ticket!!)!!
            if(destination) engine.enterCursorMode() else engine.leaveCursorMode()
            assertTrue(runner.tap { assertTrue(it);finished=true })
        }
        fun releaseTap() { val job=jobs.removeAt(0);if(!job.cancelled) job.task() }
        fun acknowledge() { assertTrue(finished);gate.acknowledge(ticket!!,destination);runner.retry() }
    }
    @Test fun mapEndsMovementCameraAndFireThenTapsOnceBeforeCursorAcknowledgement() {
        val r=Rig();r.engine.key(MappingEngine.W,true);r.engine.mouseButton(1,true);r.engine.relativeMouse(5f,2f)
        assertEquals(3,r.composer.activeCount)
        r.runner.input("key",40,true);r.transition()
        assertEquals(listOf("cursor:one"),r.composer.snapshot().map { it.owner })
        assertEquals(300f,r.frames.last().xs.single(),0f)
        assertTrue(r.gate.pending);r.releaseTap();assertEquals(0,r.composer.activeCount)
        assertTrue(r.gate.pending);r.acknowledge();assertFalse(r.gate.pending)
        assertTrue(r.gate.baseCursor);assertEquals(1L,r.runner.completed)
        r.engine.leaveCursorMode();r.engine.key(MappingEngine.W,false)
        assertEquals(0,r.composer.activeCount)
    }
    @Test fun cursorToControlAlsoProducesExactlyOneTapWithoutRestoringOldInputs() {
        val r=Rig(true);r.engine.cursorButton(true,150f,200f)
        r.runner.input("mouse",4,true);r.transition();assertFalse(r.destination)
        assertEquals(listOf("cursor:two"),r.composer.snapshot().map { it.owner })
        r.releaseTap();r.acknowledge();assertFalse(r.gate.baseCursor)
        assertEquals(0,r.composer.activeCount);assertTrue(r.engine.cameraEnabled)
    }
    @Test fun oneFreshCommandWaitsForExistingAckWithoutTogglingBaseEarly() {
        val r=Rig();val previous=r.gate.toggleBase()!!
        r.runner.input("key",40,true);assertNull(r.runner.currentOwner);assertEquals(1,r.runner.queued)
        assertTrue(r.gate.baseCursor)
        r.gate.acknowledge(previous,true);r.runner.retry()
        assertEquals("cursor:one",r.runner.currentOwner);assertFalse(r.gate.baseCursor)
    }
    @Test fun waitingCommandRemainsPendingAfterTriggerUpAcrossTheAckRetryGap() {
        val r=Rig();val previous=r.gate.toggleBase()!!
        r.runner.input("key",40,true);r.runner.input("key",40,false)
        assertTrue(r.runner.pending);assertNull(r.runner.currentOwner)
        r.gate.acknowledge(previous,true)
        assertFalse(r.gate.pending);assertTrue(r.runner.pending)
        r.runner.retry();assertTrue(r.gate.pending);assertTrue(r.runner.pending)
        r.transition();r.releaseTap();r.acknowledge()
        assertFalse(r.runner.pending);assertEquals(1L,r.runner.completed)
    }
    @Test fun busyAndRepeatedDownsCannotBuildABacklog() {
        val r=Rig();r.runner.input("key",40,true)
        repeat(20) { r.runner.input("key",40,true) }
        r.runner.input("mouse",4,true)
        assertEquals(1L,r.runner.rejected);assertEquals(0,r.runner.queued)
        r.transition();r.releaseTap();r.acknowledge()
        r.runner.input("mouse",4,true);assertNull(r.runner.currentOwner)
        r.runner.input("mouse",4,false);r.runner.input("mouse",4,true)
        assertEquals("cursor:two",r.runner.currentOwner)
    }
    @Test fun physicalMouseUpAfterPointerCancellationClearsLatchForNextPress() {
        val r=Rig();r.runner.input("mouse",4,true);r.transition()
        r.runner.cancel(releaseContact=true);r.gate.invalidate()
        r.runner.input("mouse",4,false);r.gate.begin(true)
        r.runner.input("mouse",4,true)
        assertEquals("cursor:two",r.runner.currentOwner)
    }
    @Test fun pauseClearsPendingCommandAndInvalidatesTapCallbackWithoutReplay() {
        val r=Rig();r.runner.input("key",40,true);r.transition();val stale=r.jobs.single().task
        r.runner.cancel();r.gate.invalidate();r.composer.cancelAll()
        val count=r.frames.size;stale();r.runner.retry()
        assertEquals(count,r.frames.size);assertEquals(0,r.composer.activeCount)
        assertFalse(r.finished);assertFalse(r.runner.pending)
        r.gate.begin(false);r.runner.input("key",40,true);assertNull(r.runner.currentOwner)
    }
    @Test fun heldOverrideKeepsCursorButCommandStillTapsAndUpdatesPersistentBase() {
        val r=Rig();val hold=r.gate.hold(true)!!;r.gate.acknowledge(hold,true)
        r.runner.input("key",40,true);r.transition();assertTrue(r.destination)
        r.releaseTap();r.acknowledge();assertTrue(r.gate.baseCursor)
        assertNull(r.gate.hold(false))
    }
    @Test fun f5DuringTapIsAppliedOnlyAfterTapAndCaptureAck() {
        val r=Rig();r.runner.input("key",40,true);r.transition()
        assertNull(r.gate.toggleBase());r.releaseTap()
        val next=r.gate.acknowledge(r.ticket!!,true)!!
        assertEquals(false,r.gate.requestedCursor(next));assertTrue(r.gate.pending)
        assertEquals(1L,r.runner.completed)
    }
}
