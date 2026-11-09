package dev.uwumouse.app.backend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WorkerRetirementTest {
    private class FakeProcess(private val results:MutableList<Boolean>,private val exitOnForce:Boolean=false,
                              private val interrupt:Boolean=false,initiallyAlive:Boolean=true):Process() {
        private var running=initiallyAlive
        var forced=0
        override fun getOutputStream()=ByteArrayOutputStream()
        override fun getInputStream()=ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream()=ByteArrayInputStream(byteArrayOf())
        override fun waitFor():Int { running=false;return 0 }
        override fun waitFor(timeout:Long,unit:TimeUnit):Boolean {
            if(interrupt) throw InterruptedException()
            return if(results.isNotEmpty()) results.removeAt(0) else !running
        }
        override fun exitValue():Int=if(running) throw IllegalThreadStateException() else 0
        override fun isAlive()=running
        override fun destroy() { }
        override fun destroyForcibly():Process { forced++;if(exitOnForce)running=false;return this }
    }
    @Test fun onlyAnActuallyExitedProcessIsRetired() {
        val exited=FakeProcess(mutableListOf(true),initiallyAlive=false)
        assertTrue(WorkerConnection.awaitTerminationAfterClose(exited,1L));assertEquals(0,exited.forced)
        val inconsistent=FakeProcess(mutableListOf(true))
        assertFalse(WorkerConnection.awaitTerminationAfterClose(inconsistent,1L))
    }
    @Test fun timedOutGracefulExitNeedsConfirmedForcedExit() {
        val forced=FakeProcess(mutableListOf(false,true),exitOnForce=true)
        assertTrue(WorkerConnection.awaitTerminationAfterClose(forced,1L));assertEquals(1,forced.forced)
        val hanging=FakeProcess(mutableListOf(false,false))
        assertFalse(WorkerConnection.awaitTerminationAfterClose(hanging,1L));assertEquals(1,hanging.forced)
    }
    @Test fun interruptionDoesNotInventRetirementAndPreservesInterrupt() {
        val interrupted=FakeProcess(mutableListOf(),interrupt=true)
        try {
            assertFalse(WorkerConnection.awaitTerminationAfterClose(interrupted,1L))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun retiringOutsideTheOriginalContextForcesExitBeforeAnyPipeClosure() {
        val worker=FakeProcess(mutableListOf(true),exitOnForce=true)
        assertTrue(WorkerConnection.forceTerminationAndAwait(worker,1L))
        assertEquals(1,worker.forced)
        val hanging=FakeProcess(mutableListOf(false,false))
        assertFalse(WorkerConnection.forceTerminationAndAwait(hanging,1L))
        assertEquals(2,hanging.forced)
    }
}
