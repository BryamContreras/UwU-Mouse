package dev.uwumouse.app.session

import android.content.Context
import android.os.*
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import dev.uwumouse.app.backend.IBackendEvents
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.engine.*
import dev.uwumouse.app.backend.recovery.RecoveryNoticeGate
import dev.uwumouse.app.profile.*

object SessionController {
    private val runtimeBinding = SessionRuntimeBinding()
    private val environment: SessionEnvironment get() = runtimeBinding.ports.environment
    private val events: SessionEvents get() = runtimeBinding.ports.events

    /** Install once before Android components start; replacing a live session host is forbidden. */
    fun bindRuntime(ports: SessionRuntimePorts) = runtimeBinding.bind(ports)

    const val TARGET = "dev.uwumouse.inputlab"
    @Volatile var targetPackage=TARGET; private set
    @Volatile var targetDisplayId=0; private set
    @Volatile var mode=SessionMode.STOPPED; private set
    @Volatile var activeProfileId:String?=null; private set
    @Volatile var profileName:String?=null; private set
    @Volatile private var runtimeProfile:PreparedProfile?=null
    @Volatile private var explicitlyStopped=false
    private var originalTargetPackage=TARGET
    private var originalTargetDisplayId=0
    @Volatile private var originalContinuousCamera=false
    @Volatile private var cameraContactAdmitted=false
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("UwUMouseEngine").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var backendSessionId=0L
    private var previousBackendSessionId=0L
    private var originalTargetUid=-1
    private val noticeGate=RecoveryNoticeGate()
    private val focusRecovery=FocusRecoveryGate()
    private val client=object:IBackendEvents.Stub() {
        override fun recovery(sessionId:Long,workerGeneration:Long,phase:Int,reason:String) {
            worker.post { handleRecovery(sessionId,workerGeneration,phase,reason) }
        }
    }
    private fun handleRecovery(sessionId:Long,workerGeneration:Long,phase:Int,reason:String) {
            if(!noticeGate.accept(sessionId,workerGeneration,phase)) return
            if(!SessionClosurePolicy.mayApplyRecoveryNotice(sessionBackend!=null,releaseUnverified,contacts)) return
            if(phase==1) { pause("Recuperando inyector: $reason"); mode=SessionMode.RECOVERING }
            if(phase==2) worker.post {
                if(sessionId==backendSessionId) {
                    val result=runCatching { (sessionBackend ?: environment.injector)?.let(::guardianClosureSnapshot) }.getOrNull()
                    if(result?.provesClosureOf(originalSessionIdentity())==true) {
                        composer?.acknowledgeBackendReset(); releaseUnverified=false; contacts=0;cameraContactAdmitted=false
                        guardianClosurePending=false;sessionBackend=null
                        mode=if(explicitlyStopped) SessionMode.STOPPED else SessionMode.PAUSED
                        state=if(explicitlyStopped) "Detenida · secuencia recuperada" else "Pausada · secuencia recuperada"
                        error=if(explicitlyStopped) "Secuencia anterior cerrada; sesión detenida" else "Inicia una sesión nueva manualmente"
                    }
                }
                main.post { events.changed() }
            }
            if(phase==3) {
                // FAILED can arrive before STARTED through independent Binder threads.
                // Stop input even when the ordering gate correctly discards a later STARTED.
                pause("No se pudo cerrar el gesto anterior: $reason")
                releaseUnverified=true; state="Cierre pendiente"; error=reason; mode=SessionMode.BLOCKED
            }
            main.post { events.changed() }
    }
    private val metricsLock = Any()
    @Volatile var armed = false; private set
    @Volatile var active = false; private set
    @Volatile var targetConfirmed = false; private set
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var probeStarted = false
    @Volatile var state = "Detenida"; private set
    @Volatile var error = ""; private set
    @Volatile var releaseUnverified = false; private set
    @Volatile var inputKeys = 0L; private set
    private val inputMouseCount=AtomicLong()
    val inputMouse:Long get()=inputMouseCount.get()
    private val relativeSampleCount=AtomicLong()
    val relativeSamples:Long get()=relativeSampleCount.get()
    @Volatile var wheelSteps = 0f; private set
    @Volatile var injected = 0L; private set
    @Volatile var contacts = 0; private set
    @Volatile var syntheticProbe = "Sin ejecutar"; private set
    private var width = 0
    private var height = 0
    private var composer: TouchComposer? = null
    private var engine: MappingEngine? = null
    private var macroRunner: MacroRunner? = null // Engine worker only.
    private var repeatRunner:RepeatRunner?=null
    private var fireCadenceRunner:FireCadenceRunner?=null
    private var cadencePressAtMs=-1L
    private var cadenceFirstDeltaAtMs=-1L
    private var cadenceLastDeltaAtMs=-1L
    private var cursorActionRunner:CursorActionRunner?=null
    private var cursorCommandTicket:Int?=null
    @Volatile private var cursorCommandPending=false
    @Volatile private var macroState:Map<String,Any?> = emptyMap()
    @Volatile private var actionState:Map<String,Any?> = emptyMap()
    private val precisionAimFilter=PrecisionAimFilter()
    @Volatile private var cameraPacer:CameraMotionPacer?=null
    private var cameraPacerWakeup:Runnable?=null
    private var ggCameraWakeup:Runnable?=null
    private var ggCameraWakeupAtMillis=Long.MAX_VALUE
    private var nativeRatePacing=false
    @Volatile private var cameraResetBatchSupported=false
    @Volatile private var cameraResetBatches=0L
    @Volatile private var cameraResetFailures=0L
    @Volatile private var cameraResetUnknownProgress=0L
    private val cameraResetIpcTime=RollingLatencySamples(512)
    private val cameraIdlePolicy=CameraIdlePolicy()
    @Volatile private var cameraIdleWakeup:Runnable?=null
    // Engine-thread ownership: an ACK from a replacement process cannot release this stream.
    private var sessionBackend: IInjector? = null
    @Volatile private var guardianClosurePending=false
    private var closureObservationPending=false
    private var nextClosureObservationAt=0L
    @Volatile private var camera = true
    private val pointerTransition=PointerTransitionGate()
    val pointerTransitionPending:Boolean get()=pointerTransition.pending || cursorCommandPending
    @Volatile private var startInCursor=false
    val cursorMode:Boolean get()=active && runtimeProfile!=null && !camera
    val mouseCaptureRateHz:Int get()=runtimeProfile?.profile?.camera?.updateRateHz ?: 0
    val profileSession:Boolean get()=runtimeProfile!=null
    private val queueWait = RollingLatencySamples(2048)
    private val ipcTime = RollingLatencySamples(2048)
    private val cameraDeliveryWait = RollingLatencySamples(2048)
    private val cameraSourceAge = RollingLatencySamples(2048)
    @Volatile private var expiredCameraSamples = 0L
    @Volatile private var invalidCameraTimeSamples = 0L
    @Volatile private var maxCameraSourceAgeNanos = 0L
    private var cameraSampleDiscontinuity = false // Engine worker only.
    @Volatile private var testRunning = false
    @Volatile private var generation = 0L
    @Volatile private var lastAttemptedFrame:TouchFrame?=null
    @Volatile private var lastMotorError:String?=null
    @Volatile private var firstRejectedInput:Map<String,Any?>?=null
    @Volatile private var metricsSessionId=0L
    private var inputDrainPacer:CameraMotionPacer?=null
    private var inputDrainMoveComposer:TouchComposer?=null
    private var inputDrainGeneration=0L
    @Volatile private var cameraTrial="sequential"
    @Volatile private var activePauseHistory:List<Map<String,Any?>> = emptyList()
    private val queue:InputQueue = InputQueue(worker, { type, code, x, y, enqueued, run ->
        if (active && targetConfirmed && run == generation) {
            if((type==3 || type==8) && InputLatencyProbe.enabled) {
                // Merged motion represents its latest source sample, before any camera pacing.
                val sampleTime=queue.consumingSampleTimeNanos
                if(sampleTime!=InputQueueEntry.NO_SAMPLE_TIME)
                    InputLatencyProbe.recordStage("mapper_mouse_worker_latest_sample",sampleTime)
            }
            synchronized(metricsLock) { queueWait.record(SystemClock.elapsedRealtimeNanos() - enqueued) }
            if(type!=8 && type!=3 && !finishBoundedMoveDrain()) {
                if(active) pause("No se pudo completar el movimiento pendiente")
                return@InputQueue
            }
            if(type!=3 && type!=8 && cameraPacer?.flush()==false) {
                if(active) pause("No se pudo completar el movimiento pendiente")
                return@InputQueue
            }
            if(!active || run!=generation) return@InputQueue
            when(type) {
                1 -> {
                    val canStart=camera && !pointerTransition.pending && cursorActionRunner?.pending!=true
                    when {
                        cursorActionRunner?.input("key",code,x!=0f)==true -> publishActionState()
                        repeatRunner?.input("key",code,x!=0f,canStart)==true -> publishActionState()
                        macroRunner?.input("key",code,x!=0f,canStart)==true -> publishMacroState()
                        !pointerTransition.pending && cursorActionRunner?.pending!=true && (runtimeProfile==null || camera) -> engine?.key(code,x!=0f)
                    }
                }
                2 -> {
                    val canStart=camera && !pointerTransition.pending && cursorActionRunner?.pending!=true
                    val command=when {
                        cursorActionRunner?.input("mouse",code,x!=0f)==true -> { publishActionState();true }
                        repeatRunner?.input("mouse",code,x!=0f,canStart)==true -> { publishActionState();true }
                        macroRunner?.input("mouse",code,x!=0f,canStart)==true -> { publishMacroState();true }
                        else -> false
                    }
                    val recognized=if(command || !canStart) false else engine?.mouseButton(code,x!=0f)==true
                    if(code==MappingEngine.LEFT_BUTTON) precisionAimFilter.setFiring(x!=0f && recognized)
                    val cadence=fireCadenceRunner
                    val cadenceCanStart=canStart && recognized && engine?.lastOperationSucceeded==true && composer?.paused==false
                    if(code==MappingEngine.LEFT_BUTTON && x!=0f && cadence?.held==false && cadenceCanStart) {
                        cadencePressAtMs=SystemClock.uptimeMillis();cadenceFirstDeltaAtMs=-1L;cadenceLastDeltaAtMs=-1L
                    }
                    cadence?.input("mouse",code,x!=0f,cadenceCanStart)
                }
                3 -> if (camera && !pointerTransition.pending && cursorActionRunner?.pending!=true && cameraShortcutEnabled() &&
                    (runtimeProfile?.bindings?.ggCameraPolicy==null || code==InputQueueEntry.NO_POINTER_EPOCH ||
                        pointerTransition.admitsMotion(code))) {
                    if(!admitPhysicalCameraSample(queue.consumingSampleTimeNanos)) return@InputQueue
                    if(nativeRatePacing && (runtimeProfile?.bindings?.ggCameraPolicy!=null ||
                        runtimeProfile?.bindings?.continuousCamera==false || cameraResetBatchSupported)) {
                        if(!beginBoundedMoveDrain() || engine?.relativeMouse(precisionMouseX(x),y)!=true) {
                            if(active) pause("No se pudo integrar el movimiento de cámara")
                        }
                    } else if(cameraPacer?.offer(precisionMouseX(x),y,enqueued,queue.consumingSampleTimeNanos)==false && active)
                        pause("No se pudo enviar el movimiento de cámara")
                }
                8 -> if(camera && !pointerTransition.pending && cursorActionRunner?.pending!=true && cameraShortcutEnabled() &&
                    (code==InputQueueEntry.NO_POINTER_EPOCH || pointerTransition.admitsMotion(code))) {
                    val batch=queue.consumingMotionBatch ?: return@InputQueue
                    if(runtimeProfile?.bindings?.ggCameraPolicy==null &&
                        runtimeProfile?.bindings?.continuousCamera==true && !cameraResetBatchSupported) {
                        val sumX=batch.filteredXSum { dx,time -> precisionAimFilter.filterX(dx,time) }
                        if(cameraPacer?.offer(sumX,batch.ySum(),enqueued,batch.timesNanos.last())==false && active)
                            pause("No se pudo enviar el movimiento de cámara")
                    } else {
                        // Both nonlinear compensation and precision aim consume original
                        // samples. Only delivery coalesces; contact edges always flush.
                        val sink=composer ?: return@InputQueue
                        val currentEngine=engine ?: return@InputQueue
                        if(!beginBoundedMoveDrain()) { if(active) pause("No se pudo preparar el movimiento de cámara");return@InputQueue }
                        val completed=batch.deliverMotion { dx,dy,time ->
                            active && targetConfirmed && run==generation &&
                                (!admitPhysicalCameraSample(time) ||
                                    currentEngine.relativeMouse(precisionAimFilter.filterX(dx,time),dy))
                        }
                        if(!completed) {
                            inputDrainMoveComposer=null;sink.discardMoveBatch()
                            if(active && run==generation) pause("No se pudo integrar el movimiento de cámara")
                        }
                    }
                }
                4 -> {
                    val requestedCursor=pointerTransition.requestedCursor(code) ?: return@InputQueue
                    precisionAimFilter.reset()
                    fireCadenceRunner?.cancel()
                    macroRunner?.cancel(releaseContact=true);publishMacroState()
                    repeatRunner?.cancel(releaseContact=true);publishActionState()
                    camera = !requestedCursor
                    if(runtimeProfile!=null) {
                        if(camera) engine?.leaveCursorMode() else engine?.enterCursorMode()
                        state=if(camera) "Juego activo" else "Cursor del juego activo"
                    } else {
                        if (!camera) engine?.mouseButton(MappingEngine.LEFT_BUTTON, false)
                        engine?.setCameraEnabled(camera)
                    }
                    if(cursorCommandTicket==code) {
                        publishEngineState()
                        if(active && run==generation) beginCursorCommand(run,code,requestedCursor)
                    } else publishMouse(run, runtimeProfile!=null || camera,code,requestedCursor)
                }
                5 -> if(!camera && runtimeProfile!=null && !pointerTransition.pending && cursorActionRunner?.pending!=true) engine?.cursorButton(code!=0,x,y)
                6 -> if(!camera && runtimeProfile!=null && !pointerTransition.pending && cursorActionRunner?.pending!=true) engine?.cursorMove(x,y)
                7 -> {
                    fireCadenceRunner?.input("mouse",code,false,canStart=false)
                    macroRunner?.input("mouse",code,false,canStart=false);publishMacroState()
                    repeatRunner?.input("mouse",code,false,canStart=false)
                    cursorActionRunner?.input("mouse",code,false);publishActionState()
                }
            }
            publishEngineState()
            // Only key transitions in free-cursor mode can change bubble availability here.
            // Steady mouse movement never schedules UI work or reads UI preferences.
            if((type==1 || type==5) && !camera && runtimeProfile!=null) main.post {
                if(run==generation) events.changed()
            }
        }
    }, { pause("Cola saturada; transiciones detenidas") }, beforeDrain={
        inputDrainPacer=if(active && targetConfirmed && runtimeProfile?.bindings?.continuousCamera==true &&
            runtimeProfile?.bindings?.ggCameraPolicy==null && !cameraResetBatchSupported) cameraPacer else null
        inputDrainGeneration=generation
        inputDrainPacer?.beginInputBatch()
    }, afterDrain={
        if(!finishBoundedMoveDrain() && active) pause("No se pudo enviar el movimiento de cámara pendiente")
        val pacer=inputDrainPacer
        inputDrainPacer=null
        if(pacer!=null) {
            if(!active || !targetConfirmed || inputDrainGeneration!=generation || pacer!==cameraPacer) pacer.discard()
            else if(!pacer.endInputBatch() && active) pause("No se pudo enviar el movimiento de cámara pendiente")
        }
        requestCameraIdleRetirement()
        requestGgCameraWakeup()
    })

    /** Worker admission only: old physical displacements never build a delayed camera tail. */
    private fun admitPhysicalCameraSample(sampleTimeNanos:Long):Boolean {
        if(runtimeProfile?.bindings?.ggCameraPolicy==null || sampleTimeNanos==InputQueueEntry.NO_SAMPLE_TIME) return true
        val age=CameraSampleAgePolicy.ageNanos(sampleTimeNanos,System.nanoTime())
        synchronized(metricsLock) {
            if(age==CameraSampleAgePolicy.INVALID_AGE) invalidCameraTimeSamples++
            else {
                cameraSourceAge.record(age)
                maxCameraSourceAgeNanos=maxOf(maxCameraSourceAgeNanos,age)
                if(!CameraSampleAgePolicy.admitsAge(age)) expiredCameraSamples++
            }
        }
        if(!CameraSampleAgePolicy.admitsAge(age)) {
            cameraSampleDiscontinuity=true
            return false
        }
        if(cameraSampleDiscontinuity) {
            precisionAimFilter.discardMotionHistory()
            cameraSampleDiscontinuity=false
        }
        return true
    }

    /** One bounded wakeup per moving burst; defer while application input is queued. */
    private fun usesIdleCameraRetirement():Boolean=runtimeProfile?.bindings?.let {
        it.ggCameraPolicy==null && (it.continuousCamera || it.retireBoundedCameraOnIdle)
    }==true

    private fun requestGgCameraWakeup() {
        if(!active || !targetConfirmed || !camera || pointerTransitionPending) return
        val currentEngine=engine ?: return
        val delay=currentEngine.ggCameraWakeupDelayMillis ?: return
        val due=SystemClock.uptimeMillis()+delay
        if(ggCameraWakeup!=null && ggCameraWakeupAtMillis<=due) return
        ggCameraWakeup?.let { worker.removeCallbacks(it) }
        val run=generation
        val task=object:Runnable {
            override fun run() {
                if(ggCameraWakeup!==this) return
                ggCameraWakeup=null;ggCameraWakeupAtMillis=Long.MAX_VALUE
                if(run!=generation || engine!==currentEngine || !active || !targetConfirmed || !camera ||
                    pointerTransitionPending) return
                if(!currentEngine.dispatchGgCameraTimers()) {
                    if(active) pause("No se pudo renovar el contacto de cámara")
                    return
                }
                publishEngineState()
                requestGgCameraWakeup()
            }
        }
        ggCameraWakeup=task
        ggCameraWakeupAtMillis=due
        if(!worker.postDelayed(task,delay)) {
            ggCameraWakeup=null;ggCameraWakeupAtMillis=Long.MAX_VALUE
            pause("El hilo de cámara no está disponible")
        }
    }
    private fun requestCameraIdleRetirement() {
        if(cameraIdleWakeup!=null || !active || !targetConfirmed || !camera || pointerTransitionPending ||
            !nativeRatePacing || !usesIdleCameraRetirement()) return
        val currentEngine=engine ?: return
        if(!currentEngine.canRetireIdleCamera) return
        val remaining=cameraIdlePolicy.remainingIdleNanos(SystemClock.elapsedRealtimeNanos()) ?: return
        val run=generation
        val task=object:Runnable {
            override fun run() {
                if(cameraIdleWakeup!==this) return
                if(run!=generation || engine!==currentEngine || !active || !targetConfirmed || !camera ||
                    pointerTransitionPending || !currentEngine.canRetireIdleCamera) {
                    cameraIdleWakeup=null;return
                }
                val wait=cameraIdlePolicy.remainingIdleNanos(SystemClock.elapsedRealtimeNanos())
                if(wait==null) { cameraIdleWakeup=null;return }
                if(wait>0L) { postCameraIdleCheck(this,wait);return }
                if(!queue.isEmpty() || cameraPacer?.pending==true || !environment.inputDispatchIdle()) {
                    postCameraIdleCheck(this,cameraIdlePolicy.quietThresholdNanos);return
                }
                cameraIdleWakeup=null
                // Only the camera finger ends. Held WASD/actions, source capture and mode stay owned.
                if(!currentEngine.retireIdleCamera()) {
                    if(active) pause("No se pudo renovar el contacto de cámara")
                } else publishEngineState()
            }
        }
        postCameraIdleCheck(task,remaining)
    }
    private fun postCameraIdleCheck(task:Runnable,delayNanos:Long) {
        cameraIdleWakeup=task
        if(!worker.postDelayed(task,if(delayNanos<=0L) 0L else (delayNanos+999_999L)/1_000_000L)) {
            cameraIdleWakeup=null
            pause("El hilo de cámara no está disponible")
        }
    }

    private fun beginBoundedMoveDrain():Boolean {
        if(inputDrainMoveComposer!=null) return true
        val sink=composer ?: return false
        if(!sink.beginMoveBatch()) return false
        inputDrainMoveComposer=sink
        return true
    }
    private fun finishBoundedMoveDrain():Boolean {
        val sink=inputDrainMoveComposer ?: return true
        inputDrainMoveComposer=null
        if(!active || !targetConfirmed || inputDrainGeneration!=generation || sink!==composer) {
            sink.discardMoveBatch();return true
        }
        if(sink.paused) { sink.discardMoveBatch();return false }
        val accepted=sink.endMoveBatch()
        if(accepted && usesIdleCameraRetirement())
            cameraIdlePolicy.motionDelivered(SystemClock.elapsedRealtimeNanos())
        return accepted
    }

    /** Apply only acknowledged transitions; a compact reset still has three independent ACKs. */
    private fun acknowledgeFrame(frame:TouchFrame) {
        injected++
        when(frame.action and 255) {
            TouchComposer.DOWN -> cameraContactAdmitted=frame.ids.contains(TouchCoordinatePolicy.CAMERA_ID)
            TouchComposer.POINTER_DOWN -> if(frame.ids[frame.action ushr 8]==TouchCoordinatePolicy.CAMERA_ID) cameraContactAdmitted=true
            TouchComposer.POINTER_UP -> if(frame.ids[frame.action ushr 8]==TouchCoordinatePolicy.CAMERA_ID) cameraContactAdmitted=false
            TouchComposer.UP,TouchComposer.CANCEL -> cameraContactAdmitted=false
        }
    }

    /** Engine-thread only; timed camera delivery has the same failure/release path as queued edges. */
    private fun publishEngineState() {
        contacts=composer?.activeCount ?: 0
        lastMotorError=composer?.lastError
        if(active && (composer?.paused==true || engine?.lastOperationSucceeded==false)) pause("Fallo del motor o de inyección")
    }
    /** Published at macro boundaries only; never allocates on ordinary relative mouse samples. */
    private fun publishMacroState() {
        macroState=macroRunner?.let { mapOf("activeId" to it.activeId,"stepIndex" to it.stepIndex,
            "pending" to it.pending,"completed" to it.completed,"rejected" to it.rejected,"error" to it.lastError) } ?: emptyMap()
    }
    private fun publishActionState() {
        cursorCommandPending=active && cursorActionRunner?.pending==true
        actionState=mapOf("repeatActive" to (repeatRunner?.activeCount ?: 0),"repeatPulses" to repeatRunner?.pulses,
            "repeatPending" to (repeatRunner?.pending ?: false),
            "repeatRejected" to repeatRunner?.rejected,"repeatError" to repeatRunner?.lastError,
            "cursorOwner" to cursorActionRunner?.currentOwner,"cursorQueued" to cursorActionRunner?.queued,
            "cursorCompleted" to cursorActionRunner?.completed,"cursorRejected" to cursorActionRunner?.rejected,
            "cursorError" to cursorActionRunner?.lastError,
            "cadence" to mapOf("running" to (fireCadenceRunner?.running ?: false),
                "active" to (fireCadenceRunner?.active ?: false),"pending" to (fireCadenceRunner?.pending ?: false),
                "ticks" to (fireCadenceRunner?.ticks ?: 0L),"totalY" to (fireCadenceRunner?.totalY ?: 0.0),
                "skippedElapsedMillis" to (fireCadenceRunner?.skippedElapsedMillis ?: 0L),
                "rejected" to (fireCadenceRunner?.rejected ?: 0L),"error" to fireCadenceRunner?.lastError,
                "pressAcceptedUptimeMs" to cadencePressAtMs,"firstDeltaUptimeMs" to cadenceFirstDeltaAtMs,
                "lastDeltaUptimeMs" to cadenceLastDeltaAtMs,
                "verticalPixelsPerSecond" to runtimeProfile?.fireCadence?.verticalPixelsPerSecond,
                "delayMillis" to runtimeProfile?.fireCadence?.delayMillis,
                "durationMillis" to runtimeProfile?.fireCadence?.durationMillis))
    }
    private fun scheduleAction(run:Long,delay:Long,action:()->Unit):()->Unit {
        val task=Runnable { if(run==generation && active && targetConfirmed) action() }
        if(!worker.postDelayed(task,delay)) pause("El hilo de controles no está disponible")
        return { worker.removeCallbacks(task) }
    }
    /** Presentation is hidden on main before a map command injects its one-shot tap. */
    private fun beginCursorCommand(run:Long,ticket:Int,cursor:Boolean) {
        main.post {
            if(run==generation && active && targetConfirmed && pointerTransition.current(ticket)) {
                environment.hideCursorForTransition()
                worker.post {
                    if(run==generation && active && targetConfirmed && pointerTransition.current(ticket)) {
                        val started=cursorActionRunner?.tap { success ->
                            if(run==generation && active && targetConfirmed && pointerTransition.current(ticket)) {
                                if(success) {
                                    cursorCommandTicket=null
                                    publishMouse(run,true,ticket,cursor)
                                } else pause("No se pudo completar la acción de cursor")
                            }
                        }==true
                        if(!started && active) pause("Acción de cursor sin transición preparada")
                    }
                }
            }
        }
    }
    private fun precisionMouseX(dx:Float):Float=precisionAimFilter.filterX(dx,queue.consumingSampleTimeNanos)
    private fun discardPacedMotion() {
        ggCameraWakeup?.let { worker.removeCallbacks(it) }
        ggCameraWakeup=null;ggCameraWakeupAtMillis=Long.MAX_VALUE
        cameraIdleWakeup?.let { worker.removeCallbacks(it) }
        cameraIdleWakeup=null;cameraIdlePolicy.reset()
        precisionAimFilter.reset()
        cameraPacerWakeup?.let { worker.removeCallbacks(it) }
        cameraPacerWakeup=null;cameraPacer?.discard();cameraPacer=null
    }

    private fun publishMouse(run: Long, enabled: Boolean,modeTicket:Int?=null,appliedCursor:Boolean=false) {
        main.post { synchronized(this) {
            if (run == generation) {
                environment.captureMouse(enabled && active && targetConfirmed && (runtimeProfile!=null || camera),run)
                modeTicket?.let { ticket ->
                    pointerTransition.acknowledge(ticket,appliedCursor)?.let { next ->
                        if(active && targetConfirmed) queue.offer(4,code=next,generation=run)
                    }
                    worker.post {
                        if(run==generation && active && targetConfirmed) {
                            cursorActionRunner?.retry();publishActionState()
                        }
                    }
                }
                events.changed()
            }
        } }
    }
    @Synchronized fun arm(width: Int, height: Int,displayId:Int=0) {
        useLaboratory(displayId)
        armInternal(width,height)
        targetChanged(capturedTargetConfirmed())
    }
    @Synchronized fun useLaboratory(displayId:Int=0) {
        check(SessionPolicy.mayInstallConfiguration(mode,contacts,releaseUnverified) && !active && !armed && !testRunning && !starting && !stopping) { "Espera a que termine el gesto anterior" }
        require(displayId>=0) { "Pantalla de destino no válida" }
        runtimeProfile=null; startInCursor=false;activeProfileId=null; profileName=null; targetPackage=TARGET;targetDisplayId=displayId
        lastAttemptedFrame=null;lastMotorError=null;firstRejectedInput=null
        if(Looper.myLooper()==Looper.getMainLooper()) environment.refreshContext()
        targetChanged(capturedTargetConfirmed())
    }
    /** A same-package cache from another display must never authorize a new session. */
    private fun capturedTargetConfirmed():Boolean {
        if(!environment.available) return false
        val capture=environment
        return capture.currentApplicationPackage==targetPackage && capture.boundsForPackage(targetPackage)!=null
    }
    @Synchronized fun selectEditingTarget(profile:GameProfile,displayId:Int=0) {
        check(SessionPolicy.mayEdit(contacts,releaseUnverified,active) && !stopping)
        require(displayId>=0) { "Pantalla de destino no válida" }
        runtimeProfile=null; activeProfileId=profile.id; profileName=profile.name; targetPackage=profile.packageName;targetDisplayId=displayId
        explicitlyStopped=false; mode=SessionMode.EDITING; state="Edición"; error="Mapeo pausado; guardar no reanuda acciones mantenidas"
    }
    @Synchronized fun armProfile(prepared:PreparedProfile,cursor:Boolean=false,displayId:Int=targetDisplayId) {
        check(SessionPolicy.mayInstallConfiguration(mode,contacts,releaseUnverified) && !active && !stopping) { "Termina o recupera la sesión anterior" }
        require(displayId>=0) { "Pantalla de destino no válida" }
        // Closed diagnostic choice is RAM-only and applies only at this empty transition.
        // The saved profile and the classic laboratory configuration are unchanged.
        runtimeProfile=if(prepared.profile.packageName==TARGET || prepared.bindings.continuousCamera) prepared else prepared.copy(bindings=prepared.bindings.copy(
            overlapCameraHandoff=cameraTrial=="overlap",distinctCameraContacts=cameraTrial!="same-id",cameraLast=true,
            continuousCamera=cameraTrial=="deferred",
            interlacedCameraHandoff=cameraTrial=="interlaced",retireBoundedCameraOnIdle=cameraTrial in setOf("idle","deferred"),
            heldFireCameraBounds=if(cameraTrial=="idle") prepared.profile.calibration.area.let { area ->
                AimBounds(area.left,area.top,Math.nextDown(area.right),Math.nextDown(area.bottom))
            } else null))
        startInCursor=cursor; activeProfileId=prepared.profile.id; profileName=prepared.profile.name; targetPackage=prepared.profile.packageName;targetDisplayId=displayId
        armInternal(prepared.profile.calibration.screenWidth,prepared.profile.calibration.screenHeight)
        targetChanged(capturedTargetConfirmed())
    }
    private fun armInternal(width:Int,height:Int) {
        pointerTransition.invalidate()
        explicitlyStopped=false
        check(environment.injector?.asBinder()?.isBinderAlive == true) { "Activa el backend ADB" }
        check(environment.available) { "Abre UwU Mouse para preparar la captura nativa" }
        check(!releaseUnverified) { "Liberación no verificada; revisar laboratorio antes de reactivar" }
        check(!active && !armed && !starting && !stopping && !testRunning) { "Detén la sesión anterior y espera su liberación" }
        this.width = width; this.height = height
        val run = ++generation
        targetConfirmed = false
        armed = true; mode=SessionMode.PAUSED; state = "Esperando destino"; error = ""
        main.postDelayed({ if (run == generation && armed) pause("No se confirmó el destino en 10 segundos") }, 10_000)
    }
    @Synchronized fun configureCameraTrial(strategy:String) {
        require(strategy in setOf("sequential","same-id","overlap","interlaced","idle","deferred"))
        // This choice only affects the next bounded profile; a stopped continuous profile
        // may still be the previous execution snapshot when the stored mode has just changed.
        check(SessionPolicy.mayInstallConfiguration(mode,contacts,releaseUnverified) && !active && !armed && !starting && !stopping && !testRunning) {
            "Detén la sesión y confirma cero contactos antes de cambiar el ensayo"
        }
        cameraTrial=strategy
    }
    @Synchronized fun targetChanged(isTarget: Boolean) {
        focusRecovery.contextChanged(isTarget)
        targetConfirmed = isTarget
        if (!isTarget) {
            if (active || starting || (testRunning && probeStarted)) pause("Aplicación destino sin foco; inicia otra vez manualmente")
            // A removed display cannot regain focus. Keep the bounded closure retry /
            // proof-observation heartbeat alive without authorizing or arming input.
            requestPendingRecovery()
            return
        }
        requestPendingRecovery()
        if (armed && !active && !testRunning && !starting && !stopping) {
            val run = generation
            starting = true
            worker.post {
                if (run != generation || !targetConfirmed) return@post
                if (prepare(run)) {
                    val accepted = synchronized(this) {
                        if (run == generation && targetConfirmed && armed && !stopping) {
                            pointerTransition.begin(!camera)
                            active = true; armed = false; starting = false; mode=SessionMode.PLAYING; state = if(targetPackage==TARGET) "Laboratorio activo" else "Juego activo"; true
                        } else false
                    }
                    if (accepted) publishMouse(run, true) else releaseOnWorker()
                } else synchronized(this) {
                    if (run == generation) { armed = false; starting = false }
                }
            }
        }
    }
    val needsRecoveryContext:Boolean get()=releaseUnverified || mode==SessionMode.RECOVERING
    val recoveryTargetPackage:String? get()=originalTargetPackage.takeIf { needsRecoveryContext && backendSessionId>0L }
    val recoveryTargetDisplayId:Int get()=originalTargetDisplayId

    /** An explicit retry can renew bounded closure attempts; it never arms input. */
    @Synchronized fun retryPendingClosure() {
        if(!needsRecoveryContext) return
        focusRecovery.retryExplicitly(backendSessionId)
        requestPendingRecovery()
    }

    /** Retrying closure is separate from arming: only an explicit later action starts mapping. */
    @Synchronized private fun requestPendingRecovery() {
        val backend=sessionBackend ?: return
        if(environment.injector?.asBinder()!==backend.asBinder()) return
        val eligible=releaseUnverified && !active && !armed && !starting && !stopping && !testRunning &&
            mode!=SessionMode.RECOVERING && (guardianClosurePending || composer?.recoveryFrame()!=null)
        if(!eligible) return
        // This RPC closes the immutable original stream only. Without the original context,
        // the guardian retires every producer and requires fresh global no-down proof.
        val attempt=focusRecovery.begin(backendSessionId,true,SystemClock.uptimeMillis(),allowUnfocused=true)
            ?: run { observeGuardianClosure(backend);return }
        val originalComposer=composer
        worker.post {
            var succeeded=false
            var retryAllowed=true
            try {
                if(backendSessionId!=attempt.sessionId || sessionBackend!==backend || composer!==originalComposer ||
                    active || armed || stopping || !releaseUnverified) return@post
                if(!backend.status().getBoolean("sessionRecoverySupported",false)) {
                    retryAllowed=false
                    mode=SessionMode.BLOCKED;state="Bloqueada";error="Actualiza el motor para recuperar esta sesión"
                    return@post // Protocol 4 engines with the original 22 transactions remain readable.
                }
                mode=SessionMode.RECOVERING;state="Recuperando gesto anterior"
                main.post { events.changed() }
                // A surviving guardian can still own an ACTIVE journal after app reattachment.
                if(guardianClosurePending) runCatching { backend.release(attempt.sessionId) }
                backend.recoverSession(attempt.sessionId)
                val closed=guardianClosureSnapshot(backend)?.provesClosureOf(originalSessionIdentity())==true
                if(closed && backendSessionId==attempt.sessionId && sessionBackend===backend && composer===originalComposer) {
                    composer?.acknowledgeBackendReset();releaseUnverified=false;contacts=0;cameraContactAdmitted=false
                    guardianClosurePending=false;sessionBackend=null
                    mode=if(explicitlyStopped) SessionMode.STOPPED else SessionMode.PAUSED
                    state=if(explicitlyStopped) "Detenida · secuencia recuperada" else "Pausada · secuencia recuperada"
                    error="Secuencia anterior cerrada; inicia manualmente"
                    succeeded=true
                } else {
                    mode=SessionMode.BLOCKED;state="Cierre pendiente";error="Aún no se ha confirmado que Android haya liberado los contactos"
                }
            } catch(_:Exception) {
                mode=SessionMode.BLOCKED;state="Bloqueada";error="No se pudo confirmar el cierre de la secuencia anterior"
            } finally {
                focusRecovery.finished(attempt,succeeded,SystemClock.uptimeMillis(),retryAllowed)
                main.post { events.changed() }
            }
        }
    }
    /** A reattached app has no old callback Binder. Observe proof after bounded retries end. */
    @Synchronized private fun observeGuardianClosure(backend:IInjector) {
        val now=SystemClock.uptimeMillis()
        if(closureObservationPending || now<nextClosureObservationAt) return
        closureObservationPending=true;nextClosureObservationAt=now+250L
        val sid=backendSessionId;val originalComposer=composer
        worker.post {
            try {
                if(!releaseUnverified || active || armed || starting || stopping || sid!=backendSessionId ||
                    sessionBackend!==backend || composer!==originalComposer) return@post
                if(guardianClosureSnapshot(backend)?.provesClosureOf(originalSessionIdentity())==true) {
                    composer?.acknowledgeBackendReset();releaseUnverified=false;contacts=0;cameraContactAdmitted=false
                    guardianClosurePending=false;sessionBackend=null
                    mode=SessionClosurePolicy.settledMode(explicitlyStopped,true,false)
                    state=if(explicitlyStopped) "Detenida · secuencia recuperada" else "Pausada · secuencia recuperada"
                    error="Secuencia anterior cerrada; inicia manualmente"
                    main.post { events.changed() }
                }
            } catch(_:Exception) { /* Missing status never establishes closure. */ }
            finally { synchronized(this) { closureObservationPending=false } }
        }
    }
    private fun prepare(run: Long): Boolean {
        if (run != generation) return false
        return try {
            discardPacedMotion()
            val backend = requireNotNull(environment.injector)
            check((composer?.activeCount ?: 0) == 0 && composer?.releaseUnverified != true && !releaseUnverified) {
                "La sesión anterior conserva contactos o una liberación no verificada"
            }
            val continuousCamera=runtimeProfile?.bindings?.continuousCamera==true
            val packageName=targetPackage
            val displayId=targetDisplayId
            if(displayId!=0) check(backend.status().getBoolean("multiDisplaySupported")) { "Reactiva el motor para usar esta pantalla" }
            val sid=if(displayId!=0) backend.beginTargetOnDisplay(client,width,height,packageName,displayId,continuousCamera)
                else if(continuousCamera) backend.beginContinuousTarget(client,width,height,packageName)
                else if(packageName==TARGET) backend.begin(client,width,height) else backend.beginTarget(client,width,height,packageName)
            check(sid>0) { "Backend no listo o liberación no verificada" }
            // Own the newly opened empty session before another status RPC can fail.
            previousBackendSessionId=backendSessionId
            backendSessionId=sid;sessionBackend=backend;check(noticeGate.begin(sid))
            originalTargetUid=-1;originalTargetPackage=packageName;originalTargetDisplayId=displayId
            originalContinuousCamera=continuousCamera;cameraContactAdmitted=false
            val backendState=backend.status()
            check(backendState.getInt("displayId",-1)==displayId) { "El motor no confirmó la pantalla de destino" }
            originalTargetUid=backendState.getInt("targetUid",-1)
            lastAttemptedFrame=null;lastMotorError=null;firstRejectedInput=null
            synchronized(metricsLock) {
                queueWait.clear();ipcTime.clear();cameraDeliveryWait.clear();cameraResetIpcTime.clear();cameraSourceAge.clear()
                expiredCameraSamples=0L;invalidCameraTimeSamples=0L;maxCameraSourceAgeNanos=0L;metricsSessionId=sid
            }
            cameraSampleDiscontinuity=false
            cameraResetBatches=0L;cameraResetFailures=0L;cameraResetUnknownProgress=0L
            // Continuous look retains its finger while moving; renew only during real short pauses.
            cameraResetBatchSupported=packageName!=TARGET && !continuousCamera &&
                backendState.getBoolean("cameraResetBatchSupported",false)
            check(runtimeProfile?.bindings?.interlacedCameraHandoff!=true ||
                backendState.getBoolean("cameraResetInterlacedSupported",false)) {
                "Actualiza el motor para probar el relevo de cámara"
            }
            val resetClient=if(cameraResetBatchSupported) CameraResetClient(backend,sid) else null
            // A callback already admitted before pause may have offered after its clear.
            // Configuration starts empty; no old-session motion or held edge is replayed.
            queue.clear()
            queue.resetMetrics()
            val mergeGeometry=runtimeProfile?.geometry
            if(mergeGeometry!=null) queue.configureCameraMerge(
                mergeGeometry.cameraRadiusX.toDouble()/mergeGeometry.sensitivityX,
                mergeGeometry.cameraRadiusY.toDouble()/mergeGeometry.sensitivityY,
                preserveSamples=runtimeProfile?.profile?.camera?.precisionAim==true || cameraResetBatchSupported ||
                    runtimeProfile?.bindings?.ggCameraPolicy!=null)
            else queue.disableCameraMerge()
            queue.configureMotionBatchCoalescing(runtimeProfile?.bindings?.ggCameraPolicy!=null)
            if (run != generation) { releaseOnWorker(); return false }
            composer = TouchComposer(SystemClock::uptimeMillis, { frame ->
                if (run != generation || stopping || !(active || (testRunning && probeStarted))) false else {
                lastAttemptedFrame=frame
                val before = SystemClock.elapsedRealtimeNanos()
                var failureOrigin="backendRejected"
                val accepted = try {
                    if(environment.allowsInjection(frame,continuousCamera && cameraContactAdmitted)!=true) {
                        failureOrigin="contextGate"
                        pause("Una ventana del sistema intercepta los controles; reanuda manualmente")
                        false
                    } else {
                        // These two stages start at synthetic composition time (1 ms precision),
                        // not the physical mouse timestamp. A Binder ACK does not prove display.
                        if(InputLatencyProbe.enabled) InputLatencyProbe.recordStage(
                            "injectionSubmit_syntheticComposition",frame.eventTime*1_000_000L,frame.action,frame.eventTime)
                        val admitted=backend.inject(sid,frame.action, frame.downTime, frame.eventTime, frame.ids, frame.xs, frame.ys)
                        if(InputLatencyProbe.enabled) InputLatencyProbe.recordStage(
                            "injectionAck_syntheticComposition",frame.eventTime*1_000_000L,frame.action,frame.eventTime)
                        admitted
                    }
                } catch(e:Exception) {
                    failureOrigin="backendException:${e.javaClass.simpleName}"
                    false
                }
                finally { synchronized(metricsLock) { ipcTime.record(SystemClock.elapsedRealtimeNanos() - before) } }
                if(!accepted && frame.action and 255 != TouchComposer.CANCEL && firstRejectedInput==null) {
                    firstRejectedInput=mapOf("sessionId" to sid,"origin" to failureOrigin,
                        "action" to frame.action,"ids" to frame.ids.toList(),"xs" to frame.xs.toList(),"ys" to frame.ys.toList(),
                        "downTime" to frame.downTime,"eventTime" to frame.eventTime,
                        "returnedUptimeMs" to SystemClock.uptimeMillis(),
                        "callMs" to (SystemClock.elapsedRealtimeNanos()-before)/1e6,
                        "windowContextAfterReturn" to environment.contextDiagnostics())
                }
                if(accepted) acknowledgeFrame(frame)
                accepted
                }
            },continuousCamera=continuousCamera,resetSink=resetClient?.let { reset -> { sequence ->
                if(run!=generation || stopping || !(active || (testRunning && probeStarted))) CameraResetResult(0,0)
                else if(sequence.frames.any { !environment.allowsInjection(it,continuousCamera && cameraContactAdmitted) }) {
                    pause("Una ventana del sistema intercepta los controles; reanuda manualmente")
                    CameraResetResult(0,0)
                } else {
                    val started=SystemClock.elapsedRealtimeNanos()
                    val result=reset.inject(sequence.frames)
                    synchronized(metricsLock) {
                        cameraResetIpcTime.record(SystemClock.elapsedRealtimeNanos()-started)
                    }
                    cameraResetBatches++
                    lastAttemptedFrame=reset.lastAttemptedFrame
                    if(result.progressKnown) reset.acceptedFrames.forEach(::acknowledgeFrame)
                    else cameraResetUnknownProgress++
                    if(!result.progressKnown || result.acceptedCount!=3) {
                        cameraResetFailures++
                        if(firstRejectedInput==null) firstRejectedInput=mapOf(
                            "sessionId" to sid,"origin" to "cameraResetBatch",
                            "progressKnown" to result.progressKnown,"acceptedCount" to result.acceptedCount,
                            "attemptedCount" to result.attemptedCount,"returnedUptimeMs" to SystemClock.uptimeMillis())
                    }
                    result
                }
            } },orderedContacts=runtimeProfile?.bindings?.interlacedCameraHandoff==true)
            val prepared=runtimeProfile
            precisionAimFilter.configure(prepared?.let { it.profile.camera.precisionAim &&
                it.bindings.handlesButton(MappingEngine.LEFT_BUTTON) }==true)
            engine = if(prepared!=null) MappingEngine(composer!!,prepared.geometry,prepared.bindings,SystemClock::uptimeMillis).apply { if(startInCursor) enterCursorMode() else setCameraEnabled(true) }
            else MappingEngine(composer!!, MappingGeometry(width.toFloat(), height.toFloat(),
                joystickX=width*.23f, joystickY=height*.70f, joystickRadius=minOf(width*.12f,height*.24f),
                actionX=width*.82f, actionY=height*.82f)).apply { setCameraEnabled(true) }
            val actionComposer=composer!!
            val beginAction:(String,Float,Float)->Boolean={ owner,x,y ->
                val limit=if(actionComposer.continuousCamera && !actionComposer.hasOwner(MappingEngine.CAMERA_OWNER)) 9 else 10
                !actionComposer.paused && actionComposer.activeCount<limit && actionComposer.begin(owner,x,y,
                    beforeOwner=if(prepared?.bindings?.cameraLast==true) MappingEngine.CAMERA_OWNER else null)
            }
            repeatRunner?.cancel();fireCadenceRunner?.cancel();cursorActionRunner?.cancel();cursorCommandTicket=null;cursorCommandPending=false
            cadencePressAtMs=-1L;cadenceFirstDeltaAtMs=-1L;cadenceLastDeltaAtMs=-1L
            val cadenceEngine=engine!!
            fireCadenceRunner=prepared?.fireCadence?.let { config ->
                FireCadenceRunner(config,SystemClock::uptimeMillis,
                    schedule={ delay,action -> scheduleAction(run,delay,action) },
                    moveCamera={ dy ->
                        val eligible=run==generation && active && targetConfirmed && camera &&
                            !pointerTransitionPending && engine===cadenceEngine && composer===actionComposer
                        if(!eligible || !finishBoundedMoveDrain() || cameraPacer?.flush()==false) false else {
                            val submitted=SystemClock.uptimeMillis()
                            val accepted=cadenceEngine.syntheticCameraMotion(dy)
                            if(accepted) {
                                cameraIdlePolicy.motionDelivered(SystemClock.elapsedRealtimeNanos())
                                if(cadenceFirstDeltaAtMs<0L) cadenceFirstDeltaAtMs=submitted
                                cadenceLastDeltaAtMs=submitted
                            }
                            publishEngineState()
                            requestGgCameraWakeup()
                            accepted && active && run==generation
                        }
                    },canContinue={ run==generation && active && targetConfirmed && camera &&
                        !pointerTransitionPending && engine===cadenceEngine && composer===actionComposer &&
                        !actionComposer.paused && cadenceEngine.cameraEnabled },
                    changed={ publishActionState() })
            }
            repeatRunner=prepared?.repeats?.takeIf { it.isNotEmpty() }?.let { repeats ->
                RepeatRunner(repeats,SystemClock::uptimeMillis,{ delay,action -> scheduleAction(run,delay,action) },
                    beginAction,{ actionComposer.end(it) },{ publishActionState();publishEngineState() })
            }
            cursorActionRunner=prepared?.cursorActions?.takeIf { it.isNotEmpty() }?.let { commands ->
                CursorActionRunner(commands,reserveTransition={
                    val ticket=pointerTransition.requestCommand()
                    if(ticket==null) false else {
                        cursorCommandTicket=ticket
                        queue.offer(4,code=ticket,generation=run)
                    }
                },schedule={ delay,action -> scheduleAction(run,delay,action) },begin=beginAction,
                    end={ actionComposer.end(it) },changed={ publishActionState();publishEngineState() })
            }
            publishActionState()
            macroRunner?.cancel()
            macroRunner=prepared?.macros?.takeIf { it.isNotEmpty() }?.let { macros ->
                val sessionComposer=composer!!
                MacroRunner(macros, schedule={ delay, action ->
                    val task=Runnable { if(run==generation && active && targetConfirmed) action() }
                    worker.postDelayed(task,delay)
                    val cancel:()->Unit = { worker.removeCallbacks(task) }
                    cancel
                },begin={ owner,x,y ->
                    // Refuse this macro alone when the normal contact slots are occupied.
                    // Continuous camera reserves ID9 even while that finger is currently up.
                    val limit=if(sessionComposer.continuousCamera && !sessionComposer.hasOwner(MappingEngine.CAMERA_OWNER)) 9 else 10
                    !sessionComposer.paused && sessionComposer.activeCount<limit && sessionComposer.begin(owner,x,y,
                        beforeOwner=if(prepared.bindings.cameraLast) MappingEngine.CAMERA_OWNER else null)
                },end={ sessionComposer.end(it) },changed={ publishMacroState();publishEngineState() })
            }
            publishMacroState()
            lateinit var sessionPacer:CameraMotionPacer
            val wakeup=Runnable {
                if(run==generation && active && camera && targetConfirmed) {
                    if(!sessionPacer.drainDue() && active) pause("No se pudo enviar el movimiento de cámara programado")
                }
                else sessionPacer.discard()
            }
            sessionPacer=CameraMotionPacer(SystemClock::elapsedRealtimeNanos,{ dx,dy,oldestEnqueued ->
                if(run!=generation || !active || !camera || !targetConfirmed) false else {
                    synchronized(metricsLock) { cameraDeliveryWait.record(SystemClock.elapsedRealtimeNanos()-oldestEnqueued) }
                    if(engine?.relativeMouse(dx,dy)==true) cameraIdlePolicy.motionDelivered(SystemClock.elapsedRealtimeNanos())
                    publishEngineState()
                    requestCameraIdleRetirement()
                    requestGgCameraWakeup()
                    active && run==generation && composer?.paused!=true && engine?.lastOperationSucceeded!=false
                }
            },{ delayNanos ->
                val delayMillis=if(delayNanos<=0L) 0L else (delayNanos+999_999L)/1_000_000L
                if(!worker.postDelayed(wakeup,delayMillis)) pause("El hilo de cámara no está disponible")
            })
            val nativeRateSupported=backendState.getBoolean("nativeCaptureRateSupported",false)
            nativeRatePacing=nativeRateSupported
            val requestedRate=prepared?.profile?.camera?.updateRateHz ?: 0
            cameraIdlePolicy.configureRate(requestedRate)
            check(nativeRateSupported || requestedRate !in setOf(100,1000)) {
                "Actualiza el motor para limitar la captura nativa"
            }
            check(nativeRateSupported || requestedRate==0 || prepared?.bindings?.ggCameraPolicy==null) {
                "Actualiza el motor para limitar la captura sin alterar la compensación de cámara"
            }
            sessionPacer.configure(if(nativeRateSupported) 0 else requestedRate)
            cameraPacer=sessionPacer;cameraPacerWakeup=wakeup
            camera = prepared==null || !startInCursor; true
        } catch(e: Exception) {
            releaseOnWorker()
            synchronized(this) { if (run == generation) { error = e.message ?: "Backend no disponible"; state = "Pausada" } }
            false
        }
    }
    fun key(code: Int, down: Boolean) {
        if(!active || !targetConfirmed) return
        inputKeys++
        val shortcuts=runtimeProfile?.profile?.shortcuts
        if(shortcuts!=null) {
            if(shortcuts.stop>0 && code==shortcuts.stop) { if(down) pause("Parada por atajo configurado"); return }
            if(cameraShortcutEnabled() && code==shortcuts.cameraToggle) { if(down) requestPointerMode(); return }
            val suspendKey=runtimeProfile?.profile?.camera?.suspendKey ?: 0
            if(cameraShortcutEnabled() && suspendKey>0 && code==suspendKey) {
                pointerTransition.hold(down)?.let { queue.offer(4,code=it,generation=generation) }
                return
            }
            if(shortcuts.edit>0 && code==shortcuts.edit) { if(down) main.post { events.requestEditor() }; return }
            if(code in (runtimeProfile?.bindings?.commandKeys ?: emptySet())) {
                queue.offer(1,code,if(down)1f else 0f,generation=generation);return
            }
            // A consumed DOWN may be followed by its UP after switching to the cursor.
            // Let the runner clear its latch without starting anything in that mode.
            if(!down && code in (runtimeProfile?.bindings?.macroKeys ?: emptySet())) {
                queue.offer(1,code,0f,generation=generation);return
            }
            if(cursorMode || pointerTransitionPending) return
            if(runtimeProfile?.bindings?.handlesKey(code)==true) queue.offer(1,code,if(down)1f else 0f,generation=generation)
            return
        }
        when (SessionKeys.actionFor(code)) {
            SessionKeyAction.STOP -> { if (down) pause("Parada por atajo configurado"); return }
            SessionKeyAction.CAMERA_TOGGLE -> { if (down) requestPointerMode(); return }
            SessionKeyAction.PASS_THROUGH -> return
            SessionKeyAction.MAPPING -> Unit
        }
        if(!pointerTransitionPending) queue.offer(1, code, if (down) 1f else 0f, generation=generation)
    }
    private fun requestPointerMode() { pointerTransition.toggleBase()?.let { queue.offer(4,code=it,generation=generation) } }
    fun cameraShortcutEnabled():Boolean=runtimeProfile?.bindings?.cameraMappingEnabled!=false
    fun isCursorCommandButton(code:Int):Boolean=runtimeProfile?.cursorActions?.any { it.triggerKind=="mouse" && it.code==code }==true
    fun isCameraSuspendButton(code:Int):Boolean=code>0 && cameraShortcutEnabled() &&
        runtimeProfile?.profile?.camera?.suspendMouseButton==code
    /** Both physical edges bypass the transition gate, just like the keyboard hold. */
    fun cameraSuspendButton(code:Int,down:Boolean):Boolean {
        if(!active || !targetConfirmed || !isCameraSuspendButton(code)) return false
        inputMouseCount.incrementAndGet()
        pointerTransition.hold(down)?.let { queue.offer(4,code=it,generation=generation) }
        return true
    }
    /** Cold presentation setup, before the cursor window becomes visible. */
    fun cursorEntryBounds(target:PointerBounds):PointerBounds {
        val area=runtimeProfile?.profile?.calibration?.area
        return CursorPositionPolicy.entryArea(area?.let { PointerBounds(it.left,it.top,it.right,it.bottom) },target)
    }
    val cursorDisplayWidth:Int get()=width
    val cursorDisplayHeight:Int get()=height
    fun nativeMouseReleased() {
        val run=generation
        main.postDelayed({ worker.post {
            if(run==generation && !active && !starting) runCatching { environment.injector?.release(backendSessionId) }
        } },48)
    }
    fun button(code: Int, down: Boolean) {
        if(cameraSuspendButton(code,down)) return
        inputMouseCount.incrementAndGet();queue.offer(2,code,if(down)1f else 0f,generation=generation)
    }
    /** Physical UP bypasses mode transitions to clear latches; ordinary taps still receive UP. */
    fun mappedButtonReleased(code:Int):Boolean {
        val bindings=runtimeProfile?.bindings ?: return false
        val consumesRelease=code in bindings.macroButtons || code in bindings.commandButtons
        val releasesCadence=runtimeProfile?.fireCadence?.code==code
        if(!active || !targetConfirmed || (!consumesRelease && !releasesCadence)) return false
        queue.offer(7,code=code,generation=generation)
        return consumesRelease // Cadence never consumes the fire tap's ordinary physical UP.
    }
    fun macroButtonReleased(code:Int):Boolean=mappedButtonReleased(code)
    fun mouse(dx: Float, dy: Float, sampleTimeNanos:Long=Long.MIN_VALUE) {
        if(!active || !targetConfirmed || pointerTransitionPending) return
        inputMouseCount.incrementAndGet();if(dx!=0f || dy!=0f) {
            if(camera && usesIdleCameraRetirement()) {
                cameraIdlePolicy.sourceMotion(sampleTimeNanos)
                cameraIdlePolicy.motionReceived(SystemClock.elapsedRealtimeNanos())
            }
            relativeSampleCount.incrementAndGet();queue.offer(3,code=InputQueueEntry.NO_POINTER_EPOCH,
                x=dx,y=dy,generation=generation,sampleTimeNanos=sampleTimeNanos)
        }
    }
    fun mouseBatch(batch:RawMouseMotionBatch) {
        val run=generation
        if(!active || !targetConfirmed || pointerTransitionPending) return
        val pointerEpoch=if(runtimeProfile?.bindings?.ggCameraPolicy!=null)
            pointerTransition.motionEpoch() ?: return else InputQueueEntry.NO_POINTER_EPOCH
        enqueueMouseBatch(batch,run,camera && usesIdleCameraRetirement(),pointerEpoch)
    }
    /** Cold publication by main. A racing lifecycle change only makes this hint stale. */
    fun nativeCameraRoute(captureId:Long,buttons:Int):NativeMotionDispatchGate.Route? {
        val run=generation
        if(!active || !targetConfirmed || !camera || pointerTransitionPending || runtimeProfile?.bindings?.ggCameraPolicy==null) return null
        val pointerEpoch=pointerTransition.motionEpoch() ?: return null
        return if(run==generation) NativeMotionDispatchGate.Route(captureId,run,buttons,pointerEpoch) else null
    }
    /** Steady Binder ingress only enqueues; overflow keeps the ordinary pause/release path. */
    fun tryNativeCameraBatch(batch:RawMouseMotionBatch,route:NativeMotionDispatchGate.Route):Boolean {
        val run=route.generation
        if(run!=generation || !active || !targetConfirmed || !camera || pointerTransitionPending ||
            runtimeProfile?.bindings?.ggCameraPolicy==null || !pointerTransition.admitsMotion(route.pointerEpoch)) return false
        // This camera policy owns its timers on the worker. Legacy idle-retirement
        // policies keep the main route and cannot seed another generation's idle state.
        enqueueMouseBatch(batch,run,trackIdle=false,pointerEpoch=route.pointerEpoch)
        // Even an overflow is handled here: its ordinary pause/release path owns the failure.
        // Returning false after an offer would let the caller submit this same batch twice.
        return true
    }
    /** The one-sample native lane needs no temporary timestamp/X/Y arrays. */
    fun tryNativeCameraMotion(sampleTimeNanos:Long,dx:Float,dy:Float,route:NativeMotionDispatchGate.Route):Boolean {
        if(sampleTimeNanos<0L || !dx.isFinite() || !dy.isFinite()) return false
        val run=route.generation
        if(run!=generation || !active || !targetConfirmed || !camera || pointerTransitionPending ||
            runtimeProfile?.bindings?.ggCameraPolicy==null || !pointerTransition.admitsMotion(route.pointerEpoch)) return false
        inputMouseCount.incrementAndGet()
        if(dx!=0f || dy!=0f) {
            relativeSampleCount.incrementAndGet()
            queue.offer(3,code=route.pointerEpoch,x=dx,y=dy,generation=run,sampleTimeNanos=sampleTimeNanos)
        }
        return true
    }
    private fun enqueueMouseBatch(batch:RawMouseMotionBatch,run:Long,trackIdle:Boolean,pointerEpoch:Int) {
        inputMouseCount.addAndGet(batch.size.toLong())
        if(batch.movingSampleCount==0) return
        relativeSampleCount.addAndGet(batch.movingSampleCount.toLong())
        if(trackIdle) {
            for(index in batch.timesNanos.indices) if(batch.xs[index]!=0f || batch.ys[index]!=0f)
                cameraIdlePolicy.sourceMotion(batch.timesNanos[index])
            cameraIdlePolicy.motionReceived(SystemClock.elapsedRealtimeNanos())
        }
        queue.offerMotionBatch(batch,run,pointerEpoch)
    }
    fun cursorMove(x:Float,y:Float) {
        if(!active || !targetConfirmed || pointerTransitionPending) return
        inputMouseCount.incrementAndGet();queue.offer(6,x=x,y=y,generation=generation)
    }
    fun cursorButton(down:Boolean,x:Float,y:Float) {
        if(!active || !targetConfirmed || pointerTransitionPending) return
        inputMouseCount.incrementAndGet();queue.offer(5,code=if(down)1 else 0,x=x,y=y,generation=generation)
    }
    fun wheel(vertical: Float) { wheelSteps += vertical } // observation only, no fake binding
    fun backendDied() { pause("Backend perdido; la liberación requiere comprobarse en receptor") }
    fun consumesKey(code:Int):Boolean {
        if(!active || !targetConfirmed) return false
        val p=runtimeProfile ?: return SessionKeys.consumes(code)
        return ProfileInputPolicy.consumesKey(p,code,cursorMode,pointerTransitionPending)
    }
    fun consumesButton(code:Int):Boolean {
        if(!active || !targetConfirmed) return false
        val profile=runtimeProfile ?: return code==MappingEngine.LEFT_BUTTON
        return ProfileInputPolicy.consumesButton(profile,code,cursorMode,pointerTransitionPending)
    }
    fun isTransitioning()=armed || starting || stopping || testRunning
    fun pauseThen(reason:String,onReady:(Boolean)->Unit) {
        pause(reason)
        val run=generation
        worker.post { val safe=SessionPolicy.mayEdit(contacts,releaseUnverified,active)
            main.post { if(run==generation) onReady(safe) }
        }
    }
    @Synchronized fun editorClosed() {
        if(active || stopping || mode==SessionMode.RECOVERING) return
        mode=SessionClosurePolicy.settledMode(explicitlyStopped,!releaseUnverified,false)
        state=when(mode) { SessionMode.STOPPED -> "Detenida";SessionMode.BLOCKED -> "Cierre pendiente";else -> "Pausada" }
    }
    @Synchronized fun stopSession() {
        explicitlyStopped=true
        // A new explicit close renews the bounded proof attempts, including a blocked
        // journal whose earlier retries ran while a physical finger was still down.
        focusRecovery.retryExplicitly(backendSessionId)
        if(!SessionClosurePolicy.needsStopWork(active,isTransitioning(),sessionBackend!=null,contacts,
                releaseUnverified,guardianClosurePending)) {
            mode=SessionMode.STOPPED;state="Detenida";error=""
            main.post { events.changed() }
            return
        }
        if(!stopping) pause("Detenida por usuario")
    }
    @Synchronized fun sessionServiceDestroyed() {
        // A prior pause owns its release and diagnostic reason. Service cleanup must
        // not replace either, including an inactive blocked/recovering sequence.
        if(stopping || (!active && !armed && !starting && !testRunning)) return
        pause("Servicio de sesión detenido")
    }
    @Synchronized fun pause(reason: String = "Detenida por usuario") {
        if(active || probeStarted) {
            val frame=lastAttemptedFrame
            val trace=mapOf<String,Any?>("uptimeMs" to SystemClock.uptimeMillis(),"reason" to reason,
                "sessionId" to backendSessionId,"packageName" to targetPackage,"contactsBeforePause" to contacts,
                "motorError" to lastMotorError,"attemptedAction" to frame?.action,"attemptedIds" to frame?.ids?.toList(),
                "firstRejectedInput" to firstRejectedInput,
                "cameraRate" to cameraPacer?.diagnostics(),
                "attemptedXs" to frame?.xs?.toList(),"attemptedYs" to frame?.ys?.toList(),
                "windowContext" to environment.contextDiagnostics(),
                "pointerMode" to if(cursorMode) "cursor" else "camera","keys" to inputKeys,"relativeSamples" to relativeSamples)
            activePauseHistory=(activePauseHistory+trace).takeLast(8)
        }
        active = false; armed = false; generation++; testRunning = false;pointerTransition.invalidate();cursorCommandPending=false
        starting = false; stopping = true; probeStarted = false
        val run = generation
        // STOPPED is a confirmed outcome, not the request while release is still queued.
        mode=SessionMode.PAUSED; state = if(explicitlyStopped) "Cerrando sesión" else "Pausada"; error = reason
        queue.clear()
        publishMouse(run, false)
        main.post { events.changed() }
        worker.post {
            releaseOnWorker()
            synchronized(this) { if (run == generation) {
                stopping = false
                mode=SessionClosurePolicy.settledMode(explicitlyStopped,!releaseUnverified,mode==SessionMode.RECOVERING)
                state=when(mode) {
                    SessionMode.STOPPED -> "Detenida"
                    SessionMode.BLOCKED -> "Cierre pendiente"
                    SessionMode.RECOVERING -> "Recuperando gesto anterior"
                    else -> "Pausada"
                }
            } }
            requestPendingRecovery()
            main.post { events.changed() }
            Log.i("UwUMouse", "paused releaseAcknowledged=${!releaseUnverified} pending=$contacts reason=$reason")
        }
    }
    private fun releaseOnWorker(): Boolean {
        repeatRunner?.cancel();fireCadenceRunner?.cancel();cursorActionRunner?.cancel();cursorCommandTicket=null;publishActionState()
        macroRunner?.cancel();publishMacroState()
        discardPacedMotion()
        val c = composer
        val backend = sessionBackend
        val priorUnverified = releaseUnverified
        val pending = (c?.activeCount ?: 0) > 0 || c?.releaseUnverified == true || priorUnverified
        val localReleased = engine?.pause() ?: c?.cancelAll() ?: !pending
        if(backend!=null) runCatching { backend.release(backendSessionId) }
        val observed=runCatching { backend?.let(::guardianClosureSnapshot) }.getOrNull()
        // beginTarget can succeed before its following status reply is lost. Recover the
        // missing UID only from that same guardian/SID/package/display, never a new target.
        if(originalTargetUid<10_000 && observed?.identity?.let {
                it.sessionId==backendSessionId && it.packageName==originalTargetPackage && it.displayId==originalTargetDisplayId
            }==true) originalTargetUid=observed.identity.targetUid
        val backendReleased=observed?.provesClosureOf(originalSessionIdentity())==true
        // Only the Binder captured with this composer can acknowledge its possibly delivered stream.
        // A new registered backend may know nothing about contacts left by a dead process.
        if (backendReleased) { c?.acknowledgeBackendReset(); sessionBackend = null;cameraContactAdmitted=false;guardianClosurePending=false }
        else if(backend!=null && backendSessionId>0L && c?.recoveryFrame()==null) guardianClosurePending=true
        releaseUnverified = if (backendReleased) false else
            (c?.releaseUnverified == true || (c?.activeCount ?: 0) > 0 || (priorUnverified && !localReleased) ||
                guardianClosurePending || (backend!=null && backendSessionId>0L))
        contacts = if(backendReleased) 0 else maxOf(c?.activeCount ?: 0,if(guardianClosurePending) contacts else 0)
        return !releaseUnverified
    }
    private fun probeTargetFocused(): Boolean = if (environment.available) targetConfirmed else
        runCatching { environment.injector?.status()?.getBoolean("targetFocused") == true }.getOrDefault(false)
    /** Deterministic injection only. Never counts as a physical keyboard/mouse test. */
    @Synchronized fun probe(width: Int, height: Int,displayId:Int=0) {
        useLaboratory(displayId)
        check(!active && !armed && !starting && !stopping && !testRunning) { "Detén primero la sesión y espera su liberación" }
        check(!releaseUnverified) { "Liberación pendiente de verificar" }
        this.width = width; this.height = height
        val run = ++generation
        testRunning = true; probeStarted = false; syntheticProbe = "Esperando receptor"; state = "Diagnóstico sintético"; error = ""
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var focusConfirmedAt = 0L
        val awaitTarget = object : Runnable {
        override fun run() {
            if (run != generation) return
            if (!probeTargetFocused()) {
                focusConfirmedAt = 0L
                if (SystemClock.elapsedRealtime() >= deadline) { syntheticProbe = "Destino no confirmado"; pause("No se confirmó foco del receptor") }
                else worker.postDelayed(this, 150)
                return
            }
            // The focused Activity can still be transformed by its launch animation. This delay is
            // diagnostic startup only; no delay is introduced in the physical input engine.
            val now = SystemClock.elapsedRealtime()
            if (focusConfirmedAt == 0L) {
                focusConfirmedAt = now
                syntheticProbe = "Esperando estabilización de ventana"
                worker.postDelayed(this, 600)
                return
            }
            if (now - focusConfirmedAt < 600L) { worker.postDelayed(this, 600L - (now - focusConfirmedAt)); return }
            if (!prepare(run)) { if (run == generation) { syntheticProbe = "Falló: $error"; testRunning = false }; return }
            if (run != generation || !probeTargetFocused()) { releaseOnWorker(); if (run == generation) pause("Destino perdido al preparar"); return }
            probeStarted = true
            val c = composer!!
            fun step(delay: Long, operation: () -> Boolean) { worker.postDelayed({
                if (run != generation) return@postDelayed
                if (!probeTargetFocused()) { syntheticProbe = "Interrumpido: destino sin foco"; pause("Destino perdido durante diagnóstico"); return@postDelayed }
                if (!operation()) { syntheticProbe = "Falló: ${c.lastError}"; pause("Fallo diagnóstico") }
                contacts = c.activeCount
            }, delay) }
            step(0) { c.begin("probe-joystick", width*.23f, height*.70f) }
            step(150) { c.begin("probe-camera", width*.66f, height*.48f) }
            step(300) { c.begin("probe-action", width*.82f, height*.82f) }
            step(450) { c.move("probe-joystick", width*.30f, height*.66f) }
            step(600) { c.end("probe-camera") }
            step(750) { c.move("probe-joystick", width*.19f, height*.67f) }
            step(900) { c.end("probe-action") }
            step(1050) { c.end("probe-joystick") }
            step(1200) {
                val released = releaseOnWorker()
                if (!released) return@step false
                syntheticProbe = "8 transiciones aceptadas; verificar informe del receptor"
                state = "Diagnóstico terminado"; testRunning = false; probeStarted = false
                Log.i("UwUMouse", "probe complete pending=${c.activeCount} injected=$injected")
                true
            }
        } }
        worker.post(awaitTarget)
    }
    fun shutdownBackend() { pause(); worker.post { runCatching { environment.injector?.shutdown() }; environment.injector = null } }
    /** Closed laboratory route; exercises cursor delivery with an independent touch receiver. */
    @Synchronized fun testCursorLab(context:Context) {
        check(environment.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory()
        val display=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(context,0)
        val size=android.graphics.Point(display.width,display.height)
        val p=GameProfile.create(TARGET,"Cursor InputLab",size.x,size.y,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=CameraSpec.BOUNDED,updateRateHz=0),shortcuts=SessionShortcuts(138,139,0))
        }
        armProfile(ProfileCompiler.prepare(p,size.x,size.y,display.rotation),cursor=true)
        activeProfileId=null // Ephemeral laboratory configuration, not a selected game profile.
    }
    /** Closed synthetic repeat/Map regression; same receiver, shared compositor, RAM-only profile. */
    fun testActionLab(context:Context,displayId:Int=0) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testActionLab(context,displayId) }.onFailure { pause(it.message ?: "Falló la prueba de acciones") } }
            return
        }
        check(environment.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory(displayId)
        val display=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(context,displayId)
        val bounds=requireNotNull(environment.boundsForPackage(TARGET)) { "Receptor sin ventana verificada" }
        val size=android.graphics.Point(display.width,display.height)
        val profile=GameProfile.create(TARGET,"Acciones InputLab",size.x,size.y,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true,
                area=GameArea(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat())),
                camera=it.camera.copy(enabled=true,mode=CameraSpec.SMOOTH,suspendKey=0,updateRateHz=0,precisionAim=false),
                action=ActionSpec(.87f,.80f,code=62),shortcuts=SessionShortcuts(138,139,0),
                additionalActions=listOf(
                    ActionSpec(.50f,.50f,code=30,behavior=ActionSpec.REPEAT,repeatIntervalMs=100),
                    ActionSpec(.40f,.60f,code=31,behavior=ActionSpec.REPEAT,repeatIntervalMs=250,
                        repeatToggle=true,repeatDurationSeconds=1),
                    ActionSpec(.75f,.35f,code=41,behavior=ActionSpec.CURSOR_TOGGLE)))
        }
        armProfile(ProfileCompiler.prepare(profile,size.x,size.y,display.rotation));activeProfileId=null
        val run=generation;val deadline=SystemClock.uptimeMillis()+5000
        syntheticProbe="Preparando repetición y dos acciones Map; entrada sintética"
        val await=object:Runnable { override fun run() {
            if(run!=generation) return
            // Synthetic input has no native-capture admission gate. Wait until the
            // initial capture handoff finishes before it can create contacts.
            if(!active || !environment.inputDispatchIdle()) {
                if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20)
                else pause("No inició la prueba de acciones")
                return
            }
            fun step(delay:Long,action:()->Unit) {
                worker.postDelayed({ if(run==generation && active && targetConfirmed) action() },delay)
            }
            var loopStartPulses=0L
            var expiredPulses=0L
            fun verifyLoop(ok:Boolean,reason:String):Boolean {
                if(!ok) {
                    syntheticProbe="Fallo del bucle: $reason"
                    pause("Fallo del diagnóstico de bucle: $reason")
                }
                return ok
            }
            step(0) { key(51,true);mouse(40f,20f) }
            step(100) { key(30,true) }
            step(500) { mouse(20f,-10f) }
            step(650) { key(30,false) };step(700) { key(51,false) }
            step(850) { key(41,true) };step(900) { key(41,false) }
            step(1100) { key(41,true) };step(1150) { key(41,false) }
            step(1300) { mouse(20f,10f) }
            step(1400) { key(30,true) }
            step(1480) { key(138,true) };step(1490) { key(138,false) }
            step(1510) { key(30,false) }
            step(1600) { key(138,true) };step(1610) { key(138,false) }
            step(1700) { key(30,true) };step(1810) { key(30,false) }
            step(2000) { loopStartPulses=repeatRunner?.pulses ?: 0L;key(31,true) }
            step(2010) { key(31,false) }
            step(2400) {
                verifyLoop(repeatRunner?.activeCount==1 && (repeatRunner?.pulses ?: 0L)>loopStartPulses+1,
                    "no continuó después de soltar")
            }
            step(3100) {
                if(verifyLoop(repeatRunner?.activeCount==0 && repeatRunner?.pending==false,"no venció el temporizador"))
                    expiredPulses=repeatRunner?.pulses ?: 0L
            }
            step(3200) {
                if(verifyLoop(repeatRunner?.pulses==expiredPulses,"pulsos después del temporizador")) key(31,true)
            }
            step(3210) { key(31,false) }
            step(3450) { key(31,true) };step(3460) { key(31,false) }
            step(3500) { verifyLoop(repeatRunner?.activeCount==0 && repeatRunner?.pending==false,"no se detuvo con la segunda pulsación") }
            step(3600) { key(31,true) };step(3610) { key(31,false) }
            step(3750) {
                if(!verifyLoop(repeatRunner?.activeCount==1,"no reinició antes de pausar")) return@step
                syntheticProbe="Acciones sintéticas terminadas: bucle tras soltar, temporizador de 1 s y segunda pulsación verificados; pausa durante bucle"
                pause("Fin de diagnóstico de acciones")
            }
        } }
        worker.post(await)
    }
    /** Shell-only camera regression, targeted exclusively at the independent InputLab receiver. */
    fun testCameraLab(context:Context,displayId:Int=0) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testCameraLab(context,displayId) }.onFailure { pause(it.message ?: "Falló la prueba de cámara") } }
            return
        }
        check(environment.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory(displayId)
        val display=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(context,displayId)
        val bounds=requireNotNull(environment.boundsForPackage(TARGET)) { "Receptor sin ventana verificada" }
        val profile=GameProfile.create(TARGET,"Cámara InputLab",display.width,display.height,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true,
                area=GameArea(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat())),
                camera=it.camera.copy(enabled=true,mode=CameraSpec.SMOOTH,suspendKey=0,
                    sensitivityX=.7f,sensitivityY=.7f,updateRateHz=0,precisionAim=false),
                action=ActionSpec(.87f,.80f,code=62),shortcuts=SessionShortcuts(138,139,0))
        }
        val prepared=ProfileCompiler.prepare(profile,display.width,display.height,display.rotation)
        // Exercise Free Fire's mathematical policy without ever targeting the game or saving a profile.
        armProfile(prepared.copy(bindings=prepared.bindings.copy(
            ggCameraPolicy=requireNotNull(prepared.bindings.ggCameraPolicy).copy(freeFire=true))))
        activeProfileId=null
        val run=generation
        val deadline=SystemClock.uptimeMillis()+5000
        syntheticProbe="Preparando inversiones, entrada antigua y renovación de cámara; entrada sintética"
        val await=object:Runnable { override fun run() {
            if(run!=generation) return
            if(!active || !environment.inputDispatchIdle()) {
                if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20)
                else pause("No inició la prueba de cámara")
                return
            }
            fun step(delay:Long,action:()->Unit) {
                worker.postDelayed({ if(run==generation && active && targetConfirmed) action() },delay)
            }
            fun mark() { key(62,true);key(62,false) }
            fun verifyAnchor(stage:String):Boolean {
                val contact=composer?.snapshot()?.singleOrNull { it.owner==MappingEngine.CAMERA_OWNER }
                val ok=contact!=null && kotlin.math.abs(contact.x-prepared.geometry.cameraX)<=.0001f &&
                    kotlin.math.abs(contact.y-prepared.geometry.cameraY)<=.0001f
                if(!ok) { syntheticProbe="Falló cámara: $stage";pause("Falló el diagnóstico de cámara: $stage") }
                return ok
            }
            step(0) { mouse(1f,0f,System.nanoTime());mouse(-1f,0f,System.nanoTime()) }
            step(50) { key(51,true);mark() }
            step(100) { mouse(100f,0f,System.nanoTime()) }
            step(150) { mark() }
            step(200) { mouse(-100f,0f,System.nanoTime()) }
            step(250) { if(verifyAnchor("inversión de 100")) mark() }
            step(300) {
                val now=System.nanoTime()
                mouseBatch(RawMouseMotionBatch(LongArray(64) { now-(63-it)*100_000L },
                    FloatArray(64) { if(it<32) 4f else -4f },
                    FloatArray(64) { if(it<32) 2f else -2f }))
            }
            step(400) { if(verifyAnchor("lote de inversiones")) mark() }
            step(450) {
                val now=System.nanoTime()
                mouseBatch(RawMouseMotionBatch(longArrayOf(now-300_000_000L,now-2_000_000L,now-1_000_000L),
                    floatArrayOf(400f,5f,-5f),floatArrayOf(0f,0f,0f)))
            }
            step(550) { if(verifyAnchor("muestra antigua")) mark() }
            step(600) { mouse(0f,100_000_000f,System.nanoTime()) }
            step(700) { mouse(0f,10f,System.nanoTime()) }
            step(800) {
                val contacts=composer?.snapshot().orEmpty()
                val camera=contacts.singleOrNull { it.owner==MappingEngine.CAMERA_OWNER }
                val expectedY=prepared.geometry.cameraY+7f
                if(camera==null || kotlin.math.abs(camera.y-expectedY)>.001f ||
                    contacts.none { it.owner=="joystick" }) {
                    syntheticProbe="Falló cámara: renovación con joystick mantenido"
                    pause("Falló el diagnóstico de renovación de cámara")
                } else mark()
            }
            step(900) { key(51,false) }
            step(1000) {
                syntheticProbe="Cámara sintética verificada: inversión sin residuo, lote, descarte antiguo y renovación con joystick"
                pause("Fin de diagnóstico de cámara")
            }
        } }
        worker.post(await)
    }
    /** Shell-only RAM profile: real shared composer, independent receiver, no saved game edits. */
    fun testMacroLab(context:Context,automated:Boolean) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testMacroLab(context,automated) }.onFailure { pause(it.message ?: "Falló la prueba de macro") } };return
        }
        check(environment.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory()
        val display=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(context,0)
        val size=android.graphics.Point(display.width,display.height)
        val p=GameProfile.create(TARGET,"Macro InputLab",size.x,size.y,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(suspendKey=113),
                shortcuts=SessionShortcuts(138,139,0),macros=listOf(MacroSpec("lab-macro","Secuencia",.42f,.45f,code=30,
                    steps=listOf(MacroStep(.42f,.45f,150),MacroStep(.55f,.55f,150),MacroStep(.65f,.60f,300)))))
        }
        armProfile(ProfileCompiler.prepare(p,size.x,size.y,display.rotation));activeProfileId=null
        if(!automated) return
        val run=generation;val deadline=SystemClock.uptimeMillis()+5000
        val await=object:Runnable { override fun run() {
            if(run!=generation) return
            if(!active) {
                if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20) else pause("No inició la prueba de macro")
                return
            }
            fun step(delay:Long,action:()->Unit) { worker.postDelayed({ if(run==generation && active && targetConfirmed) action() },delay) }
            step(0) { key(51,true);mouse(40f,20f) }
            step(100) { key(30,true) };step(150) { key(30,false) }
            step(450) { mouse(20f,-10f) };step(800) { key(51,false) }
            step(950) { key(30,true) };step(970) { key(30,false) }
            step(990) { key(113,true) };step(1120) { key(113,false) }
            step(1200) { mouse(20f,10f) }
            step(1300) { key(30,true) };step(1320) { key(30,false) }
            step(1400) { syntheticProbe="Macro completa, cancelación por Suspender y pausa solicitadas; verificar receptor";pause("Fin de diagnóstico de macro") }
        } }
        worker.post(await)
    }
    /** Replay the user's bindings against our own receiver; never edits or recalibrates the saved game. */
    @Synchronized fun testMappedLab(context:Context,automated:Boolean=false,holdContinuous:Boolean=false,precision:Boolean=false) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testMappedLab(context,automated,holdContinuous,precision) }.onFailure { pause(it.message ?: "No inició el perfil de prueba") } }
            return
        }
        check(environment.available)
        val capture=environment
        check(capture.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        val saved=requireNotNull(ProfileStore(context).selected()) { "Selecciona un perfil primero" }
        useLaboratory()
        capture.refreshContext()
        val bounds=requireNotNull(capture.boundsForPackage(TARGET)) { "Receptor sin ventana verificada" }
        val display=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(context,0)
        val size=android.graphics.Point(display.width,display.height)
        val mirrored=saved.copy(packageName=TARGET,name="Perfil en InputLab",
            camera=if(precision) saved.camera.copy(precisionAim=true) else saved.camera,
            calibration=saved.calibration.copy(screenWidth=size.x,screenHeight=size.y,rotation=display.rotation,
                area=GameArea(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()),
                confirmed=true,displayScope="phone",windowFrame=null),
            shortcuts=SessionShortcuts(138,139,0))
        val precisionMarkerKey=mirrored.actions.firstOrNull { it.triggerKind=="key" }?.code
        require(!precision || precisionMarkerKey!=null) { "El perfil de prueba necesita una acción de teclado" }
        val probe=ProfileCompiler.prepare(mirrored,size.x,size.y,display.rotation)
        // Existing recovery scripts probe the exterior-coordinate capability directly;
        // this laboratory override is never offered as a game-camera setting.
        armProfile(if(holdContinuous) probe.copy(bindings=probe.bindings.copy(ggCameraPolicy=null)) else probe)
        activeProfileId=null
        if(!automated && !holdContinuous) return
        val run=generation;val deadline=SystemClock.uptimeMillis()+5000
        syntheticProbe="Preparando secuencia del perfil"
        val await=object:Runnable {
            override fun run() {
                if(run!=generation) return
                if(!active) {
                    if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20)
                    else pause("No inició el perfil de prueba")
                    return
                }
                fun step(delay:Long,action:()->Unit) { worker.postDelayed({ if(run==generation && active) action() },delay) }
                if(holdContinuous) {
                    step(0) { button(1,true) }
                    step(40) { key(mirrored.joystick.upKey,true) }
                    step(80) { mouse(size.x*2f/mirrored.camera.sensitivityX,-size.y*2f/mirrored.camera.sensitivityY);syntheticProbe="Cámara continua exterior mantenida" }
                    step(15000) { pause("Fin de prueba continua mantenida") }
                    return
                }
                step(0) { mouse(20f,10f) }
                step(40) { button(1,true) }
                if(precision) repeat(4) { index ->
                    step(50L+index*8L) {
                        mouse(if(index%2==0) 1f else -1f,.25f,System.nanoTime())
                        // Non-MOVE marker frames retain exact admitted coordinates even if Android
                        // resamples/coalesces MOVE callbacks. This is laboratory-only input.
                        key(requireNotNull(precisionMarkerKey),true);key(precisionMarkerKey,false)
                    }
                }
                step(80) { mouse(350f,-420f) }
                step(150) { key(mirrored.joystick.upKey,true) }
                step(200) { mouse(400f,300f) }
                step(260) { key(mirrored.joystick.rightKey,true) }
                step(320) { mouse(-600f,-380f) }
                step(420) { key(mirrored.joystick.upKey,false) }
                step(480) { mouse(450f,360f) }
                step(560) { key(mirrored.joystick.rightKey,false) }
                step(620) { button(1,false) }
                step(750) { syntheticProbe="Secuencia de perfil completada";key(139,true) }
            }
        }
        worker.post(await)
    }
    private fun originalSessionIdentity()=GuardianSessionIdentity(backendSessionId,originalTargetUid,originalTargetPackage,originalTargetDisplayId)
    private fun guardianClosureSnapshot(backend:IInjector):GuardianClosureSnapshot? {
        val status=backend.status()
        if(status.getInt("protocol",-1)!=4 || status.getInt("uid",-1)!=2000 || !status.getBoolean("targeted",false)) return null
        val identity=GuardianSessionIdentity(status.getLong("sessionId"),status.getInt("targetUid",-1),
            status.getString("targetPackage") ?: "",status.getInt("displayId",-1))
        return GuardianClosureSnapshot(identity,status.getInt("possibleContacts",-1),status.getInt("confirmedContacts",-1),
            status.getBoolean("releaseUnverified",true),status.getBoolean("keyReleaseUnverified",true),
            status.getBoolean("cancelAdmitted",false),status.getBoolean("terminalReconciled",false),
            status.getString("recoveryState") ?: "").takeIf { it.valid }
    }
    fun backendAvailable() {
        worker.post {
            val attached=environment.injector ?: return@post
            val guardian=runCatching { guardianClosureSnapshot(attached) }.getOrNull()
            if(guardian!=null && SessionClosurePolicy.mayAdoptGuardian(backendSessionId,composer!=null,
                    active,armed,starting,testRunning,guardian)) {
                val adopted=synchronized(this) {
                    if(!SessionClosurePolicy.mayAdoptGuardian(backendSessionId,composer!=null,active,armed,starting,testRunning,guardian)) false
                    else {
                        val original=guardian.identity
                        backendSessionId=original.sessionId;sessionBackend=attached;noticeGate.begin(original.sessionId)
                        originalTargetUid=original.targetUid;originalTargetPackage=original.packageName;originalTargetDisplayId=original.displayId
                        targetPackage=original.packageName;targetDisplayId=original.displayId
                        targetConfirmed=false;focusRecovery.contextChanged(false)
                        guardianClosurePending=true;releaseUnverified=true;contacts=maxOf(guardian.possibleContacts,guardian.confirmedContacts)
                        explicitlyStopped=true;mode=SessionMode.BLOCKED;state="Cierre pendiente"
                        error="Verificando el cierre de la sesión anterior sin reanudar entradas"
                        true
                    }
                }
                if(adopted) {
                    main.post { environment.refreshContext();events.changed() }
                    requestPendingRecovery()
                    return@post
                }
            }
            if(releaseUnverified && guardianClosurePending && sessionBackend?.asBinder()===attached.asBinder()) {
                requestPendingRecovery();return@post
            }
            val frame=composer?.recoveryFrame()
            if(releaseUnverified && frame!=null) {
                val backend=environment.injector ?: return@post
                if(sessionBackend?.asBinder()===backend.asBinder()) {
                    requestPendingRecovery()
                    return@post
                }
                state="Recuperando gesto anterior"; mode=SessionMode.RECOVERING
                main.post { events.changed() }
                try {
                    if(originalTargetDisplayId!=0) check(backend.status().getBoolean("multiDisplaySupported")) { "El motor no puede recuperar la pantalla original" }
                    val sid=if(originalTargetDisplayId!=0) backend.recoverTargetOrphanOnDisplay(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,
                            originalTargetUid,originalTargetPackage,originalTargetDisplayId,originalContinuousCamera)
                        else if(originalContinuousCamera) backend.recoverContinuousTargetOrphan(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,originalTargetUid,originalTargetPackage)
                        else if(originalTargetPackage==TARGET) backend.recoverOrphan(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,originalTargetUid)
                        else backend.recoverTargetOrphan(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,originalTargetUid,originalTargetPackage)
                    if(sid>0) {
                        val recoveredStatus=guardianClosureSnapshot(backend)
                        check(recoveredStatus?.provesClosureOf(originalSessionIdentity().copy(sessionId=sid))==true) {
                            "La recuperación no confirmó el cierre de la sesión original"
                        }
                        backendSessionId=sid; noticeGate.begin(sid); sessionBackend=null; composer?.acknowledgeBackendReset()
                        releaseUnverified=false; contacts=0;cameraContactAdmitted=false;guardianClosurePending=false
                        mode=if(explicitlyStopped) SessionMode.STOPPED else SessionMode.PAUSED
                        state=if(explicitlyStopped) "Detenida · secuencia recuperada" else "Pausada · secuencia recuperada"
                        error=if(explicitlyStopped) "Secuencia anterior cerrada; sesión detenida" else if(recoveredStatus.cancelAdmitted) "Inicia manualmente; cancelación admitida por Android" else "Inicia una sesión nueva manualmente"
                    } else { state="Bloqueada"; mode=SessionMode.BLOCKED; error="Cancelación anterior no admitida" }
                } catch(e:Exception) { state="Bloqueada"; mode=SessionMode.BLOCKED; error=e.message ?: "No se pudo recuperar" }
                main.post { events.changed() }
            }
        }
    }
    fun testStaleWork():Map<String,Boolean> {
        val frame=requireNotNull(composer?.recoveryFrame())
        val backend=requireNotNull(sessionBackend)
        check(previousBackendSessionId>0 && previousBackendSessionId!=backendSessionId)
        val staleInject=backend.inject(previousBackendSessionId,2,frame.downTime,frame.eventTime,frame.ids,frame.xs,frame.ys)
        val staleRelease=backend.release(previousBackendSessionId)
        client.recovery(previousBackendSessionId,Long.MAX_VALUE,2,"Prueba de callback antiguo")
        return mapOf("staleInjectRejected" to !staleInject,"staleReleaseRejected" to !staleRelease)
    }
    /** Closed, shell-only laboratory harness. InputLab remains a passive recipient. */
    @Synchronized fun testGesture(context:Context,count:Int,holdMillis:Int,terminalUp:Boolean=false) {
        useLaboratory()
        check(targetConfirmed && !active && !armed && !testRunning && !stopping && !releaseUnverified)
        require(count==1 || count==3); require(holdMillis in 1000..30000)
        val bounds=context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        width=bounds.width(); height=bounds.height()
        val run=++generation; testRunning=true; probeStarted=false; state="Diagnóstico sintético"; error=""
        worker.post {
            if(!prepare(run)) { testRunning=false; return@post }
            probeStarted=true
            val c=composer!!
            fun step(delay:Long,op:()->Boolean) { worker.postDelayed({
                if(run==generation && probeTargetFocused()) {
                    if(!op()) pause("Fallo durante prueba nativa")
                    contacts=c.activeCount
                }
            },delay) }
            step(0) { c.begin("test-joy",width*.23f,height*.70f) }
            if(count==3) {
                step(150) { c.begin("test-camera",width*.66f,height*.48f) }
                step(300) { c.begin("test-action",width*.82f,height*.82f) }
                step(600) { c.end("test-action") }
                step(750) { c.begin("test-action",width*.82f,height*.82f) }
            }
            if(terminalUp) step(900) { c.end("test-joy") }
            step(holdMillis.toLong()) { pause("Fin de prueba nativa"); true }
        }
    }
    internal data class StatusIdentity(val generation:Long,val sessionId:Long)
    internal fun statusIdentity()=StatusIdentity(generation,backendSessionId)

    fun status(context: Context): Map<String, Any?> =
        status(context,runCatching { environment.injector?.status() }.getOrNull())

    /** The UI supplies an off-main backend read; local window/presentation diagnostics stay on main. */
    fun status(context: Context,backendSnapshot:Bundle?): Map<String, Any?> {
        val backend=backendSnapshot
        // Android's readable Wi-Fi ADB setting may be absent or restricted on a vendor build.
        val wirelessDebuggingEnabled=runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled")==1
        }.getOrNull()
        fun percentiles(values: RollingLatencySamples): List<Double> =
            RollingLatencySamples.percentilesMillis(synchronized(metricsLock) { values.snapshot() })
        val queueMetrics = queue.metrics()
        val queueDiagnostic = queue.diagnostics()
        val captureDiagnostics = environment.diagnostics()
        return mapOf("protocol" to 1, "state" to state, "active" to active,
            "mode" to mode.wire,"activeProfileId" to activeProfileId,"profileName" to profileName,
            "stopRequested" to explicitlyStopped,"guardianClosurePending" to guardianClosurePending,
            "transitioning" to isTransitioning(),
            "activePackage" to targetPackage,"targetDisplayId" to targetDisplayId,"originalTargetDisplayId" to originalTargetDisplayId,
            "contextError" to error,
            "pointerMode" to if(!active) "none" else if(camera) "camera" else if(runtimeProfile==null) "native" else "cursor",
            "error" to error, "accessibility" to false, "backend" to (backend != null),
            "captureReady" to (captureDiagnostics.available && backend?.getBoolean("captureSupported")==true),
            "captureBackend" to captureDiagnostics.captureBackend,
            "nativeCaptureRateSupported" to (backend?.getBoolean("nativeCaptureRateSupported",false)==true),
            "captureError" to (captureDiagnostics.captureError.takeIf { it.isNotEmpty() }
                ?: backend?.getString("captureError") ?: ""),
            "overlayPermission" to android.provider.Settings.canDrawOverlays(context),
            "wirelessDebuggingEnabled" to wirelessDebuggingEnabled,
            "nativeCapture" to backend?.getBundle("nativeCapture")?.let { value ->
                value.keySet().associateWith { key -> @Suppress("DEPRECATION") (value.get(key)?.let { item ->
                    when(item) { is IntArray->item.toList();is BooleanArray->item.toList();is Array<*>->item.toList();else->item }
                }) }
            },
            "backendUid" to backend?.getInt("uid"),"backendDisplayId" to backend?.getInt("displayId"),
            "multiDisplaySupported" to backend?.getBoolean("multiDisplaySupported"),"injectPermission" to backend?.getBoolean("injectPermission"),
            "backendError" to backend?.getString("error"), "targetUid" to backend?.getInt("targetUid"),
            "targetFocused" to backend?.getBoolean("targetFocused"), "focusError" to backend?.getString("focusError"),
            "supervisorPid" to backend?.getInt("supervisorPid"), "workerPid" to backend?.getInt("workerPid"),
            "workerGeneration" to backend?.getLong("workerGeneration"), "backendSessionId" to backendSessionId,
            "guardianSessionId" to backend?.getLong("sessionId"),"guardianTargetPackage" to backend?.getString("targetPackage"),
            "recoveryState" to backend?.getString("recoveryState"), "recoveryAttempts" to backend?.getInt("recoveryAttempts"),
            "recoveredCount" to backend?.getInt("recoveredCount"), "cancelAdmitted" to backend?.getBoolean("cancelAdmitted"),
            "liveWorkerCancelAdmitted" to backend?.getBoolean("liveWorkerCancelAdmitted"),
            "terminalReconciled" to backend?.getBoolean("terminalReconciled"),
            "stoppedProducersReconciled" to backend?.getBoolean("stoppedProducersReconciled"),
            "rejectedMoveReconciled" to backend?.getBoolean("rejectedMoveReconciled"),
            "rejectedDownReconciled" to backend?.getBoolean("rejectedDownReconciled"),
            "focusLossReconciled" to backend?.getBoolean("focusLossReconciled"),
            "sessionRecoverySupported" to backend?.getBoolean("sessionRecoverySupported",false),
            "detectedUptime" to backend?.getLong("detectedUptime"), "recoveredUptime" to backend?.getLong("recoveredUptime"),
            "backendPossibleContacts" to backend?.getInt("possibleContacts"), "backendConfirmedContacts" to backend?.getInt("confirmedContacts"),
            "backendDownTime" to backend?.getLong("downTime"), "backendLastAction" to backend?.getInt("lastAction"), "backendLastEventTime" to backend?.getLong("lastEventTime"),
            "backendIds" to backend?.getIntArray("ids")?.toList(), "uptimeMs" to SystemClock.uptimeMillis(),
            "backendXs" to backend?.getFloatArray("xs")?.toList(), "backendYs" to backend?.getFloatArray("ys")?.toList(),
            "staleCallbacksDiscarded" to noticeGate.discarded,
            "backendProtocol" to backend?.getInt("protocol"), "controllerBinderAlive" to backend?.getBoolean("controllerBinderAlive"), "workerAlive" to backend?.getBoolean("workerAlive"),
            "backendCoordinatePolicy" to backend?.getString("coordinatePolicy"),"continuousCameraSupported" to backend?.getBoolean("continuousCameraSupported"),
            "systemPointerHidden" to backend?.getBoolean("systemPointerHidden"), "systemPointerError" to backend?.getString("systemPointerError"),
            "trustedCursorVisible" to backend?.getBoolean("trustedCursorVisible"),"trustedCursorError" to backend?.getString("trustedCursorError"),
            "recoveryIds" to backend?.getIntArray("recoveryIds")?.toList(), "recoveryDownTime" to backend?.getLong("recoveryDownTime"), "recoveryEventTime" to backend?.getLong("recoveryEventTime"),
            "releaseUnverified" to (releaseUnverified || backend?.getBoolean("releaseUnverified") == true || backend?.getBoolean("keyReleaseUnverified")==true),
            "keyReleaseUnverified" to (backend?.getBoolean("keyReleaseUnverified")==true),
            "contacts" to contacts, "keys" to inputKeys,
            "mouse" to inputMouse, "relativeSamples" to relativeSamples, "wheelSteps" to wheelSteps.toDouble(),
            "injected" to injected, "probe" to syntheticProbe, "maxQueueDepth" to queueMetrics.first,
            "cameraRestarts" to engine?.cameraRestarts, "aimOwner" to engine?.aimOwner,
            "cameraIdleRetirements" to engine?.idleCameraRetirements,
            "cameraIdleCheckPending" to (cameraIdleWakeup!=null),
            "cameraIdleQuietThresholdMs" to cameraIdlePolicy.quietThresholdNanos/1e6,
            "boundedIdleRetirement" to runtimeProfile?.bindings?.retireBoundedCameraOnIdle,
            "heldFireCameraBounds" to runtimeProfile?.bindings?.heldFireCameraBounds?.let {
                listOf(it.left,it.top,it.right,it.bottom)
            },
            "cameraResetBatchSupported" to cameraResetBatchSupported,
            "cameraResetBatches" to cameraResetBatches,"cameraResetFailures" to cameraResetFailures,
            "cameraResetUnknownProgress" to cameraResetUnknownProgress,
            "cameraResetIpcMs" to percentiles(cameraResetIpcTime),
            "backendCameraResetBatches" to backend?.getLong("cameraResetBatches"),
            "backendCameraResetInterlacedBatches" to backend?.getLong("cameraResetInterlacedBatches"),
            "backendCameraResetLastKind" to backend?.getString("cameraResetLastKind"),
            "backendCameraResetFrames" to backend?.getLong("cameraResetFrames"),
            "backendCameraResetFocusPrefetchHits" to backend?.getLong("cameraResetFocusPrefetchHits"),
            "backendCameraResetLastEventTimes" to backend?.getLongArray("cameraResetLastEventTimes")?.toList(),
            "backendCameraResetLastDurationMs" to backend?.getDouble("cameraResetLastDurationMs"),
            "cameraTrial" to when(runtimeProfile?.profile?.camera?.mode) {
                CameraSpec.CONTINUOUS -> "continuous"
                CameraSpec.SMOOTH -> "smooth"
                else -> cameraTrial
            },"cameraOverlap" to runtimeProfile?.bindings?.overlapCameraHandoff,
            "cameraDistinctIds" to runtimeProfile?.bindings?.distinctCameraContacts,
            "cameraMode" to runtimeProfile?.profile?.camera?.mode,"continuousCameraSession" to originalContinuousCamera,
            "ggCameraKind" to runtimeProfile?.bindings?.ggCameraPolicy?.let { if(it.freeFire) "freeFireStandard" else "mainView" },
            "cameraRate" to cameraPacer?.diagnostics(),"cameraDeliveryWaitMs" to percentiles(cameraDeliveryWait),
            "precisionAim" to precisionAimFilter.snapshot(),
            "actionRuntime" to actionState,
            "joystickMappingEnabled" to (runtimeProfile?.bindings?.joystickEnabled ?: true),
            "cameraMappingEnabled" to cameraShortcutEnabled(),
            "cameraRequestedRateHz" to runtimeProfile?.profile?.camera?.updateRateHz,
            "cameraContactAdmitted" to cameraContactAdmitted,
            "pointerTransitionPending" to pointerTransitionPending,
            "cursorCommandPending" to cursorCommandPending,
            "macros" to macroState,
            "pointerBaseMode" to if(pointerTransition.baseCursor) "cursor" else "camera",
            "cameraSuspendedByHold" to pointerTransition.temporaryCursorHeld,
            "activePauseHistory" to activePauseHistory,
            "firstRejectedInput" to firstRejectedInput,
            "metricsSessionId" to metricsSessionId,"queueDiagnostics" to mapOf(
                "currentDepth" to queueDiagnostic.currentDepth,"maxDepth" to queueDiagnostic.maxDepth,
                "mergedMoves" to queueDiagnostic.mergedMoves,"relativeMergedMoves" to queueDiagnostic.relativeMergedMoves,
                "cursorMergedMoves" to queueDiagnostic.cursorMergedMoves,
                "coalescedMotionEntries" to queueDiagnostic.coalescedMotionEntries),
            "cameraMotionFreshness" to mapOf("budgetMs" to CameraSampleAgePolicy.MAX_AGE_NANOS/1e6,
                "clock" to "CLOCK_MONOTONIC","sampleAgeMs" to percentiles(cameraSourceAge),
                "maxSampleAgeMs" to maxCameraSourceAgeNanos/1e6,"expiredSamples" to expiredCameraSamples,
                "invalidTimeSamples" to invalidCameraTimeSamples,
                "scope" to "Last 2048 physical GG camera sample ages at worker admission, including expired samples; excludes injection and presentation"),
            "backendTimingSessionId" to backend?.getLong("timingSessionId"),
            "backendFocusMs" to backend?.getDoubleArray("focusMs")?.toList(),
            "backendPipeMs" to backend?.getDoubleArray("pipeMs")?.toList(),
            "backendFocusCount" to backend?.getLong("focusTimingCount"),"backendPipeCount" to backend?.getLong("pipeTimingCount"),
            "fireHoldStarts" to engine?.fireHoldStarts, "fireHoldEnds" to engine?.fireHoldEnds, "fireBoundaryClamps" to engine?.fireBoundaryClamps,
            "heldFireCameraClamps" to engine?.heldFireCameraClamps,
            "mergedMoves" to queueMetrics.second, "queueMs" to percentiles(queueWait), "ipcMs" to percentiles(ipcTime)) + captureDiagnostics.fields + events.diagnostics()
    }
}
