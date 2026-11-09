package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class MacroRunnerTest {
    private data class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
    private class Rig {
        var now = 1L
        val frames = mutableListOf<TouchFrame>()
        val jobs = mutableListOf<Job>()
        val composer = TouchComposer({ now }, { frames.add(it); true })
        val macro = PreparedMacro("one", "macro:one", "key", 40,
            listOf(PreparedMacroStep(300f, 400f, 100), PreparedMacroStep(500f, 600f, 200)))
        val runner = MacroRunner(listOf(macro, macro.copy(id="two", owner="macro:two", triggerKind="mouse", code=4)),
            { delay, task -> val job = Job(now + delay, task); jobs.add(job); { job.cancelled = true } },
            { owner,x,y -> composer.begin(owner,x,y) }, { composer.end(it) })
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val job = jobs.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
                jobs.remove(job); now = job.at; job.task()
            }
            now = until
        }
    }

    @Test fun tapDurationAndPostReleaseWaitAreExact() {
        val r = Rig(); assertTrue(r.runner.input("key",40,true))
        assertEquals(1,r.composer.activeCount)
        r.advance(59); assertEquals(1,r.composer.activeCount)
        r.advance(1); assertEquals(0,r.composer.activeCount)
        r.advance(99); assertEquals(2,r.frames.size)
        r.advance(1); assertEquals(500f,r.frames.last().xs[0],0f)
        r.advance(260); assertNull(r.runner.activeId); assertEquals(1L,r.runner.completed)
        assertEquals(listOf(0,1,0,1),r.frames.map { it.action and 255 })
    }

    @Test fun repeatedDownAndHeldTriggerNeverLoop() {
        val r=Rig(); r.runner.input("key",40,true); repeat(10) { r.runner.input("key",40,true) }
        r.advance(1000); r.runner.input("key",40,true); assertEquals(1L,r.runner.completed)
        assertNull(r.runner.activeId)
        r.runner.input("key",40,false); r.runner.input("key",40,true)
        assertEquals("one",r.runner.activeId)
    }

    @Test fun busyTriggerIsNotQueuedForLaterReplay() {
        val r=Rig(); r.runner.input("key",40,true); r.runner.input("mouse",4,true)
        r.advance(1000); assertEquals(1L,r.runner.completed); assertEquals(1L,r.runner.rejected)
        r.runner.input("mouse",4,true); assertNull(r.runner.activeId)
        r.runner.input("mouse",4,false); r.runner.input("mouse",4,true)
        assertEquals("two",r.runner.activeId)
    }

    @Test fun ordinaryHeldContactsKeepTheirIdsAndDownTimeAcrossSteps() {
        val r=Rig(); r.composer.begin("joystick",50f,60f); r.composer.begin("camera",800f,500f)
        val baseline=r.composer.snapshot()
        r.runner.input("key",40,true); r.advance(1000)
        assertEquals(baseline,r.composer.snapshot())
        assertTrue(r.frames.all { it.downTime == 1L })
        assertEquals(listOf(0,5,5,6,5,6),r.frames.map { it.action and 255 })
    }

    @Test fun pointerTransitionReleasesOnlyMacroAndInvalidatesLateTask() {
        val r=Rig(); r.composer.begin("joystick",50f,60f)
        r.runner.input("key",40,true); val stale=r.jobs.last().task
        r.runner.cancel(releaseContact=true)
        assertEquals(listOf("joystick"),r.composer.snapshot().map { it.owner })
        val count=r.frames.size; stale(); r.advance(1000); assertEquals(count,r.frames.size)
        r.runner.input("key",40,true); assertNull(r.runner.activeId)
    }

    @Test fun stopUsesSingleGlobalCancelAndCannotRestartFromOldCallback() {
        val r=Rig(); r.runner.input("key",40,true); val stale=r.jobs.last().task
        r.runner.cancel(); r.composer.cancelAll(); val count=r.frames.size
        stale(); r.advance(1000)
        assertEquals(count,r.frames.size); assertEquals(TouchComposer.CANCEL,r.frames.last().action)
        assertEquals(0,r.composer.activeCount); assertFalse(r.runner.pending)
    }

    @Test fun cancelDuringWaitPreventsNextPoint() {
        val r=Rig(); r.runner.input("key",40,true); r.advance(60)
        val stale=r.jobs.last().task; r.runner.cancel(); stale(); r.advance(1000)
        assertEquals(2,r.frames.size); assertEquals(0,r.composer.activeCount)
    }

    @Test fun cursorModeClearsUpButDoesNotStartOrReplayOnReturn() {
        val r=Rig(); r.runner.input("key",40,true,canStart=false)
        r.runner.input("key",40,true); assertNull(r.runner.activeId)
        r.runner.input("key",40,false,canStart=false); r.runner.input("key",40,true)
        assertEquals("one",r.runner.activeId)
    }

    @Test fun mouseMacroCanBePressedAgainAfterUpInCursorMode() {
        val r=Rig();r.runner.input("mouse",4,true)
        r.runner.cancel(releaseContact=true)
        r.runner.input("mouse",4,false,canStart=false)
        r.runner.input("mouse",4,true)
        assertEquals("two",r.runner.activeId)
        assertEquals(1,r.composer.activeCount)
    }

    @Test fun unknownInputDoesNotConsumeOrMutateRunner() {
        val r=Rig(); assertFalse(r.runner.input("key",99,true)); assertFalse(r.runner.input("mouse",16,false))
        assertTrue(r.frames.isEmpty()); assertTrue(r.jobs.isEmpty())
    }

    @Test fun contactRefusalStopsSequenceWithoutWaitingOrReplaying() {
        val macro=PreparedMacro("x","macro:x","key",40,listOf(PreparedMacroStep(10f,20f,100)))
        var scheduled=0
        val runner=MacroRunner(listOf(macro),{ _,_ -> scheduled++; {} },{ _,_,_ -> false },{ true })
        runner.input("key",40,true)
        assertEquals("MACRO_CONTACT_UNAVAILABLE",runner.lastError); assertNull(runner.activeId)
        assertEquals(0,scheduled); assertEquals(1L,runner.rejected)
    }

    @Test fun cancellationFromStateCallbackDoesNotScheduleAnotherStep() {
        val macro=PreparedMacro("x","macro:x","key",40,listOf(PreparedMacroStep(10f,20f,100)))
        val composer=TouchComposer({ 1L },{ true })
        var scheduled=0
        lateinit var runner:MacroRunner
        runner=MacroRunner(listOf(macro),{ _,_ -> scheduled++; {} },
            { owner,x,y -> composer.begin(owner,x,y) },{ composer.end(it) },{ runner.cancel(releaseContact=true) })
        runner.input("key",40,true)
        assertEquals(0,scheduled);assertEquals(0,composer.activeCount);assertNull(runner.activeId)
    }
}
