package dev.mobimapper.mobimapper.backend

import android.content.Context
import android.os.*
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import dev.mobimapper.mobimapper.engine.TouchCoordinatePolicy
import java.io.*

/** Only this process calls InputManager. Inherited pipes have no public listening endpoint. */
object InjectionWorkerMain {
    const val MAGIC = 0x4d4d5732
    @JvmStatic fun main(args: Array<String>) {
        check(Process.myUid() == 2000 || Process.myUid() == 0)
        require(args.size in 2..3)
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
        val manager = context.getSystemService(Context.INPUT_SERVICE)
        val inject = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        val input = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`)))
        val output = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)))
        val props = Array(10) { MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(10) { MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f } }
        var active: dev.mobimapper.mobimapper.engine.TouchFrame? = null
        var admitted: dev.mobimapper.mobimapper.engine.TouchFrame? = null
        var admittedSession=0L
        fun send(frame: dev.mobimapper.mobimapper.engine.TouchFrame, mode: Int): Boolean {
            for(i in frame.ids.indices) { props[i].id=frame.ids[i]; coords[i].x=frame.xs[i]; coords[i].y=frame.ys[i] }
            val event=MotionEvent.obtain(frame.downTime,frame.eventTime,frame.action,frame.ids.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,if(frame.action and 255 == 3) 32 else 0)
            return try { setDisplay.invoke(event,0); inject.invoke(manager,event,mode,targetUid) as Boolean }
            finally { event.recycle() }
        }
        output.writeInt(MAGIC); output.writeLong(generation); output.writeInt(Process.myPid()); output.flush()
        try {
            while(true) {
                val header=try { input.readInt() } catch(_:EOFException) { break }
                check(header == MAGIC && input.readLong() == generation)
                val session=input.readLong()
                val mode=input.readInt().also { require(it == 1 || it == 2) }
                val fault=input.readInt().also { require(it in 0..3) }
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
                require(action and 255 in intArrayOf(0,1,2,3,5,6) && down in 0..time && time <= SystemClock.uptimeMillis())
                val frame=dev.mobimapper.mobimapper.engine.TouchFrame(action,down,time,ids,xs,ys)
                require(coordinatePolicy.allowsCoordinates(frame,width,height,
                    previous=admitted.takeIf { admittedSession==session },
                    recoveryCancellation=mode==2 && action==3))
                // Retain the possible stream before the injection call, including an ambiguous UP.
                active=frame
                val before=SystemClock.uptimeMillis()
                if(fault==1) { Log.w("MobiInjectWorker","test_crash_before sid=$session action=$action ids=${ids.joinToString()} uptime=$before"); Process.killProcess(Process.myPid()) }
                val accepted=try { if(fault==3) false else send(frame,mode) } catch(e:Exception) {
                    Log.w("MobiInjectWorker","injection_exception worker=$generation sid=$session action=$action type=${e.javaClass.simpleName}")
                    false
                }
                if(accepted) active=when(action and 255) {
                    1,3 -> null
                    6 -> { val index=action ushr 8; frame.copy(action=2,ids=ids.filterIndexed{i,_->i!=index}.toIntArray(),xs=xs.filterIndexed{i,_->i!=index}.toFloatArray(),ys=ys.filterIndexed{i,_->i!=index}.toFloatArray()) }
                    else -> frame.copy(action=2)
                }
                if(accepted) { admitted=active; admittedSession=if(active==null) 0L else session }
                if(action and 255 != 2 || !accepted) Log.i("MobiInjectWorker","pid=${Process.myPid()} worker=$generation sid=$session action=$action ids=${ids.joinToString()} down=$down event=$time target=$targetUid display=0 source=4098 device=0 effectiveDevice=-1 start=$before end=${SystemClock.uptimeMillis()} admitted=$accepted mode=$mode")
                if(fault==2) { Log.w("MobiInjectWorker","test_crash_after sid=$session action=$action admitted=$accepted uptime=${SystemClock.uptimeMillis()}"); Process.killProcess(Process.myPid()) }
                output.writeInt(MAGIC); output.writeLong(generation); output.writeLong(session); output.writeBoolean(accepted); output.writeLong(SystemClock.uptimeMillis()); output.flush()
            }
        } catch(e:Exception) { Log.w("MobiInjectWorker","pipe failure worker=$generation",e) }
        // EOF means guardian vanished. This surviving privileged process can still close its stream.
        active?.let { frame ->
            val cancel=frame.copy(action=3,eventTime=SystemClock.uptimeMillis())
            val result=runCatching { send(cancel,2) }.getOrDefault(false)
            Log.i("MobiInjectWorker","guardian EOF cancel worker=$generation down=${cancel.downTime} ids=${cancel.ids.joinToString()} admitted=$result uptime=${SystemClock.uptimeMillis()}")
        }
    }
}
