package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import dev.uwumouse.app.session.TouchTransitionProgress
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CompactTouchTransitionTest {
    private enum class Operation { BEGIN_FIRE,END_FIRE,END_LAST }
    private class Harness(val operation:Operation=Operation.BEGIN_FIRE,val compact:Boolean=true) {
        val ordinary=mutableListOf<TouchFrame>()
        val plans=mutableListOf<TouchTransitionSequence>()
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        var acceptCancel=true
        var validateJournal=true
        var result=TouchTransitionResult(2,2)
        var throwFromSink=false
        var mutateCallbackFrames=false
        val composer=TouchComposer({1000L},{ frame ->
            ordinary+=frame
            val accepted=frame.action!=TouchComposer.CANCEL || acceptCancel
            if(validateJournal) { check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,accepted)) }
            accepted
        },continuousCamera=true,orderedContacts=true,transitionSink=if(!compact) null else ({ sequence ->
            plans+=sequence
            if(throwFromSink) throw IllegalStateException("lost progress")
            if(validateJournal) for(frame in sequence.frames.take(result.acceptedCount)) {
                check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,true))
            }
            if(mutateCallbackFrames) sequence.frames.forEach { it.ids.fill(7);it.xs.fill(-999f) }
            result
        }))
        init {
            check(composer.begin(MappingEngine.CAMERA_OWNER,10f,20f,preferredId=9))
            if(operation==Operation.END_FIRE) check(composer.begin("fire",50f,60f))
            check(composer.beginMoveBatch());check(composer.move(MappingEngine.CAMERA_OWNER,30f,40f))
        }
        fun transition()=when(operation) {
            Operation.BEGIN_FIRE -> composer.begin("fire",50f,60f)
            Operation.END_FIRE -> composer.end("fire")
            Operation.END_LAST -> composer.end(MappingEngine.CAMERA_OWNER)
        }
        fun allFrames()=if(compact) ordinary.take(if(operation==Operation.END_FIRE) 2 else 1)+plans.flatMap {it.frames} else ordinary.toList()
    }

    private fun sameFrame(left:TouchFrame,right:TouchFrame) {
        assertEquals(left.action,right.action);assertEquals(left.downTime,right.downTime);assertEquals(left.eventTime,right.eventTime)
        assertArrayEquals(left.ids,right.ids);assertArrayEquals(left.xs,right.xs,0f);assertArrayEquals(left.ys,right.ys,0f)
    }

    @Test fun compactTransportPreservesEveryMoveAndEdgeAndTheOpenBatch() {
        for(operation in Operation.entries) {
            val compact=Harness(operation);val legacy=Harness(operation,compact=false)
            assertTrue(compact.composer.supportsTouchTransition);assertFalse(legacy.composer.supportsTouchTransition)
            assertTrue(compact.transition());assertTrue(legacy.transition())
            assertEquals(1,compact.plans.size)
            val expected=legacy.allFrames();val actual=compact.allFrames()
            assertEquals(expected.size,actual.size)
            expected.zip(actual).forEach { (a,b) -> sameFrame(a,b) }
            assertEquals(legacy.composer.snapshot(),compact.composer.snapshot())
            val ordinaryCount=compact.ordinary.size
            if(operation!=Operation.END_LAST) {
                assertTrue(compact.composer.move(MappingEngine.CAMERA_OWNER,31f,41f))
                assertEquals(ordinaryCount,compact.ordinary.size)
                assertTrue(compact.composer.endMoveBatch())
                assertEquals(ordinaryCount+1,compact.ordinary.size)
            } else {
                assertTrue(compact.composer.endMoveBatch())
                assertEquals(ordinaryCount,compact.ordinary.size)
            }
        }
    }

    @Test fun everyKnownPartialPrefixRetainsOnlyTheExactPossibleContactSetAndCannotReplay() {
        val partial=listOf(TouchTransitionResult(0,0),TouchTransitionResult(0,1),
            TouchTransitionResult(1,1),TouchTransitionResult(1,2))
        for(operation in Operation.entries) for(result in partial) for(cancelAccepted in listOf(false,true)) {
            val h=Harness(operation)
            val original=h.composer.snapshot().map { if(it.owner==MappingEngine.CAMERA_OWNER) it.copy(x=10f,y=20f) else it }
            h.result=result;h.acceptCancel=cancelAccepted;h.validateJournal=false
            val before=h.ordinary.size
            assertFalse(h.transition());assertTrue(h.composer.paused)
            assertEquals(!cancelAccepted,h.composer.releaseUnverified)
            assertEquals(before+1,h.ordinary.size)
            val plan=h.plans.single().frames
            val expected=when(result.attemptedCount) {
                0 -> TouchFrame(TouchComposer.MOVE,1000,1000,original.map {it.id}.toIntArray(),
                    original.map {it.x}.toFloatArray(),original.map {it.y}.toFloatArray())
                1 -> plan[0]
                else -> plan[1]
            }
            val cancel=h.ordinary.last()
            assertEquals(TouchComposer.CANCEL,cancel.action)
            assertArrayEquals(expected.ids,cancel.ids);assertArrayEquals(expected.xs,cancel.xs,0f)
            assertArrayEquals(expected.ys,cancel.ys,0f);assertEquals(expected.downTime,cancel.downTime)
            if(!cancelAccepted) {
                val recovery=requireNotNull(h.composer.recoveryFrame())
                assertArrayEquals(expected.ids,recovery.ids);assertArrayEquals(expected.xs,recovery.xs,0f)
                val expectedOwners=original.associate {it.id to it.owner}+mapOf(0 to "fire")
                h.composer.snapshot().forEach { assertEquals(expectedOwners[it.id],it.owner) }
            }
            val after=h.ordinary.size
            assertFalse(h.composer.endMoveBatch());assertFalse(h.transition())
            assertEquals(after,h.ordinary.size);assertEquals(1,h.plans.size)
        }
    }

    @Test fun lostFinalReplyIncludingSuccessfulUpRetainsLastPossibleAttempt() {
        for(operation in Operation.entries) for(attempted in 0..2) {
            val h=Harness(operation);h.validateJournal=false;h.acceptCancel=false
            val bytes=ByteBuffer.allocate(attempted*TouchTransitionProgress.RECORD_BYTES).apply {
                for(stage in 1..attempted) putInt(TouchTransitionProgress.MAGIC).putInt(stage).putInt(stage-1)
            }.array()
            h.result=TouchTransitionProgress.resolve(bytes,null)
            assertFalse(h.transition())
            val candidate=requireNotNull(h.composer.recoveryFrame())
            if(attempted>0) {
                val expected=h.plans.single().frames[attempted-1]
                assertArrayEquals(expected.ids,candidate.ids);assertArrayEquals(expected.xs,candidate.xs,0f)
            } else assertEquals(10f,h.composer.snapshot().last().x,0f)
            assertTrue(h.composer.releaseUnverified)
        }
    }

    @Test fun unknownProgressOrExceptionCannotInventCancellationOrOrphanRecovery() {
        for(throws in listOf(false,true)) {
            val h=Harness();h.validateJournal=false;h.result=TouchTransitionResult(0,0,progressKnown=false);h.throwFromSink=throws
            val ordinary=h.ordinary.size
            assertFalse(h.transition());assertEquals(ordinary,h.ordinary.size)
            assertTrue(h.composer.paused);assertTrue(h.composer.releaseUnverified)
            assertFalse(h.composer.recoveryCandidateKnown);assertNull(h.composer.recoveryFrame())
            assertEquals("TOUCH_TRANSITION_PROGRESS_UNKNOWN",h.composer.lastError)
            assertFalse(h.composer.cancelAll());assertFalse(h.composer.resume())
            h.composer.acknowledgeBackendReset();assertTrue(h.composer.recoveryCandidateKnown)
            assertTrue(h.composer.resume());assertEquals(0,h.composer.activeCount)
        }
    }

    @Test fun callbackMutationCannotCorruptTheLocalRecoveryPlan() {
        val h=Harness();h.validateJournal=false;h.acceptCancel=false
        h.result=TouchTransitionResult(1,2);h.mutateCallbackFrames=true
        assertFalse(h.transition())
        val recovery=requireNotNull(h.composer.recoveryFrame())
        assertArrayEquals(intArrayOf(0,9),recovery.ids)
        assertArrayEquals(floatArrayOf(50f,30f),recovery.xs,0f)
    }

    @Test fun duplicateBeginAndUnknownEndKeepTheirLegacyFlushAndNeverCompact() {
        val duplicate=Harness()
        assertTrue(duplicate.composer.begin(MappingEngine.CAMERA_OWNER,31f,41f))
        assertTrue(duplicate.plans.isEmpty())
        assertEquals(listOf(TouchComposer.DOWN,TouchComposer.MOVE),duplicate.ordinary.map {it.action})
        assertTrue(duplicate.composer.endMoveBatch())
        assertEquals(3,duplicate.ordinary.size)
        val unknown=Harness()
        assertTrue(unknown.composer.end("missing"));assertTrue(unknown.plans.isEmpty())
        assertEquals(listOf(TouchComposer.DOWN,TouchComposer.MOVE),unknown.ordinary.map {it.action})
    }

    @Test fun returningToSubmittedPositionsDoesNotIntroduceARedundantMoveOrCompactCall() {
        val h=Harness()
        assertTrue(h.composer.move(MappingEngine.CAMERA_OWNER,10f,20f))
        assertTrue(h.transition());assertTrue(h.plans.isEmpty())
        assertEquals(listOf(TouchComposer.DOWN,TouchComposer.POINTER_DOWN),h.ordinary.map {it.action})
        assertTrue(h.composer.endMoveBatch())
    }

    @Test fun continuingOutsideCameraRetainsItsIdWhileAddingAndLiftingAnOrdinaryContact() {
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({1000L},{frames+=it;true},continuousCamera=true,orderedContacts=true,
            transitionSink={ plan -> frames+=plan.frames;TouchTransitionResult(2,2) })
        assertTrue(composer.begin(MappingEngine.CAMERA_OWNER,10f,20f))
        assertTrue(composer.beginMoveBatch());assertTrue(composer.move(MappingEngine.CAMERA_OWNER,1200f,-100f))
        assertTrue(composer.begin("fire",50f,60f));assertEquals(9,composer.snapshot().last().id)
        assertTrue(composer.move(MappingEngine.CAMERA_OWNER,1300f,-200f));assertTrue(composer.end("fire"))
        assertEquals(listOf(Contact(MappingEngine.CAMERA_OWNER,9,1300f,-200f)),composer.snapshot())
        assertTrue(composer.endMoveBatch())
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        for(frame in frames) { assertTrue(journal.prepare(1,frame));assertTrue(journal.acknowledged(1,frame,true)) }
    }
}
