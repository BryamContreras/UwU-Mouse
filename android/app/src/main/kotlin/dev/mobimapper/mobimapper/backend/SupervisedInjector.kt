package dev.mobimapper.mobimapper.backend

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import dev.mobimapper.mobimapper.backend.recovery.*
import dev.mobimapper.mobimapper.engine.TouchFrame
import dev.mobimapper.mobimapper.engine.TouchCoordinatePolicy
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/** Stable authorized broker; the actual injector is a replaceable child process. */
class SupervisedInjector(private val context: Context) : IInjector.Stub() {
    private val appUid=context.packageManager.getPackageUid("dev.mobimapper.mobimapper",0)
    // Bootstrap is idle and must not require the optional laboratory to be installed.
    // A real target is resolved and authorized by selectTarget before any worker/session starts.
    private var targetPackage="dev.mobimapper.mobimapper"
    private var targetUid=appUid
    private var coordinatePolicy=TouchCoordinatePolicy.STRICT
    private val journal=RecoveryJournal()
    private val recoveryThread=HandlerThread("MobiRecovery").apply { start() }
    private val handler=Handler(recoveryThread.looper)
    private var connection: WorkerConnection?=null
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
    private val taskManager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    private val focusMethod=Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo")
    private var expiry:Runnable?=null
    private val pointer = PointerPresentation { type ->
        val identity=Binder.clearCallingIdentity()
        try {
            val manager=context.getSystemService(Context.INPUT_SERVICE)
            manager.javaClass.getMethod("setPointerIconType",Int::class.javaPrimitiveType).invoke(manager,type)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun clearExpiry() {
        expiry?.let { handler.removeCallbacks(it) }
        expiry=null
    }

    private fun authorize() { check(Binder.getCallingUid()==appUid) { "Unauthorized client" } }
    private fun privileged(block:()->Boolean):Boolean {
        val identity=Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(identity) }
    }
    private fun focused():Boolean=privileged {
        runCatching { val info=focusMethod.invoke(taskManager); val top=info.javaClass.getField("topActivity").get(info) as? ComponentName
            top?.packageName==targetPackage && info.javaClass.getField("displayId").getInt(info)==0 }.getOrDefault(false)
    }
    private fun selectTarget(packageName:String, expectedUid:Int?=null,
                             policy:TouchCoordinatePolicy=TouchCoordinatePolicy.STRICT) {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && packageName!="dev.mobimapper.mobimapper")
        val identity=Binder.clearCallingIdentity()
        val uid=try {
            require(context.packageManager.getLaunchIntentForPackage(packageName)!=null) { "Destino sin actividad de lanzamiento" }
            context.packageManager.getPackageUid(packageName,0)
        } finally { Binder.restoreCallingIdentity(identity) }
        require(uid>=10000 && (expectedUid==null || expectedUid==uid)) { "El UID del destino cambió" }
        val snap=journal.snapshot()
        check(snap.possibleContacts==0 && snap.state!=RecoveryInjectionState.RECOVERY_NEEDED && !recoveryScheduled) { "Recupera el gesto anterior antes de cambiar destino" }
        if(uid!=targetUid || packageName!=targetPackage || policy!=coordinatePolicy) {
            connection?.close(); connection=null
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
    private fun beginTargetWithPolicy(client:IBackendEvents,width:Int,height:Int,targetPackage:String,
                                      policy:TouchCoordinatePolicy):Long {
        authorize(); check(!closed)
        selectTarget(targetPackage,policy=policy)
        check(focused()) { "Destino sin foco" }
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
        if(targetPackage!="dev.mobimapper.inputlab") pointer.hide() else pointer.restore()
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
    @Synchronized override fun release(sessionId:Long):Boolean {
        authorize()
        if(sessionId!=journal.snapshot().sessionId) return false
        pointer.restore(force=true)
        if(recoveryScheduled || state=="BLOCKED") return false
        return releaseInternal()
    }
    private fun releaseInternal(scheduleOnFailure:Boolean=true):Boolean {
        pointer.restore()
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
    private fun recoverTargetOrphanWithPolicy(client:IBackendEvents,width:Int,height:Int,downTime:Long,
        ids:IntArray,xs:FloatArray,ys:FloatArray,originalTargetUid:Int,originalTargetPackage:String,
        policy:TouchCoordinatePolicy):Long {
        authorize(); check(!closed)
        require(width in 100..8192 && height in 100..8192)
        selectTarget(originalTargetPackage,originalTargetUid,policy)
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
        context.packageManager.getApplicationInfo("dev.mobimapper.mobimapper",0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrElse {
        // An unavailable package state cannot authorize a new privileged child.
        Log.w("MobiSupervisor","cannot verify controller stop state",it)
        true
    }
    /** Cleanup may use the existing worker; it must never start a replacement after Force stop. */
    private fun stopForForceStop() {
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
        pointer.restore()
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
        val apk=context.packageManager.getApplicationInfo("dev.mobimapper.mobimapper",0).sourceDir
        val builder=ProcessBuilder("/system/bin/app_process","/system/bin","--nice-name=mobimapper_injector","dev.mobimapper.mobimapper.backend.InjectionWorkerMain",generation.toString(),targetUid.toString(),
            if(coordinatePolicy==TouchCoordinatePolicy.CONTINUOUS_CAMERA_9) "1" else "0")
        builder.environment()["CLASSPATH"]=apk
        builder.redirectError(File("/data/local/tmp/mobimapper-injector.log"))
        val process=builder.start()
        val worker=try { WorkerConnection(process,generation) } catch(e:Exception) { process.destroyForcibly(); throw e }
        connection=worker; lastWorkerPid=worker.pid
        Thread({ process.waitFor(); synchronized(this) {
            if(!closed && connection===worker) {
                connection=null; worker.close()
                val snap=journal.snapshot()
                if(snap.state==RecoveryInjectionState.ACTIVE || snap.possibleContacts>0) scheduleRecovery("WORKER_EXIT")
            }
        } },"MobiWorkerDeath").apply { isDaemon=true; start() }
        Log.i("MobiSupervisor","worker_started generation=$generation pid=$lastWorkerPid uptime=${SystemClock.uptimeMillis()}")
    }
    private fun send(frame:TouchFrame,sid:Long,mode:Int):Boolean {
        val snapshot=journal.snapshot()
        val fault=if(mode==1 && frame.action and 255 == faultAction) faultStage else 0
        if(fault!=0) { faultAction=-1; faultStage=0 }
        return requireNotNull(connection).send(sid,frame,snapshot.width,snapshot.height,mode,fault)
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
            putString("targetPackage",targetPackage)
            putString("coordinatePolicy",coordinatePolicy.name)
            putBoolean("continuousCameraSupported",true)
            putBoolean("systemPointerHidden",pointer.hidden); putString("systemPointerError",pointer.error)
            putBoolean("workerAlive",connection?.alive==true); putBoolean("controllerBinderAlive",client?.asBinder()?.isBinderAlive==true)
            putLong("workerGeneration",workerGeneration); putLong("sessionId",snap.sessionId); putString("recoveryState",state)
            putInt("confirmedContacts",snap.activeContacts); putInt("possibleContacts",snap.possibleContacts); putInt("activeContacts",snap.possibleContacts)
            putBoolean("cancelAdmitted",snap.cancellationAccepted); putBoolean("receiverVerified",false)
            putBoolean("liveWorkerCancelAdmitted",liveCancellationAccepted)
            putBoolean("terminalReconciled",snap.terminalReconciled)
            putBoolean("rejectedMoveReconciled",snap.rejectedMoveReconciled)
            putBoolean("releaseUnverified",snap.state==RecoveryInjectionState.RECOVERY_NEEDED)
            putBoolean("injectPermission",context.checkPermission("android.permission.INJECT_EVENTS",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED)
            putInt("targetUid",targetUid); putInt("displayId",0); putInt("source",4098); putInt("deviceId",0); putInt("effectiveDeviceId",-1)
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
        authorize(); releaseInternal(); closed=true
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

private class WorkerConnection(private val process:java.lang.Process,val generation:Long) {
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
    fun send(session:Long,frame:TouchFrame,width:Int,height:Int,mode:Int,fault:Int):Boolean {
        setDeadline(if(mode==2)3000 else 1500)
        try {
            output.writeInt(InjectionWorkerMain.MAGIC); output.writeLong(generation); output.writeLong(session); output.writeInt(mode); output.writeInt(fault)
            output.writeInt(frame.action); output.writeLong(frame.downTime); output.writeLong(frame.eventTime); output.writeInt(width); output.writeInt(height); output.writeInt(frame.ids.size)
            for(i in frame.ids.indices) { output.writeInt(frame.ids[i]); output.writeFloat(frame.xs[i]); output.writeFloat(frame.ys[i]) }
            output.flush()
            check(input.readInt()==InjectionWorkerMain.MAGIC && input.readLong()==generation && input.readLong()==session)
            val result=input.readBoolean(); input.readLong(); return result
        } finally { setDeadline(0) }
    }
    fun close() {
        synchronized(timer) { closed=true; timer.notifyAll() }
        runCatching { output.close() }; runCatching { input.close() }; if(process.isAlive)process.destroy()
    }
}
