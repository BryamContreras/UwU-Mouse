package dev.uwumouse.app.backend

import android.content.Context
import android.os.*
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.KeyEvent
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

/** Only this process calls InputManager. Inherited pipes have no public listening endpoint. */
object InjectionWorkerMain {
    const val MAGIC = 0x4d4d5732
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000 || Process.myUid() == 0)
        require(args.size in 2..4)
        val generation = args[0].toLong().also { require(it > 0) }
        Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val context = at.getMethod("getSystemContext").invoke(thread) as Context
        // Immutable for this worker generation; changing destination requires an empty stream and a replacement.
        val targetUid = args[1].toInt().also { require(it >= 10000) }
        val coordinatePolicy=when(args.getOrNull(2)) {
            null,"0" -> TouchCoordinatePolicy.STRICT
            "1" -> TouchCoordinatePolicy.CONTINUOUS_CAMERA_9
            else -> error("Unknown coordinate policy")
        }
        val displayId=args.getOrNull(3)?.toInt()?.also { require(it >= 0) } ?: 0
        val manager = context.getSystemService(Context.INPUT_SERVICE)
        val inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        val input = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`)))
        val output = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
        val props = Array(10) { MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(10) { MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f } }
        var active: dev.uwumouse.app.engine.TouchFrame? = null
        var admitted: dev.uwumouse.app.engine.TouchFrame? = null
        var admittedSession=0L
        // A rejected/ambiguous DOWN can still have reached Android. Keep a possible latch
        // until an acknowledged UP, so EOF cleanup can release every known key owner.
        val activeKeys=HashMap<Int,WorkerKeyFrame>()
        fun send(frame: dev.uwumouse.app.engine.TouchFrame, mode: Int): Boolean {
            for(i in frame.ids.indices) {
                props[i].id=frame.ids[i];coords[i].x=frame.xs[i];coords[i].y=frame.ys[i]
                coords[i].pressure=WorkerTouchPressure.forPointer(frame.action,i)
            }
            val event=MotionEvent.obtain(frame.downTime,frame.eventTime,frame.action,frame.ids.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,if(frame.action and 255 == 3) 32 else 0)
            return try { setDisplay.invoke(event,displayId); inject.invoke(manager,event,mode,targetUid) as Boolean }
            finally { event.recycle() }
        }
        fun sendKey(frame:WorkerKeyFrame):Boolean {
            val event=KeyEvent(frame.downTime,frame.eventTime,frame.action,frame.keyCode,
                if(frame.action==KeyEvent.ACTION_DOWN)frame.repeats else 0,
                frame.metaState,-1,frame.scanCode,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD)
            setDisplay.invoke(event,displayId)
            return inject.invoke(manager,event,1,targetUid) as Boolean
        }
        fun acknowledge(session:Long,accepted:Boolean) {
            output.writeInt(MAGIC);output.writeLong(generation);output.writeLong(session)
            output.writeBoolean(accepted);output.writeLong(SystemClock.uptimeMillis());output.flush()
        }
        output.writeInt(MAGIC); output.writeLong(generation); output.writeInt(Process.myPid()); output.flush()
        try {
            while(true) {
                val header=try { input.readInt() } catch(_:EOFException) { break }
                check(header == MAGIC && input.readLong() == generation)
                val session=input.readLong()
                val mode=input.readInt().also { require(it == 1 || it == 2 || it == WorkerKeyFrame.COMMAND_MODE) }
                val fault=input.readInt().also { require(it in 0..3) }
                if(mode==WorkerKeyFrame.COMMAND_MODE) {
                    require(session>0 && fault==0)
                    val key=WorkerKeyFrame.readFrom(input,SystemClock.uptimeMillis(),KeyEvent.getMaxKeyCode())
                    if(key.action==KeyEvent.ACTION_DOWN) activeKeys[key.keyCode]=key
                    val accepted=try { sendKey(key) } catch(failure:Exception) {
                        Log.w("UwuInjectWorker","key injection exception worker=$generation sid=$session type=${failure.javaClass.simpleName}")
                        false
                    }
                    if(accepted && key.action==KeyEvent.ACTION_UP) activeKeys.remove(key.keyCode)
                    acknowledge(session,accepted)
                    continue
                }
                val action=input.readInt()
                val down=input.readLong(); val time=input.readLong()
                val width=input.readInt(); val height=input.readInt(); val count=input.readInt()
                require(session > 0 && width in 100..8192 && height in 100..8192 && count in 1..10)
                val ids=IntArray(count); val xs=FloatArray(count); val ys=FloatArray(count)
                var bits=0
                for(i in 0 until count) {
                    ids[i]=input.readInt(); xs[i]=input.readFloat(); ys[i]=input.readFloat()
                    require(ids[i] in 0..9 && bits and (1 shl ids[i]) == 0)
                    bits=bits or (1 shl ids[i])
                }
                require((when(action and 255) { 0,1,2,3,5,6 -> true;else -> false }) && down in 0..time && time <= SystemClock.uptimeMillis())
                val frame=dev.uwumouse.app.engine.TouchFrame(action,down,time,ids,xs,ys)
                require(coordinatePolicy.allowsCoordinates(frame,width,height,
                    previous=admitted.takeIf { admittedSession==session },
                    recoveryCancellation=mode==2 && action==3))
                // Retain the possible stream before the injection call, including an ambiguous UP.
                active=frame
                val before=SystemClock.uptimeMillis()
                if(fault==1) { Log.w("UwuInjectWorker","test_crash_before sid=$session action=$action ids=${ids.joinToString()} uptime=$before"); Process.killProcess(Process.myPid()) }
                val accepted=try { if(fault==3) false else send(frame,mode) } catch(e:Exception) {
                    Log.w("UwuInjectWorker","injection_exception worker=$generation sid=$session action=$action type=${e.javaClass.simpleName}")
                    false
                }
                if(accepted) active=when(action and 255) {
                    1,3 -> null
                    2 -> frame
                    6 -> { val index=action ushr 8; frame.copy(action=2,ids=ids.filterIndexed{i,_->i!=index}.toIntArray(),xs=xs.filterIndexed{i,_->i!=index}.toFloatArray(),ys=ys.filterIndexed{i,_->i!=index}.toFloatArray()) }
                    else -> frame.copy(action=2)
                }
                if(accepted) { admitted=active; admittedSession=if(active==null) 0L else session }
                if(action and 255 != 2 || !accepted) Log.i("UwuInjectWorker","pid=${Process.myPid()} worker=$generation sid=$session action=$action ids=${ids.joinToString()} down=$down event=$time target=$targetUid display=$displayId source=4098 device=0 effectiveDevice=-1 start=$before end=${SystemClock.uptimeMillis()} admitted=$accepted mode=$mode")
                if(fault==2) { Log.w("UwuInjectWorker","test_crash_after sid=$session action=$action admitted=$accepted uptime=${SystemClock.uptimeMillis()}"); Process.killProcess(Process.myPid()) }
                output.writeInt(MAGIC); output.writeLong(generation); output.writeLong(session); output.writeBoolean(accepted); output.writeLong(SystemClock.uptimeMillis()); output.flush()
            }
        } catch(e:Exception) { Log.w("UwuInjectWorker","pipe failure worker=$generation",e) }
        // EOF means guardian vanished. This surviving privileged process can still close its stream.
        val cleanupFinished=AtomicBoolean(false)
        if(active!=null || activeKeys.isNotEmpty()) Thread({
            try { Thread.sleep(1500) } catch(_:InterruptedException) { return@Thread }
            if(!cleanupFinished.get()) Process.killProcess(Process.myPid())
        },"UwuWorkerEofDeadline").apply { isDaemon=true;start() }
        // Key releases use WAIT_FOR_RESULT; attempt them before a touch cancellation
        // using WAIT_FOR_FINISH could wait for a stalled target application.
        var releasedKeys=0
        for(key in activeKeys.values) {
            val up=key.copy(action=KeyEvent.ACTION_UP,eventTime=maxOf(SystemClock.uptimeMillis(),key.downTime),metaState=0,repeats=0)
            if(runCatching { sendKey(up) }.getOrDefault(false)) releasedKeys++
        }
        if(activeKeys.isNotEmpty()) Log.i("UwuInjectWorker","guardian EOF key release worker=$generation display=$displayId count=${activeKeys.size} admitted=$releasedKeys")
        active?.let { frame ->
            val cancel=frame.copy(action=3,eventTime=SystemClock.uptimeMillis())
            val result=runCatching { send(cancel,2) }.getOrDefault(false)
            Log.i("UwuInjectWorker","guardian EOF cancel worker=$generation display=$displayId down=${cancel.downTime} ids=${cancel.ids.joinToString()} admitted=$result uptime=${SystemClock.uptimeMillis()}")
        }
        cleanupFinished.set(true)
    }
}
