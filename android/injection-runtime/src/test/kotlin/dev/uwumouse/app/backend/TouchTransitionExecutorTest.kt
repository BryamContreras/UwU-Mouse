package dev.uwumouse.app.backend

import dev.uwumouse.app.backend.recovery.RecoveryInjectionState
import dev.uwumouse.app.backend.recovery.RecoveryJournal
import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import dev.uwumouse.app.engine.TouchFrame
import dev.uwumouse.app.engine.TouchTransitionResult
import dev.uwumouse.app.engine.TouchTransitionSequence
import dev.uwumouse.app.session.TouchTransitionProgress
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class TouchTransitionExecutorTest {
    private enum class Edge { ADD,LIFT,LAST_UP }
    private class Harness(edge:Edge=Edge.ADD):TouchTransitionExecutor.Port {
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        val order=mutableListOf<String>()
        val progress=ByteArrayOutputStream()
        val sent=mutableListOf<TouchFrame>()
        val admittedFrames=mutableListOf<TouchFrame>()
        val recoveries=mutableListOf<String>()
        val previous:TouchFrame
        val sequence:TouchTransitionSequence
        var stage=0
        var deniedAt=0
        var contextExceptionAt=0
        var prepareDeniedAt=0
        var writeFailureAt=0
        var rollbackFails=false
        var rollbackThrows=false
        var sendFailureAt=0
        var rejectedAt=0
        var ackMismatchAt=0
        var ackExceptionAt=0
        var cleanupThrows=false
        var recoveryThrows=false
        var awaitingAck=false
        var cleanupCalls=0
        var rollbackCalls=0
        var nowMillis=120L
        var slowAdmissionAt=0
        var journalPrepareCalls=0

        init {
            val down=TouchFrame(TouchComposer.DOWN,100,100,intArrayOf(9),floatArrayOf(500f),floatArrayOf(200f))
            check(journal.prepare(1,down));check(journal.acknowledged(1,down,true))
            if(edge==Edge.LIFT) {
                val fire=TouchFrame(TouchComposer.POINTER_DOWN,100,101,intArrayOf(0,9),floatArrayOf(100f,500f),floatArrayOf(100f,200f))
                check(journal.prepare(1,fire));check(journal.acknowledged(1,fire,true))
            }
            previous=requireNotNull(journal.snapshot().activeFrame)
            val move=previous.copy(action=TouchComposer.MOVE,eventTime=110,
                xs=previous.xs.copyOf().apply { this[lastIndex]=1200f },
                ys=previous.ys.copyOf().apply { this[lastIndex]=-100f })
            val contactEdge=when(edge) {
                Edge.ADD -> TouchFrame(TouchComposer.POINTER_DOWN,100,111,intArrayOf(0,9),floatArrayOf(100f,1200f),floatArrayOf(100f,-100f))
                Edge.LIFT -> move.copy(action=TouchComposer.POINTER_UP,eventTime=111)
                Edge.LAST_UP -> move.copy(action=TouchComposer.UP,eventTime=111)
            }
            sequence=TouchTransitionSequence(listOf(move,contactEdge))
        }

        override fun contextAllowsStage():Boolean {
            val next=stage+1
            order+="context:$next"
            check(!awaitingAck) { "Another stage entered before the previous ACK" }
            if(contextExceptionAt==next) throw IOException("context unavailable")
            if(slowAdmissionAt==next) nowMillis+=TouchEventAgePolicy.MAX_AGE_MILLIS+1
            return deniedAt!=next
        }
        override fun admissionLost() {
            cleanupCalls++;order+="admissionLost"
            if(cleanupThrows) throw IOException("cleanup failed")
        }
        override fun prepare(frame:TouchFrame):Boolean {
            stage++;order+="prepare:$stage"
            check(!awaitingAck)
            if(prepareDeniedAt==stage || !TouchEventAgePolicy.admits(frame,nowMillis)) return false
            journalPrepareCalls++
            return journal.prepare(1,frame)
        }
        override fun writeAttempt(stage:Int,acceptedBefore:Int) {
            order+="record:$stage:$acceptedBefore"
            check(stage==this.stage && acceptedBefore==stage-1)
            check(journal.snapshot().attempted!=null)
            if(writeFailureAt==stage) throw IOException("atomic progress write failed")
            progress.write(ByteBuffer.allocate(12).putInt(TouchTransitionProgress.MAGIC).putInt(stage).putInt(acceptedBefore).array())
        }
        override fun abortUnsent(frame:TouchFrame):Boolean {
            rollbackCalls++;order+="abort:$stage"
            check(!awaitingAck)
            if(rollbackThrows) throw IOException("rollback failed")
            return !rollbackFails && journal.abortUnsent(1,frame)
        }
        override fun send(frame:TouchFrame):Boolean {
            order+="send:$stage"
            check(!awaitingAck);awaitingAck=true
            assertEquals(stage*TouchTransitionProgress.RECORD_BYTES,progress.size())
            sent+=frame
            // Simulate failure after the command can already have reached Android.
            if(sendFailureAt==stage) throw IOException("reply lost after send")
            order+="workerAck:$stage"
            return rejectedAt!=stage
        }
        override fun acknowledge(frame:TouchFrame,accepted:Boolean):Boolean {
            order+="journalAck:$stage:$accepted"
            check(awaitingAck);awaitingAck=false
            if(ackExceptionAt==stage) throw IOException("journal acknowledgement failed")
            if(ackMismatchAt==stage) return false
            return journal.acknowledged(1,frame,accepted)
        }
        override fun admitted(frame:TouchFrame) {
            check(!awaitingAck && journal.snapshot().attempted==null)
            order+="admitted:$stage";admittedFrames+=frame
        }
        override fun recoveryNeeded(reason:String) {
            order+="recovery:$reason";recoveries+=reason
            journal.fail(1)
            if(recoveryThrows) throw IOException("recovery scheduling failed")
        }
        fun run()=TouchTransitionExecutor.execute(sequence,this)
    }

    private fun sameContacts(expected:TouchFrame,actual:TouchFrame?) {
        assertNotNull(actual)
        val present=requireNotNull(actual)
        assertEquals(expected.downTime,present.downTime)
        assertArrayEquals(expected.ids,present.ids);assertArrayEquals(expected.xs,present.xs,0f);assertArrayEquals(expected.ys,present.ys,0f)
    }

    @Test fun everyStageWritesProgressBeforeSendAndWaitsForItsAcknowledgement() {
        val h=Harness()
        assertEquals(2,h.run())
        assertEquals(listOf("context:1","prepare:1","record:1:0","send:1","workerAck:1","journalAck:1:true","admitted:1",
            "context:2","prepare:2","record:2:1","send:2","workerAck:2","journalAck:2:true","admitted:2"),h.order)
        assertEquals(2,h.admittedFrames.size);assertTrue(h.recoveries.isEmpty());assertEquals(0,h.rollbackCalls)
        assertEquals(TouchTransitionResult(2,2),TouchTransitionProgress.resolve(h.progress.toByteArray(),2))
        sameContacts(h.sequence.frames.last(),h.journal.snapshot().activeFrame)
    }

    @Test fun liftingOneOrTheLastContactCommitsOnlyTheAcknowledgedPostEventSet() {
        for(edge in Edge.entries) {
            val h=Harness(edge);assertEquals(2,h.run())
            val expected=TouchTransitionSequence.postEvent(h.sequence.frames.last())
            if(expected==null) assertNull(h.journal.snapshot().activeFrame) else sameContacts(expected,h.journal.snapshot().activeFrame)
            assertNull(h.journal.snapshot().attempted)
        }
    }

    @Test fun contextDenialOrExceptionStopsBeforeTheNextJournalPreparation() {
        for(stage in 1..2) for(exception in listOf(false,true)) {
            val h=Harness()
            if(exception) h.contextExceptionAt=stage else h.deniedAt=stage
            assertEquals(stage-1,h.run());assertEquals(1,h.cleanupCalls)
            assertEquals(stage-1,h.sent.size);assertEquals(stage-1,h.admittedFrames.size)
            assertFalse(h.order.contains("prepare:$stage"));assertNull(h.journal.snapshot().attempted)
            sameContacts(if(stage==1) h.previous else h.sequence.frames[0],h.journal.snapshot().activeFrame)
            assertEquals(TouchTransitionResult(stage-1,stage-1),TouchTransitionProgress.resolve(h.progress.toByteArray(),stage-1))
        }
    }

    @Test fun deniedPreparationCreatesNoProgressRecordOrWorkerAttempt() {
        for(stage in 1..2) {
            val h=Harness();h.prepareDeniedAt=stage
            assertEquals(stage-1,h.run());assertEquals(stage-1,h.sent.size)
            assertFalse(h.order.contains("record:$stage:${stage-1}"));assertEquals(0,h.cleanupCalls)
            assertTrue(h.recoveries.isEmpty());assertNull(h.journal.snapshot().attempted)
        }
    }

    @Test fun slowFocusOrMetadataCannotAdmitAnEventThatAgedPastTheBudgetDuringTheQuery() {
        for(stage in 1..2) {
            val h=Harness();h.slowAdmissionAt=stage
            // Both planned frames were fresh before context/focus work started.
            assertTrue(h.sequence.frames.all { TouchEventAgePolicy.admits(it,h.nowMillis) })
            assertEquals(stage-1,h.run())
            assertEquals(stage-1,h.journalPrepareCalls)
            assertEquals(stage-1,h.sent.size);assertEquals(stage-1,h.admittedFrames.size)
            assertFalse(h.order.contains("record:$stage:${stage-1}"))
            assertNull(h.journal.snapshot().attempted);assertTrue(h.recoveries.isEmpty())
            sameContacts(if(stage==1) h.previous else h.sequence.frames[0],h.journal.snapshot().activeFrame)
            assertEquals(TouchTransitionResult(stage-1,stage-1),TouchTransitionProgress.resolve(h.progress.toByteArray(),stage-1))
        }
    }

    @Test fun failedAtomicProgressWriteRollsBackOnlyTheUnsentCurrentPreparation() {
        for(stage in 1..2) {
            val h=Harness();h.writeFailureAt=stage
            assertEquals(stage-1,h.run());assertEquals(1,h.rollbackCalls)
            assertEquals(stage-1,h.sent.size);assertNull(h.journal.snapshot().attempted)
            sameContacts(if(stage==1) h.previous else h.sequence.frames[0],h.journal.snapshot().activeFrame)
            assertEquals(RecoveryInjectionState.ACTIVE,h.journal.snapshot().state)
            assertTrue(h.recoveries.isEmpty())
            assertEquals(TouchTransitionResult(stage-1,stage-1),TouchTransitionProgress.resolve(h.progress.toByteArray(),stage-1))
        }
    }

    @Test fun unsuccessfulOrThrowingUnsentRollbackFreezesRecoveryWithoutSendingThatStage() {
        for(stage in 1..2) for(throws in listOf(false,true)) {
            val h=Harness();h.writeFailureAt=stage
            h.rollbackThrows=throws;h.rollbackFails=!throws
            assertEquals(stage-1,h.run());assertEquals(stage-1,h.sent.size)
            assertEquals(listOf("PROGRESS_ROLLBACK_FAILED"),h.recoveries)
            assertEquals(RecoveryInjectionState.RECOVERY_NEEDED,h.journal.snapshot().state)
            sameContacts(h.sequence.frames[stage-1],h.journal.snapshot().attempted)
        }
    }

    @Test fun rejectedWorkerReplyRetainsTheWholeAttemptAndDoesNotPrepareAnotherFrame() {
        for(edge in Edge.entries) for(stage in 1..2) {
            val h=Harness(edge);h.rejectedAt=stage
            assertEquals(-stage,h.run());assertEquals(stage,h.sent.size);assertEquals(stage-1,h.admittedFrames.size)
            assertEquals(listOf("INJECTION_REJECTED"),h.recoveries);assertEquals(0,h.rollbackCalls)
            assertEquals(RecoveryInjectionState.RECOVERY_NEEDED,h.journal.snapshot().state)
            sameContacts(h.sequence.frames[stage-1],h.journal.snapshot().attempted)
            sameContacts(h.sequence.frames[stage-1],h.journal.cancellationFrame(200))
            assertEquals(TouchTransitionResult(stage-1,stage),TouchTransitionProgress.resolve(h.progress.toByteArray(),-stage))
        }
    }

    @Test fun lostPostSendReplyPreservesEveryPossiblePointerIncludingTheLastUp() {
        for(edge in Edge.entries) for(stage in 1..2) {
            val h=Harness(edge);h.sendFailureAt=stage
            assertEquals(-stage,h.run());assertEquals(stage,h.sent.size);assertEquals(stage-1,h.admittedFrames.size)
            assertEquals(listOf("PIPE"),h.recoveries);assertEquals(0,h.rollbackCalls)
            sameContacts(h.sequence.frames[stage-1],h.journal.snapshot().attempted)
            sameContacts(h.sequence.frames[stage-1],h.journal.cancellationFrame(200))
            assertEquals(TouchTransitionResult(stage-1,stage),TouchTransitionProgress.resolve(h.progress.toByteArray(),null))
        }
    }

    @Test fun journalMismatchAndJournalExceptionAreDistinctAndNeverMarkTheFrameAdmitted() {
        for(stage in 1..2) for(exception in listOf(false,true)) {
            val h=Harness()
            if(exception) h.ackExceptionAt=stage else h.ackMismatchAt=stage
            assertEquals(-stage,h.run());assertEquals(stage-1,h.admittedFrames.size)
            assertEquals(listOf(if(exception) "PIPE" else "JOURNAL_ACK_MISMATCH"),h.recoveries)
            sameContacts(h.sequence.frames[stage-1],h.journal.snapshot().attempted)
            assertEquals(0,h.rollbackCalls)
        }
    }

    @Test fun finalBinderReplyLossDoesNotConvertTheLastAcknowledgedEventIntoReplay() {
        for(edge in Edge.entries) {
            val h=Harness(edge);assertEquals(2,h.run())
            assertEquals(TouchTransitionResult(1,2),TouchTransitionProgress.resolve(h.progress.toByteArray(),null))
            assertEquals(2,h.sent.size);assertEquals(2,h.admittedFrames.size)
            assertNull(h.journal.snapshot().attempted)
        }
    }

    @Test fun admissionCleanupOrRecoverySchedulingFailuresPropagateToTheCallerFinally() {
        val cleanup=Harness();cleanup.deniedAt=1;cleanup.cleanupThrows=true
        assertThrows(IOException::class.java) { cleanup.run() }
        assertEquals(0,cleanup.sent.size)
        val recovery=Harness();recovery.sendFailureAt=1;recovery.recoveryThrows=true
        assertThrows(IOException::class.java) { recovery.run() }
        assertEquals(1,recovery.sent.size);assertEquals(0,recovery.rollbackCalls)
    }
}
