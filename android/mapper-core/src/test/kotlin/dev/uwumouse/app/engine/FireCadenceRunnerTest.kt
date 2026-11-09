package dev.uwumouse.app.engine

import dev.uwumouse.app.profile.PreparedFireCadence
import org.junit.Assert.*
import org.junit.Test

class FireCadenceRunnerTest {
    private data class Job(val at:Long,val task:()->Unit,var cancelled:Boolean=false)
    private class Rig(speed:Float=100f,delayMillis:Long=500L,durationMillis:Long=0L) {
        var now=1L
        var allowed=true
        var accept=true
        val jobs=mutableListOf<Job>()
        val moves=mutableListOf<Pair<Long,Float>>()
        val runner=FireCadenceRunner(PreparedFireCadence(verticalPixelsPerSecond=speed,delayMillis=delayMillis,durationMillis=durationMillis),{now},
            {delay,task -> val job=Job(now+delay,task);jobs+=job;{job.cancelled=true}},
            {dy -> moves+=now to dy;accept},{allowed})
        fun advance(milliseconds:Long) {
            val end=now+milliseconds
            while(true) {
                val next=jobs.filter { !it.cancelled && it.at<=end }.minByOrNull { it.at } ?: break
                jobs.remove(next);now=next.at;next.task()
            }
            now=end
        }
        fun lateTick(afterMillis:Long) {
            val next=jobs.filterNot { it.cancelled }.minByOrNull { it.at } ?: error("No scheduled tick")
            jobs.remove(next);now+=afterMillis;next.task()
        }
        fun liveJobs()=jobs.count { !it.cancelled }
    }

    @Test fun customDelayAndDurationStopPreciselyWhileKeepingThePhysicalPressLatched() {
        val r=Rig(delayMillis=250,durationMillis=35)
        r.runner.input("mouse",1,true)
        r.advance(249);assertTrue(r.moves.isEmpty());assertFalse(r.runner.active)
        r.advance(1);assertTrue(r.runner.active);assertTrue(r.moves.isEmpty())
        r.advance(34);assertTrue(r.runner.running);assertEquals(3.2,r.runner.totalY,.0001)
        r.advance(1);assertEquals(3.5,r.runner.totalY,.0001)
        assertFalse(r.runner.running);assertFalse(r.runner.pending);assertTrue(r.runner.held)
        r.runner.input("mouse",1,true);r.advance(1000);assertEquals(3.5,r.runner.totalY,.0001)
        r.runner.input("mouse",1,false);r.runner.input("mouse",1,true);r.advance(285)
        assertEquals(7.0,r.runner.totalY,.0001)
    }

    @Test fun zeroDelayStartsImmediatelyAndZeroDurationContinuesUntilRelease() {
        val r=Rig(delayMillis=0,durationMillis=0)
        r.runner.input("mouse",1,true);r.advance(0)
        assertTrue(r.runner.active)
        r.advance(160);assertEquals(16.0,r.runner.totalY,.0001);assertTrue(r.runner.running)
        r.runner.input("mouse",1,false);r.advance(100)
        assertEquals(16.0,r.runner.totalY,.0001);assertFalse(r.runner.running)
    }

    @Test fun lateWakeupNeverIntegratesPastTheConfiguredDuration() {
        val r=Rig(delayMillis=20,durationMillis=25)
        r.runner.input("mouse",1,true);r.advance(20);r.lateTick(1000)
        assertEquals(2.5,r.runner.totalY,.0001)
        assertFalse(r.runner.running);assertFalse(r.runner.pending);assertTrue(r.runner.held)
    }

    @Test fun startsOnlyAfterTheFullHalfSecondAndMovesOnlyOnClockDrivenTicks() {
        val r=Rig()
        assertFalse(r.runner.input("key",1,true));assertFalse(r.runner.input("mouse",2,true))
        assertTrue(r.runner.input("mouse",1,true));assertTrue(r.runner.running);assertFalse(r.runner.active)
        r.advance(499);assertTrue(r.moves.isEmpty())
        r.advance(1);assertTrue(r.moves.isEmpty());assertTrue(r.runner.active)
        r.advance(16);assertEquals(1,r.moves.size);assertEquals(1.6f,r.moves.single().second,.00001f)
        assertEquals(1L,r.runner.ticks);assertEquals(1,r.liveJobs())
    }

    @Test fun signedPreparedSpeedIsUsedDirectlyInDisplayCoordinates() {
        for(speed in listOf(-100f,100f)) {
            val r=Rig(speed);r.runner.input("mouse",1,true);r.advance(516)
            assertEquals(speed*.016f,r.moves.single().second,.00001f)
        }
    }

    @Test fun normalIrregularTicksIntegrateElapsedTimeWithoutDependingOnMousePolling() {
        val regular=Rig();val irregular=Rig()
        for(r in listOf(regular,irregular)) {r.runner.input("mouse",1,true);r.advance(500)}
        regular.advance(400)
        repeat(16) { irregular.lateTick(25) }
        assertEquals(40.0,regular.runner.totalY,.0001)
        assertEquals(regular.runner.totalY,irregular.runner.totalY,.0001)
        assertEquals(0L,irregular.runner.skippedElapsedMillis)
    }

    @Test fun duplicateDownNeverRestartsTheInitialDelayOrTheActiveCadence() {
        val r=Rig();r.runner.input("mouse",1,true);r.advance(400)
        repeat(20) {r.runner.input("mouse",1,true)}
        r.advance(116);assertEquals(1L,r.runner.ticks)
        val due=r.jobs.single { !it.cancelled }.at
        repeat(20) {r.runner.input("mouse",1,true)}
        assertEquals(due,r.jobs.single { !it.cancelled }.at)
    }

    @Test fun physicalUpBeforeWarmupCancelsAndStaleCallbacksCannotMoveAnything() {
        val r=Rig();r.runner.input("mouse",1,true)
        val stale=r.jobs.single().task
        r.advance(499);assertTrue(r.runner.input("mouse",1,false,canStart=false))
        stale();r.advance(5000)
        assertTrue(r.moves.isEmpty());assertFalse(r.runner.held);assertFalse(r.runner.pending);assertFalse(r.runner.running)
    }

    @Test fun physicalUpAfterActivationStopsImmediatelyWithoutATailOrButtonPulses() {
        val r=Rig();r.runner.input("mouse",1,true);r.advance(550)
        val before=r.moves.toList();val stale=r.jobs.single { !it.cancelled }.task
        r.runner.input("mouse",1,false);stale();r.advance(3000)
        assertEquals(before,r.moves);assertFalse(r.runner.active);assertFalse(r.runner.pending)
    }

    @Test fun pauseAndCursorCancellationRetainTheLatchUntilPhysicalUpAndFreshDown() {
        val r=Rig();r.runner.input("mouse",1,true);r.advance(516)
        val stale=r.jobs.single { !it.cancelled }.task
        r.runner.cancel();val before=r.moves.size
        stale();r.runner.input("mouse",1,true);r.advance(2000)
        assertEquals(before,r.moves.size);assertTrue(r.runner.held)
        r.runner.input("mouse",1,false,canStart=false);r.runner.input("mouse",1,true)
        r.advance(516);assertEquals(before+1,r.moves.size)
    }

    @Test fun aPressRejectedByStartGateCannotReplayWhenTheGateLaterOpens() {
        val r=Rig();r.runner.input("mouse",1,true,canStart=false)
        r.runner.input("mouse",1,true);r.advance(2000)
        assertTrue(r.moves.isEmpty());assertFalse(r.runner.pending)
        r.runner.input("mouse",1,false,canStart=false);r.runner.input("mouse",1,true);r.advance(516)
        assertEquals(1,r.moves.size)
    }

    @Test fun generationOrContextGateStopsNormallyWithoutReportingAnInjectionError() {
        val r=Rig();r.runner.input("mouse",1,true);r.advance(516)
        r.allowed=false;r.advance(16)
        assertFalse(r.runner.running);assertFalse(r.runner.pending);assertNull(r.runner.lastError)
        assertEquals(0L,r.runner.rejected)
        val before=r.moves.size;r.allowed=true;r.runner.input("mouse",1,true);r.advance(2000)
        assertEquals(before,r.moves.size)
    }

    @Test fun aLongStallAppliesAtMostOneBoundedTickAndDropsItsOldTimeDebt() {
        val r=Rig();r.runner.input("mouse",1,true);r.advance(500)
        r.lateTick(5000)
        assertEquals(1,r.moves.size);assertEquals(3.2f,r.moves.single().second,.00001f)
        assertEquals(4968L,r.runner.skippedElapsedMillis)
        assertEquals(1,r.liveJobs())
        r.advance(16);assertEquals(2,r.moves.size);assertEquals(1.6f,r.moves.last().second,.00001f)
    }

    @Test fun failedCameraMovementStopsUntilFreshPhysicalPress() {
        val r=Rig();r.accept=false;r.runner.input("mouse",1,true);r.advance(516)
        assertEquals(1L,r.runner.rejected);assertEquals("FIRE_CADENCE_CAMERA_REJECTED",r.runner.lastError)
        assertEquals(0L,r.runner.ticks);assertEquals(0.0,r.runner.totalY,0.0)
        assertFalse(r.runner.pending);r.accept=true;r.runner.input("mouse",1,true);r.advance(2000)
        assertEquals(1,r.moves.size)
    }

    @Test fun zeroSpeedCreatesNoTimerOrDefaultMovement() {
        val r=Rig(0f);r.runner.input("mouse",1,true);r.advance(10000)
        assertTrue(r.jobs.isEmpty());assertTrue(r.moves.isEmpty());assertFalse(r.runner.running)
        assertEquals(0L,r.runner.ticks)
    }

    @Test fun reentrantCancellationFromChangedCannotLeaveAnotherScheduledTask() {
        var now=1L;var scheduled=0;var cancelled=0
        lateinit var runner:FireCadenceRunner
        runner=FireCadenceRunner(PreparedFireCadence(verticalPixelsPerSecond=100f),{now},
            {_,_ -> scheduled++;{cancelled++}},{true},changed={ if(runner.running) runner.cancel() })
        runner.input("mouse",1,true)
        assertEquals(1,scheduled);assertEquals(1,cancelled)
        assertFalse(runner.running);assertFalse(runner.pending)
    }

    @Test fun changedObservesTheFinalPendingStateAtStartWarmupTickAndCancellation() {
        var now=1L
        val jobs=mutableListOf<Job>()
        val observed=mutableListOf<Triple<Boolean,Boolean,Long>>()
        lateinit var runner:FireCadenceRunner
        runner=FireCadenceRunner(PreparedFireCadence(verticalPixelsPerSecond=100f),{now},
            {delay,task -> val job=Job(now+delay,task);jobs+=job;{job.cancelled=true}},{true},
            changed={observed+=Triple(runner.running,runner.pending,runner.ticks)})
        runner.input("mouse",1,true)
        assertEquals(Triple(true,true,0L),observed.last())
        fun runNext() {
            val next=jobs.first { !it.cancelled };jobs.remove(next);now=next.at;next.task()
        }
        runNext() // Warmup has elapsed; no movement until the first elapsed tick.
        assertEquals(Triple(true,true,0L),observed.last())
        runNext()
        assertEquals(Triple(true,true,1L),observed.last())
        runner.cancel()
        assertEquals(Triple(false,false,1L),observed.last())
        assertTrue(jobs.all { it.cancelled })
    }

    @Test fun backwardMonotonicClockNeverBecomesAnOppositeDirectionMove() {
        val r=Rig();r.runner.input("mouse",1,true);r.advance(516)
        val before=r.moves.toList()
        val task=r.jobs.single { !it.cancelled };r.jobs.remove(task);r.now-=10;task.task()
        assertEquals(before,r.moves);assertEquals("FIRE_CADENCE_CLOCK_INVALID",r.runner.lastError)
        assertFalse(r.runner.pending)
    }
}
