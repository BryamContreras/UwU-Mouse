package dev.mobimapper.mobimapper.backend

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.*
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.KeyEvent
import dev.mobimapper.windowcontext.ShellWindowContextProvider
import dev.mobimapper.mobimapper.backend.recovery.*
import dev.mobimapper.mobimapper.engine.TouchFrame
import dev.mobimapper.mobimapper.engine.TouchCoordinatePolicy
import dev.mobimapper.mobimapper.engine.CameraResetSequence
import dev.mobimapper.mobimapper.engine.CameraResetKind
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer

/** Stable authorized broker; the actual injector is a replaceable child process. */
class SupervisedInjector(private val context: Context,private val identity:BackendIdentity=BackendIdentity()) : IInjector.Stub() {
    private val appUid=context.packageManager.getPackageUid(identity.applicationId,0)
    private val windowContexts=ShellWindowContextProvider()
    @Volatile private var captureRequired=false
    private var captureFailure=""
    private var ownedCaptureId=0L
    private val nativeCapture:NativeCaptureBroker?=runCatching {
        NativeCaptureBroker(context,identity.applicationId,::nativeContextAllows) { captureId,reason ->
            handler.post { synchronized(this) {
                // Loss belongs to the retired capture. A replacement may already own
                // the devices by the time this recovery-thread callback runs.
                if(captureId!=ownedCaptureId) return@synchronized
                ownedCaptureId=0L
                syncCapturePointer()
                captureFailure=reason
                releaseForwardedKeys()
                releaseInternal()
                if(state!="BLOCKED") state="PAUSED_CAPTURE_LOST"
            } }
        }
    }.getOrElse { captureFailure=it.message ?: "native_capture_unavailable";null }
    private data class ForwardedKey(val code:Int,val scan:Int,val downTime:Long,val meta:Int,var repeats:Int=0)
    private val forwardedKeys=HashMap<Int,ForwardedKey>()
    private var keyReleaseUnverified=false
    // Bootstrap is idle and must not require the optional laboratory to be installed.
    // A real target is resolved and authorized by selectTarget before any worker/session starts.
    private var targetPackage=identity.applicationId
    private var targetUid=appUid
    private var coordinatePolicy=TouchCoordinatePolicy.STRICT
    private var journal=RecoveryJournal()
    private val recoveryThread=HandlerThread("MobiRecovery").apply { start() }
    private val handler=Handler(recoveryThread.looper)
    private var connection: WorkerConnection?=null
    // Keep ownership until process exit is confirmed, including workers already replaced.
    private val unretiredWorkers=LinkedHashSet<WorkerConnection>()
    private var workerGeneration=0L
    private var nextSession=SystemClock.elapsedRealtimeNanos()
    private var client: IBackendEvents?=null
    private var clientDeath: IBinder.DeathRecipient?=null
    private var recoveryScheduled=false
    private var recoveryEpoch=0L
    private var forceStopped=false
    @Volatile private var closed=false
    // This endpoint only requests republication of the fixed Binder. Injection remains UID-gated.
    private val reattachNonce=ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val reattachServer=ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4)
    }
    private var state="READY"
    private var error=""
    private var recoveryAttempts=0
    private var recoveredCount=0
    private var lastDetection=0L
    private var lastRecovery=0L
    private var injected=0L
    private var faultAction=-1
    private var faultStage=0
    private var lastWorkerPid=0
    private var lastRecoveryFrame:TouchFrame?=null
    private var liveCancellationAccepted=false
    // Cold diagnostic snapshots only. Injection already owns this object's monitor, so
    // recording a duration requires no extra locks, tasks or per-sample allocations.
    private val focusTimes=LongArray(512)
    private val pipeTimes=LongArray(512)
    private var focusTimingCount=0L
    private var pipeTimingCount=0L
    private var timingSessionId=0L
    private var cameraResetBatches=0L
    private var cameraResetInterlacedBatches=0L
    private var cameraResetLastKind="none"
    private var cameraResetFrames=0L
    private var cameraResetPrefetchHits=0L
    private var cameraResetLastEventTimes=LongArray(0)
    private var cameraResetLastDurationNanos=0L
    private val taskManager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    private val focusMethod=Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo")
    private val motionDisplayMethod=InputEvent::class.java.getMethod("getDisplayId")
    private var expiry:Runnable?=null
    private val pointer = PointerPresentation { type ->
        val identity=Binder.clearCallingIdentity()
        try {
            val manager=context.getSystemService(Context.INPUT_SERVICE)
            manager.javaClass.getMethod("setPointerIconType",Int::class.javaPrimitiveType).invoke(manager,type)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun syncCapturePointer(forceRestore:Boolean=false) {
        val capture=nativeCapture
        pointer.setMouseCaptureActive(capture?.mouseActive==true && capture.targetPackage!="dev.mobimapper.inputlab",forceRestore)
    }

    private fun clearExpiry() {
        expiry?.let { handler.removeCallbacks(it) }
        expiry=null
    }

    private fun authorize() { check(Binder.getCallingUid()==appUid) { "Unauthorized client" } }
    /** The Binder supplies its own event copy. This read must never wait for injection/recovery. */
    override fun motionDisplayId(event:MotionEvent):Int = try {
        authorize()
        motionDisplayMethod.invoke(event) as Int
    } finally { event.recycle() }
    /** Cold global focus query, independent of whichever destination the previous session used. */
    override fun focusedApplication():Bundle {
        authorize()
        val identity=Binder.clearCallingIdentity()
        return try {
            runCatching {
                val info=requireNotNull(focusMethod.invoke(taskManager))
                val top=info.javaClass.getField("topActivity").get(info) as? ComponentName
                val displayId=info.javaClass.getField("displayId").getInt(info)
                Bundle().apply {
                    putBoolean("valid",top!=null && displayId>=0)
                    putString("packageName",top?.packageName)
                    putInt("displayId",displayId)
                }
            }.getOrElse { Bundle().apply { putBoolean("valid",false);putInt("displayId",-1) } }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun windowContext():Bundle {
        authorize()
        return windowContexts.snapshot()
    }
    private fun nativeContextAllows(packageName:String,displayId:Int):Boolean {
        val snapshot=windowContexts.snapshot()
        if(!snapshot.getBoolean("valid") || snapshot.getInt("focusedDisplayId",-1)!=displayId) return false
        val focus=snapshot.getString("focusedPackage")
        if(focus!=packageName && focus!=identity.applicationId) return false
        @Suppress("DEPRECATION")
        val windows=snapshot.getParcelableArrayList<Bundle>("windows").orEmpty()
        val expectedUid=runCatching { context.packageManager.getPackageUid(packageName,0) }.getOrNull() ?: return false
        val target=windows.filter { it.getInt("displayId",-1)==displayId && it.getInt("type")==1 &&
            it.getString("packageName")==packageName && it.getInt("ownerUid",-1)==expectedUid }
            .maxByOrNull { (it.getInt("right")-it.getInt("left")).toLong()*(it.getInt("bottom")-it.getInt("top")) } ?: return false
        if(windows.any { it.getInt("displayId",-1)==displayId && it.getInt("layer")>target.getInt("layer") &&
            it.getInt("ownerUid",-1)!=appUid && it.getInt("ownerUid",-1)!=expectedUid &&
            it.getInt("left")<=target.getInt("left") && it.getInt("top")<=target.getInt("top") &&
            it.getInt("right")>=target.getInt("right") && it.getInt("bottom")>=target.getInt("bottom") }) return false
        return privileged { runCatching {
            val task=requireNotNull(focusMethod.invoke(taskManager))
            (task.javaClass.getField("topActivity").get(task) as? ComponentName)?.packageName==packageName &&
                task.javaClass.getField("displayId").getInt(task)==displayId
        }.getOrDefault(false) }
    }
    @Synchronized override fun startCapture(client:IPhysicalInputCallback,displayId:Int,targetPackage:String,
                                           mouse:Boolean,keyboard:Boolean):Bundle =
        startConfiguredCapture(client,displayId,targetPackage,mouse,keyboard,0,false)

    @Synchronized override fun startCaptureWithRate(client:IPhysicalInputCallback,displayId:Int,targetPackage:String,
                                                   mouse:Boolean,keyboard:Boolean,mouseRateHz:Int):Bundle =
        startConfiguredCapture(client,displayId,targetPackage,mouse,keyboard,mouseRateHz,true)

    private fun startConfiguredCapture(client:IPhysicalInputCallback,displayId:Int,targetPackage:String,
                                       mouse:Boolean,keyboard:Boolean,mouseRateHz:Int,batchTransport:Boolean):Bundle {
        authorize();check(!closed)
        require(mouseRateHz in 0..1000)
        check(nativeContextAllows(targetPackage,displayId)) { "Ventana de destino sin foco o sin contexto actualizado" }
        selectTarget(targetPackage,policy=coordinatePolicy,displayId=displayId)
        check(releaseForwardedKeys()) { "Vuelve al juego para liberar las teclas anteriores" }
        val capture=requireNotNull(nativeCapture) { captureFailure.ifBlank { "Captura nativa no disponible" } }
        val result=try {
            privilegedBundle { capture.start(client,targetPackage,displayId,mouse,keyboard,mouseRateHz,batchTransport) }
        } catch(failure:Throwable) {
            ownedCaptureId=capture.activeId
            syncCapturePointer(forceRestore=true)
            throw failure
        }
        ownedCaptureId=capture.activeId
        syncCapturePointer()
        captureRequired=true
        captureFailure=""
        return result
    }
    @Synchronized override fun setCaptureMouseRate(captureId:Long,mouseRateHz:Int):Boolean {
        authorize();check(!closed)
        require(mouseRateHz in 0..1000)
        val capture=nativeCapture ?: return false
        if(captureId!=ownedCaptureId || !nativeContextAllows(targetPackage,journal.displayId)) return false
        return privileged { capture.configureMouseRate(captureId,mouseRateHz) }
    }
    override fun renewCapture(captureId:Long):Boolean {
        authorize()
        return privileged { nativeCapture?.renew(captureId)==true }
    }
    @Synchronized override fun stopCapture(captureId:Long) {
        authorize()
        if(nativeCapture?.activeId!=captureId) return
        releaseForwardedKeys()
        privileged { nativeCapture?.stop(captureId);true }
        ownedCaptureId=nativeCapture?.activeId ?: 0L
        syncCapturePointer(forceRestore=true)
    }
    override fun captureStatus():Bundle {
        authorize()
        return nativeCapture?.status() ?: Bundle().apply {
            putBoolean("captureSupported",false);putBoolean("running",false);putString("reason",captureFailure)
        }
    }
    private fun privilegedBundle(block:()->Bundle):Bundle {
        val caller=Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(caller) }
    }
    @Synchronized override fun forwardKey(captureId:Long,action:Int,keyCode:Int,scanCode:Int,eventTime:Long,metaState:Int):Boolean {
        authorize()
        if(nativeCapture?.activeId!=captureId || nativeCapture?.active!=true ||
            !nativeContextAllows(targetPackage,journal.displayId)) return false
        require(action==KeyEvent.ACTION_DOWN || action==KeyEvent.ACTION_UP)
        require(keyCode in 1..KeyEvent.getMaxKeyCode() && scanCode>=0 && eventTime<=SystemClock.uptimeMillis() &&
            SystemClock.uptimeMillis()-eventTime<5000)
        val existing=forwardedKeys[keyCode]
        if(action==KeyEvent.ACTION_UP && existing==null) return true
        val key=existing ?: ForwardedKey(keyCode,scanCode,eventTime,metaState)
        if(existing!=null && action==KeyEvent.ACTION_DOWN) key.repeats++
        if(action==KeyEvent.ACTION_DOWN) forwardedKeys[keyCode]=key
        val accepted=sendKey(key,action,eventTime,metaState)
        if(accepted && action==KeyEvent.ACTION_UP) forwardedKeys.remove(keyCode)
        if(!accepted) keyReleaseUnverified=forwardedKeys.isNotEmpty()
        return accepted
    }
    private fun sendKey(key:ForwardedKey,action:Int,eventTime:Long,metaState:Int):Boolean = privileged {
        var channel=connection?.takeIf { it.alive }
        try {
            if(channel==null) {
                if(forceStopped || closed || appIsStopped()) return@privileged false
                ensureWorker()
                channel=connection ?: return@privileged false
            }
            channel.sendKey(journal.snapshot().sessionId.coerceAtLeast(1L),action,key.downTime,eventTime,
                key.code,key.scan,metaState,if(action==KeyEvent.ACTION_DOWN) key.repeats.coerceAtMost(10_000) else 0)
        } catch(failure:Exception) {
            if(connection===channel) connection=null
            runCatching { channel?.close() }
            if(!forceStopped && !closed && journal.snapshot().possibleContacts>0) scheduleRecovery("KEY_WORKER_PIPE_LOST")
            false
        }
    }
    private fun releaseForwardedKeys():Boolean {
        val now=SystemClock.uptimeMillis()
        val iterator=forwardedKeys.entries.iterator()
        while(iterator.hasNext()) {
            val key=iterator.next().value
            if(sendKey(key,KeyEvent.ACTION_UP,maxOf(now,key.downTime),0)) iterator.remove()
        }
        keyReleaseUnverified=forwardedKeys.isNotEmpty()
        return !keyReleaseUnverified
    }
    override fun showNotifications() {
        authorize()
        privileged {
            runCatching {
                val manager=context.getSystemService("statusbar")
                manager.javaClass.getMethod("expandNotificationsPanel").invoke(manager)
            }.isSuccess
        }
    }
    private fun privileged(block:()->Boolean):Boolean {
        val identity=Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun focused():Boolean=privileged {
        runCatching { val info=focusMethod.invoke(taskManager); val top=info.javaClass.getField("topActivity").get(info) as? ComponentName
            top?.packageName==targetPackage && info.javaClass.getField("displayId").getInt(info)==journal.displayId }.getOrDefault(false)
    }
    /** Query failure/null focus never establishes that Android switched away from this stream. */
    private fun focusDefinitelyElsewhere():Boolean=privileged {
        runCatching {
            val info=requireNotNull(focusMethod.invoke(taskManager))
            val top=info.javaClass.getField("topActivity").get(info) as? ComponentName
            val display=info.javaClass.getField("displayId").getInt(info)
            top!=null && display>=0 && (top.packageName!=targetPackage || display!=journal.displayId)
        }.getOrDefault(false)
    }
    private fun recoveryContextMatches(original:RecoverySnapshot,originalPackage:String):Boolean=privileged {
        runCatching {
            targetPackage==originalPackage && targetUid==original.targetUid &&
                coordinatePolicy==original.coordinatePolicy && journal.displayId==original.displayId &&
                context.packageManager.getPackageUid(originalPackage,0)==original.targetUid &&
                context.getSystemService(DisplayManager::class.java).getDisplay(original.displayId)?.isValid==true &&
                nativeContextAllows(originalPackage,original.displayId)
        }.getOrDefault(false)
    }
    private fun selectTarget(packageName:String, expectedUid:Int?=null,
                             policy:TouchCoordinatePolicy=TouchCoordinatePolicy.STRICT,displayId:Int=0) {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && packageName!=identity.applicationId)
        require(displayId >= 0) { "Pantalla de destino no válida" }
        val identity=Binder.clearCallingIdentity()
        val uid=try {
            require(context.getSystemService(DisplayManager::class.java).getDisplay(displayId)?.isValid==true) { "La pantalla de destino ya no está disponible" }
            require(context.packageManager.getLaunchIntentForPackage(packageName)!=null) { "Destino sin actividad de lanzamiento" }
            context.packageManager.getPackageUid(packageName,0)
        } finally { Binder.restoreCallingIdentity(identity) }
        require(uid>=10000 && (expectedUid==null || expectedUid==uid)) { "El UID del destino cambió" }
        val destinationChanged=uid!=targetUid || packageName!=targetPackage || displayId!=journal.displayId
        if(destinationChanged) {
            // Release with the OLD UID/package/display before any destination fields change.
            // A key UP must never be redirected to the next application.
            check(releaseForwardedKeys()) { "Vuelve al destino anterior para liberar sus teclas" }
            check(nativeCapture?.active!=true ||
                (nativeCapture.targetPackage==packageName && nativeCapture.displayId==displayId)) {
                "Detén la captura anterior antes de cambiar el destino"
            }
        }
        val snap=journal.snapshot()
        check(DestinationChangePolicy.maySelect(snap,recoveryScheduled)) { "Recupera el gesto anterior antes de cambiar destino" }
        if(uid!=targetUid || packageName!=targetPackage || policy!=coordinatePolicy || displayId!=journal.displayId) {
            connection?.close(); connection=null
            // A display belongs to the complete gesture. Never retarget an unfinished journal.
            if(displayId!=journal.displayId) journal=RecoveryJournal(displayId=displayId)
            targetPackage=packageName; targetUid=uid; coordinatePolicy=policy
        }
    }
    private fun attachClient(newClient:IBackendEvents) {
        client?.let { old -> clientDeath?.let { runCatching { old.asBinder().unlinkToDeath(it,0) } } }
        client=newClient
        val token=newClient.asBinder()
        clientDeath=IBinder.DeathRecipient { handler.post { synchronized(this) {
            if(client?.asBinder()==token) {
                Log.i("MobiSupervisor","controller_died sid=${journal.snapshot().sessionId} uptime=${SystemClock.uptimeMillis()}")
                client=null
                if(appIsStopped()) stopForForceStop()
                else { releaseInternal(); state="PAUSED_CONTROLLER_GONE" }
            }
        } } }
        token.linkToDeath(clientDeath!!,0)
    }
    @Synchronized override fun begin(client:IBackendEvents,width:Int,height:Int):Long {
        return beginTarget(client,width,height,"dev.mobimapper.inputlab")
    }
    @Synchronized override fun beginTarget(client:IBackendEvents,width:Int,height:Int,targetPackage:String):Long {
        return beginTargetWithPolicy(client,width,height,targetPackage,TouchCoordinatePolicy.STRICT)
    }
    @Synchronized override fun beginContinuousTarget(client:IBackendEvents,width:Int,height:Int,targetPackage:String):Long {
        return beginTargetWithPolicy(client,width,height,targetPackage,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)
    }
    @Synchronized override fun beginTargetOnDisplay(client:IBackendEvents,width:Int,height:Int,targetPackage:String,
                                                   displayId:Int,continuousCamera:Boolean):Long =
        beginTargetWithPolicy(client,width,height,targetPackage,
            if(continuousCamera) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT,displayId)
    private fun beginTargetWithPolicy(client:IBackendEvents,width:Int,height:Int,targetPackage:String,
                                      policy:TouchCoordinatePolicy,displayId:Int=0):Long {
        authorize(); check(!closed)
        selectTarget(targetPackage,policy=policy,displayId=displayId)
        check(focused()) { "Destino sin foco" }
        check(!keyReleaseUnverified || releaseForwardedKeys()) { "Vuelve al destino para liberar las teclas anteriores" }
        captureRequired=nativeCapture?.active==true
        require(width in 100..8192 && height in 100..8192)
        val old=journal.snapshot()
        check(old.possibleContacts==0 && old.state!=RecoveryInjectionState.RECOVERY_NEEDED) { "Recuperación pendiente" }
        check(!appIsStopped()) { "Aplicación detenida por el usuario" }
        // Only an explicit request from the reopened app can lift the restart inhibition.
        forceStopped=false
        ensureWorker()
        val id=++nextSession
        check(journal.begin(id,width,height,targetUid,policy)) { "Sesión anterior no terminó" }
        attachClient(client); state="ACTIVE"; error=""
        liveCancellationAccepted=false
        syncCapturePointer()
        pointer.setSessionActive(targetPackage!="dev.mobimapper.inputlab")
        clearExpiry()
        val timeout=Runnable { synchronized(this) {
            if(!closed && journal.canExpire(id)) {
                releaseInternal()
                if(!recoveryScheduled && !forceStopped) state="PAUSED_TIMEOUT"
            }
        } }
        expiry=timeout
        handler.postDelayed(timeout,30*60*1000L)
        timingSessionId=id
        focusTimingCount=0L; pipeTimingCount=0L
        return id
    }
    @Synchronized override fun inject(sessionId:Long,action:Int,downTime:Long,eventTime:Long,ids:IntArray,xs:FloatArray,ys:FloatArray):Boolean {
        authorize()
        if(state!="ACTIVE" || client?.asBinder()?.isBinderAlive!=true) return false
        if(captureRequired && nativeCapture?.active!=true) {
            releaseInternal();state="PAUSED_CAPTURE_LOST";return false
        }
        val focusStart=SystemClock.elapsedRealtimeNanos()
        val targetFocused=try { focused() } finally {
            focusTimes[(focusTimingCount++ and 511L).toInt()]=SystemClock.elapsedRealtimeNanos()-focusStart
        }
        if(!targetFocused) { releaseInternal(); state="PAUSED_FOCUS"; return false }
        val now=SystemClock.uptimeMillis()
        require(eventTime<=now && now-eventTime<5000)
        val frame=TouchFrame(action,downTime,eventTime,ids,xs,ys)
        if(!journal.prepare(sessionId,frame)) return false
        return try {
            val pipeStart=SystemClock.elapsedRealtimeNanos()
            val accepted=try { send(frame,sessionId,1) } finally {
                pipeTimes[(pipeTimingCount++ and 511L).toInt()]=SystemClock.elapsedRealtimeNanos()-pipeStart
            }
            journal.acknowledged(sessionId,frame,accepted)
            if(accepted) injected++ else scheduleRecovery("INJECTION_REJECTED")
            accepted
        } catch(e:Exception) { journal.fail(sessionId); scheduleRecovery("WORKER_PIPE: ${e.javaClass.simpleName}"); false }
    }
    /**
     * One camera-only sequential/interlaced replacement, with per-frame worker ACKs as inject.
     * The caller owns a fresh pipe for this operation. A complete 12-byte record is committed
     * after journal.prepare and before each send, so guardian death cannot hide which of the
     * caller's immutable planned frames might have reached Android. Event times are refreshed
     * here; pointer coordinates, IDs and gesture down times never differ from that plan.
     */
    @Synchronized override fun injectCameraReset(sessionId:Long,actions:IntArray,downTimes:LongArray,
        eventTimes:LongArray,counts:IntArray,ids:IntArray,xs:FloatArray,ys:FloatArray,
        progress:ParcelFileDescriptor?):Int {
        var writer:ParcelFileDescriptor.AutoCloseOutputStream?=null
        val started=SystemClock.elapsedRealtimeNanos()
        try {
            // Acquire ownership before authorization as well: every return/exception closes
            // the remote writer. A regular file or a full pipe cannot stall the guardian.
            if(progress!=null) writer=ParcelFileDescriptor.AutoCloseOutputStream(progress)
            authorize()
            require(progress!=null && writer!=null) { "A camera reset progress pipe is required" }
            val descriptor=progress.fileDescriptor
            require(OsConstants.S_ISFIFO(Os.fstat(descriptor).st_mode)) { "Camera reset progress must be a pipe" }
            val descriptorFlags=Os.fcntlInt(descriptor,OsConstants.F_GETFL,0)
            Os.fcntlInt(descriptor,OsConstants.F_SETFL,descriptorFlags or OsConstants.O_NONBLOCK)
            require(actions.size==3 && downTimes.size==3 && eventTimes.size==3 && counts.size==3 &&
                counts.all { it in 1..10 }) { "Camera reset requires three bounded frames" }
            val total=counts.sum()
            require(ids.size==total && xs.size==total && ys.size==total)
            var offset=0
            val sequence=CameraResetSequence(actions.indices.map { index ->
                val end=offset+counts[index]
                TouchFrame(actions[index],downTimes[index],eventTimes[index],ids.copyOfRange(offset,end),
                    xs.copyOfRange(offset,end),ys.copyOfRange(offset,end)).also { offset=end }
            })
            val frames=sequence.frames
            val original=journal.snapshot()
            val previous=original.activeFrame ?: return 0
            val channel=connection ?: return 0
            val captureId=ownedCaptureId
            if(sessionId!=original.sessionId || original.coordinatePolicy!=coordinatePolicy ||
                original.state!=RecoveryInjectionState.ACTIVE || original.attempted!=null ||
                !cameraResetOwnershipValid(sessionId,original,channel,captureId)) return 0
            require(sequence.matchesPrevious(previous)) {
                "Camera reset must replace the exact acknowledged contact"
            }
            if(sequence.kind==CameraResetKind.INTERLACED)
                require(coordinatePolicy==TouchCoordinatePolicy.STRICT) {
                    "An interlaced reset requires the bounded coordinate policy"
                }
            if(coordinatePolicy==TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)
                require(frames.all { it.ids.last()==TouchCoordinatePolicy.CAMERA_ID }) {
                    "The continuous camera capability reserves only ID 9"
                }
            val now=SystemClock.uptimeMillis()
            var prior:TouchFrame?=previous
            var priorTime=previous.eventTime
            // Reject the whole malformed plan before its first edge. Runtime admission below
            // is still checked afresh before every actual prepare/send.
            for(frame in frames) {
                require(frame.eventTime>=priorTime && frame.eventTime<=now && now-frame.eventTime<5000 &&
                    coordinatePolicy.allowsCoordinates(frame,original.width,original.height,prior)) {
                    "Camera reset plan does not match the original coordinate policy"
                }
                prior=CameraResetSequence.postEvent(frame)
                priorTime=frame.eventTime
            }
            cameraResetBatches++
            if(sequence.kind==CameraResetKind.INTERLACED) cameraResetInterlacedBatches++
            cameraResetLastKind=sequence.kind.name
            cameraResetLastEventTimes=LongArray(3)
            val focusLease=CameraFocusLease()
            var interlacedEventTime=0L
            val record=ByteBuffer.allocate(12).putInt(0x55435231)
            for(index in frames.indices) {
                if(!cameraResetOwnershipValid(sessionId,original,channel,captureId)) {
                    releaseInternal();return index
                }
                val checkAt=SystemClock.elapsedRealtimeNanos()
                val observation=focusLease.observationAt(checkAt)
                if(observation==null) {
                    val queryStarted=SystemClock.elapsedRealtimeNanos()
                    focusLease.observe(queryStarted,cameraResetFocused())
                } else if(observation) cameraResetPrefetchHits++
                val focusedNow=focusLease.observationAt(SystemClock.elapsedRealtimeNanos())==true
                if(!focusedNow || !cameraResetMetadataAllows(original) ||
                    !cameraResetOwnershipValid(sessionId,original,channel,captureId) ||
                    focusLease.observationAt(SystemClock.elapsedRealtimeNanos())!=true) {
                    releaseInternal();if(state!="BLOCKED") state="PAUSED_FOCUS";return index
                }
                val eventTime=if(sequence.kind==CameraResetKind.INTERLACED && index==1) interlacedEventTime
                    else SystemClock.uptimeMillis()
                if(sequence.kind==CameraResetKind.INTERLACED && index==0) interlacedEventTime=eventTime
                val frame=frames[index].copy(eventTime=eventTime)
                if(!journal.prepare(sessionId,frame)) return index
                // Atomic-sized nonblocking write: no worker send occurs on write failure.
                record.putInt(4,index+1);record.putInt(8,index)
                try { writer.write(record.array()) } catch(_:Exception) {
                    // This command has not entered the worker pipe. Restore the exact
                    // acknowledged prefix; worker-send failures must never use this path.
                    if(!journal.abortUnsent(sessionId,frame)) {
                        journal.fail(sessionId);scheduleRecovery("CAMERA_RESET_PROGRESS_ROLLBACK_FAILED")
                    }
                    return index
                }
                try {
                    val pipeStart=SystemClock.elapsedRealtimeNanos()
                    val accepted=try {
                        send(frame,sessionId,1,if(index==frames.lastIndex) null else ({
                            // A young observation spans this single semantic replacement.
                            // Only an ageing lease overlaps a new focus query with this ACK.
                            if(focusLease.shouldPrefetch(SystemClock.elapsedRealtimeNanos())) {
                                val queryStarted=SystemClock.elapsedRealtimeNanos()
                                focusLease.observe(queryStarted,cameraResetFocused())
                            }
                        }))
                    } finally {
                        pipeTimes[(pipeTimingCount++ and 511L).toInt()]=SystemClock.elapsedRealtimeNanos()-pipeStart
                    }
                    check(journal.acknowledged(sessionId,frame,accepted)) { "Camera reset journal ACK mismatch" }
                    if(!accepted) { scheduleRecovery("CAMERA_RESET_REJECTED");return -(index+1) }
                    injected++;cameraResetFrames++;cameraResetLastEventTimes[index]=frame.eventTime
                } catch(failure:Exception) {
                    journal.fail(sessionId);scheduleRecovery("CAMERA_RESET_PIPE: ${failure.javaClass.simpleName}")
                    return -(index+1)
                }
            }
            return 3
        } finally {
            cameraResetLastDurationNanos=SystemClock.elapsedRealtimeNanos()-started
            runCatching { if(writer!=null) writer.close() else progress?.close() }
        }
    }
    /** No destination, worker, client or native capture may be replaced inside a reset. */
    private fun cameraResetOwnershipValid(sessionId:Long,original:RecoverySnapshot,
        channel:WorkerConnection,captureId:Long):Boolean {
        val capture=nativeCapture
        val current=journal.snapshot()
        return !closed && !forceStopped && !recoveryScheduled && state=="ACTIVE" &&
            client?.asBinder()?.isBinderAlive==true && current.sessionId==sessionId &&
            current.state==RecoveryInjectionState.ACTIVE && current.attempted==null &&
            current.targetUid==original.targetUid && current.displayId==original.displayId &&
            current.coordinatePolicy==original.coordinatePolicy && coordinatePolicy==original.coordinatePolicy &&
            targetUid==original.targetUid && connection===channel && channel.alive &&
            channel.displayId==original.displayId && captureRequired && captureId>0L &&
            ownedCaptureId==captureId && capture?.activeId==captureId && capture.active &&
            capture.targetPackage==targetPackage && capture.displayId==original.displayId
    }
    private fun cameraResetFocused():Boolean {
        val started=SystemClock.elapsedRealtimeNanos()
        return try { focused() } finally {
            focusTimes[(focusTimingCount++ and 511L).toInt()]=SystemClock.elapsedRealtimeNanos()-started
        }
    }
    /** Cached WMS provenance complements the short-lived independent task-focus query. */
    private fun cameraResetMetadataAllows(original:RecoverySnapshot):Boolean {
        val snapshot=windowContexts.snapshot()
        if(!snapshot.getBoolean("valid") || snapshot.getInt("focusedDisplayId",-1)!=original.displayId) return false
        val focused=snapshot.getString("focusedPackage")
        if(focused!=targetPackage && focused!=identity.applicationId) return false
        @Suppress("DEPRECATION")
        val windows=snapshot.getParcelableArrayList<Bundle>("windows").orEmpty()
        val target=windows.filter { it.getInt("displayId",-1)==original.displayId && it.getInt("type")==1 &&
            it.getString("packageName")==targetPackage && it.getInt("ownerUid",-1)==original.targetUid }
            .maxByOrNull { (it.getInt("right")-it.getInt("left")).toLong()*(it.getInt("bottom")-it.getInt("top")) } ?: return false
        return windows.none { it.getInt("displayId",-1)==original.displayId && it.getInt("layer")>target.getInt("layer") &&
            it.getInt("ownerUid",-1)!=appUid && it.getInt("ownerUid",-1)!=original.targetUid &&
            it.getInt("left")<=target.getInt("left") && it.getInt("top")<=target.getInt("top") &&
            it.getInt("right")>=target.getInt("right") && it.getInt("bottom")>=target.getInt("bottom") }
    }
    @Synchronized override fun release(sessionId:Long):Boolean {
        authorize()
        if(sessionId!=journal.snapshot().sessionId) return false
        syncCapturePointer()
        pointer.setSessionActive(false,forceRestore=true)
        if(recoveryScheduled || state=="BLOCKED") return false
        return releaseInternal()
    }
    private fun releaseInternal(scheduleOnFailure:Boolean=true):Boolean {
        val beforeRelease=journal.snapshot()
        if(beforeRelease.state==RecoveryInjectionState.ACTIVE && focusDefinitelyElsewhere())
            journal.failForFocusLoss(beforeRelease.sessionId)
        releaseForwardedKeys()
        syncCapturePointer()
        pointer.setSessionActive(false)
        clearExpiry()
        val snap=journal.snapshot()
        if(snap.state==RecoveryInjectionState.RECOVERY_ADMITTED || snap.state==RecoveryInjectionState.STOPPED) return true
        journal.fail(snap.sessionId)
        val frame=journal.cancellationFrame(SystemClock.uptimeMillis())
        if(frame==null) { journal.cancelled(snap.sessionId,true); state="PAUSED"; return true }
        val ok=try { send(frame,snap.sessionId,2) } catch(_:Exception) { false }
        journal.cancelled(snap.sessionId,ok)
        if(ok) state="PAUSED" else if(scheduleOnFailure) scheduleRecovery("RELEASE_PIPE_LOST")
        return ok
    }
    /** Close this guardian's original stream. No caller-supplied destination or replacement journal. */
    @Synchronized override fun recoverSession(sessionId:Long):Boolean {
        authorize()
        val original=journal.snapshot()
        val originalPackage=targetPackage
        if(closed || forceStopped || appIsStopped() || recoveryScheduled || sessionId<=0L ||
            sessionId!=original.sessionId || original.state==RecoveryInjectionState.ACTIVE ||
            !recoveryContextMatches(original,originalPackage)) return false
        nativeCapture?.stop(nativeCapture.activeId)
        ownedCaptureId=nativeCapture?.activeId ?: 0L
        syncCapturePointer(forceRestore=true)
        pointer.setSessionActive(false)
        if(nativeCapture?.active==true || !releaseForwardedKeys() || recoveryScheduled ||
            !recoveryContextMatches(original,originalPackage)) return false
        if(original.state in setOf(RecoveryInjectionState.RECOVERY_ADMITTED,RecoveryInjectionState.STOPPED)) {
            state="RECOVERED";error=""
            return true
        }
        if(original.state!=RecoveryInjectionState.RECOVERY_NEEDED) return false
        if(original.possibleContacts==0) {
            // A fully acknowledged terminal event leaves no candidate for another CANCEL.
            val closed=releaseInternal(scheduleOnFailure=false)
            if(closed) { state="RECOVERED";error="" }
            return closed && recoveryContextMatches(original,originalPackage)
        }
        ++recoveryEpoch
        state="RECOVERING"
        val previousGeneration=workerGeneration
        connection=null
        val retirementDeadline=SystemClock.uptimeMillis()+1_500L
        for(old in unretiredWorkers.toList()) {
            val remaining=retirementDeadline-SystemClock.uptimeMillis()
            if(remaining<2L || !old.closeAndAwaitTermination(minOf(750L,remaining/2))) {
                state="BLOCKED";error="PREVIOUS_WORKER_EXIT_UNCONFIRMED"
                return false
            }
            unretiredWorkers.remove(old)
        }
        if(!recoveryContextMatches(original,originalPackage)) {
            state="BLOCKED";error="RECOVERY_CONTEXT_CHANGED";return false
        }
        return try {
            ensureWorker()
            val replacement=requireNotNull(connection)
            val cancel=requireNotNull(journal.cancellationFrame(SystemClock.uptimeMillis()))
            lastRecoveryFrame=cancel
            val accepted=send(cancel,sessionId,2) // A thrown/missing ACK cannot establish rejection.
            var reconciled=false
            if(!accepted && replacement.alive && connection===replacement &&
                recoveryContextMatches(original,originalPackage) &&
                journal.rejectedFocusLossCancellation(sessionId,cancel,true,previousGeneration,replacement.generation) &&
                DispatcherTouchState.isDefinitelyUntouched() &&
                replacement.alive && connection===replacement && !appIsStopped() &&
                recoveryContextMatches(original,originalPackage)) {
                reconciled=journal.reconciledFocusLoss(sessionId,replacement.generation,true)
            }
            if(!reconciled) journal.cancelled(sessionId,accepted)
            lastRecovery=SystemClock.uptimeMillis()
            val closed=accepted || reconciled
            state=if(closed) "RECOVERED" else "BLOCKED"
            error=if(closed) "" else "RECOVERY_CANCEL_NOT_ADMITTED"
            if(closed) recoveredCount++
            // An admitted CANCEL remains recorded if focus changed after it; no input resumes.
            closed && !keyReleaseUnverified && recoveryContextMatches(original,originalPackage)
        } catch(failure:Exception) {
            state="BLOCKED";error="RECOVERY_ACK_UNCONFIRMED"
            connection?.close();connection=null
            false
        }
    }
    @Synchronized override fun recoverOrphan(client:IBackendEvents,width:Int,height:Int,downTime:Long,ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int):Long {
        return recoverTargetOrphan(client,width,height,downTime,ids,xs,ys,originalTargetUid,"dev.mobimapper.inputlab")
    }
    @Synchronized override fun recoverTargetOrphan(client:IBackendEvents,width:Int,height:Int,downTime:Long,ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int,originalTargetPackage:String):Long {
        return recoverTargetOrphanWithPolicy(client,width,height,downTime,ids,xs,ys,originalTargetUid,
            originalTargetPackage,TouchCoordinatePolicy.STRICT)
    }
    @Synchronized override fun recoverContinuousTargetOrphan(client:IBackendEvents,width:Int,height:Int,downTime:Long,ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int,originalTargetPackage:String):Long {
        return recoverTargetOrphanWithPolicy(client,width,height,downTime,ids,xs,ys,originalTargetUid,
            originalTargetPackage,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)
    }
    @Synchronized override fun recoverTargetOrphanOnDisplay(client:IBackendEvents,width:Int,height:Int,downTime:Long,
        ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int,originalTargetPackage:String,
        originalDisplayId:Int,continuousCamera:Boolean):Long =
        recoverTargetOrphanWithPolicy(client,width,height,downTime,ids,xs,ys,originalTargetUid,originalTargetPackage,
            if(continuousCamera) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT,originalDisplayId)
    private fun recoverTargetOrphanWithPolicy(client:IBackendEvents,width:Int,height:Int,downTime:Long,
        ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int,originalTargetPackage:String,
        policy:TouchCoordinatePolicy,displayId:Int=0):Long {
        authorize(); check(!closed)
        require(width in 100..8192 && height in 100..8192)
        selectTarget(originalTargetPackage,originalTargetUid,policy,displayId)
        check(focused()) { "Vuelve al destino para recuperar el gesto anterior" }
        require(originalTargetUid==targetUid) { "Original target UID changed" }
        check(!appIsStopped()) { "Aplicación detenida por el usuario" }
        val id=++nextSession
        val frame=TouchFrame(3,downTime,SystemClock.uptimeMillis(),ids,xs,ys)
        check(journal.adoptForRecovery(id,width,height,targetUid,frame,policy)) { "No se puede adoptar el gesto" }
        forceStopped=false
        attachClient(client); state="RECOVERING"; lastDetection=SystemClock.uptimeMillis()
        ensureWorker()
        val accepted=runCatching { send(frame,id,2) }.getOrDefault(false)
        val reconciled=!accepted && DispatcherTouchState.isDefinitelyUntouched() && journal.reconciledOrphan(id,true)
        if(!reconciled) journal.cancelled(id,accepted)
        state=if(accepted || reconciled) "RECOVERED" else "BLOCKED"
        lastRecovery=SystemClock.uptimeMillis()
        return if(accepted || reconciled) id else 0
    }
    private fun notifyClient(phase:Int,reason:String) {
        val sid=journal.snapshot().sessionId
        runCatching { client?.recovery(sid,workerGeneration,phase,reason) }
    }
    private fun appIsStopped():Boolean = runCatching {
        context.packageManager.getApplicationInfo(identity.applicationId,0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrElse {
        // An unavailable package state cannot authorize a new privileged child.
        Log.w("MobiSupervisor","cannot verify controller stop state",it)
        true
    }
    /** Cleanup may use the existing worker; it must never start a replacement after Force stop. */
    private fun stopForForceStop() {
        nativeCapture?.stop(nativeCapture.activeId)
        ownedCaptureId=0L
        pointer.releaseOwners()
        forceStopped=true
        recoveryEpoch++
        recoveryScheduled=false
        clearExpiry()
        val released=releaseInternal(scheduleOnFailure=false)
        val old=connection
        connection=null
        old?.close()
        state="PAUSED_FORCE_STOP"
        if(!released) error="FORCE_STOP_RELEASE_UNVERIFIED"
        Log.i("MobiSupervisor","force_stop sid=${journal.snapshot().sessionId} cancelAdmitted=$released restartInhibited=true")
    }
    private fun scheduleRecovery(reason:String) {
        if(closed || recoveryScheduled) return
        syncCapturePointer()
        pointer.setSessionActive(false)
        if(forceStopped || appIsStopped()) { stopForForceStop(); return }
        val snap=journal.snapshot()
        journal.fail(snap.sessionId)
        recoveryScheduled=true; state="RECOVERING"; error=reason; lastDetection=SystemClock.uptimeMillis()
        Log.w("MobiSupervisor","recovery_detected sid=${snap.sessionId} worker=$workerGeneration pid=$lastWorkerPid confirmed=${snap.activeContacts} possible=${journal.snapshot().possibleContacts} action=${snap.attempted?.action ?: snap.lastConfirmed?.action} uptime=$lastDetection reason=$reason")
        notifyClient(1,reason)
        val epoch=++recoveryEpoch
        val failedWorkerGeneration=workerGeneration
        handler.post { synchronized(this) {
            if(closed || epoch!=recoveryEpoch) return@synchronized
            if(forceStopped || appIsStopped()) { stopForForceStop(); return@synchronized }
            val sid=journal.snapshot().sessionId
            var accepted=false
            var reconciled=false
            // A live worker can close a rejected/ambiguous injection before its EOF cleanup
            // loses the acknowledgement. Keep the journal's original candidate unchanged.
            if(connection?.alive==true) {
                val cancel=journal.cancellationFrame(SystemClock.uptimeMillis())
                lastRecoveryFrame=cancel
                accepted=runCatching { cancel==null || send(cancel,sid,2) }.getOrDefault(false)
                liveCancellationAccepted=accepted
                Log.i("MobiSupervisor","live_worker_cancel sid=$sid admitted=$accepted worker=$workerGeneration")
            }
            connection?.close(); connection=null
            for(attempt in 1..3) {
                if(accepted) break
                if(forceStopped || appIsStopped()) { stopForForceStop(); return@synchronized }
                recoveryAttempts++
                try {
                    // Never use a provider or launch the controller during worker recovery.
                    ensureWorker()
                    val cancel=journal.cancellationFrame(SystemClock.uptimeMillis())
                    lastRecoveryFrame=cancel
                    accepted=cancel==null || send(cancel,sid,2)
                    if(accepted) break
                    // Only a real negative reply from a live replacement worker establishes
                    // this provenance. A pipe exception remains ambiguous and skips this path.
                    if(cancel!=null && connection?.alive==true &&
                        (connection?.generation ?: 0)>failedWorkerGeneration) {
                        journal.rejectedRecoveryCancellation(sid,cancel)
                    }
                    // An UP may have completed before the worker died, while its reply was lost.
                    // A rejected MOVE has a separate, stricter failed-CANCEL provenance gate.
                    // This exceptional query never runs in ordinary movement processing.
                    val attempted=journal.snapshot().attempted
                    val rejectedMove=journal.canReconcileRejectedMove(sid)
                    if((attempted?.action==1 || rejectedMove) && DispatcherTouchState.isDefinitelyUntouched()) {
                        reconciled=if(rejectedMove) connection?.takeIf {
                            it.alive && it.generation>failedWorkerGeneration
                        }?.let { journal.reconciledRejectedMove(sid,true) } ?: false
                            else journal.reconciledTerminal(sid,true)
                        if(reconciled) break
                    }
                } catch(e:Exception) { error="${e.javaClass.simpleName}: ${e.message}"; connection?.close(); connection=null }
            }
            if(forceStopped || appIsStopped()) { stopForForceStop(); return@synchronized }
            if(!reconciled) journal.cancelled(sid,accepted)
            lastRecovery=SystemClock.uptimeMillis(); recoveryScheduled=false
            state=if(accepted || reconciled) "RECOVERED" else "BLOCKED"
            if(accepted || reconciled) recoveredCount++
            Log.i("MobiSupervisor","recovery_complete sid=$sid worker=$workerGeneration pid=$lastWorkerPid admitted=$accepted terminalReconciled=$reconciled rejectedMoveReconciled=${journal.snapshot().rejectedMoveReconciled} attempts=$recoveryAttempts uptime=$lastRecovery durationMs=${lastRecovery-lastDetection} receiverVerified=false")
            val message=when {
                accepted -> "Cancelación admitida; iniciar manualmente"
                journal.snapshot().rejectedMoveReconciled -> "Android no conserva contactos tras rechazar MOVE y CANCEL; iniciar manualmente"
                reconciled -> "UP terminal cerrado en Android; iniciar manualmente"
                else -> "No se pudo cancelar el gesto anterior"
            }
            notifyClient(if(accepted || reconciled) 2 else 3,message)
        } }
    }
    private fun ensureWorker() {
        if(connection?.alive==true) return
        check(!closed && !forceStopped && !appIsStopped()) { "Worker restart inhibited" }
        val generation=++workerGeneration
        check(unretiredWorkers.size<8) { "Previous workers have not terminated" }
        val apk=context.packageManager.getApplicationInfo(identity.applicationId,0).sourceDir
        val builder=ProcessBuilder("/system/bin/app_process","/system/bin","--nice-name=${identity.injectorProcess}","dev.mobimapper.mobimapper.backend.InjectionWorkerMain",generation.toString(),targetUid.toString(),
            if(coordinatePolicy==TouchCoordinatePolicy.CONTINUOUS_CAMERA_9) "1" else "0",journal.displayId.toString())
        builder.environment()["CLASSPATH"]=apk
        builder.redirectError(File("/data/local/tmp/mobimapper-injector.log"))
        val process=builder.start()
        val worker=try { WorkerConnection(process,generation,journal.displayId) } catch(e:Exception) { process.destroyForcibly(); throw e }
        connection=worker; lastWorkerPid=worker.pid
        unretiredWorkers.add(worker)
        Thread({ process.waitFor(); synchronized(this) {
            unretiredWorkers.remove(worker)
            if(!closed && connection===worker) {
                connection=null; worker.close()
                val snap=journal.snapshot()
                if(snap.state==RecoveryInjectionState.ACTIVE || snap.possibleContacts>0) scheduleRecovery("WORKER_EXIT")
            }
        } },"MobiWorkerDeath").apply { isDaemon=true; start() }
        Log.i("MobiSupervisor","worker_started generation=$generation pid=$lastWorkerPid uptime=${SystemClock.uptimeMillis()}")
    }
    private fun send(frame:TouchFrame,sid:Long,mode:Int,afterWriteBeforeAck:(()->Unit)?=null):Boolean {
        val snapshot=journal.snapshot()
        val fault=if(mode==1 && frame.action and 255 == faultAction) faultStage else 0
        if(fault!=0) { faultAction=-1; faultStage=0 }
        val worker=requireNotNull(connection)
        check(worker.displayId==snapshot.displayId) { "Worker display no longer matches the original gesture" }
        return worker.send(sid,frame,snapshot.width,snapshot.height,mode,fault,afterWriteBeforeAck)
    }
    @Synchronized override fun armTestFault(action:Int,stage:Int) {
        authorize(); require(action in intArrayOf(0,1,2,3,5,6) && stage in 1..3)
        faultAction=action; faultStage=stage
    }
    @Synchronized override fun status():Bundle {
        authorize(); val snap=journal.snapshot()
        fun milliseconds(values:LongArray,count:Long):DoubleArray {
            if(count==0L) return doubleArrayOf()
            val sorted=values.copyOf(minOf(count,values.size.toLong()).toInt()).apply { sort() }
            return doubleArrayOf(.50,.95,.99).map { sorted[((sorted.size-1)*it).toInt()]/1e6 }.toDoubleArray()
        }
        return Bundle().apply {
            putInt("protocol",4); putInt("uid",Process.myUid()); putInt("supervisorPid",Process.myPid()); putInt("workerPid",lastWorkerPid)
            putBoolean("captureSupported",nativeCapture!=null)
            putBoolean("nativeCaptureRateSupported",nativeCapture!=null)
            putBoolean("nativeMotionBatchSupported",nativeCapture!=null)
            putString("captureProvider","native-evdev")
            putString("captureError",captureFailure)
            putBundle("nativeCapture",nativeCapture?.status())
            putBoolean("keyReleaseUnverified",keyReleaseUnverified)
            putInt("forwardedKeys",forwardedKeys.size)
            putString("targetPackage",targetPackage)
            putString("coordinatePolicy",coordinatePolicy.name)
            putBoolean("continuousCameraSupported",true)
            putBoolean("cameraResetBatchSupported",true)
            putBoolean("cameraResetInterlacedSupported",true)
            putLong("cameraResetBatches",cameraResetBatches);putLong("cameraResetFrames",cameraResetFrames)
            putLong("cameraResetInterlacedBatches",cameraResetInterlacedBatches)
            putString("cameraResetLastKind",cameraResetLastKind)
            putLong("cameraResetFocusPrefetchHits",cameraResetPrefetchHits)
            putLongArray("cameraResetLastEventTimes",cameraResetLastEventTimes.copyOf())
            putDouble("cameraResetLastDurationMs",cameraResetLastDurationNanos/1e6)
            putBoolean("multiDisplaySupported",true)
            putBoolean("systemPointerHidden",pointer.hidden); putString("systemPointerError",pointer.error)
            putBoolean("workerAlive",connection?.alive==true); putBoolean("controllerBinderAlive",client?.asBinder()?.isBinderAlive==true)
            putLong("workerGeneration",workerGeneration); putLong("sessionId",snap.sessionId); putString("recoveryState",state)
            putInt("confirmedContacts",snap.activeContacts); putInt("possibleContacts",snap.possibleContacts); putInt("activeContacts",snap.possibleContacts)
            putBoolean("cancelAdmitted",snap.cancellationAccepted); putBoolean("receiverVerified",false)
            putBoolean("liveWorkerCancelAdmitted",liveCancellationAccepted)
            putBoolean("terminalReconciled",snap.terminalReconciled)
            putBoolean("rejectedMoveReconciled",snap.rejectedMoveReconciled)
            putBoolean("focusLossReconciled",snap.focusLossReconciled)
            putBoolean("sessionRecoverySupported",true)
            putBoolean("releaseUnverified",snap.state==RecoveryInjectionState.RECOVERY_NEEDED)
            putBoolean("injectPermission",context.checkPermission("android.permission.INJECT_EVENTS",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED)
            putInt("targetUid",targetUid); putInt("displayId",snap.displayId); putInt("source",4098); putInt("deviceId",0); putInt("effectiveDeviceId",-1)
            putBoolean("targetFocused",focused()); putString("focusError",""); putBoolean("targeted",true); putInt("maxContacts",10)
            putLong("timingSessionId",timingSessionId)
            putLong("focusTimingCount",focusTimingCount); putLong("pipeTimingCount",pipeTimingCount)
            putDoubleArray("focusMs",milliseconds(focusTimes,focusTimingCount))
            putDoubleArray("pipeMs",milliseconds(pipeTimes,pipeTimingCount))
            putString("error",error); putLong("injected",injected); putInt("recoveryAttempts",recoveryAttempts); putInt("recoveredCount",recoveredCount)
            putLong("detectedUptime",lastDetection); putLong("recoveredUptime",lastRecovery); putLong("uptimeMs",SystemClock.uptimeMillis())
            val f=snap.attempted ?: snap.activeFrame ?: snap.lastConfirmed
            f?.let { putInt("lastAction",it.action); putLong("downTime",it.downTime); putLong("lastEventTime",it.eventTime); putIntArray("ids",it.ids); putFloatArray("xs",it.xs); putFloatArray("ys",it.ys) }
            lastRecoveryFrame?.let { putIntArray("recoveryIds",it.ids); putLong("recoveryDownTime",it.downTime); putLong("recoveryEventTime",it.eventTime) }
        }
    }
    @Synchronized override fun shutdown() {
        authorize(); nativeCapture?.close();ownedCaptureId=0L;pointer.releaseOwners();releaseInternal(); closed=true
        windowContexts.close()
        runCatching { reattachServer.close() }
        connection?.close(); connection=null
        handler.post { Process.killProcess(Process.myPid()) }
    }
    /** Sent only through the shell-authenticated provider; never included in diagnostics. */
    fun addReattachConfiguration(extras:Bundle) {
        extras.putInt("reattachPort",reattachServer.localPort)
        extras.putByteArray("reattachNonce",reattachNonce.copyOf())
    }
    /** A private capability requests this one fixed operation; there is no input/socket protocol. */
    fun startReattachServer(register:()->Bundle?) {
        Thread({
            while(!closed) {
                val socket=try { reattachServer.accept() } catch(_:IOException) { break }
                try {
                    socket.soTimeout=1500
                    val input=DataInputStream(socket.getInputStream())
                    val nonce=ByteArray(32)
                    val header=input.readInt()
                    input.readFully(nonce)
                    val command=input.readUnsignedByte()
                    val authenticated=socket.inetAddress.isLoopbackAddress && header==0x4d4d5232 &&
                        MessageDigest.isEqual(nonce,reattachNonce) && command==1
                    val accepted=authenticated && !closed && !appIsStopped() && register()?.getInt("protocol")==4
                    socket.getOutputStream().write(if(accepted)1 else 0)
                    socket.getOutputStream().flush()
                    if(accepted) {
                        Log.i("MobiSupervisor","controller_reattached pid=${Process.myPid()} uptime=${SystemClock.uptimeMillis()}")
                    }
                } catch(_:Exception) {
                    // Never print the request, capability, preferences or supplied bytes.
                    Log.w("MobiSupervisor","reattach request rejected or interrupted")
                } finally { runCatching { socket.close() } }
            }
        },"MobiReattach").apply { isDaemon=true; start() }
    }
}
