package dev.uwumouse.app.backend

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.*
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.KeyEvent
import dev.uwumouse.windowcontext.ShellWindowContextProvider
import dev.uwumouse.app.backend.recovery.*
import dev.uwumouse.app.engine.TouchFrame
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import dev.uwumouse.app.engine.CameraResetSequence
import dev.uwumouse.app.engine.CameraResetKind
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var targetPackage=identity.applicationId
    private var targetUid=appUid
    private var coordinatePolicy=TouchCoordinatePolicy.STRICT
    private var journal=RecoveryJournal()
    private val recoveryThread=HandlerThread("UwuRecovery").apply { start() }
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
    private var terminalRetirementSessionId=0L
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
    // Cache reflection metadata, never a focus result. Every admission still queries Android.
    // A less specific vendor return type retains the previous runtime-field lookup fallback.
    private val focusTopField=runCatching { focusMethod.returnType.getField("topActivity") }.getOrNull()
    private val focusDisplayField=runCatching { focusMethod.returnType.getField("displayId") }.getOrNull()
    private fun focusPackage(info:Any):String? =
        ((focusTopField ?: info.javaClass.getField("topActivity")).get(info) as? ComponentName)?.packageName
    private fun focusDisplay(info:Any):Int =
        (focusDisplayField ?: info.javaClass.getField("displayId")).getInt(info)
    private val motionDisplayMethod=InputEvent::class.java.getMethod("getDisplayId")
    private var expiry:Runnable?=null
    private val pointer = PointerPresentation { type ->
        // AOSP Android 15+ keeps this hidden compatibility method as a no-op. Report the
        // limitation instead of marking the system cursor hidden when it is still visible.
        if(android.os.Build.VERSION.SDK_INT>=35)
            throw UnsupportedOperationException("setPointerIconType is unsupported on Android ${android.os.Build.VERSION.SDK_INT}")
        val identity=Binder.clearCallingIdentity()
        try {
            val manager=context.getSystemService(Context.INPUT_SERVICE)
            manager.javaClass.getMethod("setPointerIconType",Int::class.javaPrimitiveType).invoke(manager,type)
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    private val visualCursor=TrustedCursorOverlay(context) { display -> nativeCapture?.let { it.mouseActive && it.displayId==display }==true && !closed }
    private val statusVisuals=TrustedStatusVisuals(context) { target,display ->
        !closed && target==targetPackage && nativeContextAllows(target,display)
    }
    private val frameRateSampler=FrameRateSampler()
    // Only the reservation touches the injection monitor. WMS and persistence are cold IO.
    private var displayResolutionChanging=false
    private val displayResolutionRestoreQueued=AtomicBoolean(false)
    private val displayResolutionRestoreExecutor by lazy {
        ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task,"UwuDisplayResolution").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    }
    private val displayResolution=DisplayResolutionManager(context,identity,::requestDisplayResolutionRestoration)
    @Volatile private var inheritedDisplayResolutionUnknown=displayResolution.hasPendingRestoration
    private val inheritedDisplayResolutionProof=DisplayResolutionInheritedProof(
        { DisplayResolutionProducerPids.read(identity.injectorProcess) },
        { DisplayResolutionProducerPids.read(identity.guardianProcess) },
        DispatcherTouchState::isDefinitelyUntouched,Process.myPid())
    init {
        // This new journal cannot describe the previous guardian's worker or Android
        // contacts. Restore only after proving old producers exited and no down remains.
        if(inheritedDisplayResolutionUnknown && reserveDisplayResolutionChange()) {
            try {
                if(confirmInheritedDisplayResolutionClosure()) privileged { displayResolution.restorePending();true }
            } finally { finishDisplayResolutionChange() }
        }
    }

    private fun displayResolutionIdleLocked():Boolean {
        val snapshot=journal.snapshot()
        return DisplayResolutionAdmission.mayChange(
            snapshot.state in setOf(RecoveryInjectionState.IDLE,RecoveryInjectionState.STOPPED,RecoveryInjectionState.RECOVERY_ADMITTED),
            snapshot.possibleContacts,snapshot.activeContacts,snapshot.state==RecoveryInjectionState.RECOVERY_NEEDED,
            forwardedKeys.size,keyReleaseUnverified,nativeCapture?.active==true,recoveryScheduled || state=="RECOVERING")
    }
    private fun reserveDisplayResolutionChange():Boolean=synchronized(this) {
        if(closed || displayResolutionChanging || !displayResolutionIdleLocked()) false
        else { displayResolutionChanging=true;true }
    }
    private fun finishDisplayResolutionChange(schedulePending:Boolean=false) {
        synchronized(this) { displayResolutionChanging=false }
        if(schedulePending && displayResolution.hasPendingRestoration) requestDisplayResolutionRestoration()
    }
    private data class DisplayResolutionProducerSnapshot(val current:WorkerConnection?)
    private fun displayResolutionProducersLocked():DisplayResolutionProducerSnapshot? {
        if(!displayResolutionChanging || !displayResolutionIdleLocked()) return null
        val current=connection?.takeIf { it.alive }
        // The live current connection is also retained in this ownership set. Every
        // other member is an unretired producer, even if its close flag is already set.
        if(unretiredWorkers.any { it!==current }) return null
        return DisplayResolutionProducerSnapshot(current)
    }
    private fun confirmInheritedDisplayResolutionClosure():Boolean {
        if(!inheritedDisplayResolutionUnknown) return true
        val before=synchronized(this) { displayResolutionProducersLocked() } ?: return false
        // Process queries and the global dispatcher dump are cold IO, outside this
        // guardian's monitor and recovery handler. No PID is killed by this proof.
        if(!privileged { inheritedDisplayResolutionProof.verified(before.current?.pid) }) return false
        return synchronized(this) {
            val after=displayResolutionProducersLocked()
            if(after==null || after.current!==before.current) false
            else { inheritedDisplayResolutionUnknown=false;true }
        }
    }
    private fun requestDisplayResolutionRestoration() {
        if(!displayResolutionRestoreQueued.compareAndSet(false,true)) return
        try {
            displayResolutionRestoreExecutor.execute {
                // A later owner-death event may enqueue one replacement while this runs.
                displayResolutionRestoreQueued.set(false)
                if(!reserveDisplayResolutionChange()) return@execute
                try {
                    if(confirmInheritedDisplayResolutionClosure()) privileged { displayResolution.restorePending();true }
                }
                finally { finishDisplayResolutionChange() }
            }
        } catch(_:java.util.concurrent.RejectedExecutionException) { displayResolutionRestoreQueued.set(false) }
    }
    private fun blockedDisplayResolution(displayId:Int):Bundle=privilegedBundle {
        displayResolution.status(displayId).apply {
            putBoolean("operationSucceeded",false);putBoolean("changed",false)
            putString("message","Pausa la sesión y espera a que se liberen los contactos y la captura antes de cambiar la resolución")
        }
    }
    private fun inheritedDisplayResolutionBlocked(displayId:Int):Bundle=privilegedBundle {
        displayResolution.status(displayId).apply { describeInheritedDisplayResolution(this) }
    }
    private fun describeInheritedDisplayResolution(result:Bundle) {
        if(inheritedDisplayResolutionUnknown) {
            result.putBoolean("canRestore",false)
            result.putBoolean("operationSucceeded",false);result.putBoolean("changed",false)
            result.putString("message","No se pudo verificar el cierre de la sesión anterior; la resolución se conserva para reintentar")
        }
    }
    override fun getDisplayResolution(displayId:Int):Bundle {
        authorize()
        if(displayResolution.hasPendingRestoration && reserveDisplayResolutionChange()) {
            try {
                if(confirmInheritedDisplayResolutionClosure()) privileged { displayResolution.restorePending();true }
            }
            finally { finishDisplayResolutionChange() }
        }
        return privilegedBundle { displayResolution.status(displayId).apply { describeInheritedDisplayResolution(this) } }
    }
    override fun applyDisplayResolution(displayId:Int,displayUniqueId:String,width:Int,height:Int,owner:IBinder):Bundle {
        authorize()
        if(!reserveDisplayResolutionChange()) return blockedDisplayResolution(displayId)
        var verified=false
        return try {
            verified=confirmInheritedDisplayResolutionClosure()
            if(!verified) inheritedDisplayResolutionBlocked(displayId)
            else privilegedBundle {
                displayResolution.restorePending()
                displayResolution.apply(displayId,displayUniqueId,width,height,owner)
            }
        } finally { finishDisplayResolutionChange(schedulePending=verified) }
    }
    override fun restoreDisplayResolution(displayId:Int,displayUniqueId:String):Bundle {
        authorize()
        if(!reserveDisplayResolutionChange()) return blockedDisplayResolution(displayId)
        return try {
            if(!confirmInheritedDisplayResolutionClosure()) inheritedDisplayResolutionBlocked(displayId)
            else privilegedBundle { displayResolution.restore(displayId,displayUniqueId) }
        }
        finally { finishDisplayResolutionChange() }
    }

    override fun showVisualCursor(displayId:Int,x:Float,y:Float,sizeDp:Float):Long {
        authorize();return visualCursor.show(displayId,x,y,sizeDp)
    }
    override fun moveVisualCursor(cursorToken:Long,x:Float,y:Float,sizeDp:Float) { authorize();visualCursor.move(cursorToken,x,y,sizeDp) }
    override fun hideVisualCursor(cursorToken:Long) { authorize();visualCursor.hide(cursorToken) }
    override fun setVisualCursorStyle(style:String) { authorize();visualCursor.setStyle(style) }
    override fun showTrustedStatusVisual(kind:Int,displayId:Int,packageName:String,x:Int,y:Int,alpha:Float,bitmap:Bitmap?,client:IBinder):Long {
        authorize()
        val token=statusVisuals.show(kind,displayId,packageName,x,y,alpha,bitmap,client)
        if(token>0 && !visualCursor.raiseAboveStatus(displayId)) {
            statusVisuals.hide(kind,token)
            return 0L
        }
        return token
    }
    override fun updateTrustedStatusVisual(kind:Int,token:Long,x:Int,y:Int,alpha:Float,bitmap:Bitmap?):Boolean {
        authorize()
        return statusVisuals.update(kind,token,x,y,alpha,bitmap)
    }
    override fun hideTrustedStatusVisual(kind:Int,token:Long):Boolean {
        authorize()
        return statusVisuals.hide(kind,token)
    }
    override fun isTrustedStatusVisualVisible(kind:Int,token:Long):Boolean {
        authorize()
        return statusVisuals.isVisible(kind,token)
    }

    override fun sampleGameFps(packageName:String,displayId:Int):Bundle {
        authorize()
        if(closed || displayId<0 || !FrameRateSampler.validPackage(packageName)) return Bundle().apply { putBoolean("available",false) }
        val identity=Binder.clearCallingIdentity()
        try {
            fun focusedComponent():ComponentName? = runCatching {
                val info=requireNotNull(focusMethod.invoke(taskManager))
                if(focusDisplay(info)!=displayId) null
                else ((focusTopField ?: info.javaClass.getField("topActivity")).get(info) as? ComponentName)
            }.getOrNull()?.takeIf { it.packageName==packageName }
            val component=focusedComponent() ?: return Bundle().apply { putBoolean("available",false) }
            val fps=frameRateSampler.sample(packageName,component.flattenToString(),component.flattenToShortString())
            return Bundle().apply {
                val valid=fps!=null && !closed && focusedComponent()==component
                putBoolean("available",valid)
                if(valid) putDouble("fps",fps)
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun syncCapturePointer(forceRestore:Boolean=false) {
        val capture=nativeCapture
        if(capture?.mouseActive!=true) visualCursor.hide()
        pointer.setMouseCaptureActive(capture?.mouseActive==true && capture.targetPackage!="dev.uwumouse.inputlab",forceRestore)
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
            val result=runCatching {
                val info=requireNotNull(focusMethod.invoke(taskManager))
                val top=focusPackage(info)
                val displayId=focusDisplay(info)
                Bundle().apply {
                    putBoolean("valid",top!=null && displayId>=0)
                    putString("packageName",top)
                    putInt("displayId",displayId)
                }
            }.getOrElse { Bundle().apply { putBoolean("valid",false);putInt("displayId",-1) } }
            // Task existence is independent of focus/visibility and never participates in
            // injection admission. Unsupported firmware leaves automatic closure disabled.
            val taskTarget=targetPackage
            val task=when {
                closed || taskTarget==this.identity.applicationId ->
                    TaskPresenceSnapshot(taskTarget,SystemClock.uptimeMillis(),false,false)
                result.getBoolean("valid") && result.getString("packageName")==taskTarget ->
                    TaskPresenceSnapshot(taskTarget,SystemClock.uptimeMillis(),true,true)
                context.checkSelfPermission("android.permission.REAL_GET_TASKS")!=android.content.pm.PackageManager.PERMISSION_GRANTED ->
                    // getTasks can otherwise silently return a caller-filtered list.
                    TaskPresenceSnapshot(taskTarget,SystemClock.uptimeMillis(),false,false)
                else -> TargetTaskPresence.snapshot(taskManager,taskTarget)
            }
            result.apply {
                putString("targetTaskPackage",task.packageName)
                putLong("targetTaskCapturedUptimeMs",task.capturedUptimeMs)
                putBoolean("targetTaskQueryValid",task.valid)
                putBoolean("targetTaskPresent",task.present)
            }
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
            focusPackage(task)==packageName && focusDisplay(task)==displayId
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
        check(!displayResolutionChanging) { "Espera a que termine el cambio de resolución" }
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
        val original=journal.snapshot()
        releaseForwardedKeys(allowWorkerRestart=recoveryContextMatches(original,targetPackage) &&
            terminalRetirementSessionId!=original.sessionId)
        privileged { nativeCapture?.stop(captureId);true }
        ownedCaptureId=nativeCapture?.activeId ?: 0L
        syncCapturePointer(forceRestore=true)
        if(displayResolution.hasPendingRestoration) requestDisplayResolutionRestoration()
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
    private fun sendKey(key:ForwardedKey,action:Int,eventTime:Long,metaState:Int,
                        allowWorkerRestart:Boolean=true):Boolean = privileged {
        if(terminalRetirementPending()) return@privileged false
        var channel=connection?.takeIf { it.alive }
        try {
            if(channel==null) {
                if(!allowWorkerRestart || forceStopped || closed || appIsStopped()) return@privileged false
                ensureWorker()
                channel=connection ?: return@privileged false
            }
            channel.sendKey(journal.snapshot().sessionId.coerceAtLeast(1L),action,key.downTime,eventTime,
                key.code,key.scan,metaState,if(action==KeyEvent.ACTION_DOWN) key.repeats.coerceAtMost(10_000) else 0)
        } catch(failure:Exception) {
            if(connection===channel) connection=null
            // Closing a failed key pipe first can run the worker's touch EOF CANCEL.
            // Retain ownership on unconfirmed exit; no cleanup event may bypass context admission.
            runCatching { channel?.terminateAndAwaitTermination(500L) }
            if(!forceStopped && !closed && journal.snapshot().possibleContacts>0) scheduleRecovery("KEY_WORKER_PIPE_LOST")
            false
        }
    }
    private fun releaseForwardedKeys(allowWorkerRestart:Boolean=true):Boolean {
        val now=SystemClock.uptimeMillis()
        val iterator=forwardedKeys.entries.iterator()
        while(iterator.hasNext()) {
            val key=iterator.next().value
            if(sendKey(key,KeyEvent.ACTION_UP,maxOf(now,key.downTime),0,allowWorkerRestart)) iterator.remove()
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
        runCatching { val info=requireNotNull(focusMethod.invoke(taskManager))
            focusPackage(info)==targetPackage && focusDisplay(info)==journal.displayId }.getOrDefault(false)
    }
    /** Query failure/null focus never establishes that Android switched away from this stream. */
    private fun focusDefinitelyElsewhere():Boolean=privileged {
        runCatching {
            val info=requireNotNull(focusMethod.invoke(taskManager))
            val top=focusPackage(info)
            val display=focusDisplay(info)
            top!=null && display>=0 && (top!=targetPackage || display!=journal.displayId)
        }.getOrDefault(false)
    }
    /** Immutable ownership is independent of whether the old display still has focus or exists. */
    private fun recoveryIdentityMatches(original:RecoverySnapshot,originalPackage:String):Boolean=privileged {
        runCatching {
            val current=journal.snapshot()
            targetPackage==originalPackage && targetUid==original.targetUid &&
                coordinatePolicy==original.coordinatePolicy && journal.displayId==original.displayId &&
                current.sessionId==original.sessionId && current.width==original.width && current.height==original.height &&
                current.source==original.source && current.deviceId==original.deviceId &&
                context.packageManager.getPackageUid(originalPackage,0)==original.targetUid
        }.getOrDefault(false)
    }
    private fun recoveryContextMatches(original:RecoverySnapshot,originalPackage:String):Boolean=privileged {
        recoveryIdentityMatches(original,originalPackage) && runCatching {
            context.getSystemService(DisplayManager::class.java).getDisplay(original.displayId)?.isValid==true &&
                nativeContextAllows(originalPackage,original.displayId)
        }.getOrDefault(false)
    }
    /** An unavailable service/query is not evidence of display removal. */
    private fun originalDisplayDefinitelyRemoved(displayId:Int):Boolean=privileged {
        runCatching {
            val manager=requireNotNull(context.getSystemService(DisplayManager::class.java))
            val displays=manager.displays
            displays.isNotEmpty() && displays.none { it.displayId==displayId } && manager.getDisplay(displayId)==null
        }.getOrDefault(false)
    }

    private fun retireAllRecoveryWorkers():Boolean {
        connection=null
        val deadline=SystemClock.uptimeMillis()+1_500L
        for(old in unretiredWorkers.toList()) {
            val remaining=deadline-SystemClock.uptimeMillis()
            if(remaining<2L) return false
            val terminated=runCatching { old.terminateAndAwaitTermination(minOf(750L,remaining/2)) }.getOrDefault(false)
            if(!terminated) return false
            unretiredWorkers.remove(old)
        }
        return unretiredWorkers.isEmpty()
    }

    private fun terminalRetirementPending():Boolean {
        val current=journal.snapshot()
        return current.sessionId>0L && terminalRetirementSessionId==current.sessionId &&
            current.state==RecoveryInjectionState.RECOVERY_NEEDED
    }

    private fun normalRecoveryAdmitted(original:RecoverySnapshot,originalPackage:String):Boolean =
        RecoveryClosurePolicy.mayAttemptNormalRecovery(recoveryContextMatches(original,originalPackage),
            recoveryIdentityMatches(original,originalPackage),original.explicitRejectedPointerUp,
            terminalRetirementSessionId==original.sessionId)

    /** Preserve worker-loss recovery, always before terminal retirement or global proof. */
    private fun tryNormalRecovery(original:RecoverySnapshot,originalPackage:String):Boolean {
        if(!normalRecoveryAdmitted(original,originalPackage)) return false
        val sid=original.sessionId
        if(connection?.alive==true) {
            val cancel=journal.cancellationFrame(SystemClock.uptimeMillis()) ?: return true
            lastRecoveryFrame=cancel
            if(!normalRecoveryAdmitted(original,originalPackage)) return false
            val accepted=runCatching { send(cancel,sid,2) }.getOrDefault(false)
            liveCancellationAccepted=accepted
            if(accepted) return true
        }
        repeat(3) {
            if(closed || forceStopped || appIsStopped() || !normalRecoveryAdmitted(original,originalPackage)) return false
            // No old producer can deliver a delayed event after the replacement's ACK.
            if(!retireAllRecoveryWorkers() || !normalRecoveryAdmitted(original,originalPackage)) return false
            recoveryAttempts++
            val accepted=runCatching {
                ensureWorker()
                val cancel=journal.cancellationFrame(SystemClock.uptimeMillis())
                lastRecoveryFrame=cancel
                if(!normalRecoveryAdmitted(original,originalPackage)) false
                else cancel==null || send(cancel,sid,2)
            }.getOrDefault(false)
            if(accepted) return true
        }
        return false
    }

    /** Global terminal proof after every producer has stopped; no focus return or new worker. */
    private fun reconcileStoppedProducers(original:RecoverySnapshot,originalPackage:String):Boolean {
        if(closed || forceStopped || appIsStopped() || !recoveryIdentityMatches(original,originalPackage)) return false
        terminalRetirementSessionId=original.sessionId
        nativeCapture?.stop(nativeCapture.activeId)
        ownedCaptureId=nativeCapture?.activeId ?: 0L
        syncCapturePointer(forceRestore=true);pointer.setSessionActive(false)
        val retiredGeneration=workerGeneration
        // A real finger may have replaced the injected stream even while the game
        // retains focus. Retirement itself must never synthesize an EOF cancellation.
        if(!retireAllRecoveryWorkers()) {
            state="BLOCKED";error="PREVIOUS_WORKER_EXIT_UNCONFIRMED";return false
        }
        if(nativeCapture?.active==true || keyReleaseUnverified || forwardedKeys.isNotEmpty() ||
            !recoveryIdentityMatches(original,originalPackage) || !DispatcherTouchState.isDefinitelyUntouched() ||
            closed || forceStopped || appIsStopped() || workerGeneration!=retiredGeneration ||
            connection!=null || unretiredWorkers.isNotEmpty() || nativeCapture?.active==true || keyReleaseUnverified ||
            !recoveryIdentityMatches(original,originalPackage)) {
            state="BLOCKED";error="STOPPED_PRODUCERS_CLOSURE_UNVERIFIED";return false
        }
        val reconciled=journal.reconciledStoppedProducers(original,true,true,true)
        if(reconciled) {
            state="RECOVERED";error="";lastRecovery=SystemClock.uptimeMillis();recoveredCount++
            Log.i("UwuSupervisor","stopped_producers_reconciled sid=${original.sessionId} display=${original.displayId} worker=$retiredGeneration cancelAdmitted=false receiverVerified=false")
        } else { state="BLOCKED";error="RECOVERY_JOURNAL_CHANGED" }
        return reconciled
    }

    /** Cold closure proof only. Never launch a worker or retarget input to another display. */
    private fun reconcileRemovedDisplay(original:RecoverySnapshot,originalPackage:String):Boolean {
        if(closed || forceStopped || appIsStopped() || !recoveryIdentityMatches(original,originalPackage) ||
            !originalDisplayDefinitelyRemoved(original.displayId)) return false
        terminalRetirementSessionId=original.sessionId
        nativeCapture?.let { if(it.active) it.stop(it.activeId) }
        ownedCaptureId=nativeCapture?.activeId ?: 0L
        syncCapturePointer(forceRestore=true);pointer.setSessionActive(false)
        if(nativeCapture?.active==true || !releaseForwardedKeys(allowWorkerRestart=false) || keyReleaseUnverified) {
            state="BLOCKED";error="REMOVED_DISPLAY_INPUT_RELEASE_UNCONFIRMED";return false
        }
        if(!retireAllRecoveryWorkers()) {
            state="BLOCKED";error="PREVIOUS_WORKER_EXIT_UNCONFIRMED";return false
        }
        if(!recoveryIdentityMatches(original,originalPackage) || !originalDisplayDefinitelyRemoved(original.displayId) ||
            !DispatcherTouchState.isDefinitelyUntouched() || closed || forceStopped || appIsStopped() ||
            nativeCapture?.active==true || keyReleaseUnverified || unretiredWorkers.isNotEmpty() ||
            !recoveryIdentityMatches(original,originalPackage) || !originalDisplayDefinitelyRemoved(original.displayId)) {
            state="BLOCKED";error="REMOVED_DISPLAY_CLOSURE_UNVERIFIED";return false
        }
        val reconciled=journal.reconciledRemovedDisplay(original,true,true,true)
        if(reconciled) {
            state="RECOVERED";error="";lastRecovery=SystemClock.uptimeMillis();recoveredCount++
            Log.i("UwuSupervisor","removed_display_reconciled sid=${original.sessionId} display=${original.displayId} workersTerminated=true cancelAdmitted=false receiverVerified=false")
        } else { state="BLOCKED";error="RECOVERY_JOURNAL_CHANGED" }
        return reconciled
    }
    private fun selectTarget(packageName:String, expectedUid:Int?=null,
                             policy:TouchCoordinatePolicy=TouchCoordinatePolicy.STRICT,displayId:Int=0) {
        check(!displayResolutionChanging) { "Espera a que termine el cambio de resolución" }
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
                Log.i("UwuSupervisor","controller_died sid=${journal.snapshot().sessionId} uptime=${SystemClock.uptimeMillis()}")
                client=null
                if(appIsStopped()) stopForForceStop()
                else { releaseInternal(); state="PAUSED_CONTROLLER_GONE" }
            }
        } } }
        token.linkToDeath(clientDeath!!,0)
    }
    @Synchronized override fun begin(client:IBackendEvents,width:Int,height:Int):Long {
        return beginTarget(client,width,height,"dev.uwumouse.inputlab")
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
        pointer.setSessionActive(targetPackage!="dev.uwumouse.inputlab")
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
        releaseForwardedKeys(allowWorkerRestart=focused())
        syncCapturePointer()
        pointer.setSessionActive(false)
        clearExpiry()
        val snap=journal.snapshot()
        if(snap.state==RecoveryInjectionState.RECOVERY_ADMITTED || snap.state==RecoveryInjectionState.STOPPED) {
            if(displayResolution.hasPendingRestoration) requestDisplayResolutionRestoration()
            return true
        }
        if(terminalRetirementPending()) return false
        if(snap.explicitRejectedPointerUp) {
            if(scheduleOnFailure) scheduleRecovery("POINTER_UP_REJECTED")
            return false
        }
        journal.fail(snap.sessionId)
        val frame=journal.cancellationFrame(SystemClock.uptimeMillis())
        if(frame==null) {
            journal.cancelled(snap.sessionId,true); state="PAUSED"
            if(displayResolution.hasPendingRestoration) requestDisplayResolutionRestoration()
            return true
        }
        if(!recoveryContextMatches(snap,targetPackage)) {
            if(scheduleOnFailure) scheduleRecovery("RELEASE_CONTEXT_UNAVAILABLE")
            return false
        }
        val ok=try { send(frame,snap.sessionId,2) } catch(_:Exception) { false }
        journal.cancelled(snap.sessionId,ok)
        if(ok) {
            state="PAUSED"
            if(displayResolution.hasPendingRestoration) requestDisplayResolutionRestoration()
        } else if(scheduleOnFailure) scheduleRecovery("RELEASE_PIPE_LOST")
        return ok
    }
    /** Close this guardian's original stream. No caller-supplied destination or replacement journal. */
    @Synchronized override fun recoverSession(sessionId:Long):Boolean {
        authorize()
        val original=journal.snapshot()
        val originalPackage=targetPackage
        if(closed || forceStopped || appIsStopped() || recoveryScheduled || sessionId<=0L ||
            sessionId!=original.sessionId || original.state==RecoveryInjectionState.ACTIVE ||
            !recoveryIdentityMatches(original,originalPackage)) return false
        nativeCapture?.stop(nativeCapture.activeId)
        ownedCaptureId=nativeCapture?.activeId ?: 0L
        syncCapturePointer(forceRestore=true)
        pointer.setSessionActive(false)
        if(nativeCapture?.active==true ||
            !releaseForwardedKeys(allowWorkerRestart=recoveryContextMatches(original,originalPackage)) || recoveryScheduled ||
            !recoveryIdentityMatches(original,originalPackage)) return false
        if(original.state in setOf(RecoveryInjectionState.RECOVERY_ADMITTED,RecoveryInjectionState.STOPPED)) {
            state="RECOVERED";error=""
            return true
        }
        if(original.state!=RecoveryInjectionState.RECOVERY_NEEDED) return false
        if(original.possibleContacts>0 && originalDisplayDefinitelyRemoved(original.displayId)) {
            ++recoveryEpoch
            state="RECOVERING"
            return reconcileRemovedDisplay(original,originalPackage)
        }
        if(original.possibleContacts==0) {
            // A fully acknowledged terminal event leaves no candidate for another CANCEL.
            val closed=releaseInternal(scheduleOnFailure=false)
            if(closed) { state="RECOVERED";error="" }
            return closed
        }
        ++recoveryEpoch
        state="RECOVERING"
        // A live original worker may acknowledge its normal release while its immutable
        // context still admits it. After retirement, a failed global proof never starts
        // another worker or sends another CANCEL against a potentially physical stream.
        if(tryNormalRecovery(original,originalPackage)) {
            journal.cancelled(sessionId,true)
            state="RECOVERED";error="";lastRecovery=SystemClock.uptimeMillis();recoveredCount++
            return !keyReleaseUnverified
        }
        return reconcileStoppedProducers(journal.snapshot(),originalPackage)
    }
    @Synchronized override fun recoverOrphan(client:IBackendEvents,width:Int,height:Int,downTime:Long,ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int):Long {
        return recoverTargetOrphan(client,width,height,downTime,ids,xs,ys,originalTargetUid,"dev.uwumouse.inputlab")
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
        Log.w("UwuSupervisor","cannot verify controller stop state",it)
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
        Log.i("UwuSupervisor","force_stop sid=${journal.snapshot().sessionId} cancelAdmitted=$released restartInhibited=true")
    }
    private fun scheduleRecovery(reason:String) {
        if(closed || recoveryScheduled) return
        syncCapturePointer()
        pointer.setSessionActive(false)
        if(forceStopped || appIsStopped()) { stopForForceStop(); return }
        val snap=journal.snapshot()
        journal.fail(snap.sessionId)
        recoveryScheduled=true; state="RECOVERING"; error=reason; lastDetection=SystemClock.uptimeMillis()
        Log.w("UwuSupervisor","recovery_detected sid=${snap.sessionId} worker=$workerGeneration pid=$lastWorkerPid confirmed=${snap.activeContacts} possible=${journal.snapshot().possibleContacts} action=${snap.attempted?.action ?: snap.lastConfirmed?.action} uptime=$lastDetection reason=$reason")
        notifyClient(1,reason)
        val epoch=++recoveryEpoch
        val failedPackage=targetPackage
        handler.post { synchronized(this) {
            if(closed || epoch!=recoveryEpoch) return@synchronized
            if(forceStopped || appIsStopped()) { stopForForceStop(); return@synchronized }
            val sid=journal.snapshot().sessionId
            val current=journal.snapshot()
            if(current.possibleContacts==0) {
                journal.cancelled(sid,true)
                state="RECOVERED";error="";recoveryScheduled=false
                notifyClient(2,"Secuencia sin contactos pendientes; iniciar manualmente")
                return@synchronized
            }
            if(current.possibleContacts>0 && originalDisplayDefinitelyRemoved(current.displayId)) {
                val reconciled=reconcileRemovedDisplay(current,failedPackage)
                lastRecovery=SystemClock.uptimeMillis();recoveryScheduled=false
                notifyClient(if(reconciled) 2 else 3,if(reconciled)
                    "Pantalla anterior retirada y Android sin contactos; iniciar manualmente"
                    else "No se pudo confirmar el cierre de la pantalla anterior")
                return@synchronized
            }
            if(current.possibleContacts>0 && !recoveryContextMatches(current,failedPackage)) {
                val reconciled=reconcileStoppedProducers(current,failedPackage)
                lastRecovery=SystemClock.uptimeMillis();recoveryScheduled=false
                notifyClient(if(reconciled) 2 else 3,if(reconciled)
                    "Productores detenidos y Android sin contactos; iniciar manualmente"
                    else "Cierre pendiente; no se ha demostrado que Android haya liberado los contactos")
                return@synchronized
            }
            val accepted=tryNormalRecovery(current,failedPackage)
            if(!accepted) {
                val reconciled=reconcileStoppedProducers(journal.snapshot(),failedPackage)
                lastRecovery=SystemClock.uptimeMillis();recoveryScheduled=false
                notifyClient(if(reconciled) 2 else 3,if(reconciled)
                    "Productores detenidos y Android sin contactos; iniciar manualmente"
                    else "Cierre pendiente; no se ha demostrado que Android haya liberado los contactos")
                return@synchronized
            }
            journal.cancelled(sid,true)
            connection?.close();connection=null
            lastRecovery=SystemClock.uptimeMillis();recoveryScheduled=false
            state="RECOVERED";error="";recoveredCount++
            Log.i("UwuSupervisor","recovery_complete sid=$sid worker=$workerGeneration pid=$lastWorkerPid admitted=true terminalReconciled=false attempts=$recoveryAttempts uptime=$lastRecovery receiverVerified=false")
            notifyClient(2,"Cancelación admitida; iniciar manualmente")
        } }
    }
    private fun ensureWorker() {
        check(!displayResolutionChanging) { "Espera a que termine la verificación y el cambio de resolución" }
        check(!terminalRetirementPending()) { "Terminal closure cannot restart an input worker" }
        if(connection?.alive==true) return
        check(!closed && !forceStopped && !appIsStopped()) { "Worker restart inhibited" }
        val generation=++workerGeneration
        check(unretiredWorkers.size<8) { "Previous workers have not terminated" }
        val apk=context.packageManager.getApplicationInfo(identity.applicationId,0).sourceDir
        val builder=ProcessBuilder("/system/bin/app_process","/system/bin","--nice-name=${identity.injectorProcess}","dev.uwumouse.app.backend.InjectionWorkerMain",generation.toString(),targetUid.toString(),
            if(coordinatePolicy==TouchCoordinatePolicy.CONTINUOUS_CAMERA_9) "1" else "0",journal.displayId.toString())
        builder.environment()["CLASSPATH"]=apk
        builder.redirectError(File("/data/local/tmp/uwumouse-injector.log"))
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
        } },"UwuWorkerDeath").apply { isDaemon=true; start() }
        Log.i("UwuSupervisor","worker_started generation=$generation pid=$lastWorkerPid uptime=${SystemClock.uptimeMillis()}")
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
            putBoolean("trustedCursorSupported",true);putBoolean("trustedCursorVisible",visualCursor.visible)
            putBoolean("trustedCursorStyleSupported",true)
            putBoolean("trustedStatusVisualSupported",true)
            putBoolean("trustedBubbleVisualVisible",statusVisuals.bubbleVisible)
            putBoolean("trustedMonitorVisualVisible",statusVisuals.monitorVisible)
            putString("trustedStatusVisualError",statusVisuals.error)
            putBoolean("gameFpsMonitorSupported",true)
            putBoolean("displayResolutionSupported",displayResolution.supported)
            putBoolean("displayResolutionChanging",displayResolutionChanging)
            putBoolean("displayResolutionInheritedPending",inheritedDisplayResolutionUnknown)
            putBoolean("trustedCursorCleanupWithoutTokenSupported",true)
            putString("trustedCursorError",visualCursor.error)
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
            putBoolean("rejectedDownReconciled",snap.rejectedDownReconciled)
            putBoolean("focusLossReconciled",snap.focusLossReconciled)
            putBoolean("removedDisplayReconciled",snap.removedDisplayReconciled)
            putBoolean("stoppedProducersReconciled",snap.stoppedProducersReconciled)
            putBoolean("terminalRetirementStarted",terminalRetirementSessionId==snap.sessionId && snap.sessionId>0L)
            putBoolean("explicitRejectedPointerUp",snap.explicitRejectedPointerUp)
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
    override fun shutdown() {
        authorize()
        val restore=synchronized(this) {
            check(!displayResolutionChanging) { "Espera a que termine el cambio de resolución antes de cerrar el motor" }
            nativeCapture?.close();ownedCaptureId=0L;visualCursor.hide();statusVisuals.hideAll();pointer.releaseOwners();releaseInternal();closed=true
            displayResolutionIdleLocked().also { displayResolutionChanging=it }
        }
        // Restore only after input closure, and never hold the injection/recovery monitor.
        try {
            if(restore && confirmInheritedDisplayResolutionClosure()) privileged { displayResolution.restoreAll();true }
        }
        finally {
            displayResolution.close()
            synchronized(this) {
                displayResolutionChanging=false
                windowContexts.close()
                runCatching { reattachServer.close() }
                connection?.close();connection=null
            }
            if(displayResolutionRestoreQueued.get()) displayResolutionRestoreQueued.set(false)
            handler.post { Process.killProcess(Process.myPid()) }
        }
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
                        Log.i("UwuSupervisor","controller_reattached pid=${Process.myPid()} uptime=${SystemClock.uptimeMillis()}")
                    }
                } catch(_:Exception) {
                    // Never print the request, capability, preferences or supplied bytes.
                    Log.w("UwuSupervisor","reattach request rejected or interrupted")
                } finally { runCatching { socket.close() } }
            }
        },"UwuReattach").apply { isDaemon=true; start() }
    }
}
