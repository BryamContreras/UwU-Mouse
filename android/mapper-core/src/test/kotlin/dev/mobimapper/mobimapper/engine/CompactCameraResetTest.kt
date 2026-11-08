package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryInjectionState
import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class CompactCameraResetTest {
    private class Harness(val continuous:Boolean=false,val held:Boolean=true) {
        val geometry=MappingGeometry(1000f,2000f)
        val frames=mutableListOf<TouchFrame>()
        val ordinaryCalls=mutableListOf<TouchFrame>()
        val plans=mutableListOf<CameraResetSequence>()
        var now=1000L
        var failedStage=0
        var lostReply=false
        var acceptCancel=true
        var stopBeforeStage=0
        var unknownProgress=false
        var mutateCallbackFrames=false
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,
            if(continuous) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT)) }
        val composer=TouchComposer({++now},{frame ->
            ordinaryCalls+=frame;frames+=frame
            if(frame.action==TouchComposer.CANCEL && journal.snapshot().state==RecoveryInjectionState.RECOVERY_NEEDED) {
                val expected=requireNotNull(journal.cancellationFrame(frame.eventTime))
                check(expected.ids.contentEquals(frame.ids) && expected.xs.contentEquals(frame.xs) &&
                    expected.ys.contentEquals(frame.ys) && expected.downTime==frame.downTime)
                check(journal.cancelled(1,acceptCancel));acceptCancel
            } else {
                check(journal.prepare(1,frame))
                if(frame.action==TouchComposer.CANCEL && !acceptCancel) {
                    check(journal.acknowledged(1,frame,false));false
                } else { check(journal.acknowledged(1,frame,true));true }
            }
        },continuousCamera=continuous,resetSink={sequence ->
            plans+=sequence
            var accepted=0;var attempted=0
            for((index,planned) in sequence.frames.withIndex()) {
                val stage=index+1
                if(stopBeforeStage==stage) break
                // Backend uses actual per-stage receipt time while preserving the planned downTime.
                val frame=planned.copy(eventTime=++now)
                check(journal.prepare(1,frame));frames+=frame;attempted=stage
                if(failedStage==stage) {
                    if(lostReply) check(journal.fail(1)) else check(journal.acknowledged(1,frame,false))
                    break
                }
                check(journal.acknowledged(1,frame,true));accepted=stage
            }
            if(mutateCallbackFrames) sequence.frames.forEach { it.xs.fill(-999f);it.ids.fill(7) }
            CameraResetResult(accepted,attempted,!unknownProgress)
        })
        val engine=MappingEngine(composer,geometry,MappingBindings(continuousCamera=continuous,
            distinctCameraContacts=!continuous,cameraLast=true)).apply {
            check(setCameraEnabled(true))
            if(held) { check(key(MappingEngine.W,true));check(mouseButton(1,true)) }
            check(relativeMouse(if(continuous) 4000f else 170f,0f))
        }
        fun reset():Boolean=composer.resetCameraAndMove(MappingEngine.CAMERA_OWNER,geometry.cameraX,geometry.cameraY,
            geometry.cameraX+2f,geometry.cameraY,if(continuous) 9 else 9)
    }

    @Test fun compactSuccessChangesOnlyCameraAndPreservesOpenMoveBatch() {
        for(continuous in listOf(false,true)) for(held in listOf(false,true)) {
            val h=Harness(continuous,held)
            val before=h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }
            val oldDownTime=h.frames.last().downTime
            val regularCount=h.ordinaryCalls.size
            assertTrue(h.composer.beginMoveBatch())
            assertTrue(h.reset())
            assertEquals(regularCount,h.ordinaryCalls.size)
            assertEquals(1,h.plans.size)
            assertEquals(before,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
            assertEquals(692f,h.composer.snapshot().last().x,0f)
            val plan=h.plans.single().frames
            if(held) assertEquals(oldDownTime,plan[1].downTime)
            else assertTrue(plan[1].downTime>=plan[0].eventTime)
            assertTrue(h.composer.move(MappingEngine.CAMERA_OWNER,693f,h.geometry.cameraY))
            assertEquals(regularCount,h.ordinaryCalls.size)
            assertTrue(h.composer.endMoveBatch())
            assertEquals(regularCount+1,h.ordinaryCalls.size)
            assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
        }
    }

    @Test fun eachAmbiguousOrRejectedStageRetainsExactCandidateAndStopsRemainingPlan() {
        for(continuous in listOf(false,true)) for(held in listOf(false,true))
            for(stage in 1..3) for(lostReply in listOf(false,true)) for(cancel in listOf(false,true)) {
                val h=Harness(continuous,held)
                h.failedStage=stage;h.lostReply=lostReply;h.acceptCancel=cancel
                val count=h.frames.size
                assertFalse(h.reset())
                assertEquals(stage+1,h.frames.size-count) // Attempted prefix and one CANCEL only.
                assertTrue(h.composer.paused)
                assertEquals(!cancel,h.composer.releaseUnverified)
                val attempted=h.plans.single().frames[stage-1]
                val cancelled=h.frames.last()
                assertEquals(TouchComposer.CANCEL,cancelled.action)
                assertArrayEquals(attempted.ids,cancelled.ids)
                assertArrayEquals(attempted.xs,cancelled.xs,0f)
                assertArrayEquals(attempted.ys,cancelled.ys,0f)
                assertEquals(attempted.downTime,cancelled.downTime)
                val after=h.frames.size
                assertFalse(h.reset());assertFalse(h.engine.relativeMouse(1f,0f))
                assertEquals(after,h.frames.size)
                if(!cancel) {
                    val recovery=requireNotNull(h.composer.recoveryFrame())
                    assertArrayEquals(attempted.ids,recovery.ids)
                    assertArrayEquals(attempted.xs,recovery.xs,0f)
                    h.acceptCancel=true;assertTrue(h.engine.pause())
                }
                assertEquals(0,h.journal.snapshot().possibleContacts)
            }
    }

    @Test fun stoppedBeforeEachStageReconstructsOnlyTheConfirmedPostEventSet() {
        for(held in listOf(false,true)) for(stop in 1..3) {
            val h=Harness(held=held)
            h.stopBeforeStage=stop;h.acceptCancel=false
            assertFalse(h.reset())
            val count=if(stop==2 && !held) 0 else if(stop==2) 2 else if(held) 3 else 1
            assertEquals(count,h.composer.activeCount)
            if(count>0) {
                val candidate=requireNotNull(h.composer.recoveryFrame())
                val expected=when(stop) {
                    1 -> h.plans.single().frames[0]
                    2 -> h.plans.single().frames[0].let { it.copy(ids=it.ids.dropLast(1).toIntArray(),
                        xs=it.xs.dropLast(1).toFloatArray(),ys=it.ys.dropLast(1).toFloatArray()) }
                    else -> h.plans.single().frames[1]
                }
                assertArrayEquals(expected.ids,candidate.ids)
                assertArrayEquals(expected.xs,candidate.xs,0f)
                h.acceptCancel=true;assertTrue(h.engine.pause())
            } else assertNull(h.composer.recoveryFrame())
        }
    }

    @Test fun unknownProgressDoesNotInventCancelOrOrphanCandidate() {
        for(stop in 1..4) {
            val h=Harness()
            h.stopBeforeStage=stop;h.unknownProgress=true
            val regular=h.ordinaryCalls.size
            assertFalse(h.reset())
            assertFalse(h.composer.recoveryCandidateKnown)
            assertTrue(h.composer.paused);assertTrue(h.composer.releaseUnverified)
            assertEquals("CAMERA_RESET_PROGRESS_UNKNOWN",h.composer.lastError)
            assertNull(h.composer.recoveryFrame())
            assertFalse(h.composer.cancelAll());assertFalse(h.composer.resume())
            assertEquals(regular,h.ordinaryCalls.size)
            // Only an independently confirmed guardian release/reset may clear this state.
            h.composer.acknowledgeBackendReset()
            assertTrue(h.composer.recoveryCandidateKnown)
            assertFalse(h.composer.releaseUnverified)
            assertEquals(0,h.composer.activeCount)
        }
    }

    @Test fun callbackCannotMutateTheLocalExactRecoveryPlan() {
        val h=Harness()
        h.failedStage=3;h.acceptCancel=false;h.mutateCallbackFrames=true
        assertFalse(h.reset())
        val recovery=requireNotNull(h.composer.recoveryFrame())
        assertEquals(692f,recovery.xs.last(),0f)
        assertEquals(9,recovery.ids.last())
        h.acceptCancel=true;assertTrue(h.engine.pause())
    }

    @Test fun boundedCommonRestartUsesOneCompactCallAndKeepsFullRawDelta() {
        val h=Harness()
        val regular=h.ordinaryCalls.size
        assertTrue(h.engine.relativeMouse(100f,-30f))
        assertEquals(1,h.plans.size)
        assertEquals(regular,h.ordinaryCalls.size)
        assertEquals(790f,h.composer.snapshot().last().x,0f)
        assertEquals(h.geometry.cameraY-30f,h.composer.snapshot().last().y,0f)
        assertEquals(1L,h.engine.cameraRestarts)
    }

    @Test fun compactCapableTransportDoesNotChangeTheContinuousGestureDuringMovement() {
        val h=Harness(continuous=true)
        val controls=h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }
        val downTime=h.frames.last().downTime
        assertTrue(h.composer.supportsCameraReset)
        assertTrue(h.engine.relativeMouse(300f,0f))
        assertTrue(h.engine.relativeMouse(5000f,-7000f))
        assertTrue(h.plans.isEmpty())
        assertEquals(0L,h.engine.cameraRestarts)
        assertEquals(0L,h.engine.idleCameraRetirements)
        assertEquals(9990f,h.composer.snapshot().last().x,0f)
        assertEquals(h.geometry.cameraY-7000f,h.composer.snapshot().last().y,0f)
        assertEquals(9,h.composer.snapshot().last().id)
        assertEquals(downTime,h.frames.last().downTime)
        assertEquals(controls,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        val cameraDowns=h.frames.count { (it.action and 255) in listOf(TouchComposer.DOWN,TouchComposer.POINTER_DOWN) &&
            it.ids[it.action ushr 8]==9 }
        assertEquals(1,cameraDowns)
        assertFalse(h.frames.any { (it.action and 255) in listOf(TouchComposer.UP,TouchComposer.POINTER_UP) })
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun continuousRawReversalsKeepThePersistentFingerWithAndWithoutMoveBatching() {
        val exact=Harness(continuous=true);val batched=Harness(continuous=true)
        assertTrue(batched.composer.beginMoveBatch())
        repeat(5000) { index ->
            val x=(index%5-2)*2.25f;val y=(index%7-3)*1.5f
            assertTrue(exact.engine.relativeMouse(x,y));assertTrue(batched.engine.relativeMouse(x,y))
        }
        assertTrue(batched.composer.endMoveBatch())
        assertTrue(exact.plans.isEmpty());assertTrue(batched.plans.isEmpty())
        assertEquals(0L,exact.engine.cameraRestarts);assertEquals(0L,batched.engine.cameraRestarts)
        assertEquals(exact.composer.snapshot(),batched.composer.snapshot())
        fun edges(h:Harness)=h.frames.filter { it.action!=TouchComposer.MOVE }.map {
            listOf(it.action,it.ids.toList(),it.xs.toList(),it.ys.toList())
        }
        assertEquals(edges(exact),edges(batched))
        assertTrue(batched.frames.size<exact.frames.size)
        for(h in listOf(exact,batched)) {
            assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
        }
    }

    @Test fun compactCapableContinuousIntegratesALargeRawOnceAndStillFailsClosedAtCoordinateLimit() {
        val h=Harness(continuous=true)
        val start=h.frames.size
        assertTrue(h.engine.relativeMouse(20000f,-20000f))
        val move=h.frames.drop(start).single()
        assertEquals(TouchComposer.MOVE,move.action)
        assertEquals(24690f,move.xs.last(),0f)
        assertEquals(h.geometry.cameraY-20000f,move.ys.last(),0f)
        assertTrue(h.plans.isEmpty())
        assertFalse(h.engine.relativeMouse(TouchCoordinatePolicy.LIMIT*2,0f))
        assertTrue(h.plans.isEmpty())
        assertTrue(h.composer.paused);assertFalse(h.engine.cameraEnabled)
        assertArrayEquals(move.ids,h.frames.last().ids)
        assertArrayEquals(move.xs,h.frames.last().xs,0f)
        assertArrayEquals(move.ys,h.frames.last().ys,0f)
        assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun continuousReservedNineRemainsAvailableWithASingleContactBudget() {
        var now=1000L
        val composer=TouchComposer({++now},{true},maxContacts=1,continuousCamera=true,
            resetSink={ CameraResetResult(3,3) })
        assertTrue(composer.begin(MappingEngine.CAMERA_OWNER,690f,860f))
        assertEquals(9,composer.snapshot().single().id)
        assertTrue(composer.resetCameraAndMove(MappingEngine.CAMERA_OWNER,690f,860f,692f,860f,9))
        assertEquals(listOf(Contact(MappingEngine.CAMERA_OWNER,9,692f,860f)),composer.snapshot())
    }
}
