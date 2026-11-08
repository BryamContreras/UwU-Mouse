package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryInjectionState
import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class InterlacedCameraResetTest {
    private class Harness(held:Boolean=true,orderedContacts:Boolean=false,bindings:MappingBindings=MappingBindings(
        distinctCameraContacts=true,cameraLast=true,interlacedCameraHandoff=true)) {
        val geometry=MappingGeometry(1000f,2000f)
        var now=1000L
        var failStage=0
        var stopBeforeStage=0
        var lostReply=false
        var unknown=false
        var acceptCancel=true
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        val frames=mutableListOf<TouchFrame>()
        val ordinaryCalls=mutableListOf<TouchFrame>()
        val plans=mutableListOf<CameraResetSequence>()
        val composer=TouchComposer({++now},{frame ->
            ordinaryCalls+=frame;frames+=frame
            if(frame.action==TouchComposer.CANCEL && journal.snapshot().state==RecoveryInjectionState.RECOVERY_NEEDED) {
                val expected=requireNotNull(journal.cancellationFrame(frame.eventTime))
                check(expected.ids.contentEquals(frame.ids) && expected.xs.contentEquals(frame.xs) &&
                    expected.ys.contentEquals(frame.ys) && expected.downTime==frame.downTime)
                check(journal.cancelled(1,acceptCancel));acceptCancel
            } else {
                check(journal.prepare(1,frame))
                val accepted=frame.action!=TouchComposer.CANCEL || acceptCancel
                check(journal.acknowledged(1,frame,accepted));accepted
            }
        },resetSink={sequence ->
            plans+=sequence
            check(sequence.matchesPrevious(requireNotNull(journal.snapshot().activeFrame)))
            val handoffTime=++now
            var accepted=0;var attempted=0
            for((index,planned) in sequence.frames.withIndex()) {
                val stage=index+1
                if(stopBeforeStage==stage) break
                val time=if(sequence.kind==CameraResetKind.INTERLACED && index<2) handoffTime else ++now
                val frame=planned.copy(eventTime=time)
                check(journal.prepare(1,frame));frames+=frame;attempted=stage
                if(failStage==stage) {
                    if(lostReply) check(journal.fail(1)) else check(journal.acknowledged(1,frame,false))
                    break
                }
                check(journal.acknowledged(1,frame,true));accepted=stage
                now+=4L // The two handoff events retain their shared eventTime across real ACK waits.
            }
            CameraResetResult(accepted,attempted,!unknown)
        },orderedContacts=orderedContacts)
        val engine=MappingEngine(composer,geometry,bindings).apply {
            check(setCameraEnabled(true))
            if(held) { check(key(MappingEngine.W,true));check(mouseButton(1,true)) }
            check(relativeMouse(170f,0f))
        }
        fun interlace()=composer.interlaceCameraAndMove(MappingEngine.CAMERA_OWNER,
            geometry.cameraX,geometry.cameraY,geometry.cameraX+2f,geometry.cameraY)
    }

    @Test fun engineHandoffKeepsSortedIdsHeldContactsAndSharedDownUpTime() {
        val h=Harness()
        val held=h.composer.snapshot().dropLast(1)
        val downTime=h.frames.last().downTime
        assertTrue(h.composer.canInterlaceCamera)
        val count=h.ordinaryCalls.size
        repeat(6) { assertTrue(h.engine.relativeMouse(100f,0f)) }
        assertEquals(count,h.ordinaryCalls.size)
        assertEquals(6,h.plans.size)
        assertEquals(6L,h.engine.cameraRestarts)
        assertEquals(held,h.composer.snapshot().dropLast(1))
        val replacementIds=mutableListOf<Int>()
        for(sequence in h.plans) {
            assertEquals(CameraResetKind.INTERLACED,sequence.kind)
            val (down,up,move)=sequence.frames
            assertEquals(down.eventTime,up.eventTime)
            assertEquals(downTime,down.downTime);assertEquals(downTime,move.downTime)
            assertEquals(listOf(0,1,8,9),down.ids.toList())
            assertEquals(4,up.ids.size);assertEquals(3,move.ids.size)
            assertEquals(TouchComposer.POINTER_DOWN,down.action and 255)
            assertEquals(TouchComposer.POINTER_UP,up.action and 255)
            replacementIds+=down.ids[down.action ushr 8]
        }
        assertTrue(replacementIds.zipWithNext().all { (a,b) -> a!=b })
        assertEquals(770f,integratedX(h.frames),.001f) // Initial170 + all six original100 samples.
        assertTrue(h.composer.snapshot().none { it.owner.startsWith("handoff:") })
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun everyFailureRetainsTheExactPossibleSetAndTemporaryRoleUntilOldUpIsAcknowledged() {
        for(held in listOf(false,true)) for(stage in 1..3)
            for(lost in listOf(false,true)) for(cancel in listOf(false,true)) {
                val h=Harness(held)
                h.failStage=stage;h.lostReply=lost;h.acceptCancel=cancel
                val count=h.frames.size
                assertFalse(h.interlace())
                assertEquals(stage+1,h.frames.size-count)
                val attempted=h.plans.single().frames[stage-1]
                assertArrayEquals(attempted.ids,h.frames.last().ids)
                assertArrayEquals(attempted.xs,h.frames.last().xs,0f)
                assertArrayEquals(attempted.ys,h.frames.last().ys,0f)
                assertEquals(attempted.downTime,h.frames.last().downTime)
                assertTrue(h.composer.paused);assertEquals(!cancel,h.composer.releaseUnverified)
                if(!cancel) {
                    val possible=requireNotNull(h.composer.recoveryFrame())
                    assertArrayEquals(attempted.ids,possible.ids)
                    assertArrayEquals(attempted.xs,possible.xs,0f)
                    assertEquals(stage<3,h.composer.snapshot().any { it.owner=="handoff:camera" })
                    if(stage<3) assertEquals(2,h.composer.snapshot().count { it.id in 8..9 })
                    else assertEquals(1,h.composer.snapshot().count { it.id in 8..9 })
                    h.acceptCancel=true;assertTrue(h.engine.pause())
                }
                val after=h.frames.size
                assertFalse(h.engine.relativeMouse(1f,0f));assertEquals(after,h.frames.size)
                assertEquals(0,h.journal.snapshot().possibleContacts)
            }
    }

    @Test fun eachConfirmedPrefixProjectsItsActionIndexBeforeCancellation() {
        for(held in listOf(false,true)) for(stop in 1..3) {
            val h=Harness(held)
            val before=h.composer.snapshot()
            h.stopBeforeStage=stop;h.acceptCancel=false
            assertFalse(h.interlace())
            val expected=if(stop==1) before else {
                val plan=h.plans.single()
                val frame=requireNotNull(CameraResetSequence.postEvent(plan.frames[stop-2]))
                frame.ids.indices.map { index ->
                    val id=frame.ids[index]
                    val owner=before.firstOrNull { it.id==id }?.owner ?: if(stop==2) "handoff:camera" else "camera"
                    Contact(owner,id,frame.xs[index],frame.ys[index])
                }
            }
            assertEquals(expected,h.composer.snapshot())
            assertEquals(stop==2,h.composer.snapshot().any { it.owner=="handoff:camera" })
            h.acceptCancel=true;assertTrue(h.engine.pause())
        }
    }

    @Test fun unknownProgressCannotPublishCancelOrAnOrphanCandidate() {
        for(stop in 1..4) {
            val h=Harness()
            h.stopBeforeStage=stop;h.unknown=true
            val count=h.ordinaryCalls.size
            assertFalse(h.interlace())
            assertFalse(h.composer.recoveryCandidateKnown)
            assertTrue(h.composer.releaseUnverified);assertTrue(h.composer.paused)
            assertNull(h.composer.recoveryFrame())
            assertFalse(h.composer.cancelAll());assertFalse(h.composer.resume())
            assertEquals(count,h.ordinaryCalls.size)
            h.composer.acknowledgeBackendReset()
            assertEquals(0,h.composer.activeCount);assertTrue(h.composer.recoveryCandidateKnown)
        }
    }

    @Test fun fullContactBudgetFallsBackToSequentialWithoutAnEleventhPointer() {
        val touches=(100..107).map { PreparedTouch("touch:$it",500f,600f,key=it) }
        val h=Harness(held=false,bindings=MappingBindings(actionKey=null,actionButton=null,touches=touches,
            distinctCameraContacts=true,cameraLast=true,interlacedCameraHandoff=true))
        // Release camera before filling every low ID, then recreate it at the remaining high ID.
        assertTrue(h.engine.setCameraEnabled(false))
        assertTrue(h.engine.key(MappingEngine.W,true))
        touches.forEach { assertTrue(h.engine.key(requireNotNull(it.key),true)) }
        assertTrue(h.engine.setCameraEnabled(true));assertTrue(h.engine.relativeMouse(170f,0f))
        assertEquals(10,h.composer.activeCount);assertFalse(h.composer.canInterlaceCamera)
        assertTrue(h.engine.relativeMouse(100f,0f))
        assertEquals(CameraResetKind.SEQUENTIAL,h.plans.single().kind)
        assertTrue(h.frames.all { it.ids.size<=10 })
        assertEquals(10,h.composer.activeCount)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun occupiedOtherCameraIdAndUnsortedExistingIdsUseSequentialFallback() {
        for(unsorted in listOf(false,true)) {
            var now=1000L
            val plans=mutableListOf<CameraResetSequence>()
            val composer=TouchComposer({++now},{true},resetSink={plans+=it;CameraResetResult(3,3)})
            if(unsorted) {
                assertTrue(composer.begin("first",10f,20f));assertTrue(composer.begin("second",20f,30f))
                assertTrue(composer.end("first"));assertTrue(composer.begin("third",30f,40f))
            } else assertTrue(composer.begin("held8",10f,20f,preferredId=8))
            assertTrue(composer.begin(MappingEngine.CAMERA_OWNER,800f,860f,preferredId=if(unsorted) 8 else 9))
            val held=composer.snapshot().dropLast(1)
            assertFalse(composer.canInterlaceCamera)
            assertTrue(composer.interlaceCameraAndMove(MappingEngine.CAMERA_OWNER,690f,860f,692f,860f))
            assertEquals(CameraResetKind.SEQUENTIAL,plans.single().kind)
            assertEquals(held,composer.snapshot().dropLast(1))
        }
    }

    @Test fun existingOverlapTrialIsNotSilentlyReplacedBySequentialCompactTransport() {
        val h=Harness(bindings=MappingBindings(distinctCameraContacts=true,cameraLast=true,overlapCameraHandoff=true))
        val count=h.frames.size
        assertTrue(h.engine.relativeMouse(100f,0f))
        assertTrue(h.plans.isEmpty())
        assertEquals(listOf(TouchComposer.POINTER_DOWN,TouchComposer.POINTER_UP,TouchComposer.MOVE),
            h.frames.drop(count).map { it.action and 255 })
        assertTrue(h.engine.pause())
    }

    @Test fun defaultBindingsKeepTheExistingSequentialCompactPlan() {
        val h=Harness(bindings=MappingBindings(distinctCameraContacts=true,cameraLast=true))
        assertTrue(h.engine.relativeMouse(100f,0f))
        assertEquals(CameraResetKind.SEQUENTIAL,h.plans.single().kind)
    }

    @Test fun orderedTrialInsertsReusedLowIdWithoutReorderingExistingOwners() {
        val h=Harness(orderedContacts=true)
        val fire=h.composer.snapshot().single { it.owner==MappingEngine.FIRE_OWNER }
        assertTrue(h.engine.key(MappingEngine.W,false))
        val before=h.composer.snapshot()
        val start=h.frames.size
        assertTrue(h.engine.key(MappingEngine.W,true))
        val down=h.frames[start]
        assertEquals(TouchComposer.POINTER_DOWN,down.action and 255)
        assertEquals(listOf(0,1,8),down.ids.toList())
        assertEquals(before.map { it.id },down.ids.filterIndexed { index,_ -> index!=(down.action ushr 8) })
        assertEquals(fire,h.composer.snapshot().single { it.owner==MappingEngine.FIRE_OWNER })
        assertTrue(h.composer.canInterlaceCamera)
        assertTrue(h.engine.relativeMouse(100f,0f))
        assertEquals(CameraResetKind.INTERLACED,h.plans.single().kind)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun oversizedRawUsesExistingSegmentedFallbackAndPreservesItsEntireDelta() {
        val h=Harness()
        assertTrue(h.engine.relativeMouse(1500f,-1200f))
        assertTrue(h.plans.isEmpty()) // This raw cannot fit at the anchor as one three-frame plan.
        assertTrue(h.engine.cameraRestarts>1)
        assertEquals(1670f,integratedX(h.frames),.01f)
        assertFalse(h.composer.paused)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun shapeValidationRejectsChangedHeldCoordinatesOrderAndHandoffTimes() {
        val h=Harness();assertTrue(h.interlace())
        val plan=h.plans.single().frames
        val invalid=listOf(
            listOf(plan[0],plan[1].copy(xs=plan[1].xs.copyOf().apply { this[0]+=1f }),plan[2]),
            listOf(plan[0],plan[1].copy(eventTime=plan[1].eventTime+1L),plan[2]),
            listOf(plan[0],plan[1],plan[2].copy(xs=plan[2].xs.copyOf().apply { this[0]+=1f })),
            listOf(plan[0],plan[1],plan[2].copy(ids=plan[2].ids.reversedArray())),
            listOf(plan[0],plan[1],plan[2].copy(downTime=plan[2].downTime+1L)))
        for(frames in invalid) assertThrows(IllegalArgumentException::class.java) { CameraResetSequence(frames) }
    }

    @Test fun previousStateMatchingAndPostProjectionAreExactAndDetached() {
        val h=Harness()
        val previous=h.frames.last()
        assertTrue(h.interlace())
        val sequence=h.plans.single()
        assertTrue(sequence.matchesPrevious(previous))
        assertFalse(sequence.matchesPrevious(previous.copy(xs=previous.xs.copyOf().apply { this[lastIndex]+=1f })))
        assertFalse(sequence.matchesPrevious(previous.copy(ids=previous.ids.reversedArray())))
        assertFalse(sequence.matchesPrevious(previous.copy(eventTime=sequence.frames.first().eventTime+1L)))
        val projected=requireNotNull(CameraResetSequence.postEvent(sequence.frames[1]))
        assertEquals(listOf(0,1,9),projected.ids.toList())
        projected.ids[0]=7
        assertEquals(0,sequence.frames[1].ids[0])
    }

    private fun integratedX(frames:List<TouchFrame>):Float {
        val positions=mutableMapOf<Int,Float>();var sum=0f
        for(frame in frames) {
            val action=frame.action and 255;val index=frame.action ushr 8
            if(action==TouchComposer.DOWN || action==TouchComposer.POINTER_DOWN)
                positions[frame.ids[index]]=frame.xs[index]
            if(action==TouchComposer.MOVE) for(i in frame.ids.indices) if(frame.ids[i] in 8..9) {
                sum+=frame.xs[i]-requireNotNull(positions[frame.ids[i]]);positions[frame.ids[i]]=frame.xs[i]
            }
            if(action==TouchComposer.UP || action==TouchComposer.POINTER_UP) positions.remove(frame.ids[index])
        }
        return sum
    }
}
