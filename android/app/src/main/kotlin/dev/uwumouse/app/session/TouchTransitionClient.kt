package dev.uwumouse.app.session

import android.os.ParcelFileDescriptor
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.engine.TouchFrame
import dev.uwumouse.app.engine.TouchTransitionResult
import dev.uwumouse.app.engine.TouchTransitionSequence
import java.io.InputStream

/** One bounded MOVE/contact-edge RPC, with per-stage progress surviving a lost Binder reply. */
class TouchTransitionClient(private val backend:IInjector,private val sid:Long) {
    private var accepted=emptyList<TouchFrame>()
    private var attempted:TouchFrame?=null
    private val pendingRead=CancellationOwner<ParcelFileDescriptor> { it.close() }

    /** Never takes inject()'s monitor; closing the reader makes the next progress write fail. */
    fun cancelPending()=pendingRead.cancel()

    val acceptedFrames:List<TouchFrame>
        @Synchronized get()=accepted.map { it.detached() }

    val lastAttemptedFrame:TouchFrame?
        @Synchronized get()=attempted?.detached()

    @Synchronized fun inject(frames:List<TouchFrame>):TouchTransitionResult {
        accepted=emptyList();attempted=null
        if(pendingRead.cancelled) return TouchTransitionResult(0,0)
        val detached=try { TouchTransitionSequence(frames).frames } catch(_:IllegalArgumentException) { return unknown() }
        val counts=IntArray(detached.size) { detached[it].ids.size }
        val actions=IntArray(detached.size) { detached[it].action }
        val downTimes=LongArray(detached.size) { detached[it].downTime }
        val eventTimes=LongArray(detached.size) { detached[it].eventTime }
        val total=counts.sum()
        val ids=IntArray(total);val xs=FloatArray(total);val ys=FloatArray(total)
        var offset=0
        for(frame in detached) {
            frame.ids.copyInto(ids,offset);frame.xs.copyInto(xs,offset);frame.ys.copyInto(ys,offset)
            offset+=frame.ids.size
        }
        val descriptors=try { ParcelFileDescriptor.createPipe() } catch(_:Exception) { return TouchTransitionResult(0,0) }
        var rpcStarted=false
        val result=try {
            if(!pendingRead.register(descriptors[0])) return TouchTransitionResult(0,0)
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { input ->
                var reply:Int?=null
                if(!pendingRead.claim(descriptors[0]) || pendingRead.cancelled) return TouchTransitionResult(0,0)
                rpcStarted=true
                try {
                    reply=backend.injectTouchTransition(sid,actions,downTimes,eventTimes,counts,ids,xs,ys,descriptors[1])
                } catch(_:Exception) {
                    // The progress descriptor supplies the last possible stage, never this exception.
                } finally {
                    try { descriptors[1].close() } catch(_:Exception) { }
                }
                if(pendingRead.cancelled) unknown()
                else TouchTransitionProgress.resolve(input.readBoundedProgress(),reply)
            }
        } catch(_:Exception) { if(rpcStarted) unknown() else TouchTransitionResult(0,0) }
        finally {
            pendingRead.clear(descriptors[0])
            try { descriptors[0].close() } catch(_:Exception) { }
            try { descriptors[1].close() } catch(_:Exception) { }
        }
        val resolved=if(rpcStarted && pendingRead.cancelled) unknown() else result
        if(resolved.progressKnown) {
            accepted=detached.take(resolved.acceptedCount)
            attempted=detached.getOrNull(resolved.attemptedCount-1)
        }
        return resolved
    }

    private fun InputStream.readBoundedProgress():ByteArray {
        val bytes=ByteArray(TouchTransitionProgress.MAX_BYTES+1)
        var count=0
        while(count<bytes.size) {
            val received=read(bytes,count,bytes.size-count)
            if(received<0) break
            if(received==0) continue
            count+=received
        }
        return bytes.copyOf(count)
    }

    private fun TouchFrame.detached()=copy(ids=ids.copyOf(),xs=xs.copyOf(),ys=ys.copyOf())
    private fun unknown()=TouchTransitionResult(0,0,progressKnown=false)
}
