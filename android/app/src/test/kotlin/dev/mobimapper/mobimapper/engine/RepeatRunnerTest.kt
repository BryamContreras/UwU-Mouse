package dev.mobimapper.mobimapper.engine

import org.junit.Assert.*
import org.junit.Test

class RepeatRunnerTest {
    private data class Job(val at:Long,val task:()->Unit,var cancelled:Boolean=false)
    private class Rig {
        var now=1L
        val frames=mutableListOf<TouchFrame>()
        val jobs=mutableListOf<Job>()
        val composer=TouchComposer({ now },{ frames.add(it);true })
        val first=PreparedRepeat("repeat:one",300f,400f,"key",40,100)
        val second=PreparedRepeat("repeat:two",500f,600f,"mouse",4,150)
        val runner=RepeatRunner(listOf(first,second),{ now },
            { delay,task -> val job=Job(now+delay,task);jobs.add(job);{ job.cancelled=true } },
            { owner,x,y -> composer.activeCount<10 && composer.begin(owner,x,y) },{ composer.end(it) })
        fun advance(ms:Long) {
            val until=now+ms
            while(true) {
                val job=jobs.filter { !it.cancelled && it.at<=until }.minByOrNull { it.at } ?: break
                jobs.remove(job);now=job.at;job.task()
            }
            now=until
        }
    }
    @Test fun twentyMillisecondPulsesStartAtRequestedPeriodOnlyWhileHeld() {
        val r=Rig();assertTrue(r.runner.input("key",40,true))
        r.advance(19);assertEquals(1,r.composer.activeCount)
        r.advance(1);assertEquals(0,r.composer.activeCount)
        r.advance(79);assertEquals(2,r.frames.size)
        r.advance(1);assertEquals(1,r.composer.activeCount)
        assertEquals(listOf(1L,21L,101L),r.frames.map { it.eventTime })
        r.runner.input("key",40,false);r.advance(1000)
        assertEquals(4,r.frames.size);assertFalse(r.runner.pending)
    }
    @Test fun physicalUpDuringPulseReleasesImmediatelyAndDoesNotWaitForTimer() {
        val r=Rig();r.runner.input("mouse",4,true);val stale=r.jobs.single().task
        r.advance(5);r.runner.input("mouse",4,false)
        assertEquals(0,r.composer.activeCount);val count=r.frames.size
        stale();r.advance(1000);assertEquals(count,r.frames.size)
        assertEquals(0,r.runner.activeCount)
    }
    @Test fun distinctRepeatOwnersPreserveAnExistingJoystickAndHaveIndependentUp() {
        val r=Rig();r.composer.begin("joystick",50f,60f);val original=r.composer.snapshot().single()
        r.runner.input("key",40,true);r.runner.input("mouse",4,true)
        assertEquals(3,r.composer.activeCount)
        r.runner.input("key",40,false)
        assertTrue(r.composer.hasOwner("repeat:two"));assertTrue(r.composer.hasOwner("joystick"))
        r.advance(300);r.runner.input("mouse",4,false)
        assertEquals(listOf(original),r.composer.snapshot())
        assertTrue(r.frames.all { it.downTime==1L })
    }
    @Test fun repeatDownDoesNotRestartThePulseOrCadence() {
        val r=Rig();r.runner.input("key",40,true);r.advance(10)
        repeat(20) { r.runner.input("key",40,true) }
        r.advance(90);assertEquals(2L,r.runner.pulses)
        assertEquals(3,r.frames.size)
    }
    @Test fun pointerModeCancellationRequiresUpAndFreshDownBeforeRestarting() {
        val r=Rig();r.runner.input("key",40,true)
        r.runner.cancel(releaseContact=true);assertEquals(0,r.composer.activeCount)
        val count=r.frames.size;r.runner.input("key",40,true);r.advance(1000)
        assertEquals(count,r.frames.size)
        r.runner.input("key",40,false,canStart=false);r.runner.input("key",40,true)
        assertEquals(1,r.composer.activeCount)
    }
    @Test fun cursorPressIsLatchedWithoutStartingAndCannotReplayOnReturn() {
        val r=Rig();r.runner.input("mouse",4,true,canStart=false)
        r.runner.input("mouse",4,true);assertTrue(r.frames.isEmpty())
        r.runner.input("mouse",4,false,canStart=false);r.runner.input("mouse",4,true)
        assertEquals(1,r.composer.activeCount)
    }
    @Test fun aLateReleaseSkipsMissedPeriodsWithoutImmediateCatchup() {
        val r=Rig();r.runner.input("key",40,true)
        val release=r.jobs.single();r.jobs.remove(release);r.now=501L;release.task()
        assertEquals(2,r.frames.size);assertEquals(601L,r.jobs.single().at)
        r.advance(99);assertEquals(2,r.frames.size)
        r.advance(1);assertEquals(3,r.frames.size)
    }
    @Test fun fullContactBudgetRejectsOnlyThatRepeatUntilFreshPhysicalPress() {
        val r=Rig();repeat(10) { r.composer.begin("other:$it",20f+it,40f) }
        val baseline=r.composer.snapshot();r.runner.input("key",40,true)
        assertEquals(1L,r.runner.rejected);assertFalse(r.runner.pending)
        assertEquals(baseline,r.composer.snapshot());assertFalse(r.composer.paused)
        r.composer.end("other:0");r.runner.input("key",40,true)
        assertFalse(r.composer.hasOwner("repeat:one"))
        r.runner.input("key",40,false);r.runner.input("key",40,true)
        assertTrue(r.composer.hasOwner("repeat:one"))
    }
    @Test fun globalPauseUsesOneExactCancelAndOldTasksCannotRestart() {
        val r=Rig();r.runner.input("key",40,true);val stale=r.jobs.single().task
        r.runner.cancel();r.composer.cancelAll();val count=r.frames.size
        stale();r.advance(1000)
        assertEquals(count,r.frames.size);assertEquals(TouchComposer.CANCEL,r.frames.last().action)
        assertEquals(0,r.composer.activeCount)
    }
    @Test fun reentrantCancellationFromStateCallbackCannotScheduleARepeat() {
        val composer=TouchComposer({ 1L },{ true });var scheduled=0
        lateinit var runner:RepeatRunner
        runner=RepeatRunner(listOf(PreparedRepeat("x",20f,30f,"key",40,100)),{1L},
            { _,_ -> scheduled++;{} },{ o,x,y -> composer.begin(o,x,y) },{ composer.end(it) },
            { runner.cancel(releaseContact=true) })
        runner.input("key",40,true)
        assertEquals(0,scheduled);assertEquals(0,composer.activeCount)
    }
}
