package dev.mobimapper.mobimapper.backend

import android.os.SystemClock
import android.view.KeyEvent
import dev.mobimapper.mobimapper.engine.TouchFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/** Bounded pipe transport for the injection worker; has no capture or window-context access. */
class WorkerConnection(private val process:java.lang.Process,val generation:Long,val displayId:Int) {
    private val input=DataInputStream(BufferedInputStream(process.inputStream))
    private val output=DataOutputStream(BufferedOutputStream(process.outputStream))
    private val timer=Object()
    private var deadline=0L
    private var closed=false
    val pid:Int
    val alive:Boolean get()=process.isAlive && !closed
    init {
        // One reusable watchdog per child; no timers/tasks are allocated per input sample.
        Thread({ synchronized(timer) {
            while(!closed) {
                if(deadline==0L) timer.wait() else {
                    val left=deadline-SystemClock.uptimeMillis()
                    if(left>0) timer.wait(left) else { process.destroyForcibly(); deadline=0 }
                }
            }
        } },"MobiPipeDeadline").apply { isDaemon=true; start() }
        setDeadline(4000)
        try {
            check(input.readInt()==InjectionWorkerMain.MAGIC && input.readLong()==generation)
            pid=input.readInt(); setDeadline(0)
        } catch(e:Exception) {
            synchronized(timer) { closed=true; timer.notifyAll() }; process.destroyForcibly()
            runCatching { input.close() }; runCatching { output.close() }; throw e
        }
    }
    private fun setDeadline(delay:Long) { synchronized(timer) { deadline=if(delay==0L)0 else SystemClock.uptimeMillis()+delay; timer.notifyAll() } }
    fun send(session:Long,frame:TouchFrame,width:Int,height:Int,mode:Int,fault:Int,
             afterWriteBeforeAck:(()->Unit)?=null):Boolean {
        setDeadline(if(mode==2)3000 else 1500)
        try {
            return exchangeTouch(input,output,generation,session,frame,width,height,mode,fault,afterWriteBeforeAck)
        } finally { setDeadline(0) }
    }
    fun sendKey(session:Long,action:Int,downTime:Long,eventTime:Long,keyCode:Int,
                scanCode:Int,metaState:Int,repeats:Int):Boolean {
        require(session>0)
        val frame=WorkerKeyFrame(action,downTime,eventTime,keyCode,scanCode,metaState,repeats)
            .requireValid(SystemClock.uptimeMillis(),KeyEvent.getMaxKeyCode())
        setDeadline(1500)
        try {
            output.writeInt(InjectionWorkerMain.MAGIC)
            output.writeLong(generation)
            output.writeLong(session)
            output.writeInt(WorkerKeyFrame.COMMAND_MODE)
            output.writeInt(0) // Fault injection remains a touch-only diagnostic protocol.
            frame.writeTo(output)
            output.flush()
            check(input.readInt()==InjectionWorkerMain.MAGIC && input.readLong()==generation && input.readLong()==session)
            val result=input.readBoolean()
            input.readLong()
            return result
        } finally { setDeadline(0) }
    }
    fun close() {
        synchronized(timer) { closed=true; timer.notifyAll() }
        runCatching { output.close() }; runCatching { input.close() }; if(process.isAlive)process.destroy()
    }
    /** Recovery must retire the old producer before observing a globally empty dispatcher. */
    fun closeAndAwaitTermination(timeoutMillis:Long=1_000L):Boolean {
        require(timeoutMillis in 1L..3_000L)
        close()
        return awaitTerminationAfterClose(process,timeoutMillis)
    }
    internal companion object {
        /** Pure wire exchange, shared by normal injection and compact reset transport. */
        internal fun exchangeTouch(input:DataInputStream,output:DataOutputStream,generation:Long,
            session:Long,frame:TouchFrame,width:Int,height:Int,mode:Int,fault:Int,
            afterWriteBeforeAck:(()->Unit)?=null):Boolean {
            output.writeInt(InjectionWorkerMain.MAGIC);output.writeLong(generation);output.writeLong(session)
            output.writeInt(mode);output.writeInt(fault);output.writeInt(frame.action)
            output.writeLong(frame.downTime);output.writeLong(frame.eventTime)
            output.writeInt(width);output.writeInt(height);output.writeInt(frame.ids.size)
            for(index in frame.ids.indices) {
                output.writeInt(frame.ids[index]);output.writeFloat(frame.xs[index]);output.writeFloat(frame.ys[index])
            }
            output.flush()
            // Only admission work for the next edge may overlap this worker's ACK.
            // This exchange never writes another event before reading the current reply.
            afterWriteBeforeAck?.invoke()
            check(input.readInt()==InjectionWorkerMain.MAGIC && input.readLong()==generation && input.readLong()==session)
            val accepted=input.readBoolean()
            input.readLong()
            return accepted
        }
        internal fun awaitTerminationAfterClose(process:java.lang.Process,timeoutMillis:Long):Boolean = try {
            if(process.waitFor(timeoutMillis,TimeUnit.MILLISECONDS)) !process.isAlive else {
                process.destroyForcibly()
                process.waitFor(timeoutMillis,TimeUnit.MILLISECONDS) && !process.isAlive
            }
        } catch(_:InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
